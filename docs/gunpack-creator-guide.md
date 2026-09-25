# 枪包创作者指南

面向「想给本模组做新枪包，但不知道从哪里下手」的创作者。

- 你需要的工具：**Blockbench**（含 GeckoLib 插件）、任意图片编辑器、文本编辑器。
- 你需要的前置知识：会看 JSON，知道 Minecraft 的资源包/数据包目录长什么样。
- 本指南的所有字段、默认值、行为都来自模组实现；默认枪包（`gunpacks/default/`，游戏内会解压到 `gunpacks/default/`）可以直接当作可运行范例抄改。

---

## 0. 先看懂整体结构

一把枪不是「一个模型」，而是**一堆模块拼起来的**：

```
一把完整的枪 = 机匣 + 供弹 + 供气 + 枪管        （4 个必需槽位，缺一不可射击）
             + 枪口 / 护木 / 护木配件 / 瞄具 / 侧瞄 / 后托 / 挂件（全部可选）
```

- 每个模块 = **一份 JSON 定义**（`modules/*.json`）+ **一份模型与贴图**（`assets/<命名空间>/...`）。
- 装配在**气动枪械装配台**里做；装好的枪把「槽位 → 模块 id」存进物品 NBT。
- 拆掉**枪管**会同时弹出**枪口**；拆掉**护木**会同时弹出**所有护木配件**；取出**机匣**等于把整把枪拿走。
- 模块定义里写的是**数值属性**（伤害、射速、后座……）；模型里写的是**外观和挂载点**（骨骼）。两者通过**同一个模块 id** 绑定。

一个枪包的完整工作流：

```
Blockbench 建模 → 导出 gecko 模型/动画 → 放进 gunpacks/<包名>/assets/<ns>/
                                         写 modules/<模块>.json
                                         （可选）数据包里写合成配方
                    → 客户端 + 服务端都放一份 → 重启 → 装配台里装配 → 试枪
```

---

## 1. 枪包

### 1.1 安装位置

枪包是**目录**，不是压缩包：

```
<游戏目录>/gunpacks/<枪包目录名>/
```

- **客户端和服务端都要装**（单机就是 `.minecraft/gunpacks/`，服务器要放进服务器的游戏目录）。
- 目录名随意，只影响加载顺序（按名字排序）与日志显示；真正的身份是模块 JSON 里的 `name`。
- **不支持 zip**：只扫描 `gunpacks/` 下的**目录**，压缩包会被忽略。
- **没有 manifest**：不需要 `pack.json`、不需要 `pack.mcmeta`。一个枪包里只有两样东西会被读取：`modules/` 与 `assets/`。其它目录（包括 `data/`）会被忽略——**配方属于数据包，不属于枪包**，见 §3.7。
- 新增枪包需要**重启游戏/服务器**（音效注册发生在启动阶段）；已经装好的枪包改内容可以用热重载（§1.5）。

### 1.2 服务端 / 客户端一致性（重要）

模组启动时会把**所有枪包里 `modules/*.json` 的内容**做一次 SHA-1，作为网络握手协议版本。

> **客户端与服务端的 `modules/` 必须完全一致**，否则 Forge 握手阶段直接拒绝连接。
> 只有贴图/模型/音效不一致不会影响握手（但会看起来不对），所以发布时请把整包一起发。

### 1.3 内置默认枪包

模组自带一份范例枪包（仓库里的 `gunpacks/default/`，打包进 jar 的 `gunpack_defaults/`）：

- **首次启动**解压到 `gunpacks/default/`；
- 之后**永不覆盖**：你改过的文件保留，你删掉的文件下次启动会**重新解压**；
- 想彻底丢掉它，把整个 `gunpacks/default/` 改名或搬走即可（它会以新名字再生成一份，不碍事）。

### 1.4 枪包目录结构

```
gunpacks/<枪包目录名>/
├── modules/                                  ← 模块定义（必需，客户端+服务端都读）
│   ├── mak_1_receiver.json
│   └── ...
└── assets/<命名空间>/                         ← 客户端资源（作为内置资源包注入）
    ├── geo/gun/<模块id>.geo.json               GeckoLib 模型（文件名词干 = 模块 id 的 path）
    ├── textures/gun/<模块id>.png               基础贴图
    ├── textures/gun/<模块id>_glowmask.png      发光掩码（可选）
    ├── textures/gun/<模块id>_dye.png           染色掩码（可选）
    ├── animations/gun/<模块id>.animation.json  动画（可选）
    ├── sounds.json                             音效注册（可选，原版格式）
    ├── sounds/<文件名>.ogg                     音效文件
    └── lang/zh_cn.json, lang/en_us.json        本地化（可选）
```

### 1.5 资源路径约定

模块 JSON 的 `name` 字段（例如 `createpneumatictacticals:mak_1_receiver`）就是**资源键**：

| 用途 | 路径 |
|---|---|
| 模型 | `assets/<namespace>/geo/gun/<path>.geo.json` |
| 贴图 | `assets/<namespace>/textures/gun/<path>.png` |
| 发光掩码 | `assets/<namespace>/textures/gun/<path>_glowmask.png` |
| 染色掩码 | `assets/<namespace>/textures/gun/<path>_dye.png` |
| 动画 | `assets/<namespace>/animations/gun/<path>.animation.json` |
| 模块物品名 | 语言键 `module.<namespace>.<path>` |
| 成品枪名 | 机匣里的 `gun_name`（语言键，随你写） |

- `<namespace>` 可以是**你自己的命名空间**（不必是 `createpneumatictacticals`），只要和 `name` 一致即可。
- **文件名必须等于模块 id 的 path**。geo JSON 内部的 `description.identifier`（Blockbench 导出常是 `geometry.unknown`）**不参与校验**，随便写。
- 找不到模型/贴图时不会崩：物品用 `placeholder` 模型顶替，装到枪上则**不渲染并打一条一次性警告**。
- 命名前缀有约定：制退器 `8dvo` / `1vo` / `14dvo` / `12dvo`（数字 = 口径直径去掉小数点）、导轨件 `pica*`——见 §2.7。

### 1.6 热重载与调试

| 操作 | 作用 |
|---|---|
| `F3+T`（客户端） | 重载枪包资源（模型/贴图/动画/语言）+ 重新读取模块定义；图集与掩码缓存一并清空 |
| `/cpt reload`（服务端，需要 OP 2 级） | 重新读取服务端的模块定义（不改资源） |
| 重启 | 新增/修改**音效**（`sounds.json`）必须重启 |

- 日志关键字：`Gunpack root: ...`（启动时的枪包根目录与内容哈希）、`Loaded N modules ...`、`Failed to read module file ...`（某个 JSON 坏了，该文件被跳过，其余照常加载）。
- 创造模式物品栏里有一个 **`gunpacks`** 标签页：列出**所有已加载的模块定义**，以及每种机匣的**示例整枪**——这是最快的测试入口（不需要配方）。

### 1.7 五分钟：一个能开枪的最小枪包

下面是一个自洽的最小例子（命名空间 `example`，四个必需模块）。把它放到 `gunpacks/example/` 就能在装配台里拼出一把能射击的枪。

**① 机匣** `modules/example_receiver.json`

```json
{
  "name": "example:example_receiver",
  "module_type": "receiver",
  "gun_properties": { "fire_rate_multiplier": 0.5, "damage_multiplier": 0.2 },
  "gun_type": "medium",
  "fire_modes": ["semi", "auto"],
  "base_recoil_pitch": 2.0,
  "base_recoil_yaw": 1.0,
  "gun_name": "gun.example.example_gun"
}
```

**② 弹匣** `modules/example_magazine.json`

```json
{
  "name": "example:example_magazine",
  "module_type": "feed",
  "gun_properties": { "reload_speed": -0.1 },
  "load_type": "magazine",
  "clip_size": 20
}
```

**③ 供气** `modules/example_supply.json`

```json
{
  "name": "example:example_supply",
  "module_type": "supply",
  "supply_type": "cartridge",
  "air_capacity": 0,
  "air_per_shot": 0
}
```

**④ 枪管** `modules/example_barrel.json`（`gun_type` 必须和机匣一致）

```json
{
  "name": "example:example_barrel",
  "module_type": "barrel",
  "gun_type": "medium",
  "gun_properties": { "bullet_speed": 1.0, "ergonomics": 0.1 }
}
```

**⑤ 模型**（Blockbench 导出，放到 `assets/example/geo/gun/`）

- `example_receiver.geo.json`：根骨 `main`，里面放 `loc_barrel`、`loc_feed`、`loc_supply` 三个**空骨**（定位骨，见 §2.2），位置就是各模块的安装点。
- `example_barrel.geo.json`：`main` 里放 `loc_muzzle`（枪口位置，用于枪长与枪口烟雾）。
- `example_magazine.geo.json`、`example_supply.geo.json`：围绕**自己的原点 (0,0,0)** 建模，原点就是与定位骨 pivot 重合的点。

**⑥ 贴图**：`assets/example/textures/gun/<模块id>.png`（≤64×64，见 §2.5）。

**⑦ 本地化** `assets/example/lang/zh_cn.json`

```json
{
  "module.example.example_receiver": "示例机匣",
  "module.example.example_magazine": "示例弹匣",
  "module.example.example_supply": "示例气瓶击针",
  "module.example.example_barrel": "示例枪管",
  "gun.example.example_gun": "示例气枪"
}
```

**⑧ 测试**：重启 → 创造模式 `gunpacks` 标签页拿模块 → 气动枪械装配台装配 → 装填弹药（弹药来自 Create 的土豆加农炮投射物类型，见 §4）→ 射击。

**⑨（可选）合成配方**：放进**数据包**（不是枪包），见 §3.7。

---

### 1.8 发布清单

- [ ] 以**目录**形式分发（让玩家解压到 `gunpacks/<包名>/`；压缩包不会被加载）。
- [ ] 提醒玩家**客户端与服务端都要装**，且 `modules/` 内容必须一致。
- [ ] 每个模块 id 都有对应的 `geo/gun/<id>.geo.json` 与 `textures/gun/<id>.png`（缺了会渲染成占位/不显示）。
- [ ] 附上 `lang/zh_cn.json`（与 `en_us.json`），至少写全模块名与 `gun_name`。
- [ ] 配方、弹药定义**另附数据包**（枪包不读 `data/`）。
- [ ] 改过 `sounds.json` 时在说明里注明需要重启。
- [ ] 说明依赖版本（本模组 + Create）。

---

## 2. 建模约定

### 2.1 单位、轴向、原点

| 项目 | 约定 |
|---|---|
| 单位 | Blockbench 像素（px），**16 px = 1 方块**；geo 的 `pivot`/`origin`/`size` 与动画里的 `position` 都是 px |
| 枪口方向 | **−Z**：枪管、枪口装置、护木都往 −Z 延伸；模型的「前」= −Z |
| 上方向 | +Y |
| 模块原点 | 模块模型**必须围绕自己的原点 (0,0,0) 建模**；渲染时原点会与宿主定位骨的 pivot 重合，并继承该骨骼（含动画）的旋转与缩放 |
| 根骨 | 官方模型根骨都叫 `main`，但这只是习惯，代码不按名字引用根骨 |

### 2.2 关键骨骼清单（代码按名字引用的唯一权威清单）

> 枪的**本体模型与贴图由机匣模块提供**（机匣模型就是「枪」）。下面第一组骨骼写在**机匣**模型里；其余各组写在对应模块自己的模型里。

**机匣模型上的挂载定位骨**（缺哪个，就装不了/不显示哪类模块）：

| 骨骼名 | 挂载的 `module_type` | 说明 |
|---|---|---|
| `loc_feed` | `feed` | 弹匣/供弹具 |
| `loc_supply` | `supply` | 供气模块 |
| `loc_barrel` | `barrel` | 枪管；其 pivot 的 z 还用于计算枪长 |
| `loc_handguard` | `handguard` | 护木 |
| `loc_stock` | `stock` | 后托 |
| `loc_sight` | `sight` | 主瞄具（远距离时自动隐藏） |
| `loc_sight_side` | `tactical_sight` | 侧瞄（远距离时自动隐藏） |
| `loc_charm` | `charm` | 挂件 |

**机匣上的相机骨**（可选，空骨即可）：

| 骨骼名 | 作用 |
|---|---|
| `ads_camera` | 主瞄具的**眼睛点**：瞄准时整枪平移，使该点落到屏幕中心 |
| `tactical_camera` | 侧瞄眼睛点；**骨骼的旋转即侧瞄倾角**（例如 Z 轴 45°），瞄准时整机按逆旋转摆平 |

> 已安装的瞄具模块可以用**同名骨骼**覆盖机匣的相机骨（pivot 与机匣 `loc_sight`/`loc_sight_side` 的 pivot 相加复合）。两边都没有 → 该姿态保持腰射位置。

**其它模块模型上的骨骼**：

| 骨骼名 | 出现位置 | 作用 |
|---|---|---|
| `loc_muzzle_attachment` | 枪管模型 | 枪口装置（`muzzle`）的挂载点 |
| `loc_muzzle` | 枪管模型 | 裸枪管口尖端（枪口烟雾锚点 + 枪长计算） |
| `loc_muzzle` | 枪口装置模型（可选） | 装置前端口；缺失时取「所有立方体的最小 z 面」 |
| `loc_handguard_top` / `_bottom` / `_left` / `_right` | 护木模型 | 护木配件（`handguard_attachment`）的挂载点，必须与模块 JSON 的 `attachment_points` 一一对应 |
| `chain_0`, `chain_1`, … | 挂件模型 | 链条，依次探测，最多 16 节；没有链条时直接驱动 `pendant` |
| `pendant` | 挂件模型 | 坠子骨 |
| `loc_mass` | 挂件模型（可选） | 坠子质心标记；缺失时以 `pendant` 为质心 |
| `laser_beam` | 激光类配件 | 光束骨；在 GUI/掉落物等非世界渲染 pass 自动隐藏 |

**挂件的局部坐标约定**：+Y = 安装面的外法线，y = 0 = 机匣表面；在水平枪上，挂件局部 −Z 是下垂方向。

### 2.3 挂载语义（“装到骨骼 X”到底做了什么）

渲染时，模组把宿主模型 **root → 定位骨** 的整条骨骼链变换（含动画后的状态）施加到模块模型上：

```
模块世界变换 = T(定位骨位置) · T(pivot) · R(旋转) · S(缩放)
```

推论（建模时必须记住的三件事）：

1. **模块原点 = 定位骨 pivot**。模块不改变父子关系，也不需要知道宿主长什么样。
2. **定位骨带旋转，模块就继承旋转**。例如默认枪包把 `loc_charm` 设成 `[0,-90,90]` 让挂件垂下来；护木的 `loc_handguard_left` 设成 `[0,0,90]`，于是所有配件都按「顶部朝向」建模即可。
3. **定位骨打关键帧会带动模块**。例如在 `fire` 动画里让 `loc_barrel` 后座，枪管和枪口装置会一起动。

**递归只有一层**：枪管模型可以再带 `loc_muzzle_attachment`（挂枪口装置），护木模型可以再带 `loc_handguard_*`（挂配件）；更深一层不再支持。

**缺骨行为**：宿主缺少对应定位骨 → 该模块**不渲染**并打一条一次性警告（`module ... not rendered: parent model lacks locator bone ...`），不崩溃。

**LOD**：相机距离超过配置项 `lodDistance`（默认 32 格）时，瞄具/侧瞄/枪口/挂件/护木配件不渲染。

**实例：默认包的真实层级**

```
mak_1_receiver（机匣模型 = 枪本体）
main
├─ bolt                  ← fire / reload 动画驱动
├─ loc_supply            rot [0,0,-90]
├─ loc_handguard
├─ loc_stock
├─ loc_sight
├─ loc_sight_side
├─ loc_barrel
├─ loc_feed
├─ loc_charm             rot [0,-90,90]
├─ ads_camera
└─ tactical_camera       rot [0,0,-45]
```

```
spiccato_711（套筒式手枪：瞄具/挂件挂在会动的套筒下，射击时一起动）
main
├─ lower → hammer        ← fire 动画驱动
├─ slide
│   ├─ loc_charm
│   └─ loc_sight         ← 瞄具随套筒
├─ loc_barrel
├─ loc_feed              rot [15,0,0]（弹匣继承倾斜）
├─ loc_supply
├─ loc_stock
├─ loc_handguard / loc_sight_side
├─ ads_camera
└─ tactical_camera
```

```
模块模型（各自独立层级，根骨被放到宿主定位骨的 pivot 上）
711_barrel          main → { loc_muzzle_attachment, loc_muzzle }
1vo_muzzle_brake_a  main → loc_muzzle
mak_1_ammo_20       main → mag            ← reload 动画驱动 mag
charm_crystal       main → support, chain_0 → chain_1 → chain_2 → { pendant, loc_mass }
```

### 2.4 用骨骼设定模块位置（实操）

1. 在机匣模型里新建一个**空骨**（不加立方体），命名成 §2.2 表里的名字。
2. 把它的 **pivot 拖到安装点**：模块的原点会精确落在 pivot 上，且**模块模型本身的坐标是相对它自己的原点的**。
3. 需要让模块倾斜/翻转，就**旋转定位骨**（例如侧挂的供气模块 `loc_supply` 用 `[0,0,-90]`），不要去改模块模型的角度。
4. 护木：在护木模型里放 `loc_handguard_<位置>`，然后在护木 JSON 的 `attachment_points` 里声明同样的位置列表；配件模型统一按「顶部朝向」建，位置差异交给定位骨的旋转。
5. 枪长与枪口烟雾依赖 `loc_barrel` / `loc_muzzle` / `loc_muzzle_attachment` 的 pivot z；缺失时回退成 0.5 方块的旧默认值（枪口烟雾会出现在错误位置）。
6. 瞄具：除了模型本身，建议在**瞄具模型**里也放一个 `ads_camera`（pivot = 眼睛点），这样每把瞄具都能自己决定瞄准眼位。

### 2.5 贴图要求与建议

| 项目 | 要求 |
|---|---|
| 分辨率 | **建议 32×32**（默认包实际用 16×16 / 32×32 / 64×64，以 32×32 为主）；geo 里声明的 `texture_width/height` 必须与 PNG 实际尺寸一致 |
| 尺寸上限 | **≤ 64×64 会进入运行时图集**（性能最佳）；**> 64×64 自动降级**为独立贴图逐次绘制，能跑但更吃性能 |
| UV | 必须落在 [0,1] 内（不要越界/不要 wrap），否则该模型无法进入图集 |
| 渲染语义 | 本体按 **cutout**（alpha 裁切）渲染，半透明像素会按阈值裁掉 |
| 命名 | 文件名 = 模块 id 的 path，放在 `textures/gun/` 下 |

**发光掩码 `<id>_glowmask.png`（可选）**

- 与基础贴图**同尺寸**；**掩码的 alpha 决定哪些像素发光**，发光颜色取基础贴图的 RGB，按全亮渲染。
- 尺寸不一致 → 打警告并**跳过发光**（不崩）。
- 例：`pica_laser_dbg_glowmask.png`、两个瞄具的 glowmask。

**染色掩码 `<id>_dye.png`（可选，灰度图）**

- 亮度分区决定可染色区域：`0–63` → 区域 1，`64–127` → 区域 2，`128–191` → 区域 3，`192–255` → **不染**。
- 染色结果 = 灰度(底色) × 区域颜色，保留 alpha。
- **没有掩码时整张贴图按区域 1 染色**。
- 颜色由玩家在**配件加工台**染色（16 种原版染料，3 个区域各选一种）并存进 NBT，**模块 JSON 里没有默认配色字段**。
- 机匣贴图不参与染色（但支持 glowmask）。

> 关于图集的完整设计（画布尺寸、档位、降级梯子）见 `docs/gun-atlas-design.md`。创作者视角只需要记住：**一模块一张贴图，不要超过 64×64，掩码同尺寸同命名**。

### 2.6 动画

**模组会播放的动画名**（写在动画 JSON 里的名字，用裸名 `fire` 或 Blockbench 全名 `animation.<模型名>.fire` 都认）：

| 动画名 | 触发时机 | 播放方式 | 速度 |
|---|---|---|---|
| `idle` | 手持时常驻 | 强制循环 | 1× |
| `fire` | 每次击发 | 单次 | 1× |
| `reload` | 弹匣式换弹开始（`load_type` 非 `round`） | 单次 | = `reload_speed` 倍率 |
| `reload_round` | 逐发装填（`load_type: "round"`），每批一次 | 单次 | = `reload_speed` |
| `bolt` | 空仓换弹的拉栓段，接在 `reload` 之后 | 单次 | = `reload_speed` |

- **换弹时长 = 动画长度**：换弹锁定窗口由**机匣动画文件**里 `reload`（+ 空仓时的 `bolt`）的 `animation_length` 决定，再除以 `reload_speed`。动画文件缺失时回退：弹匣 50 tick / 逐发 16 tick / 拉栓 10 tick。**想要 1.75 秒换弹，就把动画做成 1.75 秒。**
- **同一触发会广播给机匣和每个已装模块**：模块可以自带一份**同名动画**（例如弹匣的 `reload` 驱动自己的 `mag` 骨）；没写动画的模块静默跳过，不会报错。
- `idle` 可以省略（默认包两个机匣都没写）；`fire`/`reload`/`bolt` 建议机匣一定要有，否则手上什么都不会动。
- 动画 JSON 里可以写 GeckoLib 的 `sound_effects` 关键帧来触发音效（默认包未使用）。
- 动画只在**手持**渲染时播放；物品栏图标、掉落物、展示框固定为静态 rest pose。

### 2.7 命名与尺寸规范（口径 / 导轨）

这两条是**作者约定**：不照着做也能跑，但照着做能保证不同枪包的零件在视觉上对得上、命名一眼能懂。

**① 口径 → 枪管直径 → 制退器命名**

| 口径 `gun_type` | 枪管横截面直径 | 制退器名字前缀 |
|---|---|---|
| `light` 小口径 | **0.8** px | `8dvo` |
| `medium` 中口径 | **1.0** px | `1vo` |
| `heavy` 大口径 | **1.4** px | `14dvo` |
| `shotgun` 霰弹 | **1.2** px | `12dvo` |

- 名字里的数字 = 对应直径**去掉小数点**（0.8→`8`、1.0→`1`、1.4→`14`、1.2→`12`），后缀 `dvo`（1.0 习惯写作 `1vo`）。
- 制退器模型的**内径必须等于同口径枪管的直径**；外轮廓随意（默认包两个制退器的外轮廓分别是 1.4×1.4 与 1.4×1.3）。
- 实例：`711_barrel` 横截面 0.8×0.8（light）↔ `8dvo_muzzle_brake_competition`；`mak_1_barrel_short` 横截面 1.0×1.0（medium）↔ `1vo_muzzle_brake_a`。
- `muzzle` 模块本身**没有 `gun_type` 字段**，所以「哪个制退器能装哪根枪管」由枪管的 `module_affected` 白名单表达（§3.5），尺寸规范靠你自觉遵守。

**② `pica` 前缀 = 皮卡丁尼导轨件**

- `pica*` = **皮卡丁尼导轨（Picatinny rail）上的配件与瞄具**：默认包里 `pica_grip_rvg`（握把）、`pica_laser_dbg`（激光）、`pica_sight_small_mounted_md1`（导轨瞄具）。不是导轨件就别用 `pica` 前缀（例：直接装在套筒顶上的 `psts_sight_small_doc1`）。
- 导轨标准尺寸：**宽 1 px、高 0.4 px**（横截面 1×0.4）。默认包里机匣顶面、护木的 top / bottom / left 三个安装面，都是这个尺寸的导轨立方体。
- **定位骨的 pivot 落在导轨的安装面上**（不是导轨中心）：机匣 `loc_sight` = 顶导轨上表面，护木 `loc_handguard_bottom` = 底导轨下表面，`loc_handguard_left` = 侧导轨外表面。
- 导轨件建模：**原点 = 安装面**（模块 y = 0 就是贴着导轨的那个面），夹持结构高 **0.4**、向里包住安装面一点（默认包握把/激光的夹子占 y ∈ [−0.1, 0.3]）。
- 导轨长在**宿主**上（机匣、护木），配件只负责夹持；护木配件还要两边都声明位置（护木的 `attachment_points` + 配件的 `positions`，§3.6）。

### 2.8 导出为 GeckoLib 格式（Blockbench 步骤）

1. **工程**：用 Blockbench + GeckoLib 插件；工程格式 `animated_entity_model`（本仓库所有 `.bbmodel` 都是这个格式）。
2. **导出模型**：`File → Export → Export GeckoLib Model`，得到 `format_version: "1.12.0"` 的几何 JSON，保存为
   `assets/<ns>/geo/gun/<模块id>.geo.json`。
   - **文件名 = 模块 id 的 path**，必须一致（`description.identifier` 随便）。
3. **导出动画**（有动画时）：`Export GeckoLib Animations` → `assets/<ns>/animations/gun/<模块id>.animation.json`。
4. **贴图**：PNG 放 `assets/<ns>/textures/gun/<模块id>.png`，需要发光/染色再加 `_glowmask.png` / `_dye.png`（同尺寸）。
5. **模块定义**：`modules/<模块id>.json`，`name` 写完整 id（建议文件名与 id 的 path 一致，方便对照）。
6. 没有离线构建脚本：**导出物手工拷进枪包**即可（仓库里的 `gecko/` 只是作者的工作区）。

### 2.9 建模自检清单

- [ ] 模块模型围绕自己的原点 (0,0,0) 建模，朝向与「枪口 = −Z」一致。
- [ ] 机匣有需要的 `loc_*` 空骨；护木的 `loc_handguard_*` 与 JSON `attachment_points` 对得上。
- [ ] 枪管有 `loc_muzzle_attachment`（要挂枪口时）与 `loc_muzzle`。
- [ ] 瞄具有 `ads_camera`（建议），侧瞄有 `tactical_camera` 且带倾角旋转。
- [ ] 贴图 ≤64×64、UV 不越界、掩码同尺寸同命名。
- [ ] 动画名与时长符合 §2.6；模块动画与机匣同名。
- [ ] 文件名 = 模块 id 的 path（模型、贴图、动画、定义四处一致）。
- [ ] 枪管横截面直径符合口径规范（0.8 / 1.0 / 1.4 / 1.2 px），制退器内径与名字（`8dvo` / `1vo` / `14dvo` / `12dvo`）对得上。
- [ ] 导轨件（`pica*`）按 1×0.4 的导轨尺寸建模，原点落在导轨安装面上。
- [ ] 客户端与服务端的 `modules/` 内容一致。

---

## 3. 模块自定义

### 3.1 模块 JSON 骨架

```json
{
  "name": "<命名空间>:<模块id>",        // 模块身份 + 资源路径键；缺省时回退为 createpneumatictacticals:<文件名>
  "module_type": "<11 种类型之一>",      // 必需
  "gun_properties": { ... },            // 通用数值属性（§3.3），缺省 = {}
  "module_affected": [ ... ],           // 兼容性规则（§3.5），缺省 = []
  "<类型专属字段>": ...                  // 只对匹配的 module_type 生效（§3.6）
}
```

- **未知的多余键会被静默忽略**（不会报错，也不会生效）。
- 解析失败（类型不认识、必填字段缺失/非法）→ **整个模块被跳过**并打错误日志；引用了该模块的物品会在背包里自动消失。
- 模块 id 跨枪包冲突时**先加载的枪包胜**（按目录名排序），并打警告。
- 模块物品只有一个（`createpneumatictacticals:module`），靠 NBT 里的模块 id 区分。

### 3.2 模块类型总表

| `module_type` | 槽位必需 | 挂载定位骨 | 专属字段 |
|---|---|---|---|
| `receiver` 机匣 | ✅ | （宿主本体） | `gun_type`\*、`fire_modes`\*、`fire_sound`、`gun_name`、`base_recoil_pitch`、`base_recoil_yaw`、`ignore_ammo_pitch` |
| `feed` 供弹 | ✅ | `loc_feed` | `load_type`\*、`load_amount`、`clip_size` |
| `supply` 供气 | ✅ | `loc_supply` | `supply_type`\*、`air_capacity`、`air_per_shot` |
| `barrel` 枪管 | ✅ | `loc_barrel` | `gun_type`\* |
| `muzzle` 枪口 | ❌ | 枪管上的 `loc_muzzle_attachment` | `gas_suppression`、`gas_pass_through`、`gas_guides`（写在 `gun_properties` 里） |
| `handguard` 护木 | ❌ | `loc_handguard` | `attachment_points` |
| `handguard_attachment` 配件 | ❌ | 护木上的 `loc_handguard_<位置>` | `positions`\* |
| `sight` 瞄具 | ❌ | `loc_sight` | `aim_zoom` |
| `tactical_sight` 侧瞄 | ❌ | `loc_sight_side` | `tactical_aim_zoom` |
| `stock` 后托 | ❌ | `loc_stock` | （无，纯属性） |
| `charm` 挂件 | ❌ | `loc_charm` | `charm` 对象 |

（\* = 必需字段。护木配件独占 4 个槽位：`hg_top`/`hg_bottom`/`hg_left`/`hg_right`，每位置最多一个。）

### 3.3 通用属性 `gun_properties`

所有比率属性的语义是 **「在基准 1.0 上做加法」**：所有已装模块的同名值相加，再与基准 1.0 相加，最后钳制。

- `+0.2` = **+20%**；负值 = 惩罚。
- 属性是**跨模块累加**的：机匣 +0.5 射速、枪管 −0.1 射速 → 合计 +0.4 → 1.4×。

| JSON 键 | 游戏内名称 | 默认 | 合计后钳制 | 含义 / 备注 |
|---|---|---|---|---|
| `reload_speed` | 换弹 | 0 | ≥ 0.1 | 换弹/拉栓时间除数（越大越快） |
| `damage_multiplier` | 伤害 | 0 | ≥ 0.1 | 伤害倍率（**不参与随机**） |
| `fire_rate_multiplier` | 射速 | 0 | ≥ 0.1 | 射速倍率；单发最小间隔 = 弹药 `reload_ticks` ÷ 本值（**不参与随机**） |
| `hipfire_accuracy_multiplier` | 腰射 | 0 | ≥ 0.1 | 腰射精度（散布 = 弹药基础散布 ÷ 本值） |
| `ergonomics` | 人机 | 0 | 0.1 – 5.0 | 瞄准速度、姿态切换、行走惩罚、持枪恢复；**≥1.2 可以边跑边射** |
| `bullet_speed` | 初速 | 0 | ≥ 0.1 | 初速倍率（只影响飞行时间，射程由弹药自己决定）（**不参与随机**） |
| `recoil_vertical_multiplier` | 垂直后座 | 0 | 0.1 – 3.0 | 垂直后座倍率，**负值更好** |
| `recoil_horizontal_multiplier` | 水平后座 | 0 | 0.1 – 3.0 | 水平后座倍率，**负值更好** |
| `recoil_recovery` | 回正 | 0 | 0.2 – 5.0 | 后座回正速度 |
| `gravity_multiplier` | 下坠 | 0 | 0.1 – 3.0 | 弹丸重力倍率，**负值更平直**（**不参与随机**） |
| `drag_multiplier` | 空气阻力 | 0 | 0.1 – 3.0 | 空气阻力倍率，**负值更保速**（**不参与随机**） |
| `gas_suppression` | 气体抑制 | 0 | −5 – 1 | 枪口烟雾：`−1` 烟雾翻倍，`+1` 完全无烟（**仅 `muzzle`**） |
| `unique` | — | `false` | — | 布尔值；同名模块装多个时，属性只算一次 |

**`muzzle` 专属（同样写在 `gun_properties` 里）**：

| JSON 键 | 默认 | 范围 | 含义 |
|---|---|---|---|
| `gas_suppression` | 0 | −5 – 1 | 见上表 |
| `gas_pass_through` | 1 | 0 – 1 | 有多大比例的烟雾**不走**导气孔、直冲前方 |
| `gas_guides` | `[]` | — | 侧向导气孔数组，见下 |

```json
"gas_guides": [
  { "weight": 1, "velocity_multiplier": 0.8, "spread_multiplier": 0.8,
    "direction_x": 0, "direction_y": -90 }
]
```

- `direction_x` / `direction_y` 是相对弹道方向的 **(偏航, 俯仰) 角度**，`-90` 表示垂直向下。
- `weight` 决定多条孔之间的分配比例；`velocity_multiplier` / `spread_multiplier` 控制速度与扩散。

### 3.4 属性随机特性（制造随机 / 掷点）

在**配件加工台**合成出来的模块会带一次**随机掷点**（制造品质）：

- **只有 7 个属性参与掷点**：`reload_speed`、`hipfire_accuracy_multiplier`、`ergonomics`、`recoil_vertical_multiplier`、`recoil_horizontal_multiplier`、`recoil_recovery`、`gas_suppression`。
- 其余 5 个（`damage_multiplier`、`fire_rate_multiplier`、`bullet_speed`、`gravity_multiplier`、`drag_multiplier`）**永远按你写的值生效**，不掷。
- **预算规则**：掷点掷的是**比例**（0–1），最终生效值 = 你在 JSON 里写的值 × 比例。
  - 好属性的比例之和 = `1 + q`；坏属性的比例之和 = `q`；其中 `q` 在 `0 ~ min(坏属性个数, 好属性个数 − 1)` 之间随机。
  - 「好」= 符号对射手有利（后座类属性是**负值**算好，其余正值算好）；你写 0 的属性不参与。
  - 只有一个好属性的模块（或只有坏属性的模块）**等于没有随机**：所有比例都是 1.0，掷点结果为空。
  - 好属性越多，单条属性掷满的概率越低——总量是固定的，玩家拿到的是一份随机分配。
  - 例：某枪口写了垂直后座 −0.2、水平后座 −0.1（两个好）与气体抑制 −2、人机 −0.2（两个坏），若掷出 `q = 0.6`，则好属性比例之和 1.6、坏属性比例之和 0.6，例如得到垂直后座 −0.16、水平后座 −0.08、气体抑制 −0.36、人机 −0.084。
- 掷点结果按比例存进物品 NBT（`Rolls`，只存小于 1.0 的项），装配时随模块进入枪的 NBT；工具提示显示的是**掷点后的实际值**。
- **触发点只有一个**：配件加工台合成。创造模式标签页拿到的模块、以及你手写的 NBT 都没有掷点（按满值算）。

> 对创作者的含义：**你写的是这套属性的「总量与上限」**。合成出来的每一件会在这个总量内随机分配（好的比例趋向高、坏的趋向低）；想要某件模块永远是同一数值，就让它**只有一个（好）属性**，或者只用不参与掷点的属性。

### 3.5 兼容性规则 `module_affected`

控制「这个模块允许/禁止别的什么模块装在同一把枪上」。数组里每条规则：

```json
{ "module_type": "barrel", "mode": "include", "value": ["REGEX=mak_1.+"] }
```

| 字段 | 说明 |
|---|---|
| `module_type` | 规则针对的槽位类型（11 种之一） |
| `mode` | `include` / `exclude` / `keep_empty` / `not_empty`（**缺省或写错都按 `exclude`**） |
| `value` | 字符串数组：**精确模块 id**（`mypack:my_barrel`）或 **`REGEX=` 前缀的正则** |

- **正则语义**：`REGEX=<pattern>` 用 Java `Pattern.find()`（**子串匹配**，不是整串匹配）去匹配候选模块的 `命名空间:路径` 全串。所以 `REGEX=mak_1.+` 能命中 `createpneumatictacticals:mak_1_barrel_short`。
- **匹配判定**：候选 id 命中「精确列表」中的任意一个，或命中任意一条正则 → 视为匹配。

**四种模式的实际效果**（安装时**双向**校验：已装模块的规则会检查新模块，新模块的规则也会检查已装模块）：

| 模式 | 已装模块声明它时（对新模块） | 新模块声明它时（对已装模块） |
|---|---|---|
| `include` | 新模块**不匹配** → 拒绝（`not_included_by`） | 对应槽位**已装且不匹配** → 拒绝（`requires_other`）；槽位空 → 允许 |
| `exclude` | 新模块**匹配** → 拒绝（`excluded_by`） | 已装模块**匹配** → 拒绝（`excludes_installed`） |
| `keep_empty` | 一律拒绝该类型新模块（`must_be_empty`） | 对应槽位**非空** → 拒绝（`requires_empty`） |
| `not_empty` | 一律拒绝该类型新模块（`required_nonempty`） | 安装时不校验（设计上是「完成度」约束） |

**独立于以上规则的硬校验**：

- **口径**：`barrel` 的 `gun_type` 必须与 `receiver` 的 `gun_type` 完全相等（双向检查，换机匣也会被拦）。
- **供弹↔供气**：`supply_type: "backpack_tank"` 要求 `feed` 是 `load_type: "backpack"`，反之亦然。
- **护木配件**：必须已装护木（否则 `no_mount_point`）、护木的 `attachment_points` 必须包含目标位置、配件自己的 `positions` 必须包含该位置（否则 `wrong_position`）。
- 同一模块 id 不会用自己的规则拦自己（方便同一配件装到多个位置）。

**常见用法**：机匣用 `include` + 正则白名单，把「这把枪能吃哪些枪管/弹匣/瞄具」写清楚（见 `gunpacks/default/modules/mak_1_receiver.json`）；枪管用 `include` 白名单限定枪口装置。

### 3.6 各类型专属字段

#### receiver（机匣）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `gun_type` | 字符串 | **必需** | `heavy` / `medium` / `light` / `shotgun`（大口径/中口径/小口径/霰弹）。机匣只吃自己口径的弹药，枪管必须同口径 |
| `fire_modes` | 数组 | **必需，非空** | `semi` / `auto` / `burst`；**第一个是装好后的默认模式** |
| `fire_sound` | 字符串 | `null` → 回退 `create:fwoomp` | 音效事件 id（来自枪包 `sounds.json`）；音调跟随弹药的 `sound_pitch` |
| `ignore_ammo_pitch` | 布尔 | `false` | `true` = 射击音效固定 1.0 音调，不跟弹药变调 |
| `gun_name` | 字符串 | `null` | 成品枪名的**语言键**，装配时写入 |
| `base_recoil_pitch` | 数字 | 0 | 基础垂直后座（度级），与 `recoil_vertical_multiplier` 相乘 |
| `base_recoil_yaw` | 数字 | 0 | 基础水平后座（随机左右方向） |

#### barrel（枪管）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `gun_type` | 字符串 | **必需** | 必须与机匣一致 |

#### feed（供弹）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `load_type` | 字符串 | **必需** | `magazine` 弹匣 / `round` 整装弹（逐发装填，用 `reload_round` 动画）/ `backpack` 背包供弹 |
| `load_amount` | 整数 | 0 | `round` 模式每次装填的发数（至少按 1 算） |
| `clip_size` | 整数 | 1 | 弹匣容量 |

#### supply（供气）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `supply_type` | 字符串 | **必需** | `cartridge` 整装气瓶 / `internal_tank` 内置气罐 / `backpack_tank` 背包气罐 |
| `air_capacity` | 整数 | 0 | 内置气罐容量；0 = 用物品自身的耐久上限 |
| `air_per_shot` | 整数 | 0 | 每发消耗的气量（内置气罐） |

#### muzzle（枪口）

无根级专属字段；属性写在 `gun_properties` 里（`gas_suppression` / `gas_pass_through` / `gas_guides`，见 §3.3）。

#### handguard（护木）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `attachment_points` | 数组 | `[]` | 暴露的安装位：`top` / `bottom` / `left` / `right`；每一项都需要护木模型里有对应的 `loc_handguard_<位置>` 骨 |

#### handguard_attachment（护木配件）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `positions` | 数组 | **必需，非空** | 自己能装的位置：`top` / `bottom` / `left` / `right`；非法值直接拒绝该文件 |

导轨件（`pica*`）的尺寸与命名规范见 §2.7：导轨横截面 1×0.4，配件原点落在导轨安装面上。

#### sight（瞄具）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `aim_zoom` | 数字 | 1.25 | 瞄准时的 FOV 缩放（**替换**而不是累加；装了瞄具就用瞄具的值） |

#### tactical_sight（侧瞄）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `tactical_aim_zoom` | 数字 | 1.0 | 侧瞄姿态的缩放 |

#### stock（后托）

无专属字段，纯属性模块。

#### charm（挂件）

`charm` 对象（整块可省略；省略的键用默认值）：

| 字段 | 默认 | 范围 | 说明 |
|---|---|---|---|
| `gravity_scale` | 1.0 | — | 重力缩放（0 = 失重） |
| `air_drag` | 0.3 | — | 空气阻尼（每秒速度衰减） |
| `pendant_mass` | 3.0 | ≥ 0.01 | 坠子质量 |
| `max_swing_degrees` | 75.0 | 0 – 180 | 最大摆角 |
| `surface_margin` | 0.02 | ≥ 0 | 距机匣表面的最小距离（方块） |
| `bounce` | 0.15 | 0 – 1 | 碰撞回弹 |
| `friction` | 0.6 | 0 – 1 | 接触摩擦 |

链条的节数与长度**来自模型**（`chain_0`… 与 `pendant` 的 pivot），JSON 里不写。

### 3.7 合成配方（属于数据包，不属于枪包）

枪包只被当作**客户端资源包**注入，所以 `data/` 不会生效。合成配方要放进**数据包**：

```
数据包根/
└── data/<命名空间>/recipes/<任意名>.json
```

```json
{
  "type": "createpneumatictacticals:cpt_module_crafting",
  "ingredients": [
    { "item": "create:iron_sheet" },
    { "item": "minecraft:red_dye" },
    { "item": "create:andesite_alloy" }
  ],
  "result": "createpneumatictacticals:pica_sight_small_mounted_md1"
}
```

- 无序合成：`ingredients` 按多重集合从背包里匹配。
- `result` 写**模块 id 字符串**（不是物品 id）。
- 走这个配方合成出来的模块会带 §3.4 的随机掷点。

### 3.8 常见坑

- **`burst_count` 是死键**：默认包的 `mak_1_receiver.json` 里有它，但没有任何代码读取——三连发请用 `fire_modes: ["burst"]`。
- **多余的键不会报错**：写错了字段名不会有任何提示，属性就是不生效。改完记得在工具提示里核对。
- **类型专属字段跨类型无效**：把 `aim_zoom` 写进枪管 JSON 不会报错，但也不会生效。
- **模块被跳过会导致物品消失**：模块 JSON 解析失败后，引用了它的物品在背包里会自动删除，别拿成品枪的模块做实验。
- **`unique` 的用途**：防止玩家堆多个同名模块叠加属性。
- **口径是硬门槛**：`barrel` 与 `receiver` 的 `gun_type` 不相等就装不上，报「枪管类型与机匣不匹配」。
- **护木配件必须两边都声明**：护木的 `attachment_points` 与配件的 `positions` 都要包含目标位置。
- **客户端的模块与服务端必须逐字节一致**，否则连不上服务器。

---

## 4. 弹药自定义

### 4.1 弹药定义在哪里

**弹药不是枪包的一部分。** 枪包只支持 `modules/` + `assets/`；弹药定义是 **Create 土豆加农炮投射物类型**的数据包 JSON：

```
数据包根/
└── data/<命名空间>/create/potato_projectile/type/<名字>.json      →  弹药 id = <命名空间>:<名字>
```

- 同一份文件会被**两个解析器**读：Create 读它自己的字段（进 `POTATO_PROJECTILE_TYPE` 数据注册表），本模组读**额外字段**（扩展字段全部可选）。**一个 JSON = 两套 schema 合并**。
- 所以：**新增弹药不需要写任何 Java，也不需要配方**——文件放进去就能用（封装弹装填配方与 JEI 会自动收录）。
- 路径提醒：代码注释里的 `data/<ns>/cpt_ammo/*.json` 是**过期说明**，实际不读这个目录。
- 加载时机：服务端数据包重载（`/reload` 或进世界时）。解析失败 → 该条跳过并打错误日志（`Failed to parse ammo extension ...`）；数值字段写成非数字会抛 `Field '<key>' must be numeric`。
- ⚠ **扩展字段只在服务端生效**：客户端只认代码里的内置预设/默认值，所以**数据包覆盖的数值不会反映在客户端预测与 HUD 上**（散布预览、准星、射速镜像仍按预设算），但服务端的实际命中判定、伤害、爆炸、反弹都按你的值走。

### 4.2 Create 原生字段（投射物类型本体）

| 字段 | 类型 | 默认 | 对「枪」射击的含义 |
|---|---|---|---|
| `items` | 物品 id / 列表 / `#标签` | 空 | 绑定「内容物品 → 本类型」；封装弹存的是**物品 id**，靠它反查类型；也是投射物的渲染物品与封装弹图标 |
| `reload_ticks` | 整数 | 10 | **射速锚点**：射击间隔 = `max(1, reload_ticks ÷ 枪的 fire_rate_multiplier)` tick；同时影响枪口烟雾量（20 为基准） |
| `damage` | 整数 | 1 | 基础伤害（本模组扩展字段没给伤害时用它）。⚠ 对**内置预设里带伤害的 id**，本模组会跳过这个键（见 §4.6） |
| `split` | 整数 | 1 | 每次射击的弹丸数（≥1）；**每颗弹丸都是满伤害** |
| `knockback` | 浮点 | 1.0 | 命中实体时的击退（水平，×0.6） |
| `drag` | 浮点 | 0.99 | 每 tick 速度保留比例；枪的 `drag_multiplier` 会在此之上再缩放 |
| `velocity_multiplier` | 浮点 | 1.0 | **初速** = `2 × velocity_multiplier × 枪的 bullet_speed` 方块/tick |
| `gravity_multiplier` | 浮点 | 1.0 | 每 tick 下坠 `−0.05 × gravity_multiplier`；枪的 `gravity_multiplier` 会再缩放 |
| `sound_pitch` | 浮点 | 1.0 | 机匣射击音效的音调（机匣设 `ignore_ammo_pitch: true` 时忽略） |
| `render_mode` | 对象 | billboard | 投射物渲染方式（Create 自己的渲染器）；枪射击时模型会额外缩到 0.25× |

**对枪射击无效的 Create 字段**（Create 的处理器被本模组取消，只对原版土豆炮有效）：`sticky`、`drop_stack`、`pre_entity_hit`、`on_entity_hit`、`on_block_hit`。想加效果请用 §4.3 的 `effects`。

### 4.3 本模组扩展字段（全部可选，写在同一份 JSON 里）

| 字段 | 类型 | 默认 | 单位 | 效果 |
|---|---|---|---|---|
| `gun_type` | 字符串 | `light` | 口径 | `heavy`/`medium`/`light`/`shotgun`；**必须与机匣口径完全相等**才吃得进去；写错名字会被静默忽略（保留默认值） |
| `damage` | 数字 | −1（= 用 Create 的 `damage`） | 生命值 | >0 时覆盖基础伤害；最终伤害 = 本值 × 枪的 `damage_multiplier` × 爆头倍率 × 距离衰减 |
| `effective_range` | 数字 | 256 | 方块 | 满伤距离；超过后线性衰减。**与枪的初速无关**，是弹药自己的绝对射程 |
| `damage_falloff_rate` | 数字 | 0（= 自动 `基础伤害 ÷ 射程`） | 生命值/方块 | 衰减斜率；用默认值时**两倍射程处伤害归零**，归零即弹丸自毁 |
| `headshot_multiplier` | 数字 | 1.5 | × | 命中头部区域的倍率（头部带深度 = 2×(身高−眼高)，超过半身则不算爆头） |
| `spread` | 数字 | 1.0 | 度 | 腰射锥角基准；实际散布 = `(spread × 姿态惩罚 + 连发膨胀) ÷ 腰射精度`；**瞄准时散布为 0**；每发膨胀 `+0.15×spread`，上限 `2.5×spread`，8 tick 内衰减 |
| `max_reflect` | 数字（取整） | 0（关闭）；**写了 `max_reflect` 或 `speed_decay` 中任意一个 → 默认 10** | 次 | 撞方块反弹次数，按面法线反射 |
| `speed_decay` | 数字 | 0.5 | 每次反弹保留的速度比例 | 与 `max_reflect` 配对；只有写了这两个键之一才会解析 |
| `affect_radius` | 数字 | 0（无爆炸） | 方块 | >0 时爆炸：命中实体、命中方块、射程自毁、200 tick 到期都会引爆。**永不破坏方块**；粒子/音效随半径放大 |
| `explosion_damage` | 数字 | 0 | 生命值（中心） | 对每个目标 = `explosion_damage × (1 − 距离/半径) × max(暴露比例, penetrate_ratio)` |
| `explosion_knockback` | 数字 | 0（负值 = 吸引） | 速度 | 由爆心向外推（带一点点向上分量） |
| `penetrate_ratio` | 数字 | 0 | 0–1 | **爆炸的掩体穿透下限**（按 27 点采样算暴露比例，取 `max(暴露比例, 本值)`）。⚠ **不是子弹穿墙**，游戏里没有子弹穿透机制 |
| `effects.direct` | 数组 | 空 | — | 命中实体时施加的效果列表：`{ "effect": "<效果id>", "duration": <秒>, "amplifier": <等级> }`；`"minecraft:fire"` 是特殊值 = 点燃 |
| `effects.explosion` | 数组 | 空 | — | 爆炸时对半径内所有实体施加，时长同样按暴露系数缩放 |

### 4.4 从 JSON 到游戏内：完整处理链

```
弹药 JSON（datapack）
   │  Create 解析 → POTATO_PROJECTILE_TYPE 注册表（items/reload_ticks/split/…）
   │  本模组解析 → AmmoExtension 表（gun_type/damage/爆炸/反弹/效果…）
   ▼
封装弹（Pod）内容物品 → getTypeForItem → 弹药 id → 枪 NBT（Ammo / AmmoCount）
   ▼
开火：口径校验（严格相等）→ 间隔 = reload_ticks ÷ fire_rate_multiplier
      → 生成 split 颗弹丸，初速 = 2 × velocity_multiplier × bullet_speed
      → 散布 = (spread × 姿态 + 膨胀) ÷ 腰射精度
   ▼
命中：伤害 = 弹药伤害 × damage_multiplier × 爆头倍率 × 距离衰减
      → 爆炸（若有）/ 反弹（若有）/ 施加 effects
   ▼
200 tick 后到期（带爆炸则先引爆）
```

### 4.5 选择、装填与消耗

- **口径**：`GunType.accepts` 是**严格相等**（不存在「大口径枪吃小口径弹」）。没写 `gun_type` 的弹药一律按 `light` 处理。
- **可选弹药列表**：创造模式 = 注册表里所有口径匹配的类型；生存模式 = 背包里**对应种类的封装弹**（`supply_type: "cartridge"` 用加压封装弹，其余用普通封装弹）里的内容，加上**弹药盒**的库存，全部按口径过滤。
- **装填**：弹匣式按 `clip_size` 装到满，整装弹（`round`）按 `load_amount` 逐批装；生存模式消耗封装弹（1 封装弹 = 1 发），不够时继续从**同型弹药盒**（512 发）取弹。
- **背包供弹**：每发直接消耗一个散装封装弹。
- **换弹种**：把已装填的弹药换掉时，膛内弹药会退成封装弹（背包放不下就掉落），新弹种进入 `PendingAmmo` 等待下一次装填。
- **获取途径**：两条数据包配方自动覆盖所有已注册弹药，**新增弹药无需写配方**：
  - `createpneumatictacticals:pod_filling`：气瓶 + 任意「有投射物类型」的物品 → 封装弹；
  - `createpneumatictacticals:pod_assembly`：封装弹 + 加压气瓶 → 加压封装弹。
- **名称**：弹药的显示名直接用**内容物品**的名字，不需要额外的语言键。
- **生物持枪**：每种口径有指定的廉价弹药（小口径 `create:beetroot`、中口径 `create:potato`、大口径 `create:melon_block`、霰弹 `create:sweet_berry`）。

### 4.6 内置预设与优先级

模组为 **Create 自带的投射物类型**内置了一套数值（写在代码里，因为 Create 的 jar 自带同名 JSON，模组侧的资源覆盖会在加载顺序上打输）：

| 弹药 id | 口径 | 射程（方块） | 伤害 | 备注 |
|---|---|---|---|---|
| `create:beetroot` | light | 16.0 | 3.0 | |
| `create:glow_berry` | shotgun | 16.0 | 3.0 | 多弹丸 |
| `create:chorus_fruit` | medium | 18.5 | 4.4 | |
| `create:melon_slice` | light | 18.5 | 4.4 | |
| `create:suspicious_stew` | medium | 18.5 | 4.4 | |
| `create:sweet_berry` | shotgun | 18.5 | 4.4 | 多弹丸 |
| `create:carrot` | light | 20.9 | 5.7 | |
| `create:chocolate_berry` | shotgun | 20.9 | 5.7 | 多弹丸 |
| `create:fish` | medium | 20.9 | 5.7 | |
| `create:pufferfish` | medium | 20.9 | 5.7 | |
| `create:apple` | medium | 23.4 | 6.9 | |
| `create:baked_potato` | medium | 23.4 | 6.9 | |
| `create:glistering_melon` | light | 23.4 | 6.9 | |
| `create:poison_potato` | medium | 23.4 | 6.9 | |
| `create:potato` | medium | 23.4 | 6.9 | |
| `create:honeyed_apple` | medium | 25.8 | 8.1 | **唯一带反弹**（4 次，每次保留 72% 速度） |
| `create:pumpkin_block` | medium | 25.8 | 8.1 | |
| `create:pumpkin_pie` | medium | 28.3 | 9.2 | |
| `create:cake` | heavy | 30.8 | 10.2 | |
| `create:melon_block` | heavy | 30.8 | 10.2 | |
| `create:golden_carrot` | heavy | 40.6 | 13.4 | |
| `create:blaze_cake` | heavy | 48.0 | 15.0 | |
| `create:golden_apple` | medium | 16.0 | （用 Create 原值） | 效果型弹药 |
| `create:enchanted_golden_apple` | medium | 16.0 | （用 Create 原值） | 效果型弹药 |

（预设伤害的公式：`类型伤害 × (1 + 0.5 × (48 − 射程) / 32)`，短射程用伤害找补。）

**优先级：默认值 → 内置预设 → 你的 datapack JSON（逐字段覆盖）。**

⚠ **唯一例外**：如果某个 id **既有内置预设、预设又定义了伤害**（上表里除 `golden_apple`/`enchanted_golden_apple` 之外的所有条目），那么**你的 JSON 里写的 `damage` 会被忽略**（代码表优先）；其余字段仍然 JSON 优先。想完全掌控伤害，就用自己的弹药 id（不在上表里的 id 没有任何预设，全部字段都由你说了算）。

### 4.7 一个完整的自定义弹药

`data/mypack/create/potato_projectile/type/slug.json`（弹药 id `mypack:slug`）：

```json
{
  "items": "minecraft:iron_nugget",
  "damage": 8,
  "reload_ticks": 12,
  "velocity_multiplier": 1.6,
  "knockback": 0.5,
  "gravity_multiplier": 0.8,
  "drag": 0.99,
  "sound_pitch": 1.2,
  "render_mode": { "type": "create:toward_motion", "spin": 1.0, "sprite_angle_offset": 140 },

  "gun_type": "light",
  "effective_range": 32.0,
  "spread": 1.4,
  "headshot_multiplier": 2.0,
  "max_reflect": 2,
  "speed_decay": 0.6,
  "effects": {
    "direct": [ { "effect": "minecraft:slowness", "duration": 3, "amplifier": 1 } ]
  }
}
```

**能用的最小要求**：文件能作为 Create 类型解析、`items` 非空（封装弹要有内容物品）、`gun_type` 与你想要的机匣一致（默认 `light`）。

> Create 自己还有一个 `create:fallback` 类型（`items` 为空、伤害 0），那是给「没有登记类型的物品」兜底用的，别拿它当弹药模板。

---

## 5. 附录

### 5.1 默认枪包示例索引

`gunpacks/default/modules/`（18 个模块）——每个文件演示了什么：

| 文件 | 类型 | 演示内容 |
|---|---|---|
| `mak_1_receiver.json` | receiver | 完整机匣字段、6 条 `include` + 正则白名单、动画（fire/reload/bolt）；含一个**无效死键** `burst_count` |
| `spiccato_711.json` | receiver | 高射速手枪机匣：`ergonomics`/`hipfire_accuracy_multiplier`、`fire_modes: ["semi"]` |
| `mak_1_barrel_short.json` / `711_barrel.json` | barrel | 口径字段 + 用正则白名单限定可用枪口装置 |
| `mak_1_ammo_20.json` / `711_ammo_15.json` | feed | 弹匣（`load_type: magazine` + `clip_size`）+ 换弹动画（驱动 `mag` 骨） |
| `mak_1_cartridge_supply.json` / `711_cartridge_supply.json` | supply | 整装气瓶（`supply_type: cartridge`） |
| `1vo_muzzle_brake_a.json` | muzzle | 两个侧向导气孔（±90° 偏航）+ 气体抑制 |
| `8dvo_muzzle_brake_competition.json` | muzzle | 单孔向下导气（俯仰 −90°）+ `gas_pass_through` |
| `mak_1_tactical_handguard.json` | handguard | `attachment_points`（top/bottom/left）+ 配件白名单；带 `_dye` 掩码 |
| `pica_grip_rvg.json` | handguard_attachment | `positions: ["bottom"]`；带 `_dye` 掩码 |
| `pica_laser_dbg.json` | handguard_attachment | 四个位置全支持；带 `_glowmask` + `laser_beam` 骨 |
| `pica_sight_small_mounted_md1.json` / `psts_sight_small_doc1.json` | sight | `aim_zoom`；带 `_glowmask` |
| `mak_1_wire_stock.json` | stock | 纯属性模块；带 `_dye` 掩码 |
| `charm_crystal.json` / `charm_delta_coin.json` | charm | 完整 `charm` 物理参数块（全部默认值） |

资源侧：`assets/createpneumatictacticals/geo/gun/*.geo.json`（18 个模型）、`textures/gun/*.png`（16×16 / 32×32 / 64×64，32×32 为主）、`animations/gun/*.animation.json`（4 个）、`sounds.json`。

### 5.2 游戏内提示速查

**装配（气动枪械装配台）**

| 游戏内提示 | 原因 |
|---|---|
| 该槽位不接受此模块 | 模块类型与该定位骨不匹配 |
| 枪管类型与机匣不匹配 | `barrel` 与 `receiver` 的 `gun_type` 不同 |
| 与已装模块冲突 | 被已装模块的 `exclude` 规则命中 |
| 与已装模块不兼容 | 未命中已装模块的 `include` 白名单 |
| 已装模块要求此槽位留空 / 非空 | 已装模块声明了 `keep_empty` / `not_empty` |
| 该模块排斥已装模块 | 新模块的 `exclude` 命中已装模块 |
| 该模块要求换装其他模块 | 新模块的 `include` 不匹配已装模块 |
| 该模块要求对应槽位留空 | 新模块的 `keep_empty` 但槽位已占用 |
| 护木没有对应的安装位 | 没装护木，或护木的 `attachment_points` 不含该位置 |
| 该配件不支持此安装位置 | 配件的 `positions` 不含该位置 |
| 工作台上已有一把枪 | 装配台一次只能放一把枪 |

（语言文件里还有一条 `gui.createpneumatictacticals.reject.missing_dependency`「缺少前置模块」，但没有任何代码会发它——**死键**，翻译时不用管。）

**开火与装填**

| 游戏内提示 | 原因 |
|---|---|
| 枪械未完成组装（需要机匣/供弹/供气/枪管） | 四个必需槽位没装满（物品提示写作「未完成组装 - 无法射击」） |
| 未选择弹种 | 还没选弹药 |
| 该弹种与机匣类型不兼容 | 封装弹内容物的弹药口径与机匣不同 |
| 背包中没有匹配的封装弹 | 生存模式下没有该口径的封装弹 |
| 整装气瓶需要加压封装弹（封装弹+加压气动瓶合成） | `supply_type: "cartridge"` 的枪必须用加压封装弹 |
| 气压不足 | 内置气罐没气 |
| 背包已满，%d 发弹药掉在地上 | 换弹种时退弹溢出 |

启动日志里值得看的三条：`Gunpack root: ...`（枪包目录与内容哈希）、`Loaded N modules ...`（成功加载的模块数）、`Failed to read module file ...`（坏文件，被跳过）。

### 5.3 实现索引（想读源码的人）

| 功能 | 主要类 |
|---|---|
| 枪包发现/加载/哈希/默认包解压 | `gunpack/GunPacks.java` |
| 枪包音效注册 | `gunpack/GunpackSounds.java` |
| 枪包资源注入与热重载 | `client/GunPackClientEvents.java` |
| `/cpt reload` | `gunpack/CptCommand.java` |
| 模块 JSON 解析 | `module/ModuleDefinition.java`、`module/ModuleType.java` |
| 模块注册表 | `module/ModuleManager.java` |
| 制造随机（掷点） | `module/ModuleRoll.java` |
| 属性聚合与钳制 | `gun/GunStats.java` |
| 兼容性校验 | `gun/GunNbt.java` |
| 装配台逻辑 | `menu/WorkbenchAssembler.java` |
| 挂载渲染（定位骨链） | `client/render/GunModulesLayer.java` |
| 骨骼/动画驱动 | `client/render/GunAnimations.java`、`client/GunAnimationDriver.java` |
| 相机骨/瞄准 | `client/render/AdsTransform.java` |
| 贴图图集 | `client/render/GunTextureAtlas.java` |
| 发光/染色掩码 | `client/render/GunGlowLayer.java`、`client/render/DyedTextures.java` |
| 弹药扩展字段 | `ammo/AmmoExtension.java`、`ammo/AmmoExtensionLoader.java` |
| 弹药内置预设 | `ammo/BuiltinAmmo.java` |
| 弹道/伤害/爆炸/反弹 | `mixin/PotatoProjectileMixin.java` |
| 开火与射速 | `network/GunFireHandler.java` |
| 装填 | `network/GunReloadHandler.java` |

