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
  把模块内容重算到以 loc pivot 为原点、loc 旋转为单位阵的局部空间；
- UV 从统合贴图裁出、去重、重排成每模块一张紧凑贴图（<=64x64 优先，
  内嵌回 bbmodel 并同时写出 <id>.png）；若统合 bbmodel 旁存在同尺寸的
  <名>_glowmask.png / <名>_dye.png，按同一映射拆出每模块的掩码；
- 动画按骨骼归属分给各模块（只保留该模块骨骼的 animator），
  每个输出模型的根骨骼统一改名 main。

用法：
    python tools/split_unified_bbmodel.py gecko/marble_17/marble_17.bbmodel
    python tools/split_unified_bbmodel.py <bbmodel> -o <输出目录>

依赖：Pillow。拆分后每个 <id>.bbmodel 用 Blockbench 打开，
按指南 2.8 节导出 geo / 动画即可。脚本不会删除输出目录里的旧文件；
本次未重新生成的 .bbmodel / .png 会在结束时列为「可能过时」提示。
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


def _mat4_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)]
            for i in range(4)]


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

    def world_mat(self, bone_uuid):
        chain = []
        cur = bone_uuid
        while cur is not None:
            chain.append(cur)
            cur = self.parent[cur]
        m = [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]
        for cur in reversed(chain):
            b = self.bones[cur]
            m = _mat4_mul(m, _mat4_trs(b.get("origin", [0, 0, 0]),
                                       b.get("rotation")))
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
    """为每个模块找到定位骨，并计算 rebase = inv(A_loc) @ A_modroot。"""
    receiver = next(m for m in modules.values() if m.type == "receiver")
    receiver_bones = [b for b in _subtree_bones(model, receiver.root_uuid)
                      if b["uuid"] in receiver.bone_uuids]

    for mod in modules.values():
        if mod.type == "receiver":
            # 机匣内容保持在机匣根骨空间；根骨自身变换归零
            mod.rebase = model.world_mat(mod.root_uuid)
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
        mod.rebase = _mat4_mul(_mat4_invert_rigid(
            model.world_mat(mod.loc_uuid)), model.world_mat(mod.root_uuid))


# ---------------------------------------------------------------- 重定位


def _rebase_cube(el, t, rt):
    """把立方体从模块根空间变换到新父空间（T = inv(A_loc) @ A_modroot）。

    不变式：origin' = T@origin；from/to 平移 origin'-origin（保持轴对齐）；
    rotation' = Rt @ rotation。渲染结果与原模型世界坐标完全一致。
    """
    o = list(el.get("origin", [0, 0, 0]))
    o2 = _mat4_apply(t, o)
    delta = [o2[i] - o[i] for i in range(3)]
    el["origin"] = _r5v(o2)
    el["from"] = _r5v([el["from"][i] + delta[i] for i in range(3)])
    el["to"] = _r5v([el["to"][i] + delta[i] for i in range(3)])
    rc = el.get("rotation")
    if rc or not _rot_is_identity(rt):
        r = _mat3_mul(rt, _deg_mat3(rc or [0, 0, 0]))
        euler = _r5v(_mat3_to_euler(r))
        if any(abs(v) > 1e-7 for v in euler):
            el["rotation"] = euler
        else:
            el.pop("rotation", None)


def _rebase_bone_node(node, t, rt):
    node["origin"] = _r5v(_mat4_apply(t, list(node.get("origin", [0, 0, 0]))))
    rot = node.get("rotation")
    if rot or not _rot_is_identity(rt):
        r = _mat3_mul(rt, _deg_mat3(rot or [0, 0, 0]))
        euler = _r5v(_mat3_to_euler(r))
        if any(abs(v) > 1e-7 for v in euler):
            node["rotation"] = euler
        else:
            node.pop("rotation", None)


def _rebase_module_tree(model, mod):
    """返回 (main 根骨, {cube_uuid: rebased_element})。

    只有模块根的直接子节点需要变换坐标；更深层级的相对坐标不变。
    任意深度的嵌套模块子树都会被剔除（它们拆成自己的文件）。
    """
    t = mod.rebase
    rt = _mat4_rot3(t)
    rebased_cubes = {}

    def copy_bone(node, rebase_self):
        # rebase_self 只作用于本骨骼自己的 origin/rotation；
        # 子节点生活在本骨骼空间内，坐标保持不变。
        new_node = {k: copy.deepcopy(v) for k, v in node.items()
                    if k != "children"}
        if rebase_self:
            _rebase_bone_node(new_node, t, rt)
        children = []
        for child in node.get("children", []):
            if isinstance(child, dict):
                mtype, _ = _classify_bone(child["name"])
                if mtype:
                    continue  # 嵌套模块另拆
                children.append(copy_bone(child, False))
            else:
                children.append(child)
        new_node["children"] = children
        return new_node

    root = model.bones[mod.root_uuid]
    new_root = {k: copy.deepcopy(v) for k, v in root.items()
                if k != "children"}
    new_root["name"] = "main"
    new_root["origin"] = [0, 0, 0]
    new_root.pop("rotation", None)

    children = []
    for child in root.get("children", []):
        if isinstance(child, dict):
            mtype, _ = _classify_bone(child["name"])
            if mtype:
                continue
            node = copy_bone(child, True)
            children.append(node)
        else:
            el = copy.deepcopy(model.elements[child])
            _rebase_cube(el, t, rt)
            rebased_cubes[child] = el
            children.append(child)
    new_root["children"] = children
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


def _split_animations(model, mod, all_bone_uuids, warn):
    """动画按骨骼归属拆分；音效/粒子时间轴只留在机匣。"""
    out = []
    rt = _mat4_rot3(mod.rebase)
    for anim in model.data.get("animations", []):
        sub = {}
        effects = {}
        for auuid, animator in anim.get("animators", {}).items():
            if animator.get("type", "bone") != "bone" or \
                    auuid not in all_bone_uuids:
                effects[auuid] = copy.deepcopy(animator)
            elif auuid in mod.bone_uuids:
                sub[auuid] = copy.deepcopy(animator)
        if mod.type == "receiver":
            sub.update(effects)
        if not sub:
            continue
        if mod.root_uuid in sub:
            sub[mod.root_uuid]["name"] = "main"
            # 根骨关键帧的父空间变了：rebase 带旋转时位置/旋转关键帧要跟随
            if not _rot_is_identity(rt):
                for kf in sub[mod.root_uuid].get("keyframes", []):
                    for dp in kf.get("data_points", []):
                        vec = [float(dp.get("x", 0) or 0),
                               float(dp.get("y", 0) or 0),
                               float(dp.get("z", 0) or 0)]
                        if kf["channel"] == "position":
                            nv = _mat3_apply(rt, vec)
                        elif kf["channel"] == "rotation":
                            nv = _mat3_to_euler(
                                _mat3_mul(rt, _deg_mat3(vec)))
                        else:
                            continue
                        dp["x"], dp["y"], dp["z"] = (
                            repr(_r5(nv[0])), repr(_r5(nv[1])),
                            repr(_r5(nv[2])))
                warn(f"{mod.id}: 动画 {anim['name']} 的根骨关键帧已按"
                     f"定位骨旋转变换")
        new_anim = copy.deepcopy(anim)
        event = anim["name"].split(".")[-1]
        new_anim["name"] = f"animation.{mod.id}.{event}"
        new_anim["animators"] = sub
        out.append(new_anim)
    return out


# ---------------------------------------------------------------- 校验


def _walk_transforms(data):
    """遍历整个 outliner，产出 {uuid: (world_mat, parent_rot3)}。

    骨骼：world_mat 含自身 origin/rotation；立方体：world_mat =
    父骨骼的世界矩阵（立方体自身没有节点级变换）。
    """
    model = Model(data)
    result = {}
    ident = [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]

    def walk(items, m):
        for it in items:
            if isinstance(it, dict):
                m2 = _mat4_mul(m, _mat4_trs(it.get("origin", [0, 0, 0]),
                                            it.get("rotation")))
                result[it["uuid"]] = (m2, _mat4_rot3(m))
                walk(it.get("children", []), m2)
            else:
                result[it] = (m, _mat4_rot3(m))

    walk(data.get("outliner", []), ident)
    return model, result


def _verify(src_model, src_modules, outputs, pixel_checks):
    errors = []
    _, src_tr = _walk_transforms(src_model.data)

    for mod in src_modules:
        out = outputs[mod.id]
        out_model, out_tr = _walk_transforms(out)
        a_loc = (src_model.world_mat(mod.loc_uuid) if mod.loc_uuid
                 else [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0],
                       [0, 0, 0, 1]])
        r_loc = _mat4_rot3(a_loc)

        # 1. 集合一致：模块的骨骼（除根）与立方体
        want_bones = mod.bone_uuids - {mod.root_uuid}
        want_cubes = set(mod.cube_uuids)
        got_bones = set(out_model.bones) - {mod.root_uuid}
        got_cubes = set(out_model.cube_parent)
        if want_bones != got_bones:
            errors.append(f"{mod.id}: 骨骼集合不一致 "
                          f"缺={want_bones-got_bones} 多={got_bones-want_bones}")
        if want_cubes != got_cubes:
            errors.append(f"{mod.id}: 立方体集合不一致 "
                          f"缺={want_cubes-got_cubes} 多={got_cubes-want_cubes}")

        # 2. 几何往返 + 旋转复合
        worst = 0.0
        for uuid in sorted(want_bones & got_bones):

            s_m, s_pr = src_tr[uuid]
            o_m, o_pr = out_tr[uuid]
            # 骨骼 origin 在其父空间：世界点 = parent_world @ origin
            # src_tr[uuid] 已是 bone 自身世界矩阵（含自身 origin/rot），
            # 其平移列即世界坐标。
            pe = _mat4_apply(a_loc, [o_m[0][3], o_m[1][3], o_m[2][3]])
            for i in range(3):
                worst = max(worst, abs(s_m[i][3] - pe[i]))
            # 世界旋转：R_src == R_loc @ R_out
            rs = _mat4_rot3(s_m)
            ro = _mat3_mul(r_loc, _mat4_rot3(o_m))
            worst = max(worst, max(abs(rs[i][j] - ro[i][j])
                                   for i in range(3) for j in range(3)))
        for uuid in sorted(want_cubes & got_cubes):
            s_el = src_model.elements[uuid]
            o_el = out_model.elements[uuid]
            s_pm, s_pr = src_tr[uuid]
            o_pm, o_pr = out_tr[uuid]
            # 旋转立方体的 from/to 是「旋转前盒空间」坐标，两侧表示法
            # 可以不同；正确不变量是渲染后角点集合一致。
            def rendered_corners(el):
                o = list(el.get("origin", [0, 0, 0]))
                r = _deg_mat3(el.get("rotation", [0, 0, 0]))
                f, tt = el["from"], el["to"]
                out = []
                for cx in (f[0], tt[0]):
                    for cy in (f[1], tt[1]):
                        for cz in (f[2], tt[2]):
                            rel = _mat3_apply(r, [cx - o[0], cy - o[1],
                                                  cz - o[2]])
                            out.append([o[0] + rel[0], o[1] + rel[1],
                                        o[2] + rel[2]])
                return out

            pw = _mat4_apply(s_pm, list(s_el.get("origin", [0, 0, 0])))
            pe = _mat4_apply(a_loc, _mat4_apply(
                o_pm, list(o_el.get("origin", [0, 0, 0]))))
            worst = max(worst, max(abs(pw[i] - pe[i]) for i in range(3)))
            src_corners = [_mat4_apply(s_pm, p)
                           for p in rendered_corners(s_el)]
            out_corners = [_mat4_apply(a_loc, _mat4_apply(o_pm, p))
                           for p in rendered_corners(o_el)]
            for a in src_corners:
                d = min(max(abs(a[i] - b[i]) for i in range(3))
                        for b in out_corners)
                worst = max(worst, d)
            rs = _mat3_mul(s_pr, _deg_mat3(s_el.get("rotation", [0, 0, 0])))
            ro = _mat3_mul(r_loc, _mat3_mul(
                o_pr, _deg_mat3(o_el.get("rotation", [0, 0, 0]))))
            worst = max(worst, max(abs(rs[i][j] - ro[i][j])
                                   for i in range(3) for j in range(3)))
        if worst > 1e-4:
            errors.append(f"{mod.id}: 几何/旋转往返误差 {worst}")

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
        out_bones = set(out_model.bones)
        for anim in out.get("animations", []):
            for auuid, animator in anim.get("animators", {}).items():
                if animator.get("type", "bone") == "bone" and \
                        auuid not in out_bones:
                    errors.append(f"{mod.id}: 动画 {anim['name']} 引用了"
                                  f"不存在的骨骼 {auuid}")
                if auuid == mod.root_uuid and animator["name"] != "main":
                    errors.append(f"{mod.id}: 根骨 animator 未改名 main")

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


def split(bbmodel_path, out_dir):
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

    all_bone_uuids = set(model.bones)
    outputs = {}
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
            "uuid": str(uuidlib.uuid4()),
            "id": "0",
            "relative_path": png_name,
        })

        # --- 动画
        anims = _split_animations(model, mod, all_bone_uuids, warn)

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
        summary.append((mod.id, mod.type,
                        model.bones[mod.loc_uuid]["name"]
                        if mod.loc_uuid else "-",
                        len(cube_set), len(mod.bone_uuids), f"{w}x{h}",
                        len(anims), ", ".join(mask_files)))

    # --- 校验（重新读盘，确保写出的文件自洽）
    reloaded = {mod.id: json.loads(
        (out_dir / f"{mod.id}.bbmodel").read_text(encoding="utf-8"))
        for mod in modules_order}
    errors = _verify(model, modules_order, reloaded, pixel_checks)

    written = set()
    for mod in modules_order:
        written.add(f"{mod.id}.bbmodel")
        written.add(f"{mod.id}.png")
        for suffix in mask_inputs:
            written.add(f"{mod.id}{suffix}.png")
    stale = sorted(preexisting - written)

    print(f"\n拆分完成 -> {out_dir}")
    print(f"{'模块 id':<34} {'类型':<10} {'定位骨':<22} {'cube':>4} "
          f"{'bone':>4} {'贴图':>7} {'动画':>3}  掩码")
    for row in summary:
        print(f"{row[0]:<34} {row[1]:<10} {row[2]:<22} {row[3]:>4} "
              f"{row[4]:>4} {row[5]:>7} {row[6]:>3}  {row[7]}")
    if stale:
        print("\n以下文件本次未生成，可能已过时（未自动删除，请人工确认）：")
        for name in stale:
            print(f"  [stale] {name}")
    if errors:
        print("\n校验失败：")
        for e in errors:
            print(f"  [ERROR] {e}")
        raise SystemExit(1)
    print("\n校验通过：几何/旋转往返、UV 边界、动画骨骼引用、"
          "贴图像素 全部一致")
    return 0


def main():
    ap = argparse.ArgumentParser(description="统合枪模 bbmodel 拆分工具")
    ap.add_argument("bbmodel", help="统合 .bbmodel 路径")
    ap.add_argument("-o", "--out", default=None,
                    help="输出目录（默认 <bbmodel目录>/split/）")
    args = ap.parse_args()
    src = Path(args.bbmodel)
    out_dir = Path(args.out) if args.out else src.parent / "split"
    raise SystemExit(split(src, out_dir))


if __name__ == "__main__":
    main()
