# 卡盒原生版 · 进度与续跑说明

用户 Igll 于 2026-10-03 04:25 授权连夜开发。本文件是跨会话的唯一进度真相，干活前先读完。

## 目标
把卡盒从 WebView 混合版重写为纯原生 Android（Java 手写界面，零第三方库）。行为与混合版一一对应；数据共用同一份 cards.json（213 张，data_version 10）。

## 用户铁律
- 混合版（~/workspace/cardapp，3.107 已冻结）是行为标准；不确定某功能怎么工作，去读 ~/workspace/cardapp/site/app.js 对应实现。
- 代码全部手写，不许抄参考站（db.encmasuta.com 等）代码。
- 审美：素净、统一、不用 emoji 图标；界面大小跟现版 App 语言走（白卡+深蓝英雄卡+蓝 #0A5CD6）。
- 只报改了什么、编没编过，不提前宣布「好看/修好」——真机验收在用户。
- 每段完成必须：编译通过 → APK 产出 → git commit → push。编译不过先修，不带病推进。

## 工程
- 目录：~/workspace/cardnative
- 构建：`cd ~/workspace/cardnative && bash build.sh`（aapt2+javac+d8+apksigner 手写管线，无 Gradle；javac 用 -cp 不要用 -bootclasspath，Lambda 才能编）
- JDK：~/workspace/jdk/jdk-17.0.20.1+1/；SDK：~/workspace/android-sdk（build-tools 34.0.0，android-34）
- 签名：~/workspace/card-db/apk/build/manual/debug.keystore（与混合版同一个，pass android）
- 开发期包名：com.igll.carddbnative（与混合版并排装互不干扰）；产物 ~/workspace/your_files/carddb-native.apk
- 终局才切回 com.igll.carddb（同签名可覆盖安装）
- 数据：assets/data/cards.json + assets/data/images/（源头 ~/workspace/cardapp/site/data/，刷新时整目录同步）
- 远程：私有仓 dimlogue/cardbox-native（master）
- 冻结备份：~/workspace/backups/cardbox-freeze-2026-10-03-v3.107.tar.gz

## 已完成
- [x] Phase 1（2026-10-03 04:27，commit 870ea7e，APK 0.1-native）：五页骨架+底部导航；全部卡片（搜索+卡库总览英雄卡+双列瓷砖+点击详情）；详情（图/子版本/点评/加减收藏/参数表）；我的卡片（收藏网格+总数/组织覆盖/无转换费统计条）；学生推荐（学生/留学关键词+评分排序）；设置基础行；系统返回键关详情。收藏存 SharedPreferences(cardbox_native/mine_ids)。

## 待办（按序推进，一次一段）
- [ ] Phase 2a 筛选（拆小段推进，每段独立编译提交）：
  - [x] 2a-1 筛选面板：底部弹出，类型/卡组织/状态三组单选切换行（与混合版 chipRow 同语义，点已选项再点取消），点选即时过滤网格；顶部「共 N 张」计数与「筛选 · 已选数」；含清空/完成。
  - [x] 2a-2 特点 chips + 发卡行 + 已选标签栏：特点五项（3DS/可网付/无货币转换费/自动购汇/Apple Pay，featMatch 同混合版口径）多选 AND，叠加后无卡可满足时拒绝并 toast；发卡行 17 家按混合版 chipRow 单选切换（PROGRESS 原写「多选」，以混合版行为标准为准，已改单选）；筛选面板改限高可滚动；已选标签栏按 银行/组织/状态/特点/类型 顺序展示、点标签即删；「筛选 · N」计数含特点与银行。版本 0.3-native（versionCode 3）。
  - [x] 2a-3 排序（评分高低/名称/银行）+ 列数 1/2/3 + 显示方式（平铺/按银行折叠，分组展开收起，对照 groupBank/renderAllHtml）：筛选面板加排序/显示方式/列数三组；排序与列数与分组及 bankOpen 展开集存 SharedPreferences；分组组序按卡数降序+银行中文序、组内评分序，带搜索/筛选时强制展开；已选标签栏可删排序（分组不进标签，同混合版）；首页网格改 ScrollView 行式渲染以支持分组行与列数。版本 0.4-native（versionCode 4）。
- [x] Phase 2b 学生页补全：顶部白底明亮统计卡（学生卡数/在发/最高分）+「挑卡只看三件事」条+每卡推荐理由（对照 app.js studentReason/studentFit 生成）。（2026-10-03 完成：学生口径改按 cards.json 的 student_pick 精选 6 张并按其 order 排序，不再用关键词粗筛；顶部白底统计卡左大数字「N 张精选卡 · 覆盖 X 家银行」+右三色点统计免年费/无转换费/支持 3DS；「挑卡只看三件事」条数字按当前精选与全库 213 张动态生成；每卡理由卡含图/名/银行与卡种/评分胶囊、「推荐理由：」正文（移植 studentReason：币种数/无转换费/ATM 免笔/境外返现/AI 算力/哔哩哔哩/年费/自动购汇/网申）与「适合」标签（移植 studentFit：留学生/海淘党/出境旅游/第一次办卡/AI 工具党/二次元/日常党，去重取前三），点卡进详情；已用 Python 按同一逻辑跑 6 张核对理由与统计（5 家银行/免年费 5/无转换费 4/3DS 4）。版本 0.5-native（versionCode 5）。）
- [x] Phase 2c 资讯页：读 assets/data/news.json（先从 ~/workspace/cardapp/site/news.json 同步进 assets，无则联网拉混合版同源），列表+详情展开。（2026-10-03 完成：assets/data/news.json 已从 ~/workspace/cardapp/site/data/news.json 同步（6 条，updated_at 2026-10-03），原生列表按混合版 renderNews 同字段渲染标签/日期/来源/标题/摘要，点卡展开看来源与日期并可「查看原文 ›」打开系统浏览器；本地先读 SharedPreferences 缓存、没有才用内置种子，进页后后台双线（jsDelivr/raw，与混合版 NEWS_URLS 同源）静默拉新并存缓存，有变化才刷新；已加 INTERNET 权限。版本 0.6-native（versionCode 6）。）
- [x] Phase 3a 情景选卡：对照 app.js WIZ_SCENARIOS/wizQs/scoreWizard 完整移植（四场景+档次+卡种，向导 UI：轨迹行可回改、结果理由 chips、＋收藏、进详情返回保留进度、打开时背景不可滚——原生天然满足）。（2026-10-03 完成：四场景各 2 问＋档次＋卡种共 4 题，wizScore 逐项同口径移植——停发排除、无 score（null）卡基准 5 分、仅限卡 −4、组织地区亲和/币种命中/档次加减分，理由按权重取前 3；轨迹行点任意条回那步改（回场景清答案）；结果先打分排序再按卡种过滤（候选 ≥4 才滤）取前 6，行含图/名/组织/理由 chips/仅限提示/评分胶囊（无分显示「待评分」同混合版 scoreTxt）/＋✓收藏；详情从选卡打开时关详情必回选卡原进度（detailFromWiz）；入口在全部卡片页顶部横幅（无搜索/筛选时）+设置行，系统返回键在向导内逐级回退。已验证：node 直接跑混合版 wizScore 出参考，Python 按 Java 实现复刻对拍全部 600 种答题组合（场景×地区/用途×档次×卡种），池大小/Top6 卡序/分数/理由 0 处不一致。版本 0.7-native（versionCode 7）。）
- [x] Phase 3b 自定义卡：新建/编辑/删除（名字/银行/颜色/备注），存 SharedPreferences JSON；我的卡片页展示+展开。（2026-10-03 完成：自定义卡存 SharedPreferences 的 custom_cards JSON（id/name/bank/org/note/style），6 色渐变沿用混合版 CUSTOM_STYLES、组织五项 Visa/万事达/美国运通/银联/JCB 点选可取消；我的卡片页改为整页滚动，自定义区收起时最近 3 张叠成一叠只露最新一张，展开后每张一条渐变色带（编号/名称/银行·组织/备注/↑↓ 调序/编辑/删除），点头部展开收起、点色带开详情（信息行+在卡库搜这家银行+编辑/删除需二次确认），表单校验名称必填、新增后自动展开；空收藏时自定义区照常显示。版本 0.8-native（versionCode 8）。）
- [x] Phase 3c 我的卡片拖动排序 + 卡包分析补全（2026-10-03 完成：卡包分析对照 mineAnalysisHtml 移植——深蓝英雄卡 2×2（总数+verdict/最高档次 cardTier/组织覆盖 n/6 与已覆盖清单/无转换费张数与还差组织）+「境外能力」白卡四条进度（无转换费/3DS/自动购汇/境外 ATM 免发卡行费，n/total）+最通用一张可点进详情（币种数+无转换费 3+3DS 2+网付 2+评分×0.3 同口径）+短板首条+粗算口径注；样本 12 张卡 Python 同口径验算自洽，spec 字段名逐字核对。拖动排序：双列瓷砖长按进入拖动态（放大 1.04+阴影+跟手平移，ScrollView 禁拦截），松手按位移换算目标行列换序，顺序存 SharedPreferences 的 mine_order JSON、进页 applyMineOrder 稳定排序，拖后 450ms 点击锁同混合版；自定义卡仍用既有 ↑↓ 调序。版本 0.9-native（versionCode 9）。）
- [x] Phase 4a 设置补全：字体三档（软件默认/本机/内置宋体——宋体 woff2 在混合版 assets，安卓原生用 Typeface.createFromAsset 需 ttf/otf：混合版有 woff2 不能直接用，需从 ~/.fonts 或系统找 Noto Serif CJK 源文件重新子集化为 ttf；无源就记录阻塞，别硬凑）、界面大小三档（缩放 sp 基准）、高刷（WindowManager preferredRefreshRate 对照混合版 Bridge setHighRefresh）、触感（Vibrator）。（2026-10-03 完成：字体三档走 tv() 统一分发——软件默认 DEFAULT/本机 SANS_SERIF/内置宋体 createFromAsset(fonts/serif.ttf，粗体合成)；宋体由系统 NotoSerifCJK-Regular.ttc 的 SC 面子集化（GB2312 一二级字库+cards/news/界面文案实际用字，共 7909 码位，4.2MB，已验常用字零缺字）；界面大小 0.9/1.0/1.12 倍乘在 tv() 的 sp 上；三者切换即存 SharedPreferences 并整页重建生效。高刷开后遍历 Display.getSupportedModes 取最高刷新率写 preferredDisplayModeId+preferredRefreshRate。触感 Vibrator 一次 15ms，挂在底部导航与设置切换。版本 0.10-native（versionCode 10）。）
- [x] Phase 4b 欢迎页（对照混合版文案，真实图标）+更新日志页（把混合版 CHANGELOG 文案搬成静态数据+右侧滑杆+底部收起/回顶）。（2026-10-03 完成：欢迎页首启自动出现（prefs welcomed 标记）、文案四项与混合版逐字一致（图鉴张数按当前卡库动态填）、开始使用后记住不再弹、设置「欢迎页」行可重开、系统返回键等同开始使用；更新日志把混合版 CHANGELOG 全 84 版/194 条用脚本原样导出 assets/data/changelog.json 静态内置，设置「更新日志」行进入独立页：版本头+圆点条目、右侧常显滚动条、底部常驻「↑ 回到顶部/收起日志」两钮，返回键收起；设置页整体改为可滚动保证新增行够得着。版本 0.11-native（versionCode 11）。）
- [x] Phase 4c OTA 数据更新：启动时拉 https://cdn.jsdelivr.net/gh/dimlogue/cardbox-data@main/cards.json（失败回落 raw.githubusercontent），data_version 更新则存 filesDir 并优先读它（对照 tools/publish-data.js 的发布格式）。（2026-10-03 完成：Store 改为先解析校验再替换（data_version 读取+临时表成功才换）；加载顺序 OTA 文件（filesDir/cards-ota.json）版本>内置才优先，否则内置 assets，离线可读；启动自动 checkDataUpdate(false)、设置页新增「数据」区（数据版本 vN·卡数行+手动检查，手动给 已是最新/更新成功/失败 三种 toast）；更新成功清页面缓存、正看详情不打断；OTA 新卡图片不在安装包时后台从数据仓 images/ 拉到 filesDir/ota-images 缓存（CDN 路径实测 200）。双线 URL 实测均 200，远程 data_version=10 与内置一致，自动检查当前为无更新无感。版本 0.12-native（versionCode 12）。）
- [ ] Phase P 界面打磨（2026-10-03 用户真机验收 0.12 后立项；他原话要点：筛选该是悬浮窗、底栏该悬浮且要有 iOS 风按钮、静音也要有震动且震动要分档、动画缺失、整体 UI 还粗。审美基准就是混合版现行界面，逐项往它靠）：
  - [x] P1 悬浮底栏：底部导航改悬浮——左右留边、底部留空、圆角大、白色微透+投影浮在内容上；选中态 iOS 风胶囊；五枚图标改细线自绘（Canvas 线条：首页/学生帽/卡包/资讯/齿轮，禁用 emoji、禁用系统老图标），内容区底部留白防遮挡。（2026-10-03 完成：根布局改 FrameLayout 叠层，底栏左右/底部各留 12dp、26dp 大圆角白色微透（argb 235）+1dp 浅描边+elevation 投影，对照混合版 .dock-glass 数值；选中项白色胶囊（18dp 圆角）+图标/文字转 #1C1C1E 加粗，未选中 #636366 细线；五枚图标 NavIconView 用 Canvas 在 24 网格自绘线条；content 底部留 88dp 防遮挡。版本 0.14-native（versionCode 14）。）
  - [ ] P2 悬浮搜索与筛选窗：首页加右下悬浮搜索圆钮（点了聚焦顶部搜索框）；筛选改悬浮卡窗（带阴影的浮层卡片，不是贴边全宽抽屉），开合带动画；已选标签栏位置跟混合版一致。
  - [ ] P2b 情景选卡改悬浮窗：现在是整页跳转，要改回混合版形态——从当前页升起的悬浮窗（大圆角、带阴影、底层页面还在后面），在窗里答题看结果，关掉回到刚才那一页；进详情再返回仍保留选卡进度。
  - [ ] P3 触感分档：设置「触感反馈」关/轻/中/强四档（10/20/40ms 或等效振幅），切换即试震；底部导航、筛选点选、收藏切换、主要按钮统一挂震动；静音模式下照常震（走 Vibrator 系统服务，与铃声音量无关），档位存 prefs。
  - [ ] P4 动画补齐：切页淡入+轻微上移；详情页滑入/滑出；筛选浮层缩放+淡入；卡片瓷砖按压波纹反馈；选卡步骤切换有过渡。不许为了动画牺牲滚动流畅度。
  - [ ] P4b 悬浮提示条：底部悬浮胶囊提示（带浮现动画、几秒自动消失），关键操作可带操作钮——移除收藏时出「撤销」、删自定义卡/清筛选同理；把现在生硬的 系统 Toast 在这些场景里换掉，样式照混合版白色毛玻璃长条。
  - [ ] P5 视觉细修：间距/字号层级/圆角/空状态向混合版对齐一轮（对照混合版 styles.css 的数值），图片占位给柔和渐变不要灰块。
- [ ] Phase 5 数据迁移与切换：给混合版发最后一版（3.108）把 localStorage 的 mine/自定义卡/设置经已有 setPref 桥镜像到 SharedPreferences；原生版首启读 com.igll.carddb 的 prefs（同签名同包时可读）完成迁移；切正式包名 com.igll.carddb、versionCode 接 121；提醒用户覆盖安装验收。动这一步前先在主会话汇报，未经用户点头不要切包名。

## 已知注意
- 图片都是 webp（assets/data/images），BitmapFactory 可直接解；解不到图给浅色占位，别崩。
- specs 是任意键值（中文键），详情按原顺序遍历 JSONObject.keys()（org.json 不保证顺序——混合版 JSON 本身有序，org.json 的 JSONObject 会打乱！需要保序时改用手写简易解析或接受乱序并按固定键序优先排列：卡片名称/BIN/币种支持/货币转换费（FTF）/3DS/自动购汇/网付/年费/发行情况，其余随后）。
- org 值域：visa/mastercard/mastercard-nucc/amex-cn/unionpay/jcb/空。orgLabel 已映射。
- 学生判定暂用关键词法（18 张）；Phase 2b 按 app.js 的 studentReason 等逻辑核对真实口径后再定。
