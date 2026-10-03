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
- [ ] Phase 3b 自定义卡：新建/编辑/删除（名字/银行/颜色/备注），存 SharedPreferences JSON；我的卡片页展示+展开。
- [ ] Phase 3c 我的卡片拖动排序（长按拖动+顺序持久化）+卡包分析补全（对照 mineAnalysisHtml：最高档次 cardTier、组织覆盖清单、最通用一张、短板、境外能力四条进度）。
- [ ] Phase 4a 设置补全：字体三档（软件默认/本机/内置宋体——宋体 woff2 在混合版 assets，安卓原生用 Typeface.createFromAsset 需 ttf/otf：混合版有 woff2 不能直接用，需从 ~/.fonts 或系统找 Noto Serif CJK 源文件重新子集化为 ttf；无源就记录阻塞，别硬凑）、界面大小三档（缩放 sp 基准）、高刷（WindowManager preferredRefreshRate 对照混合版 Bridge setHighRefresh）、触感（Vibrator）。
- [ ] Phase 4b 欢迎页（对照混合版文案，真实图标）+更新日志页（把混合版 CHANGELOG 文案搬成静态数据+右侧滑杆+底部收起/回顶）。
- [ ] Phase 4c OTA 数据更新：启动时拉 https://cdn.jsdelivr.net/gh/dimlogue/cardbox-data@main/cards.json（失败回落 raw.githubusercontent），data_version 更新则存 filesDir 并优先读它（对照 tools/publish-data.js 的发布格式）。
- [ ] Phase 5 数据迁移与切换：给混合版发最后一版（3.108）把 localStorage 的 mine/自定义卡/设置经已有 setPref 桥镜像到 SharedPreferences；原生版首启读 com.igll.carddb 的 prefs（同签名同包时可读）完成迁移；切正式包名 com.igll.carddb、versionCode 接 121；提醒用户覆盖安装验收。动这一步前先在主会话汇报，未经用户点头不要切包名。

## 已知注意
- 图片都是 webp（assets/data/images），BitmapFactory 可直接解；解不到图给浅色占位，别崩。
- specs 是任意键值（中文键），详情按原顺序遍历 JSONObject.keys()（org.json 不保证顺序——混合版 JSON 本身有序，org.json 的 JSONObject 会打乱！需要保序时改用手写简易解析或接受乱序并按固定键序优先排列：卡片名称/BIN/币种支持/货币转换费（FTF）/3DS/自动购汇/网付/年费/发行情况，其余随后）。
- org 值域：visa/mastercard/mastercard-nucc/amex-cn/unionpay/jcb/空。orgLabel 已映射。
- 学生判定暂用关键词法（18 张）；Phase 2b 按 app.js 的 studentReason 等逻辑核对真实口径后再定。
