# Dungeon Finder 开发文档

本文档面向对项目源码感兴趣的开发者，说明整体架构、核心算法、渲染流程与构建方式。

## 概览

本项目是用 **Java + LWJGL2（OpenGL 1.1 固定管线）** 移植的地牢查找器。核心逻辑完整复刻 infdev 版
`ChunkProviderGenerate.provideChunk`（含洞穴）与 `WorldGenDungeons.generate`，按区块逐块模拟完整
3D 地形后判定地牢位置，与游戏内部生成结果一致。渲染为原生窗口 + 异步瓦片加载。

工具支持多个游戏版本，差异集中在**箱子 loot 的 RNG 消费实现**（这会影响刷怪笼怪物 ID）。种子框右侧
下拉菜单可切换版本，切换后自动重建生成器并清除缓存。

## 目录结构

```
src/dungeon/
├── core/              算法核心（无 LWJGL 依赖，可独立验证）
│   ├── Coord.java            方块/采样坐标与封装
│   ├── JavaRandom.java       旧版 Minecraft 的 48 位 Java 随机数
│   ├── MathHelper.java       三角函数查表与向下取整（洞穴生成依赖）
│   ├── PerlinNoise.java      2D/3D Perlin 噪声（含快速采样路径与官方 bug）
│   ├── FractalNoise.java    多倍频合成分形噪声
│   ├── ScaledFractalNoise.java 缩放后的分形噪声
│   ├── SamplingCuboid.java  采样立方体
│   ├── GameVersion.java     游戏版本枚举（箱子 loot RNG 消费差异）
│   ├── ChunkGenerator.java  世界生成器（完整地形 + 洞穴 + 地牢判定）
│   └── SampleJobImpl.java   采样任务实现
├── render/
│   ├── TileRenderer.java    把一块区域栅格化为 BufferedImage（标记地牢）
│   └── TextRenderer.java    用 AWT 把文字渲染为 OpenGL 纹理
└── app/
    ├── DungeonMapApp.java  主窗口、主循环、输入与 UI
    ├── TileCache.java       异步瓦片缓存 + 后台线程池
    └── ...
src/worldgen/            a1.2.6/b1.4 专属的「海洋修正」地形（见下文对应小节）
│   ├── AlphaBetaTerrainGen.java  独立密度场（含洞穴），替代内嵌 inf 版地形
│   └── OctaveNoise/SimplexNoise 等   该密度场的噪声组件
src-minecraft-*/         各版本原版反编译源码（参考蓝本，不参与编译）
Verify.java                  fractal 测试向量 + 地牢统计 + 瓦片预览
```

## 构建与运行

`compile.bat` 负责：

1. 清理并编译 `src/` 到 `build/`；
2. 解压 `lib/windows_natives.jar` 到 `natives/`（首次）；
3. 把 lwjgl/jinput 库类合并进 `build/`；
4. 用 `jar` 打包成根目录 `dungeon-finder.jar`（含 `Main-Class`）。

`run.bat [seed]` 只运行该 jar：

```bat
java "-Djava.library.path=natives" -jar dungeon-finder.jar %*
```

`verify.bat` 编译并运行 `Verify.java`，校验核心算法（无需 LWJGL）。

## 核心算法

### 旧版 Java 随机数

Minecraft 旧版本使用 `java.util.Random` 的 48 位 LCG。世界生成必须复刻其精确序列，否则噪声坐标对不上。

### 3D Perlin 噪声

`PerlinNoise` 复刻旧版实现，含「仅当 cube_y 变化时才重算插值系数」这一官方 bug，这直接影响位级结果。`sampleSingle` 提供无分配快速路径，用于热循环。

### 完整地形生成（provideChunk）

`ChunkGenerator.generateTerrain` 逐区块生成 32KB（16×16×128）方块数组，与原版 `ChunkProviderGenerate.provideChunk` 一致：

1. 用 7 个缩放分形噪声（`noiseGen1..7`）采样密度场（5×5×17 采样点）；
2. 对每个方块做三线性插值，得到 16×16×128 的密度数组（`density>0` 为石头，海平面下为水，顶部放基岩）；
3. 地表 pass：按噪声为每列铺设基岩/地表层（草丛/沙/沙砾等）；
4. 洞穴 pass：对 17×17 邻接区块逐块推进随机流，递归挖空山体（`generateCaves`，依赖 `MathHelper` 逐位一致的 sin/cos）。

地形以 `byte[32768]` 缓存（LRU，`MAX_TERRAIN=8192`，约 256MB，容纳数千区块的邻接复用），地牢判定需要读取邻接区块，读取时用 `.clone()` 副本避免污染缓存。

### a1.2.6 / b1.4 地形（worldgen 的海洋修正密度场）

从 a1.2.6 起，地形生成整体换成了新实现（方块噪声密度场 + 海洋修正 / 大陆化），与 inf 版差异过大，`ChunkGenerator` 内嵌的 inf 版生成器无法复用。因此独立出与 dungeon 层平级的 `src/worldgen/`：

- [`AlphaBetaTerrainGen`](../src/worldgen/AlphaBetaTerrainGen.java)（`Kind.A126` / `Kind.B14_PLUS`）按对应版本的 `ChunkProviderGenerate.provideChunk` 逐步移植：密度场采样 → 三线性插值 → 地表/基岩铺设 → 洞穴 pass（`MapGenCaves` 的 a1.2.6+ 递归版）；`generate(cx,cz)` 返回与内嵌生成器相同的 `x<<11 | z<<7 | y` 布局的 32KB 字节数组（含洞穴），因此上层缓存与地牢判定链路完全复用。
- 版本细节差异：a1.2.6 水面用水动方块（`8`），b1.4 用水静方块（`9`）；b1.4 起引入砂岩；b1.4 与 b1.7.3 生成器代码逐行相同（仅生物群系类名不同），故预置 `B14_PLUS` 一个入口覆盖至 b1.7.3。
- `ChunkGenerator` 构造按版本实例化对应 `Kind`，其余版本保持 `null` 走内嵌 inf 生成器；`terrain()` 取缓存时按实例分支，缓存 LRU 与 `.clone()` 语义对两个实现一致。

### 地牢判定（populate + WorldGenDungeons）

`ChunkGenerator.getDungeons(cx,cz)` 模拟该区块的 `ChunkProviderGenerate.populate`：

- RNG 种子 = `chunkX*j6 + chunkZ*j8 ^ worldSeed`，其中 `j6=(nextLong()/2<<1)+1`、`j8=(nextLong()/2<<1)+1`；
- `a1.2.6`/`b1.4` 起：尝试前先模拟水湖（`nextInt(4)==0`）与岩浆湖（`nextInt(8)==0`），两者按原版顺序精确消费 RNG 并修改 4 邻地形副本（`simulateLakes` 复刻该版本 `WorldGenLakes.generate`，无 b1.8 的岩浆换石头逻辑）；
- 尝试次数按版本：`inf-20100625-1917`/`a1.0.1` 每区块 4 次，`a1.0.14` 起 8 次；每次随机 `x = cx*16 + rand(16) + 8`、`y = rand(128)`、`z = cz*16 + rand(16) + 8`；
- `dungeonGenerate` 复刻 `WorldGenDungeons.generate`：随机房间尺寸（`i6/i7 ∈ [2,3]`），要求地板（y-1）与天花板（y+4）均为实心、四周空气缺口 `i8 ∈ [1,5]` 才生成；
- 生成时写出石砖/苔石墙、箱子（按原版顺序消耗 RNG 的 `nextInt`）与刷怪笼。

方块实心判定与原版 `Material.isSolid` 一致（石头/草丛/泥土/基岩/沙/沙砾/石砖/苔石/刷怪笼/箱子）。地牢结果按区块永久缓存（`ConcurrentHashMap`）。`collectDungeons(cx0,cz0,cx1,cz1,out)` 收集区块范围内的所有地牢（房间可能跨入相邻区块，因此一个区块的地牢可能需要读取其邻接地形）。

### 版本差异（箱子 loot RNG 消费）

不同版本 `WorldGenDungeons` 中 `pickCheckLootItem` 的类型范围与额外分支不同，会改变箱子循环消费 RNG 的 `nextInt` 次数，从而影响刷怪笼 `mobID`。`GameVersion` 枚举抽象这些差异：

| 版本 | loot 类型数 | 新增分支 | 尝试次数 | 湖泊 |
|------|:---:|---------|:---:|:---:|
| `inf-20100625-1917` | 10 | 无 | 4 | - |
| `a1.0.1` | 11 | 红石 | 4 | - |
| `a1.0.14` | 11 | 红石 + 唱片 | 8 | - |
| `a1.2.6` | 11 | 红石 + 唱片 | 8 | 有 |
| `b1.4` | 11 | 红石 + 唱片 + 可可豆 | 8 | 有 |

`ChunkGenerator` 构造时接收版本，箱子循环内按版本消费 loot RNG（`nextInt(lootTypes)` + 各版本分支），并精确保留原版的惰性求值——红石/唱片条件不满足时返回 `null`、不消费槽位 `nextInt(27)`。`Verify` 中可用固定种子验证切换版本后同一地牢的 `mobID` 变化（例如 `-1995793183340471539` 在 `(-120,30,-138)` 的 mob 从 inf 的 Zombie 变为 a1.0.1 的 Spider）。

### 临时磁盘缓存

`ChunkGenerator` 还维护一个会话内的临时磁盘缓存（`diskFile`）：把已计算的每区块地牢结果增量追加写入磁盘，后台 `disk-flusher` 线程每 2 秒刷新脏区块。切回同一种子时可秒载；程序退出时由应用层删除缓存文件（切种子时保留以便回退复用），`close()` 负责停线程并落盘收尾。缓存文件损坏或不可读时忽略并重新计算。缓存文件名按种子+版本命名（`dungeonfinder_<seed>_<VERSION>.dmc`），切换版本时自动改用新文件，避免跨版本错误复用。

### 采样坐标与方块坐标

采样坐标为方块坐标的 1/4（`sample = block / 4`，`block = sample * 4`），区块 = `block >> 4`。瓦片渲染时用 `Math.floor` 处理负坐标区块边界。

## 渲染与瓦片

### 瓦片模型

- 每个瓦片 = 一个区块（16×16 方块），`TileRenderer.TILE_SIZE = 16`，1 方块 = 1 像素。
- 区块瓦片内容与缩放无关（地牢标记不变），因此缩放 / 平移时瓦片可跨级别复用，仅需按视野请求新瓦片。
- 缩放由 `bpp`（方块/像素）控制：默认最远视野为窗口宽覆盖 `WIDEST_BLOCKS = 1024` 方块（即 64 个区块瓦片宽），可放大 `ZOOM_STEPS = 16` 级；`bpp` 减半放大、加倍缩小，锚点缩放保持鼠标下方块坐标不变。
- 每帧最多上传 `MAX_UPLOADS_PER_FRAME = 96` 个新瓦片，与后台吞吐匹配，避免一帧突发上传大量瓦片造成掉帧。

### 异步加载

`TileCache` 用 `Runtime.getRuntime().availableProcessors()` 个后台线程做 CPU 密集的区块栅格化（`generateTerrain` 在 LRU 地形缓存锁之外执行，可真正并行），主线程只做 GL 纹理上传与绘制：

- `request(key)`：去重入队（`requested` map 去重，`Key` 用区块坐标，`hashCode = cx*73856093 ^ cz*19349663`）；
- `workerLoop()`：后台栅格化，单瓦片异常被 try-catch 捕获，不会拖垮线程；
- `uploadIfReady(key)`：就绪则上传为纹理并返回 id，否则返回 -1；
- `textureId(key)`：只查询已上传纹理 id，不触发上传（用于区分「已上传」与「已就绪未上传」）；
- `isReady(key)`：已上传或已就绪均返回 true。

> 关键约束：**GL 上传绝不能在 `glBegin/glEnd` 之间发生**。渲染分三步——先请求、再画未上传的占位块、最后上传并绘制已上传瓦片。
>
> 上传配额只统计「真正新上传」的瓦片：已上传瓦片每帧都直接绘制、不占用配额，避免已上传瓦片把 `MAX_UPLOADS_PER_FRAME` 配额占满后，真正就绪待上传的瓦片永远轮不到而残留灰底不更新。

### 纹理

瓦片用 `GL_NEAREST`（缩小）、`GL_LINEAR`（放大）、`GL_CLAMP_TO_EDGE`（`0x812F`）避免瓦片边界接缝。`TileRenderer.renderTile` 先铺暗色背景（`0xFF2A2A30`），再对瓦片覆盖区块范围内 `collectDungeons` 收集到的地牢列，以刷怪笼为中心、半径 4 方块散点标记红色（`0xFFFF2020`）。

## 输入与 UI

- 鼠标左键拖拽平移；滚轮/`+`/`-` 缩放（向上放大），以鼠标为锚点。
- 左上角：种子输入框 + 应用按钮；左下角：X/Z 坐标输入框 + 应用按钮。
- 种子框右侧：版本下拉菜单（`VER_X/VER_Y/VER_W/VER_H`，含 V 图标）。点击展开列出 5 个版本，当前版本高亮；选中后调用 `setVersion` 用当前种子重建生成器（自动换用新版本的磁盘缓存文件）。
- 输入框支持点击定位光标、拖拽选字、闪烁光标；坐标值仅在位置变化时跟随，静止时保持可编辑。
- 点击红点：显示所点地牢的完整坐标（XYZ）与刷怪笼怪物 ID；提示信息跟随红点位置，缩放更新或超出视距时关闭，避免遮挡种子输入框。
- 画面中心绘制 MC 样式反色十字准星（`GL_ONE_MINUS_DST_COLOR, GL_ONE_MINUS_SRC_COLOR`）。

## 验证

`Verify.java` 校验 fractal 测试向量（bit-exact）、统计固定种子固定范围的地牢数量并检查 y 越界、渲染一张瓦片输出 PNG。修改算法代码后请运行 `verify.bat` 确认无回归。