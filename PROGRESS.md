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
  - [ ] 2a-1 筛选状态模型 + 底部弹出面板骨架：卡片类型（全部/借记/信用）、卡组织、状态三组 chips，点选即过滤首页网格（对照 app.js chipRow/filtered 的类型/组织/状态部分）。
  - [ ] 2a-2 特点 chips（3DS/可网付/无转换费/自动购汇/Apple Pay，对照 featMatch）+ 发卡行多选 + 已选标签栏（可点删）+ 筛选计数。
  - [ ] 2a-3 排序（评分/名称/银行）+ 列数 1/2/3 + 显示方式（平铺/按银行折叠，分组展开收起，对照 groupBank/renderAllHtml）。
- [ ] Phase 2b 学生页补全：顶部白底明亮统计卡（学生卡数/在发/最高分）+「挑卡只看三件事」条+每卡推荐理由（对照 app.js studentReason/studentFit 生成）。
- [ ] Phase 2c 资讯页：读 assets/data/news.json（先从 ~/workspace/cardapp/site/news.json 同步进 assets，无则联网拉混合版同源），列表+详情展开。
- [ ] Phase 3a 情景选卡：对照 app.js WIZ_SCENARIOS/wizQs/scoreWizard 完整移植（四场景+档次+卡种，向导 UI：轨迹行可回改、结果理由 chips、＋收藏、进详情返回保留进度、打开时背景不可滚——原生天然满足）。
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
