# Android 液态玻璃（Liquid Glass）调研小结

> 2026-10-03 调研。用途：卡盒原生版 Q11 真毛玻璃地基的施工依据。
> 规矩：看思路、自己手写、零三方库。

## 结论

1. 这条路走得通，不需要引任何库。核心只用两个系统原语：`RenderEffect.createBlurEffect`（API 31+ 真模糊）与 AGSL `RuntimeShader`（API 33+ 折射/色散）。纯 Java 手写 View 可用同配方重写，不碰 Compose、不引三方。
2. 像不像玻璃，决定因素是：**边缘折射带 + Fresnel 边缘高光 + 轻饱和提升（vibrancy）**，中心保持清晰。只做半透染色/全幅模糊 = 被否掉的现状。
3. 成本不在着色器，在背景捕获：全 App 玻璃件共享一份捕获，降分辨率、按需刷新；滚动中逐帧截图位图是唯一会卡的环节。
4. 许可证无障碍（Apache-2.0 / MIT），且按「看思路自己写」连署名义务都基本不触发。

## 参考仓库

| 仓库 | 路线 | 体系 | 门槛 | 许可证 |
|---|---|---|---|---|
| Kyant0/AndroidLiquidGlass（4k★） | 显示列表录制 → RenderEffect 链（blur → vibrancy → AGSL lens） | Compose 专用 | 模糊 31+，折射 33+ | Apache-2.0 |
| jevonschan/liquid-glass-android | FrameLayout 采样背后内容；33+ 单 pass AGSL 透镜（圆角矩形 SDF 折射+三通道色散+法线高光）；低版本位图模糊 | **纯 View/XML** | minSdk 24 | MIT |
| QmDeve/AndroidLiquidGlassView | Java 普通 View + AGSL，真实折射+色散 | **纯 View、Java** | API 33 以下不渲染效果 | MIT |
| styropyr0/Prismal | 每件内嵌 GLSurfaceView 自捕获自渲染（GLES） | View | minSdk 25 | MIT |
| androidgogo/liquidglassxml-1 | Kyant 管线移植到 XML/View：「blur once, sample many」 | View | 21–30 染色、31–32 模糊、33+ 全功能 | Apache-2.0 |

性能锚点：AGSL 透镜链在 2024 年机型单帧 GPU <1ms；着色器须开硬件加速。背景捕获半分辨率、复用缓冲、约 33ms 节流时，内存与无玻璃基线无统计差异。

## 配方与参数锚点（借鉴思路，不抄码）

- 架构：根部统一捕获一份背景，底栏/悬浮钮/菜单全部采样同一份。
- 配方链：模糊（约 4dp 起、降采样）→ 饱和 ×1.2–1.5 → 仅边缘带内折射（中心清晰）→ Fresnel 边缘亮线 + 顶部 1–1.5px 高光发丝线 + 内阴影 → 半透染色（不透明度 ≤ ~35% 保可读）。
- 折射带宽约 13–16dp；色散强度 0.10、封顶 0.25，**超过 0.25 变彩虹不是玻璃**。
- 按压反馈：缩放 0.96 + 高光扫过，弹性曲线驱动。
- 不用学的噱头：重力传感器高光、双玻璃融合、焦散、虹彩微光默认开、滚动边缘渐进模糊、按背景明暗自动翻转前景色。

## 施工路线（纯手写 View、零三方）

地基：自研一个 `GlassKit`。
- 背景源：Activity 内容根部维护背景捕获——优先 RenderNode 显示列表录制（滚动几乎免费）；弹层升起时定格一份 0.5 倍位图快照。
- 效果链：API 33+ `RenderEffect.createChainEffect(模糊, AGSL 透镜)`；AGSL 约百余行（圆角矩形 SDF 法线、边缘带偏移采样、RGB 微差色散、Fresnel 提边）。**坑**：`RuntimeShader` 构造在个别 GPU 驱动抛异常，必须 try/catch 静默降级到「只有模糊」档。
- API 31–32：RenderEffect 模糊 + Canvas 手绘高光描边/内阴影（八成像）。API <31：快照软件降采样模糊或纯半透兜底。

场景分级：
- **A（照做）** 长按菜单、悬浮钮、悬浮搜索胶囊、底栏：弹起抓一帧快照模糊+微折射；滚动停稳刷新快照。
- **A（捷径）** 独立窗口（Dialog/PopupWindow）：API 31+ `FLAG_BLUR_BEHIND` + `setBackgroundBlurRadius`（合成器层真模糊）；先查 `isCrossWindowBlurEnabled()`，被 OEM 关掉回落快照法。弹窗优先用「同 Activity 内覆盖层」形态，与 Q6 一致。
- **B（小心）** 底栏压滚动列表实时模糊：只走显示列表录制，禁止逐帧位图截图；可滚动中降质量、停稳恢复。
- **C（别做）** 转场/滑动期间全屏实时折射：背景对齐必错位，转场保持现行动画不贴玻璃。

性能纪律：捕获只一份、半分辨率、脏了才重录；玻璃件仅在「背景世代号或自身位置变化」时重绘；**玻璃子树必须从捕获中自排除**，否则显示列表自引用会把渲染线程递归爆栈。

---

## 2026-10-05 补充调研（用户要求再翻开源）

> 背景：用户 2026-10-05 发来液态玻璃底栏截图并明确要求「去开源里面找」。本节为新增量，10-03 结论不变；仍只记机制、不动代码，玻璃攻坚待用户解封。

### 新增参考

| 仓库 | 新增价值 | 体系 | 门槛 |
|---|---|---|---|
| Abdullajon1881/LiquidGlass | 模块分 `liquidglass-core`（纯 Kotlin：SDF 透镜数学、AGSL 着色器、uniform 打包、分级逻辑）+ `liquidglass-compose` + **`liquidglass-view`（经典 View 渲染器）**；带液态形状融合（smooth-min SDF、最多 8 形）与 gel 按压（缩放+局部隆起+边缘 flare）；光学数学有 Kotlin 镜像与 80+ 测试 | Compose + **经典 View** + RN | **3 级降级至 API 21** |
| Lucent（meller2 的 EPUB 阅读器实践） | 基于 Kyant backdrop 的三档预设可借鉴：Chrome（底栏/工具栏：强透镜+边缘高光）、Panel（卡片/设置组：浅透镜保可读）、Control（图标钮/选中指示：紧透镜、无阴影）；一条结构铁律——读背景的玻璃件必须是被捕获层的**兄弟**、不能是其后代，否则会录到正在录制的层本身 | Compose | API 33+（AGSL） |
| tianli0/fluidglass（Flutter 移植） | Kyant 结构逐文件对应表（`Shaders.kt` 的折射/色散/高光着色器 ↔ 独立 .frag 文件），可作「Kyant 数学到底在哪几个文件」的索引 | Flutter | 同 Kyant |

### 真机崩溃坑（新证据，施工时必避）

- lanlan13-14/zephyr 的修复提交（2026-09-05）：Kyant AGSL 在小米 Adreno 上，首帧岛尺寸 0×0、胶囊中心 SDF 梯度为零向量时，`normalize()` 触发 SIGSEGV 直接崩进程。对策：normalize 前做长度守卫、跳过 0×0 的层录制、AGSL/RenderEffect 失败时禁用 GPU 玻璃路径降级而不是拖垮 Activity。**这条与 10-03 笔记里「RuntimeShader 构造 try/catch 降级」是同一纪律的延伸：着色器内部的零向量同样要守。**

### 对卡盒的适配判断（不变 + 补强）

- 用户机 vivo X200 Pro mini（OriginOS 6 / Android 15，API 35），AGSL `RuntimeShader` 可跑，门槛不是问题；App 最低支持 Android 7.0（API 24），所以**分级降级**才是真问题：API 33+ 全功能、31–32 模糊+手绘高光、<31 半透兜底——Abdullajon 的三级划分与 10-03 路线一致，可对照其 tier logic 的分法。
- 卡盒是 Java 手写 View、零三方、无 Gradle。Kyant 与 Lucent 都是 Compose，接不进来，只能读 AGSL 数学自写（与 10-03 结论一致）；QmDeve/AndroidLiquidGlassView（Java View）与 Abdullajon 的 `liquidglass-view` 是仅有的两个「经典 View 渲染器」参照，解封施工时先看这两个的捕获/渲染接法、再看 Kyant 的着色器数学。
- Kyant 结构铁律（玻璃件与被捕获层互为兄弟、玻璃子树自排除）在 View 体系同样成立，`GlassKit` 设计时先定这条层级关系。

### 未查到

- 用户截图里酷安拼图「功能」模块的液态底栏出自哪个开源项目：公开搜索未定位到对应仓库，不硬猜；后续若用户给出 App 名或作者再补查。
