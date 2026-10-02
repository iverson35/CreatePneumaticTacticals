#!/usr/bin/env python3
"""
split_unified_bbmodel.py -- 把「统合枪模」.bbmodel 拆成每模块一个 .bbmodel。

统合模型约定（详见 docs/gunpack-creator-guide.md 建模章节）：
- 根骨骼 receiver_<模块id> = 机匣模块；
- 其下任意深度、形如 <模块类型>_<模块id> 的骨骼都是要拆出的模块
  （feed / supply / barrel / muzzle / handguard / handguard_attachment /
    sight / tactical_sight / stock / charm）；
- 每个模块按「装配完成」的状态摆放。脚本找到对应的 loc 定位骨
  （feed->loc_feed、muzzle->父枪管里的 loc_muzzle_attachment、
    handguard_attachment->父护木里唯一的 loc_handguard_* 等），
  把整棵模块子树（含模块根骨与所有深度的骨骼/立方体、动画关键帧）
  按同一个刚体变换 K = inv(宿主里 root->loc 的链) 共轭到以 loc pivot
  为原点、loc 旋转为单位阵的局部空间；
- UV 从统合贴图裁出、去重、重排成每模块一张紧凑贴图（<=64x64 优先，
  内嵌回 bbmodel 并同时写出 <id>.png）；若统合 bbmodel 旁存在同尺寸的
  <名>_glowmask.png / <名>_dye.png，按同一映射拆出每模块的掩码；
- 动画按骨骼归属分给各模块（只保留该模块骨骼的 animator），
  每个输出模型的根骨骼统一改名 main。音效关键帧默认分给机匣，
  effect 无命名空间时补 createpneumatictacticals:；同帧指令关键帧
  （timeline）的 script 若是模块 id，则该帧音效分给对应模块，
  且标识用指令帧不写入任何输出。
- 拆分前会自校验：骨骼 pivot/旋转共轭、立方体几何（在模块自身坐标系
  与「按模组挂载语义摆回宿主后」两处逐点核对）、UV 边界、动画骨骼引用、
  贴图像素、音效帧守恒与命名空间、标识指令帧清除；--geo/--anim 时
  再校验导出物与 bbmodel 逐项对应。全部通过才写出。

用法：
    python tools/split_unified_bbmodel.py gecko/marble_17/marble_17.bbmodel
    python tools/split_unified_bbmodel.py <bbmodel> -o <输出目录>
    python tools/split_unified_bbmodel.py <bbmodel> --gecko
      # 除 .bbmodel/.png 外，顺带导出枪包要的 <id>.geo.json 与
      # <id>.animation.json（等价于在 Blockbench 里逐个导出，
      # 规则由仓库内 26 份 geo / 9 份动画语料反推并验证过）

依赖：Pillow。拆分后每个 <id>.bbmodel 用 Blockbench 打开，
按指南 2.8 节导出 geo / 动画即可（或直接用 --geo / --anim 让脚本导出）。
脚本不会删除输出目录里的旧文件；本次未重新生成的
.bbmodel / .png / .geo.json / .animation.json 会在结束时列为
「可能过时」提示。
"""

import argparse
import base64
import copy
import io
import json
import math
import re
import sys
import uuid as uuidlib
from pathlib import Path

from PIL import Image

# ---------------------------------------------------------------- 常量

# 顺序敏感：长前缀必须在前面（handguard_attachment 先于 handguard）
MODULE_TYPES = [
    "handguard_attachment", "tactical_sight", "receiver", "handguard",
    "barrel", "supply", "muzzle", "stock", "sight", "charm", "feed",
]
MODULE_RE = re.compile(r"^(" + "|".join(MODULE_TYPES) + r")_(.+)$")

# 模块类型 -> 机匣模型上的定位骨名
LOC_FOR_TYPE = {
    "feed": "loc_feed",
    "supply": "loc_supply",
    "barrel": "loc_barrel",
    "handguard": "loc_handguard",
    "stock": "loc_stock",
    "sight": "loc_sight",
    "tactical_sight": "loc_sight_side",
    "charm": "loc_charm",
}

MASK_SUFFIXES = ("_glowmask", "_dye")

# ---------------------------------------------------------------- 数学
# Blockbench / GeckoLib 骨骼旋转顺序：ZYX，即 R = Rz @ Ry @ Rx（角度制）。
# 该约定已用 mak_1_receiver 的 loc_charm [0,90,90]（左侧挂点，
# +Y->-X 外法线、-Z->下垂）对照指南语义验证过。


def _deg_mat3(rot):
    x, y, z = (math.radians(v) for v in rot)
    cx, sx, cy, sy, cz, sz = (math.cos(x), math.sin(x), math.cos(y),
                              math.sin(y), math.cos(z), math.sin(z))
    rx = ((1, 0, 0), (0, cx, -sx), (0, sx, cx))
    ry = ((cy, 0, sy), (0, 1, 0), (-sy, 0, cy))
    rz = ((cz, -sz, 0), (sz, cz, 0), (0, 0, 1))
    return _mat3_mul(rz, _mat3_mul(ry, rx))


def _mat3_mul(a, b):
    return tuple(tuple(sum(a[i][k] * b[k][j] for k in range(3))
                       for j in range(3)) for i in range(3))


def _mat3_transpose(m):
    return tuple(tuple(m[j][i] for j in range(3)) for i in range(3))


def _mat3_apply(m, v):
    return [sum(m[i][k] * v[k] for k in range(3)) for i in range(3)]


def _mat3_to_euler(m):
    """R = Rz @ Ry @ Rx 的逆解，返回角度 [x, y, z]。"""
    sy = max(-1.0, min(1.0, -m[2][0]))
    y = math.asin(sy)
    cy = math.cos(y)
    if cy > 1e-9:
        x = math.atan2(m[2][1], m[2][2])
        z = math.atan2(m[1][0], m[0][0])
    elif sy > 0:  # y = +90
        x = math.atan2(m[0][1], m[0][2])
        z = 0.0
    else:          # y = -90
        x = math.atan2(-m[0][1], -m[0][2])
        z = 0.0
    return [math.degrees(x), math.degrees(y), math.degrees(z)]


def _mat4_trs(origin, rot):
    r = _deg_mat3(rot) if rot else ((1, 0, 0), (0, 1, 0), (0, 0, 1))
    return ([[r[0][0], r[0][1], r[0][2], origin[0]],
             [r[1][0], r[1][1], r[1][2], origin[1]],
             [r[2][0], r[2][1], r[2][2], origin[2]],
             [0.0, 0.0, 0.0, 1.0]])


_IDENT4 = [[1.0, 0.0, 0.0, 0.0], [0.0, 1.0, 0.0, 0.0],
           [0.0, 0.0, 1.0, 0.0], [0.0, 0.0, 0.0, 1.0]]


def _mat4_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)]
            for i in range(4)]


def _prep_mat(origin, rot):
    """GeckoLib 静止姿态矩阵 prep = T(pivot) · R · T(-pivot)。

    pivot 只是旋转中心，不平移子节点：骨骼的 pivot 与立方体的坐标
    都在同一个扁平坐标系里（见 RenderUtils.prepMatrixForBone /
    renderRecursively）。静止时 prep = I。
    """
    p = list(origin or [0, 0, 0])
    return _mat4_mul(_mat4_trs(p, rot),
                     _mat4_trs([-p[0], -p[1], -p[2]], None))


def _mat4_is_identity(m):
    return all(abs(m[i][j] - _IDENT4[i][j]) < 1e-9
               for i in range(4) for j in range(4))


def _mat4_invert_rigid(m):
    rt = [[m[j][i] for j in range(3)] for i in range(3)]
    t = [m[0][3], m[1][3], m[2][3]]
    ti = [-sum(rt[i][k] * t[k] for k in range(3)) for i in range(3)]
    return [rt[0] + [ti[0]], rt[1] + [ti[1]], rt[2] + [ti[2]],
            [0.0, 0.0, 0.0, 1.0]]


def _mat4_apply(m, v):
    return [sum(m[i][k] * (list(v) + [1.0])[k] for k in range(4))
            for i in range(3)]


def _mat4_rot3(m):
    return tuple(tuple(m[i][j] for j in range(3)) for i in range(3))


def _rot_is_identity(r):
    ident = ((1, 0, 0), (0, 1, 0), (0, 0, 1))
    return all(abs(r[i][j] - ident[i][j]) < 1e-7 for i in range(3)
               for j in range(3))


def _r5(v):
    v = round(v, 5)
    return 0.0 if v == 0 else v


def _r5v(v):
    return [_r5(x) for x in v]


# ---------------------------------------------------------------- 模型索引


class Model:
    def __init__(self, data):
        self.data = data
        self.elements = {e["uuid"]: e for e in data.get("elements", [])}
        self.bones = {}          # uuid -> outliner dict
        self.parent = {}         # bone uuid -> parent bone uuid | None
        self.cube_parent = {}    # cube uuid -> parent bone uuid | None
        self._walk(data.get("outliner", []), None)

    def _walk(self, items, parent_uuid):
        for item in items:
            if isinstance(item, dict):
                self.bones[item["uuid"]] = item
                self.parent[item["uuid"]] = parent_uuid
                self._walk(item.get("children", []), item["uuid"])
            else:
                self.cube_parent[item] = parent_uuid

    def bone_chain(self, bone_uuid, from_uuid=None):
        """from_uuid（含）-> bone_uuid（含）的骨骼 uuid 列表；默认从根骨起。"""
        chain = []
        cur = bone_uuid
        while cur is not None:
            chain.append(cur)
            if cur == from_uuid:
                break
            cur = self.parent[cur]
        return list(reversed(chain))

    def chain_mat(self, bone_uuid, from_uuid=None):
        """from_uuid -> bone_uuid（含自身）的 prep 连乘。"""
        m = [row[:] for row in _IDENT4]
        for cur in self.bone_chain(bone_uuid, from_uuid):
            b = self.bones[cur]
            m = _mat4_mul(m, _prep_mat(b.get("origin"), b.get("rotation")))
        return m


def _classify_bone(name):
    m = MODULE_RE.match(name)
    return (m.group(1), m.group(2)) if m else (None, None)


class Module:
    def __init__(self, root_uuid, mtype, module_id):
        self.root_uuid = root_uuid
        self.type = mtype
        self.id = module_id
        self.bone_uuids = set()   # 含根，不含嵌套模块子树
        self.cube_uuids = []      # 保持树顺序
        self.loc_uuid = None      # 机匣为 None
        self.rebase = None        # 4x4


def _collect_modules(model):
    """识别模块骨骼；每个模块的内容 = 其子树减去嵌套模块子树。"""
    modules = {}
    order = []

    def walk(items, current_module):
        for item in items:
            if isinstance(item, dict):
                mtype, mid = _classify_bone(item["name"])
                if mtype:
                    if mid in modules:
                        raise SystemExit(
                            f"错误：模块 id 重复：{mid}（骨骼 {item['name']}）")
                    mod = Module(item["uuid"], mtype, mid)
                    mod.bone_uuids.add(item["uuid"])  # 根骨也算本模块骨骼
                    modules[mid] = mod
                    order.append(mod)
                    walk(item.get("children", []), mod)  # 嵌套模块子树
                else:
                    if current_module is None:
                        raise SystemExit(
                            f"错误：骨骼 {item['name']} 不在任何模块之下")
                    current_module.bone_uuids.add(item["uuid"])
                    walk(item.get("children", []), current_module)
            else:
                if current_module is None:
                    raise SystemExit(f"错误：立方体 {item} 不在任何模块之下")
                current_module.cube_uuids.append(item)

    top = model.data.get("outliner", [])
    if len(top) != 1 or not isinstance(top[0], dict):
        raise SystemExit("错误：统合模型顶层必须有且仅有一个 receiver 根骨")
    mtype, mid = _classify_bone(top[0]["name"])
    if mtype != "receiver":
        raise SystemExit(
            f"错误：顶层骨骼 {top[0]['name']} 不是 receiver_<id> 根骨")
    receiver = Module(top[0]["uuid"], mtype, mid)
    receiver.bone_uuids.add(top[0]["uuid"])
    modules[mid] = receiver
    order.append(receiver)
    walk(top[0].get("children", []), receiver)
    return order, modules


def _parent_module(model, modules, mod):
    by_root = {m.root_uuid: m for m in modules.values()}
    cur = model.parent[mod.root_uuid]
    while cur is not None:
        if cur in by_root:
            return by_root[cur]
        cur = model.parent[cur]
    return None


def _subtree_bones(model, root_uuid):
    out = []

    def rec(items):
        for it in items:
            if isinstance(it, dict):
                out.append(it)
                rec(it.get("children", []))
    rec(model.bones[root_uuid].get("children", []))
    return out


def _resolve_locators(model, modules):
    """为每个模块找到定位骨，并计算 rebase = inv(place(mod))。

    place(mod) 是模组把该模块摆到统合坐标系里的变换：
    place(mod) = place(host) · [宿主坐标系里 root->定位骨 的链]，
    其中定位骨在宿主坐标系里（宿主自身已被重算过）。rebase 取逆后，
    拆分出的模块被模组摆回原位时与统合模型逐点重合。
    """
    receiver = next(m for m in modules.values() if m.type == "receiver")
    receiver_bones = [b for b in _subtree_bones(model, receiver.root_uuid)
                      if b["uuid"] in receiver.bone_uuids]

    for mod in modules.values():
        if mod.type == "receiver":
            # 机匣模型本身就是统合坐标系：内容原样保留（只去掉模块子树）
            mod.rebase = [row[:] for row in _IDENT4]
            continue

        if mod.type in LOC_FOR_TYPE:
            loc_names = [LOC_FOR_TYPE[mod.type]]
            scope_bones = receiver_bones
            scope = "机匣"
        elif mod.type == "muzzle":
            loc_names = ["loc_muzzle_attachment"]
            parent = _parent_module(model, modules, mod)
            scope_bones = (_subtree_bones(model, parent.root_uuid)
                           if parent else receiver_bones)
            scope = f"父模块 {parent.id}" if parent else "机匣"
        elif mod.type == "handguard_attachment":
            loc_names = None  # 匹配 loc_handguard_*
            parent = _parent_module(model, modules, mod)
            scope_bones = (_subtree_bones(model, parent.root_uuid)
                           if parent else receiver_bones)
            scope = f"父模块 {parent.id}" if parent else "机匣"
        else:
            raise SystemExit(f"错误：未知模块类型 {mod.type}")

        if loc_names is not None:
            cands = [b for b in scope_bones if b["name"] in loc_names]
        else:
            cands = [b for b in scope_bones
                     if re.fullmatch(r"loc_handguard_(top|bottom|left|right)",
                                     b["name"])]
            if len(cands) > 1:
                names = [c["name"] for c in cands]
                raise SystemExit(
                    f"错误：护木配件 {mod.id} 的父模块里有多个挂点 "
                    f"{names}，无法自动判断装在哪一个")

        if not cands:
            raise SystemExit(
                f"错误：模块 {mod.id}（{mod.type}）在{scope}里找不到"
                f"对应定位骨")
        mod.loc_uuid = cands[0]["uuid"]
        # 模块自身坐标系在统合坐标系里的位置（模组的挂载语义）：
        #   place(mod) = place(host) · [宿主坐标系里的挂点链]
        # 宿主坐标系里的挂点链 = [K_host·(Π prep)·K_host⁻¹] · T(K_host·p_loc)
        # （K_host = inv(place(host))，机匣挂载时 K_host = I、链从机匣根骨起）
        # 于是 rebase = inv(place(mod)) = inv(m) · K_host。
        host = _parent_module(model, modules, mod)
        k_host = host.rebase if host else _IDENT4
        base = host.root_uuid if host else receiver.root_uuid
        m = model.chain_mat(mod.loc_uuid, base)
        if not _mat4_is_identity(k_host):
            # 宿主坐标系里的链要按 K_host 共轭：连平移也必须做
            # （K = T(t) 时共轭会把 pivot 一并搬到宿主坐标系里）
            m = _mat4_mul(_mat4_mul(k_host, m),
                          _mat4_invert_rigid(k_host))
        p_loc = model.bones[mod.loc_uuid].get("origin", [0, 0, 0])
        m = _mat4_mul(m, _mat4_trs(_mat4_apply(k_host, p_loc), None))
        mod.rebase = _mat4_mul(_mat4_invert_rigid(m), k_host)


# ---------------------------------------------------------------- 重定位


def _rebase_cube(el, t, rt):
    """把立方体按刚体变换 K = (rt, t) 搬到模块坐标系。

    渲染不变式：新表示的渲染点集 == K · 原渲染点集。
    origin' = K@origin；from/to 平移 origin'-origin（盒保持轴对齐）；
    rotation' = Rt @ rotation（旋转中心跟着 origin 走，故是左乘而非共轭）。
    """
    o = list(el.get("origin", [0, 0, 0]))
    o2 = _mat4_apply(t, o)
    delta = [o2[i] - o[i] for i in range(3)]
    el["origin"] = _r5v(o2)
    el["from"] = _r5v([el["from"][i] + delta[i] for i in range(3)])
    el["to"] = _r5v([el["to"][i] + delta[i] for i in range(3)])
    rc = el.get("rotation")
    # rt 为单位阵时父空间没变，保持原旋转三元组（矩阵等价重写会改变写法，
    # 例如 [0,90,90] 会被写成等价的 [-90,90,0]）
    if not _rot_is_identity(rt):
        r = _mat3_mul(rt, _deg_mat3(rc or [0, 0, 0]))
        euler = _r5v(_mat3_to_euler(r))
        if any(abs(v) > 1e-7 for v in euler):
            el["rotation"] = euler
        else:
            el.pop("rotation", None)


def _rebase_bone_node(node, t, rt):
    """把骨骼的 prep 共轭到模块坐标系：K·T(p)R T(-p)·K⁻¹。

    骨骼的 pivot 是子树的旋转中心（不平移子节点），因此 origin' = K@origin、
    rotation' = Rt @ R @ Rt⁻¹；只有这样才能整棵子树一致地共轭。
    """
    node["origin"] = _r5v(_mat4_apply(t, list(node.get("origin", [0, 0, 0]))))
    rot = node.get("rotation")
    if not _rot_is_identity(rt):
        r = _mat3_mul(_mat3_mul(rt, _deg_mat3(rot or [0, 0, 0])),
                      _mat3_transpose(rt))
        euler = _r5v(_mat3_to_euler(r))
        if any(abs(v) > 1e-7 for v in euler):
            node["rotation"] = euler
        else:
            node.pop("rotation", None)


def _rebase_module_tree(model, mod):
    """返回 (main 根骨, {cube_uuid: rebased_element})。

    整棵模块子树（含模块根骨自身）按同一个刚体变换 K 共轭：模块内部任意
    深度的骨骼/立方体坐标都在统合模型的扁平坐标系里，只变换根骨的直接
    子节点会让内容留在原处。任意深度的嵌套模块子树会被剔除（另拆文件）。
    """
    t = mod.rebase
    rt = _mat4_rot3(t)
    rebased_cubes = {}

    def copy_bone(node):
        new_node = {k: copy.deepcopy(v) for k, v in node.items()
                    if k != "children"}
        _rebase_bone_node(new_node, t, rt)
        children = []
        for child in node.get("children", []):
            if isinstance(child, dict):
                if _classify_bone(child["name"])[0]:
                    continue  # 嵌套模块另拆
                children.append(copy_bone(child))
            else:
                el = copy.deepcopy(model.elements[child])
                _rebase_cube(el, t, rt)
                rebased_cubes[child] = el
                children.append(child)
        new_node["children"] = children
        return new_node

    new_root = copy_bone(model.bones[mod.root_uuid])
    new_root["name"] = "main"
    return new_root, rebased_cubes


# ---------------------------------------------------------------- 贴图拆分


def _load_texture_images(data):
    images, scales = [], []
    for tex in data.get("textures", []):
        b64 = tex["source"].split(",", 1)[1]
        img = Image.open(io.BytesIO(base64.b64decode(b64))).convert("RGBA")
        images.append(img)
        scales.append((img.width / tex.get("uv_width", img.width),
                       img.height / tex.get("uv_height", img.height)))
    return images, scales


def _collect_rects(cubes, scales):
    """收集模块用到的贴图区域：{(tex_idx, x1, y1, x2, y2): None}（像素）。"""
    rects = {}
    for el in cubes:
        for face in el.get("faces", {}).values():
            t = face.get("texture")
            if t is None:
                continue
            u1, v1, u2, v2 = face["uv"]
            sx, sy = scales[t]
            xa, xb = sorted((u1 * sx, u2 * sx))
            ya, yb = sorted((v1 * sy, v2 * sy))
            for val in (xa, xb, ya, yb):
                if abs(val - round(val)) > 1e-6:
                    raise SystemExit(
                        f"错误：面 UV 未对齐到像素：{face['uv']}"
                        f"（缩放 {sx}x{sy}）")
            x1, x2, y1, y2 = round(xa), round(xb), round(ya), round(yb)
            if x2 - x1 < 1 or y2 - y1 < 1:
                continue  # 退化面（隐藏面），不裁图
            rects[(t, x1, y1, x2, y2)] = None
    return rects


def _shelf_pack(items, width):
    x = y = shelf_h = 0
    placement = {}
    for r in items:
        w = r[3] - r[1]
        h = r[4] - r[2]
        if w > width:
            return None, 0
        if x + w > width:
            y += shelf_h
            x = 0
            shelf_h = 0
        placement[r] = (x, y)
        x += w
        shelf_h = max(shelf_h, h)
    return placement, y + shelf_h


def _pack_rects(rects, warn):
    items = sorted(rects, key=lambda r: (-(r[4] - r[2]), -(r[3] - r[1])))
    if not items:
        return 1, 1, {}
    max_w = max(r[3] - r[1] for r in items)
    best = None
    for w in range(max(max_w, 1), 65):
        placement, h = _shelf_pack(items, w)
        if placement is not None and h <= 64:
            score = (max(w, h), w * h)
            if best is None or score < best[0]:
                best = (score, w, h, placement)
    if best is None:
        placement, h = _shelf_pack(items, 64)
        warn(f"贴图重排后超过 64x64（64x{h}），该模块贴图将走"
             f"独立贴图渲染（能跑但慢）")
        return 64, h, placement
    return best[1], best[2], best[3]


def _build_module_texture(placement, images, width, height):
    new_img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    mapping = {}
    for r, (nx, ny) in placement.items():
        t, x1, y1, x2, y2 = r
        new_img.paste(images[t].crop((x1, y1, x2, y2)), (nx, ny))
        mapping[r] = (nx, ny)
    return new_img, mapping


def _remap_uvs(cubes, scales, mapping):
    for el in cubes:
        for face in el.get("faces", {}).values():
            t = face.get("texture")
            if t is None:
                continue
            u1, v1, u2, v2 = face["uv"]
            sx, sy = scales[t]
            xa, xb = sorted((u1 * sx, u2 * sx))
            ya, yb = sorted((v1 * sy, v2 * sy))
            rect = (t, round(xa), round(ya), round(xb), round(yb))
            if rect not in mapping:
                face["uv"] = [0, 0, 0, 0]
                face["texture"] = 0
                continue
            nx, ny = mapping[rect]
            # 保留原翻转方向：新 uv = 新矩形原点 + 原相对偏移（像素单位）
            face["uv"] = [_r5(nx + u1 * sx - rect[1]),
                          _r5(ny + v1 * sy - rect[2]),
                          _r5(nx + u2 * sx - rect[1]),
                          _r5(ny + v2 * sy - rect[2])]
            face["texture"] = 0


def _img_to_data_url(img):
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return "data:image/png;base64," + base64.b64encode(
        buf.getvalue()).decode("ascii")


# ---------------------------------------------------------------- 动画拆分


# 音效缺省命名空间（effect 没写命名空间时补上）
DEFAULT_SOUND_NAMESPACE = "createpneumatictacticals"
# 「同帧」判定容差（秒）：Blockbench 时间按 1/24s 取整存 5 位小数
SOUND_ROUTE_EPS = 1e-4


def _route_effect_animator(animator, module_ids, receiver_id, anim_name,
                           warn):
    """把 effect animator（音效/指令/粒子）的关键帧按模块路由。

    返回 {module_id: [kf, ...]}：
    - sound 帧：同帧（±SOUND_ROUTE_EPS）有 script == 模块 id 的指令帧
      -> 分给该模块；否则分给机匣。effect 无命名空间时补
      createpneumatictacticals:。
    - 标识用指令帧（timeline 且 script == 模块 id）：只作路由标记，
      不输出到任何文件。
    - 其余帧（粒子、非标识指令帧）：留在机匣。
    """
    kfs = animator.get("keyframes", [])

    def script_of(kf):
        return str((kf.get("data_points") or [{}])[0].get("script", "")
                   ).strip()

    markers = []
    for kf in kfs:
        if kf.get("channel") == "timeline":
            script = script_of(kf)
            if script in module_ids:
                markers.append((float(kf.get("time", 0) or 0), script))
    sound_times = [float(kf.get("time", 0) or 0) for kf in kfs
                   if kf.get("channel") == "sound"]

    routed = {}
    for kf in kfs:
        ch = kf.get("channel")
        t = float(kf.get("time", 0) or 0)
        if ch == "timeline":
            script = script_of(kf)
            if script in module_ids:
                continue  # 标识用指令帧，不输出
            if script and any(abs(t - st) < SOUND_ROUTE_EPS
                              for st in sound_times):
                warn(f"动画 {anim_name}：{t}s 指令帧 script={script!r} "
                     f"不匹配任何模块 id，同帧音效留在机匣")
            routed.setdefault(receiver_id, []).append(copy.deepcopy(kf))
        elif ch == "sound":
            hits = [mid for mt, mid in markers
                    if abs(mt - t) < SOUND_ROUTE_EPS]
            if len(hits) > 1:
                warn(f"动画 {anim_name}：{t}s 处有多个模块指令帧 "
                     f"{hits}，该帧音效留在机匣")
            target = hits[0] if len(hits) == 1 else receiver_id
            kf2 = copy.deepcopy(kf)
            for dp in kf2.get("data_points", []):
                eff = dp.get("effect")
                if eff and ":" not in eff:
                    dp["effect"] = f"{DEFAULT_SOUND_NAMESPACE}:{eff}"
            routed.setdefault(target, []).append(kf2)
        else:
            routed.setdefault(receiver_id, []).append(copy.deepcopy(kf))
    return routed


def _transform_animator(animator, rt, warn, ctx):
    """把 animator 的关键帧从统合坐标系共轭到模块坐标系。

    GeckoLib 的骨骼关键帧是绝对值：position 是扁平坐标系里的平移
    （prep = T(pos)·T(p)·R·S·T(-p)），rotation 是绕 pivot 的旋转，scale
    在旋转之后相乘。共轭后 position' = Rt·pos、rotation' = Rt·R·Rt⁻¹、
    scale' = Rt·S·Rt⁻¹（仅等比/轴对齐缩放仍是对角阵）。
    """
    for kf in animator.get("keyframes", []):
        for dp in kf.get("data_points", []):
            vec = [float(dp.get("x", 0) or 0), float(dp.get("y", 0) or 0),
                   float(dp.get("z", 0) or 0)]
            if kf["channel"] == "position":
                nv = _mat3_apply(rt, vec)
            elif kf["channel"] == "rotation":
                nv = _mat3_to_euler(
                    _mat3_mul(_mat3_mul(rt, _deg_mat3(vec)),
                              _mat3_transpose(rt)))
            elif kf["channel"] == "scale":
                s = ((vec[0], 0.0, 0.0), (0.0, vec[1], 0.0),
                     (0.0, 0.0, vec[2]))
                s = _mat3_mul(_mat3_mul(rt, s), _mat3_transpose(rt))
                if any(abs(s[i][j]) > 1e-6 for i in range(3)
                       for j in range(3) if i != j):
                    warn(f"{ctx}: 缩放关键帧 {vec} 在定位骨旋转下无法表示"
                         f"为轴对齐缩放，已保持原值")
                    continue
                nv = [s[0][0], s[1][1], s[2][2]]
            else:
                continue
            dp["x"], dp["y"], dp["z"] = (repr(_r5(nv[0])), repr(_r5(nv[1])),
                                         repr(_r5(nv[2])))


def _split_animations(model, mod, anim_routes, warn):
    """动画按骨骼归属拆分；音效/指令关键帧按 anim_routes 路由结果分配。"""
    out = []
    rt = _mat4_rot3(mod.rebase)
    rot_changed = not _rot_is_identity(rt)
    for anim in model.data.get("animations", []):
        sub = {}
        for auuid, animator in anim.get("animators", {}).items():
            if animator.get("type", "bone") == "bone":
                if auuid in mod.bone_uuids:
                    sub[auuid] = copy.deepcopy(animator)
            else:
                kflist = anim_routes.get((anim.get("uuid"), auuid), {}
                                         ).get(mod.id)
                if kflist:
                    eff = copy.deepcopy(animator)
                    eff["keyframes"] = kflist
                    sub[auuid] = eff
        if not sub:
            continue
        # 模块坐标系的旋转与统合坐标系不同：模块内每个被驱动骨骼的
        # 关键帧都要跟着共轭（否则动画姿态与静止姿态不一致）
        if rot_changed:
            for animator in sub.values():
                if animator.get("type", "bone") == "bone":
                    _transform_animator(animator, rt, warn,
                                        f"{mod.id} 动画 {anim['name']}")
            warn(f"{mod.id}: 动画 {anim['name']} 的骨骼关键帧已按定位骨"
                 f"旋转变换")
        if mod.root_uuid in sub:
            sub[mod.root_uuid]["name"] = "main"
        new_anim = copy.deepcopy(anim)
        event = anim["name"].split(".")[-1]
        new_anim["name"] = f"animation.{mod.id}.{event}"
        new_anim["animators"] = sub
        out.append(new_anim)
    return out


# ------------------------------------------------------- GeckoLib 导出（可选）
# --geo / --anim 直接把每模块导出成枪包需要的 .geo.json / .animation.json，
# 省掉「逐个 bbmodel 在 Blockbench 里手点导出」。规则不是猜的：仓库里 26 份
# .geo.json、9 份 .animation.json 与各自的 .bbmodel 逐字段比对反推出下面这套
# 规则，并对全部语料验证通过（剩余差异已逐条确认是语料自身过时或手改，
# 见导出后校验一节）。Blockbench 用插件导出、脚本用这里导出，产物等价。
#
# 几何 —— Blockbench 的 GeckoLib 导出对 X 轴做了镜像：
#   骨骼 pivot = [-origin.x, origin.y, origin.z]
#   骨骼 rotation = [-rx, -ry, rz]（源没有旋转就不写）
#   立方体 origin = [-to.x, from.y, from.z]，size = to - from（恒正）
#   带旋转的立方体另写 rotation（同上取反）与 pivot（= -origin.x）
#   uv：box_uv 元素写 [u, v]；否则按面写 {uv: [...], uv_size: [...]}，
#   其中 up/down 面的原点是 (u2, v2)、尺寸取负（和 Blockbench 一致）
#   visible_bounds_* 取 bbmodel 的 visible_box；identifier 取 model_identifier
#   （空则 geometry.unknown，和 Blockbench 一致；本 mod 不校验该字段）
#   export=false 的骨骼/立方体与 Blockbench 一样跳过
#
# 动画 —— 与贴图无关，关键帧数值原样透传，不做镜像：
#   时间是 round(...,4) 后的字符串键、按时间升序；某通道只有 1 个关键帧时
#   写成不带时间的裸 {"vector": [...]}（语料可证 Blockbench 就是这样写的）；
#   loop=once 不写 loop 字段（GeckoLib 缺省即 play_once），loop -> true，
#   hold_on_last_frame -> "hold_on_last_frame"；
#   sound_effects / timeline 按 GeckoLib 的 {时间: {...}} 结构写。
#   start_delay / blend_weight / anim_time_update / override_previous_animation
#   GeckoLib 运行时不读，空值一律不写。

def _geo_num(v):
    f = round(float(v), 5)
    return int(f) if f == int(f) else f


def _geo_rot(rot):
    return [_geo_num(-rot[0]), _geo_num(-rot[1]), _geo_num(rot[2])]


def _geo_face_uv(face_name, uv):
    u1, v1, u2, v2 = uv
    if face_name in ("up", "down"):
        return [u2, v2], [u1 - u2, v1 - v2]
    return [u1, v1], [u2 - u1, v2 - v1]


def export_geo(data):
    """按 Blockbench 的 GeckoLib 导出规则，把（拆分后的）bbmodel 转成 geo。"""
    res = data.get("resolution") or {"width": 16, "height": 16}
    vbox = data.get("visible_box") or [2, 1.5, 0.25]
    elements = {e["uuid"]: e for e in data.get("elements", [])}
    bones = []

    def walk(items, parent):
        for it in items:
            if not isinstance(it, dict) or it.get("export") is False:
                continue
            bone = {"name": it["name"]}
            if parent:
                bone["parent"] = parent
            origin = it.get("origin") or [0, 0, 0]
            bone["pivot"] = [_geo_num(-origin[0]), _geo_num(origin[1]),
                             _geo_num(origin[2])]
            if it.get("rotation"):
                bone["rotation"] = _geo_rot(it["rotation"])
            cubes = []
            for child in it.get("children", []):
                if isinstance(child, dict):
                    continue
                el = elements[child]
                if el.get("export") is False:
                    continue
                f, t = el["from"], el["to"]
                cube = {"origin": [_geo_num(-t[0]), _geo_num(f[1]),
                                   _geo_num(f[2])],
                        "size": [_geo_num(t[0] - f[0]), _geo_num(t[1] - f[1]),
                                 _geo_num(t[2] - f[2])]}
                if el.get("rotation"):
                    pivot = el.get("origin") or [0, 0, 0]
                    cube["rotation"] = _geo_rot(el["rotation"])
                    cube["pivot"] = [_geo_num(-pivot[0]), _geo_num(pivot[1]),
                                     _geo_num(pivot[2])]
                if el.get("inflate"):
                    cube["inflate"] = _geo_num(el["inflate"])
                if el.get("mirror_uv"):
                    cube["mirror"] = True
                if el.get("box_uv"):
                    cube["uv"] = [_geo_num(el.get("uv", [0, 0])[0]),
                                  _geo_num(el.get("uv", [0, 0])[1])]
                else:
                    uv = {}
                    for face_name, face in el.get("faces", {}).items():
                        if face.get("texture") is None:
                            continue
                        u, size = _geo_face_uv(face_name, face["uv"])
                        uv[face_name] = {
                            "uv": [_geo_num(u[0]), _geo_num(u[1])],
                            "uv_size": [_geo_num(size[0]), _geo_num(size[1])]}
                    cube["uv"] = uv
                cubes.append(cube)
            if cubes:
                bone["cubes"] = cubes
            bones.append(bone)
            walk(it.get("children", []), it["name"])

    walk(data.get("outliner", []), None)
    return {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": "geometry." + (data.get("model_identifier")
                                             or "unknown"),
                "texture_width": res["width"],
                "texture_height": res["height"],
                "visible_bounds_width": _geo_num(vbox[0]),
                "visible_bounds_height": _geo_num(vbox[1]),
                "visible_bounds_offset": [0, _geo_num(vbox[2]), 0],
            },
            "bones": bones,
        }],
    }


# bbmodel 的 loop -> GeckoLib 的 loop 字段（缺省/once 不写：GeckoLib 默认
# play_once，语料里 19 个动画全是 once，导出的文件里也确实没有 loop 键）
LOOP_FIELD = {"once": None, "loop": True,
              "hold_on_last_frame": "hold_on_last_frame"}


def export_animation(data, warn=None):
    """按 Blockbench 的 GeckoLib 导出规则，把（拆分后的）bbmodel 动画转成
    .animation.json。没有动画时返回 None。"""
    if not data.get("animations"):
        return None
    anims = {}
    for anim in data["animations"]:
        for key in ("start_delay", "loop_delay", "anim_time_update",
                    "blend_weight"):
            if str(anim.get(key) or "").strip() and warn:
                warn(f"动画 {anim.get('name')} 的 {key}="
                     f"{anim[key]!r} 未导出（GeckoLib 不使用该字段）")
        bones = {}
        markers = {}
        for animator in anim.get("animators", {}).values():
            if animator.get("type", "bone") != "bone":
                for kf in animator.get("keyframes", []):
                    t = str(round(float(kf["time"]), 4))
                    dp = (kf.get("data_points") or [{}])[0]
                    if kf.get("channel") == "sound":
                        markers.setdefault("sound_effects", {})[t] = {
                            "effect": dp.get("effect", "")}
                    elif kf.get("channel") == "timeline":
                        markers.setdefault("timeline", {})[t] = \
                            f"{dp.get('script', '')};"
                    elif kf.get("channel") == "particle":
                        markers.setdefault("particle_effects", {})[t] = \
                            {"effect": dp.get("effect", "")}
                continue
            channels = {}
            for kf in animator.get("keyframes", []):
                dp = (kf.get("data_points") or [{}])[0]
                entry = {"vector": [_geo_num(dp.get("x", 0) or 0),
                                    _geo_num(dp.get("y", 0) or 0),
                                    _geo_num(dp.get("z", 0) or 0)]}
                if kf.get("easing"):
                    entry["easing"] = kf["easing"]
                channels.setdefault(kf["channel"], []).append(
                    (round(float(kf["time"]), 4), entry))
            if not channels:
                continue
            out = {}
            for channel, kfs in channels.items():
                kfs.sort(key=lambda kv: kv[0])
                if len(kfs) == 1:
                    out[channel] = kfs[0][1]
                else:
                    out[channel] = {str(t): e for t, e in kfs}
            bones[animator["name"]] = out
        entry = {"animation_length": _geo_num(anim.get("length", 0))}
        loop = LOOP_FIELD.get(anim.get("loop") or "once")
        if loop is not None:
            entry["loop"] = loop
        if bones:
            entry["bones"] = bones
        entry.update(markers)
        anims[anim["name"]] = entry
    return {"format_version": "1.8.0", "animations": anims,
            "geckolib_format_version": 2}


def _verify_exports(modules, reloaded, exported):
    """导出物与写出的 bbmodel 必须严格对应（丢骨骼/丢立方体/丢关键帧、
    UV 越界、引用不存在的骨骼都算错）。"""
    errors = []
    for mod in modules:
        out = reloaded[mod.id]
        # 输出 bbmodel 里应当被导出的骨骼/立方体（export=false 的除外）
        want_bones, want_cubes = {}, {}
        out_elements = {e["uuid"]: e for e in out.get("elements", [])}

        def collect(items, parent):
            for it in items:
                if not isinstance(it, dict) or it.get("export") is False:
                    continue
                want_bones[it["name"]] = (parent, it.get("origin"),
                                          it.get("rotation"))
                for child in it.get("children", []):
                    if isinstance(child, dict):
                        continue
                    el = out_elements[child]
                    if el.get("export") is False:
                        continue
                    want_cubes.setdefault(it["name"], []).append(el)
                collect(it.get("children", []), it["name"])

        collect(out["outliner"], None)

        geo = exported.get((mod.id, "geo"))
        if geo is not None:
            desc = geo["minecraft:geometry"][0]["description"]
            res = out["resolution"]
            if (desc["texture_width"], desc["texture_height"]) != \
                    (res["width"], res["height"]):
                errors.append(f"{mod.id}: geo 贴图尺寸与 resolution 不一致")
            got_bones = {}
            got_cubes = 0
            for bone in geo["minecraft:geometry"][0]["bones"]:
                got_bones[bone["name"]] = bone
                got_cubes += len(bone.get("cubes", []))
            if set(got_bones) != set(want_bones):
                errors.append(f"{mod.id}: geo 骨骼集合不一致 "
                              f"缺={set(want_bones)-set(got_bones)} "
                              f"多={set(got_bones)-set(want_bones)}")
            if got_cubes != sum(len(v) for v in want_cubes.values()):
                errors.append(f"{mod.id}: geo 立方体数量不一致 "
                              f"{got_cubes} != "
                              f"{sum(len(v) for v in want_cubes.values())}")
            for name, bone in got_bones.items():
                if name not in want_bones:
                    continue
                parent, origin, rotation = want_bones[name]
                origin = origin or [0, 0, 0]
                if bone.get("parent") != (parent or None) and parent:
                    errors.append(f"{mod.id}: geo {name} 的 parent 不对")
                if bone.get("pivot") != [-origin[0], origin[1], origin[2]]:
                    errors.append(f"{mod.id}: geo {name} 的 pivot 不对")
                if rotation:
                    if bone.get("rotation") != [-rotation[0], -rotation[1],
                                                rotation[2]]:
                        errors.append(f"{mod.id}: geo {name} 的 rotation 不对")
                elif bone.get("rotation"):
                    errors.append(f"{mod.id}: geo {name} 多了 rotation")
                for cube in bone.get("cubes", []):
                    uv = cube.get("uv")
                    if isinstance(uv, dict):
                        for face in uv.values():
                            u, size = face["uv"], face["uv_size"]
                            for dx in (0, size[0]):
                                for dy in (0, size[1]):
                                    if not (-1e-6 <= u[0] + dx
                                            <= res["width"] + 1e-6) or \
                                            not (-1e-6 <= u[1] + dy
                                                 <= res["height"] + 1e-6):
                                        errors.append(
                                            f"{mod.id}: geo {name} 的 UV 越界")
                    elif isinstance(uv, list):
                        if min(uv) < -1e-6:
                            errors.append(f"{mod.id}: geo {name} 的 UV 越界")
                    size = cube["size"]
                    if min(size) < -1e-6:
                        errors.append(f"{mod.id}: geo {name} 的 size 为负")

        anim = exported.get((mod.id, "anim"))
        if anim is not None:
            want_anims = {a["name"] for a in out.get("animations", [])}
            if set(anim["animations"]) != want_anims:
                errors.append(f"{mod.id}: animation 名字不一致 "
                              f"缺={want_anims-set(anim['animations'])} "
                              f"多={set(anim['animations'])-want_anims}")
            for name, exp in anim["animations"].items():
                src = next((a for a in out.get("animations", [])
                            if a["name"] == name), None)
                if src is None:
                    continue
                if exp.get("animation_length") != \
                        _geo_num(src.get("length", 0)):
                    errors.append(f"{mod.id}: {name} 的 animation_length 不一致")
                want_n = sum(len(a.get("keyframes", []))
                             for a in src.get("animators", {}).values()
                             if a.get("type", "bone") == "bone")
                got_n = sum(
                    sum(len(v) if isinstance(v, dict) and "vector" not in v
                        else 1 for v in ch.values())
                    for bone in exp.get("bones", {}).values()
                    for ch in bone.values())
                if want_n != got_n:
                    errors.append(f"{mod.id}: {name} 关键帧数不一致 "
                                  f"{got_n} != {want_n}")
                for bone_name in exp.get("bones", {}):
                    if bone_name not in want_bones:
                        errors.append(f"{mod.id}: {name} 引用了模型里不存在"
                                      f"的骨骼 {bone_name}")
                want_sounds = sorted(
                    round(float(kf["time"]), 4)
                    for a in src.get("animators", {}).values()
                    if a.get("type", "bone") != "bone"
                    for kf in a.get("keyframes", [])
                    if kf.get("channel") == "sound")
                got_sounds = sorted(float(t) for t in
                                    exp.get("sound_effects", {}))
                if want_sounds != got_sounds:
                    errors.append(f"{mod.id}: {name} 音效帧不一致 "
                                  f"{got_sounds} != {want_sounds}")
                for kf in [k for a in src.get("animators", {}).values()
                           if a.get("type", "bone") != "bone"
                           for k in a.get("keyframes", [])
                           if k.get("channel") == "sound"]:
                    t = str(round(float(kf["time"]), 4))
                    dp = (kf.get("data_points") or [{}])[0]
                    got = exp.get("sound_effects", {}).get(t, {}).get("effect")
                    if got != dp.get("effect", ""):
                        errors.append(f"{mod.id}: {name} {t}s 音效不一致 "
                                      f"{got!r} != {dp.get('effect')!r}")
                    if ":" not in str(dp.get("effect", "")):
                        errors.append(f"{mod.id}: {name} 音效缺命名空间")
    return errors


# ---------------------------------------------------------------- 校验


def _chains(data):
    """{uuid: 骨骼链矩阵}：root->骨骼（含自身 prep）连乘；立方体取父骨骼的。

    这就是 GeckoLib 的静止姿态渲染链：立方体的世界点 = 链矩阵 · 渲染角点。
    """
    result = {}

    def walk(items, m):
        for it in items:
            if isinstance(it, dict):
                m2 = _mat4_mul(m, _prep_mat(it.get("origin"),
                                            it.get("rotation")))
                result[it["uuid"]] = m2
                walk(it.get("children", []), m2)
            else:
                result[it] = m

    walk(data.get("outliner", []), [row[:] for row in _IDENT4])
    return result


def _cube_corners(el):
    """立方体在自身骨骼坐标系里的 8 个渲染角点（含绕 origin 的旋转）。"""
    o = list(el.get("origin", [0, 0, 0]))
    r = _deg_mat3(el.get("rotation", [0, 0, 0]))
    f, t = el["from"], el["to"]
    pts = []
    for cx in (f[0], t[0]):
        for cy in (f[1], t[1]):
            for cz in (f[2], t[2]):
                rel = _mat3_apply(r, [cx - o[0], cy - o[1], cz - o[2]])
                pts.append([o[0] + rel[0], o[1] + rel[1], o[2] + rel[2]])
    return pts


def _verify(src_model, src_modules, outputs, pixel_checks):
    errors = []
    by_id = {m.id: m for m in src_modules}
    receiver = next(m for m in src_modules if m.type == "receiver")
    src_chain = _chains(src_model.data)
    out_chain = {m.id: _chains(outputs[m.id]) for m in src_modules}
    out_model = {m.id: Model(outputs[m.id]) for m in src_modules}

    def host_placement(mod):
        """模组渲染该模块时施加在「模块自身坐标系」上的变换（统合坐标系）。

        即 host_applyBoneChain(loc) 再映射回统合坐标系：宿主输出模型里
        root->定位骨 的链 + 平移到 pivot，最后左乘 inv(K_host)。
        """
        if mod.type == "receiver":
            return [row[:] for row in _IDENT4]
        host = _parent_module(src_model, by_id, mod) or receiver
        hmodel = out_model[host.id]
        loc_name = src_model.bones[mod.loc_uuid]["name"]
        lu = next((u for u, b in hmodel.bones.items()
                   if b["name"] == loc_name), None)
        if lu is None:
            errors.append(f"{mod.id}: 宿主 {host.id} 缺少定位骨 {loc_name}")
            return None
        l_host = _mat4_mul(out_chain[host.id][lu],
                           _mat4_trs(hmodel.bones[lu].get("origin",
                                                          [0, 0, 0]), None))
        return _mat4_mul(_mat4_invert_rigid(host.rebase), l_host)

    for mod in src_modules:
        out = outputs[mod.id]
        omodel = out_model[mod.id]
        k = mod.rebase
        kr = _mat4_rot3(k)

        # 1. 集合一致：模块的骨骼（含根）与立方体
        want_bones = set(mod.bone_uuids)
        want_cubes = set(mod.cube_uuids)
        got_bones = set(omodel.bones)
        got_cubes = set(omodel.cube_parent)
        if want_bones != got_bones:
            errors.append(f"{mod.id}: 骨骼集合不一致 "
                          f"缺={want_bones-got_bones} 多={got_bones-want_bones}")
        if want_cubes != got_cubes:
            errors.append(f"{mod.id}: 立方体集合不一致 "
                          f"缺={want_cubes-got_cubes} 多={got_cubes-want_cubes}")

        # 2. 骨骼：pivot/rotation 必须等于 K 共轭后的值
        worst = 0.0
        for uuid in sorted(want_bones & got_bones):
            sb = src_model.bones[uuid]
            ob = omodel.bones[uuid]
            want_p = _mat4_apply(k, list(sb.get("origin", [0, 0, 0])))
            got_p = list(ob.get("origin", [0, 0, 0]))
            worst = max(worst, max(abs(want_p[i] - got_p[i])
                                   for i in range(3)))
            rs = _deg_mat3(sb.get("rotation") or [0, 0, 0])
            want_r = _mat3_mul(_mat3_mul(kr, rs), _mat3_transpose(kr))
            got_r = _deg_mat3(ob.get("rotation") or [0, 0, 0])
            worst = max(worst, max(abs(want_r[i][j] - got_r[i][j])
                                   for i in range(3) for j in range(3)))
        if worst > 1e-4:
            errors.append(f"{mod.id}: 骨骼 pivot/rotation 共轭误差 {worst}")

        # 3. 立方体：模块坐标系里的渲染 == K · 统合渲染；
        #    再按模组的挂载语义摆回宿主，必须与统合渲染重合
        placement = host_placement(mod)
        worst_local = worst_world = 0.0
        for uuid in sorted(want_cubes & got_cubes):
            s_el = src_model.elements[uuid]
            o_el = omodel.elements[uuid]
            s_pts = [_mat4_apply(src_chain[uuid], p)
                     for p in _cube_corners(s_el)]
            o_pts = [_mat4_apply(out_chain[mod.id][uuid], p)
                     for p in _cube_corners(o_el)]
            for a in s_pts:
                ka = _mat4_apply(k, a)
                worst_local = max(worst_local, min(
                    max(abs(ka[i] - b[i]) for i in range(3)) for b in o_pts))
            if placement is None:
                continue
            for b in o_pts:
                pb = _mat4_apply(placement, b)
                worst_world = max(worst_world, min(
                    max(abs(pb[i] - a[i]) for i in range(3)) for a in s_pts))
        if worst_local > 1e-4:
            errors.append(f"{mod.id}: 立方体几何共轭误差 {worst_local}")
        if worst_world > 1e-4:
            errors.append(f"{mod.id}: 摆回宿主后与统合模型几何误差 "
                          f"{worst_world}")

        # 3. UV 边界
        res = out["resolution"]
        for el in out["elements"]:
            for face in el.get("faces", {}).values():
                if face.get("texture") is None:
                    continue
                u1, v1, u2, v2 = face["uv"]
                if min(u1, u2, v1, v2) < -1e-6 or \
                        max(u1, u2) > res["width"] + 1e-6 or \
                        max(v1, v2) > res["height"] + 1e-6:
                    errors.append(f"{mod.id}: UV 越界 {face['uv']}")

        # 4. 动画完整性
        module_ids = {m.id for m in src_modules}
        out_bones = set(omodel.bones)
        for anim in out.get("animations", []):
            for auuid, animator in anim.get("animators", {}).items():
                if animator.get("type", "bone") == "bone":
                    if auuid not in out_bones:
                        errors.append(f"{mod.id}: 动画 {anim['name']} 引用了"
                                      f"不存在的骨骼 {auuid}")
                    if auuid == mod.root_uuid and animator["name"] != "main":
                        errors.append(f"{mod.id}: 根骨 animator 未改名 main")
                else:
                    for kf in animator.get("keyframes", []):
                        ch = kf.get("channel")
                        dp0 = (kf.get("data_points") or [{}])[0]
                        if ch == "sound" and ":" not in str(
                                dp0.get("effect", "")):
                            errors.append(
                                f"{mod.id}: 动画 {anim['name']} 的音效 "
                                f"{dp0.get('effect')!r} 缺命名空间")
                        if ch == "timeline" and str(
                                dp0.get("script", "")).strip() in module_ids:
                            errors.append(
                                f"{mod.id}: 动画 {anim['name']} 残留标识用"
                                f"指令帧 {dp0.get('script')!r}")

    # 4b. 音效守恒：每个事件的 sound 帧总数 == 源模型
    def count_sounds(anims):
        counts = {}
        for anim in anims:
            event = anim["name"].split(".")[-1]
            n = sum(1 for a in anim.get("animators", {}).values()
                    if a.get("type", "bone") != "bone"
                    for kf in a.get("keyframes", [])
                    if kf.get("channel") == "sound")
            counts[event] = counts.get(event, 0) + n
        return counts

    src_sounds = count_sounds(src_model.data.get("animations", []))
    out_sounds = {}
    for out in outputs.values():
        for event, n in count_sounds(out.get("animations", [])).items():
            out_sounds[event] = out_sounds.get(event, 0) + n
    if src_sounds != out_sounds:
        errors.append(f"音效帧不守恒：源 {src_sounds} vs 输出 {out_sounds}")

    # 5. 像素级贴图核对
    for label, mapping, new_img, src_imgs in pixel_checks:
        for r, (nx, ny) in mapping.items():
            t, x1, y1, x2, y2 = r
            a = src_imgs[t].crop((x1, y1, x2, y2))
            b = new_img.crop((nx, ny, nx + (x2 - x1), ny + (y2 - y1)))
            if a.tobytes() != b.tobytes():
                errors.append(f"{label}: 贴图区域 {r} 像素不一致")
                break

    return errors


# ---------------------------------------------------------------- 主流程


def split(bbmodel_path, out_dir, geo=False, anim=False):
    src_path = Path(bbmodel_path)
    data = json.loads(src_path.read_text(encoding="utf-8"))
    model = Model(data)

    warnings = []

    def warn(msg):
        warnings.append(msg)
        print(f"  [warn] {msg}", file=sys.stderr)

    modules_order, modules = _collect_modules(model)
    _resolve_locators(model, modules)

    base_images, scales = _load_texture_images(data)
    mask_inputs = {}
    for suffix in MASK_SUFFIXES:
        p = src_path.with_name(src_path.stem + suffix + ".png")
        if p.exists():
            img = Image.open(p)
            if img.size != base_images[0].size:
                raise SystemExit(
                    f"错误：{p.name} 尺寸 {img.size} 与主贴图 "
                    f"{base_images[0].size} 不一致")
            mask_inputs[suffix] = img.convert("RGBA")

    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    # 不删除旧文件：记录拆分前已存在的文件，结束后提示可能过时的残留
    preexisting = {p.name for p in out_dir.glob("*.bbmodel")}
    preexisting |= {p.name for p in out_dir.glob("*.png")}
    preexisting |= {p.name for p in out_dir.glob("*.geo.json")}
    preexisting |= {p.name for p in out_dir.glob("*.animation.json")}

    all_bone_uuids = set(model.bones)
    receiver = next(m for m in modules_order if m.type == "receiver")
    module_ids = set(modules)
    # 音效/指令关键帧路由：每个动画只算一次
    anim_routes = {}
    for src_anim in data.get("animations", []):
        for auuid, animator in src_anim.get("animators", {}).items():
            if animator.get("type", "bone") == "bone":
                continue
            anim_routes[(src_anim.get("uuid"), auuid)] = _route_effect_animator(
                animator, module_ids, receiver.id, src_anim["name"], warn)
    outputs = {}
    exported = {}
    pixel_checks = []
    summary = []

    for mod in modules_order:
        main_root, rebased_cubes = _rebase_module_tree(model, mod)
        cube_set = [copy.deepcopy(rebased_cubes.get(cu, model.elements[cu]))
                    for cu in mod.cube_uuids]

        # --- 贴图裁切 + 重排
        rects = _collect_rects(cube_set, scales)
        w, h, placement = _pack_rects(rects, warn)
        new_img, mapping = _build_module_texture(placement, base_images,
                                                 w, h)
        _remap_uvs(cube_set, scales, mapping)
        pixel_checks.append((f"{mod.id} base", mapping, new_img,
                             base_images))
        png_name = f"{mod.id}.png"
        new_img.save(out_dir / png_name)

        mask_files = []
        for suffix, mask_img in mask_inputs.items():
            m_img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
            for r, (nx, ny) in mapping.items():
                t, x1, y1, x2, y2 = r
                m_img.paste(mask_img.crop((x1, y1, x2, y2)), (nx, ny))
            m_name = f"{mod.id}{suffix}.png"
            m_img.save(out_dir / m_name)
            mask_files.append(m_name)
            pixel_checks.append((f"{mod.id}{suffix}", mapping, m_img,
                                 [mask_inputs[suffix]]))

        src_tex = data["textures"][0]
        new_tex = copy.deepcopy(src_tex)
        new_tex.update({
            "path": str((out_dir / png_name).resolve()),
            "name": png_name,
            "width": w,
            "height": h,
            "uv_width": w,
            "uv_height": h,
            "source": _img_to_data_url(new_img),
            "saved": True,
            # 确定性 uuid：同一模块重复拆分得到同一个贴图 uuid，
            # 避免「重新生成后整份文件都 diff」
            "uuid": str(uuidlib.uuid5(
                uuidlib.NAMESPACE_URL,
                f"{src_tex.get('uuid', '0')}/{mod.id}")),
            "id": "0",
            "relative_path": png_name,
        })

        # --- 动画
        anims = _split_animations(model, mod, anim_routes, warn)

        # --- 组装输出 bbmodel
        skip_keys = {"elements", "outliner", "textures", "animations",
                     "resolution", "name", "model_identifier",
                     "geckolib_filepath_cache"}
        out = {k: copy.deepcopy(v) for k, v in data.items()
               if k not in skip_keys}
        out["name"] = mod.id
        out["model_identifier"] = mod.id
        out["resolution"] = {"width": w, "height": h}
        out["elements"] = cube_set
        out["outliner"] = [main_root]
        out["textures"] = [new_tex]
        out["animations"] = anims
        outputs[mod.id] = out

        (out_dir / f"{mod.id}.bbmodel").write_text(
            json.dumps(out, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8")

        # --- 可选：直接导出枪包要的 geo / animation（等价于 Blockbench 导出）
        exported_files = []
        if geo:
            doc = export_geo(out)
            (out_dir / f"{mod.id}.geo.json").write_text(
                json.dumps(doc, ensure_ascii=False, separators=(",", ":")),
                encoding="utf-8")
            exported[(mod.id, "geo")] = doc
            exported_files.append("geo")
        if anim:
            doc = export_animation(out, warn)
            if doc is not None:
                (out_dir / f"{mod.id}.animation.json").write_text(
                    json.dumps(doc, ensure_ascii=False,
                               separators=(",", ":")), encoding="utf-8")
                exported[(mod.id, "anim")] = doc
                exported_files.append("anim")
        summary.append((mod.id, mod.type,
                        model.bones[mod.loc_uuid]["name"]
                        if mod.loc_uuid else "-",
                        len(cube_set), len(mod.bone_uuids), f"{w}x{h}",
                        len(anims), ", ".join(mask_files),
                        ",".join(exported_files) or "-"))

    # --- 校验（重新读盘，确保写出的文件自洽）
    reloaded = {mod.id: json.loads(
        (out_dir / f"{mod.id}.bbmodel").read_text(encoding="utf-8"))
        for mod in modules_order}
    errors = _verify(model, modules_order, reloaded, pixel_checks)
    errors += _verify_exports(modules_order, reloaded, exported)

    written = set()
    for mod in modules_order:
        written.add(f"{mod.id}.bbmodel")
        written.add(f"{mod.id}.png")
        for suffix in mask_inputs:
            written.add(f"{mod.id}{suffix}.png")
        for tag, suffix in (("geo", ".geo.json"),
                            ("anim", ".animation.json")):
            if (mod.id, tag) in exported:
                written.add(f"{mod.id}{suffix}")
    stale = sorted(preexisting - written)

    print(f"\n拆分完成 -> {out_dir}")
    print(f"{'模块 id':<34} {'类型':<10} {'定位骨':<22} {'cube':>4} "
          f"{'bone':>4} {'贴图':>7} {'动画':>3}  {'导出':<9} 掩码")
    for row in summary:
        print(f"{row[0]:<34} {row[1]:<10} {row[2]:<22} {row[3]:>4} "
              f"{row[4]:>4} {row[5]:>7} {row[6]:>3}  {row[8]:<9} {row[7]}")
    if stale:
        print("\n以下文件本次未生成，可能已过时（未自动删除，请人工确认）：")
        for name in stale:
            print(f"  [stale] {name}")
    if errors:
        print("\n校验失败：")
        for e in errors:
            print(f"  [ERROR] {e}")
        raise SystemExit(1)
    print("\n校验通过：骨骼 pivot/旋转共轭、立方体几何（含按模组语义摆回"
          "宿主）、UV 边界、动画骨骼引用、贴图像素 全部一致")
    if exported:
        kinds = sorted({tag for _, tag in exported})
        target = " + ".join({"geo": "geo/gun", "anim": "animations/gun"}[k]
                            for k in kinds)
        print(f"已同时导出 {'/'.join(kinds)}：共 {len(exported)} 个文件"
              f"（拷进枪包的 {target} 即可）")
    return 0


def main():
    ap = argparse.ArgumentParser(description="统合枪模 bbmodel 拆分工具")
    ap.add_argument("bbmodel", help="统合 .bbmodel 路径")
    ap.add_argument("-o", "--out", default=None,
                    help="输出目录（默认 <bbmodel目录>/split/）")
    ap.add_argument("--geo", action="store_true",
                    help="同时导出每模块 <id>.geo.json（等价 Blockbench 导出）")
    ap.add_argument("--anim", action="store_true",
                    help="同时导出每模块 <id>.animation.json（有动画时）")
    ap.add_argument("--gecko", action="store_true",
                    help="等于 --geo --anim")
    args = ap.parse_args()
    src = Path(args.bbmodel)
    out_dir = Path(args.out) if args.out else src.parent / "split"
    raise SystemExit(split(src, out_dir, geo=args.geo or args.gecko,
                           anim=args.anim or args.gecko))


if __name__ == "__main__":
    main()
