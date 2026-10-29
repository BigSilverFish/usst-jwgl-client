# USST JWGL (上海理工大学教务与移动教学助手) - 核心记忆与开发规范 (MEMORY.md)

> **重要说明**：本文件为项目的长期架构、业务设计与开发纪律备忘录。请在每次启动新会话、交接开发任务或执行代码重构前**优先通读本文件**，严格遵守各项开发规范与架构设计红线约束。

---

## 一、 项目概况与基础信息

- **项目名称**：USST JWGL (上海理工大学综合教务与移动教学助手)
- **应用包名 (applicationId)**：`bsf.usst.jwgl`
- **当前版本**：`1.2.2` (`versionCode = 10`, `versionName = "1.2.2"`)
- **适用平台**：Android 7.0+ (API Level 24 ~ 36)，纯 64 位原生架构 (`arm64-v8a`)
- **形态适配**：手机双导航模式（底栏 / 侧栏抽屉自由切换）、平板大屏横屏（265dp 常驻左侧导航栏 + 右侧主从多列网格）与竖屏适配
- **技术栈选型**：
  - **语言与架构**：Kotlin 2.0+、AndroidX、Material Design 3 (M3 组件规范)、Kotlin Coroutines 全协程异步调度、Fragment 组件化解耦架构
  - **视图层**：ViewBinding、`BlurView` 动态毛玻璃效果（顶栏与悬浮底栏）、自定义高性能排课网格 (`TimetableView`)
  - **视觉识别与相机**：CameraX 1.3.4、Google ML Kit Barcode Scanning 17.3.0（已彻底剔除旧版 ZXing 库）
  - **后台调度与通知**：AndroidX WorkManager 2.9.1、系统底层 `AlarmManager`（时钟唤醒强力穿透提醒）、桌面今日课表小部件 (AppWidget)
  - **网络与解析**：OkHttp 4.12、Jsoup 1.17 (教务 HTML 解析与清洗)、Gson 2.10、学校 CAS 统一身份认证与 WebVPN 双向凭证管理
  - **硬件级安全加密**：Google Jetpack `EncryptedSharedPreferences` (AES-256 SIV/GCM 结合 Android Keystore 硬件保护密钥)
  - **PDF 解析与导出**：Android 原生 `PdfDocument` 引擎、自研无依赖嵌入式 `RankingPdfParser` (CID-CMap 字符流解析器)

---

## 二、 核心架构设计

工程采用组件化解耦、离线优先（Stale-While-Revalidate）的高性能客户端架构：

```
app/src/main/java/cn/edu/usst/jwgl/
├── data/
│   ├── local/              # 数据库与加密存储 (AppDatabase, AuthPreferences, DataCacheManager, TronClassPreferences 等)
│   ├── model/              # 数据实体类 (CourseSchedule, StudentProfile, ExamSchedule, TronClassModels 等)
│   └── network/            # 网络通信客户端与 Cookie 容器
│       ├── JwglClient.kt       # 正方教务核心 API 通信栈 (学籍、课表、成绩、空教室、全校查课、通用 CAS SSO 静默换票)
│       ├── TronClassClient.kt  # 一网畅学核心 API 客户端 (CAS 自动换票、课程大纲、作业提交、课堂讨论区、扫码签到)
│       ├── LappClient.kt       # 移动校园轻应用与微服务通信客户端
│       ├── MemoryCookieJar.kt  # 高性能线程安全内存 Cookie 容器 (支持动态外部注入与去重)
│       └── TrustAllCerts.kt    # SSL 证书与安全连接辅助
├── ui/
│   ├── adapter/            # RecyclerView 适配器 (周课表、成绩单、全校排课、作业列表、讨论区列表等)
│   ├── fragment/           # 模块化独立 Fragment (拆分自原庞大 MainActivity)
│   │   ├── ScheduleWeekFragment.kt          # 核心周课表视图
│   │   ├── ScheduleMonthFragment.kt         # 月度与学期全景视图
│   │   ├── GradesFragment.kt                # 成绩查询与学业分析模块
│   │   ├── FreeClassroomFragment.kt         # 9大教学楼空教室实时检索
│   │   ├── SchoolCourseSearchFragment.kt    # 全校排课离线毫秒级搜索
│   │   ├── ExamQueryFragment.kt             # 考试与考场安排查询
│   │   └── GradeReportsFragment.kt          # 中英文成绩单证明生成
│   ├── view/               # 自定义高性能视图 (TimetableView, GridLine 等)
│   ├── widget/             # 桌面微组件 (TodayScheduleWidgetProvider)
│   ├── MainActivity.kt     # 主界面中央控制器 (全生命周期管理、多模式底栏/侧栏调度、双端响应式)
│   ├── LoginActivity.kt    # 原生极速登录 Activity (凭据记忆、自动登录与异常兜底)
│   ├── SsoLoginActivity.kt # 官方 SSO 页面登录容器 (自适应状态栏、防验证码阻断、会话双向同步)
│   ├── JwglWebActivity.kt  # 学校系统直通车容器 (状态栏 WindowInsets 避让、后台 OkHttp CAS SSO 预认证与无感直达)
│   ├── ExcelCleanActivity.kt # 实验课表导入预览与文件修复向导界面
│   ├── scanner/            # 扫码模块 (MlKitScannerActivity - CameraX + Google ML Kit 原生竖屏极速扫码)
│   ├── tronclass/          # 一网畅学原生二级模块与组件
│   │   ├── TronClassFragment.kt              # 一网畅学主页 (三列 Tab：作业、公告、课程；智能签到卡片)
│   │   ├── TronClassCourseDetailActivity.kt  # 课程详情 (四列 Tab：课件、公告、作业测验、讨论；底部班级成员抽屉)
│   │   ├── TronClassDiscussionActivity.kt    # 课堂讨论独立交互界面 (话题浏览、观点发表、回帖互动、点赞)
│   │   ├── TronClassHomeworkActivity.kt      # 作业详情、附件下载、拍照提交与重新提交
│   │   ├── TronClassExamActivity.kt          # 测验与考试原生列表
│   │   └── TronClassDownloadActivity.kt      # 离线缓存文档与附件管理
│   └── wakeup/             # 课表唤醒与交互组件 (CourseDetailDialog - 课表与畅课深度联动卡片)
└── util/
    ├── CookieSyncHelper.kt       # OkHttp 与 Android WebView 间双向 Cookie 智能分发同步器 (支持 WebVPN)
    ├── CourseReminderManager.kt  # 课前与考前日程精准提醒调度器
    ├── InitialSyncHelper.kt      # 初次登录全校课表与个人数据并发同步助手
    ├── RankingPdfParser.kt       # 独创轻量级 CID-CMap 盖章 PDF 成绩排名解码器
    ├── TronClassDdlWorker.kt     # 一网畅学 DDL 临期后台检查 Worker
    ├── CalendarExportHelper.kt   # 系统日历日程 ICS 导出与同步助手
    ├── RemoteConfigManager.kt    # 学期基准周次与云端动态作息配置管理
    └── TimetableSettingHelper.kt # 课表与全局界面设置持久化
```

---

## 三、 已完成的核心功能体系

### 1. 登录与身份认证系统 (Auth System)
- **原生表单极速登录**：基于 OkHttp 直连 CAS 网关，本地安全凭据自动预填，毫秒级换取教务与畅课双会话。
- **官方 SSO 页面登录选项 (`SsoLoginActivity.kt`)**：
  - 针对校园网多设备或高敏时段偶发的**图片验证码**或**拼图滑块**，在登录卡片常驻官方通道；
  - 自动向官方 CAS 网页注入学号密码预填（不自动提交，留给用户交互完成验证码）；
  - 网页认证跳转直达教务时，通过 `CookieSyncHelper.syncWebViewCookiesToJwgl()` 将 Android 系统 `CookieManager` 中的认证凭证无缝灌入 `JwglClient` (OkHttp)；
  - 自动触发学籍与全校排课初始化，直达主界面；彻底移除已失效的“从畅课导入”冗余入口。

### 2. 智能课表与时刻表双轨系统 (Timetable)
- **新老学期时刻表自适应**：自动识别新老时刻表（2025-2026-2 及以往采用 12 节老时刻表，2026-2027-1 起采用 13 节新时刻表），左侧清晰标注每小节上下课时间与时段视觉分割线。
- **周次与多维视图**：顶部动态周次胶囊，当前周高亮标明，公历日期动态匹配；支持“仅本周课程”单选过滤与单双周合并。
- **冲突排课并排等宽渲染**：同一时段重叠的多门课程自动计算等宽并列排布，杜绝层叠遮挡；支持本周单次停课与整门课程全局清理。
- **课表与畅课深度联动**：课表课程弹窗 (`CourseDetailDialog`) 嵌入 `cardTronClassLink`，自动智能对齐教务与畅课课程，动态展示当前学习进度百分比与紧迫待办倒计时，一键 0 延迟跳转畅课详情。

### 3. 一网畅学移动教学平台 (TronClass - 全面重构版)
- **主页全新三列 Tab 架构**：
  - 划分为直观的 **「待办作业」**、**「课程公告」**、**「在修课程」** 三列 Tab，大幅降低层级深度与翻找成本；
  - 顶部 Hero 卡片智能动态融合：检测到待签到时背景平滑由红变绿，卡片底部动态展示当前签到课程与快捷签到按钮，无签到时优雅收敛。
  - **深色模式对比度修复**：顶部卡片姓名与学号锁定纯白（`#FFFFFF` / `#E0FFFFFF`），杜绝因 M3 颜色反转将暗红文本绘制在深红底色上的隐形问题。
- **课程二级详情页升级**：
  - 导航重构为 **「章节课件」**、**「课程公告」**、**「作业测验」**、**「课堂讨论」** 四列 Tab；
  - 原「班级成员」Tab 移入顶栏右侧圆形按键唤起的优雅**原生底部抽屉弹窗**，纵向视觉更加聚焦。
- **课堂讨论区完整交互链路上线 (`forum`)**：
  - 精确识别课程中 `forum` 类型活动；
  - 支持查看讨论要求、全班发言与他人讨论内容；
  - 原生支持**发表个人观点**、**回帖互动交流**、**展开多级回复**与**点赞互动**。
- **作业提交与交互体验重构**：
  - 附件双选支持：快捷“拍照提交”与“选择文件”；
  - 一键提取题目：顶栏右侧快捷复制按钮，一键拷贝说明；
  - 单次点击生效：重新提交底层合并撤回与重新提交两步操作；
  - 视觉避让优化：下拉刷新指示器完整下沉避让毛玻璃顶栏，软键盘弹出平滑推起输入卡片。
- **作业截止二级智能提醒系统 (DDL Guard)**：
  - WorkManager 周期同步 + AlarmManager 系统时钟唤醒双重保障，支持 24 小时前预警与临期 1~3 小时紧急提醒，深度休眠下精准穿透，动态去重防骚扰。
- **高精度原生签到系统**：
  - 基于 Google ML Kit + CameraX，支持数字口令九宫格自动提交、GPS 经纬度位置签到、自主签到与普通签到；
  - **雷达点名坐标系统全面校准与二次加偏根治**：
    - 校准 `CoordinateUtils.USST_BUILDINGS` 教学楼坐标库为高德 GCJ-02 真实高精火星坐标，彻底根除因采信未经纠偏的原始经纬度导致的 437 米系统硬偏移；
    - 针对行货 Android 系统网络定位（`NETWORK_PROVIDER` 底层高德/百度 NLP 输出已是 GCJ-02）引入智能 Provider 鉴权，仅对纯硬件卫星 `GPS_PROVIDER` 做纠偏，彻底杜绝网络定位二次加偏；
    - 增加 10 分钟内缓存时效过滤与卫星/基站并发单次定位监听，提供精准的定位来源、精度回显与出圈米数提示。
  - 上课时段后台轻量驻留监听机制与雷达点名自动匹配。
- **基于 Android Keystore 硬件加密的多账号隔离**：
  - 支持多学号一键秒级切换，各账号生成独立设备指纹，签到与鉴权互不干扰。

### 4. 官方系统直通车 CAS SSO 单点登录根治与全面屏适配
- **学工一体化系统 (new2017)**：
  - 全面接入 WebVPN 外网专线（`https://new2017.webvpn.usst.edu.cn/default.aspx`），解析学校公网网关，根除校外访问私网 IP 超时问题；
  - 后台 OkHttp 通用 CAS SSO 静默换票，获取 `ASP.NET_SessionId`、`CASTGC` 与 `iPlanetDirectoryPro`；
  - 注入 Cookie 后直接加载学生主页 `StuDefault.aspx`，周智轩同学学生主页秒级无感直达，学籍异动、奖学金、勤工助学等完整可用。
- **正方教务系统 (jwgl)**：
  - 统一 CAS 预认证与会话保活机制，精准拦截正方原生登录页（`login_slogin.html`），强制重定向至 SSO 入口，杜绝因账密类型不同导致的密码误输与验证码风控。
- **课程直播与点播平台 (live)**：
  - 接入后台 CAS SSO 预授权，支持 Web 与移动端 Cookie 自动双向同步，秒级进入课程直播与当堂点播回放。
- **全面屏状态栏与避让优化**：
  - 布局配备 `viewStatusBarSpacer`，深度适配系统 WindowInsets，彻底解决顶栏与系统状态栏时间、电量、WIFI 图标重叠冲突。

### 5. 实验课表导入与自动修复引擎
- **多格式兼容**：支持 `.xlsx`、`.csv`、HTML 结构表及伪 xls 文本格式。
- **内置自动修复引擎**：自动剔除 UTF-8 BOM 标头、修复损坏或缺失的 XML Sheet 节点、智能清洗去重并按上理作息折算教学周与节次；零破坏临时副本解析，数据独立隔离写入 `lab_reservation` 表。

### 6. 全校课表全量离线检索系统
- **首次全量持久化**：首次全量拉取全校 9000+ 门排课并固化为本地磁盘 JSON 与内存二级缓存，真正实现离线秒查；纯内存多维联合检索耗时 **< 2ms**。
- **离线自愈与一键添至课表**：支持培养层次（本科生/研究生）过滤，内置 APK 离线镜像自愈机制，支持将全校课程一键加入个人课表。

### 7. 空教室精准排查（9 大教学楼 396 间教室全覆盖）
- **修复底层分页偏移缺陷**：解决教务接口行偏移量导致的 8000+ 条排课截断遗漏，全量排课 100% 纳入判定。
- **当前周活性判定与别名归一化**：严格依据当前教学周判定占用，杜绝非本周课程误占；归一化建筑名称别名；权威收录 9 大主流教学楼全部常规自习教室，排除报告厅与封闭实验室。

### 8. 学业情况与多维成绩档案
- **学业成绩全面统计**：分学期加权平均分与 GPA 实时核算。
- **轻量级 CID-CMap PDF 解码引擎**：自研解码器在 15ms 内解析盖章 PDF 字符流，提取专业/班级成绩排名。
- **中英文成绩单证明导出**：原生生成中文/英文加权平均分证明、排名证明 PDF，支持调起打印与分享。

---

## 四、 尚未解决的问题与后续路线图 (Pending Items & Roadmap)

1. **TronClass 视频课件在线播放与进度精准上报**：
   - 目前课件以文档/附件及基础视频播放为主，断点续传及向服务端定时上报真实观看秒数/进度的协议仍需进一步封装完善。
2. **TronClass 测验与考试的原生答题提交**：
   - 目前已实现测验考试列表与元数据查看，选择题、判断题的原生交互答题与一键交卷接口待后续补齐。
3. **部分深度定制 Android 系统（MIUI/HyperOS/OriginOS/ColorOS）的保活策略**：
   - 课前提醒与 WorkManager DDL 检测在极长时间深度休眠时可能被系统杀进程，需持续完善保活指南与白名单引导。
4. **平板竖屏界面的局部自适应微调**：
   - 平板横屏已实现优雅双列与侧栏常驻；在极端长宽比的平板竖屏模式下，个别检索栏的留白仍有微调空间。

---

## 五、 开发过程中必须严格遵守的红线禁忌 (Critical Taboos)

> [!CAUTION]
> **以下 7 条红线禁忌是全团队血与泪的教训总结，后续任何 AI 助手或人类开发者在操作此代码库时必须无条件严格遵守，违者必将引发构建崩溃、代码损毁或严重线上事故！**

### 🚨 红线 1：绝对禁止使用任何脚本自动化修改业务代码（CRITICAL!）
- **严禁编写 Python、PowerShell、Bash 或任何批处理脚本来对 `.kt`、`.xml` 等业务代码执行正则匹配、字符串查找替换或流式重写！**
- **历史惨痛教训**：
  1. Windows 平台特有的文件句柄占用问题（`user-mapped section open`），脚本直接写入极易引发 `PermissionDenied` 或生成残缺文件；
  2. 字符串转义漏洞（如引号、`$`、转义符缺失）极易在代码中引入语法错误；
  3. 曾发生自动化脚本在 `MainActivity.kt` 的单行注释 `//` 后面直接追加代码，导致数百行核心业务逻辑被注释掉、闭合花括号丢失、类定义被截断，引发 5000 余行代码编译全线瘫痪！
- **唯一正确规范**：所有代码修改必须使用官方原子编辑工具（如 `edit_file`），进行**精准、最小化、严格保持上下文闭合的单块替换**，并在修改后通过 `git diff` 逐行审查。

### 🚨 红线 2：严禁未经用户在当前对话中明确授权向远端 Git 执行推送（`git push`）
- **在未获得用户在当前对话中的明确指令前（如“推送到 GitHub”、“push 上去”），绝对禁止执行任何向远端仓库的推送命令（包括 `git push`、推送标签 `git push --tags` 或强制推送 `git push -f`）！**
- 完成本地修改后，仅允许执行本地 `git add` 与 `git commit`；提交完成后，必须向用户清晰呈现 Commit 哈希与变更要点，并主动征询用户是否需要推送。

### 🚨 红线 3：严禁随意挪动或暂存未跟踪文件后遗忘恢复
- **历史惨痛教训**：曾为了“避开编译报错”，将未完成的文件移动到临时目录避开检查，随后执行 `git checkout` 导致新开发的模块代码凭空蒸发！
- **规范**：严禁将工程源码移出 Git 仓库；任何编译报错必须就地排查修复，严禁通过删除文件或重命名来欺骗构建系统；完成每个功能点后必须立刻执行本地 `git commit` 留痕。

### 🚨 红线 4：严禁破坏既有文档、注释与代码业务完整性
- 严禁擅自删减代码中已有的防御性逻辑（如防崩溃 try-catch、学籍自愈逻辑、双端 Pad 适配逻辑、状态栏避让代码）。
- 保持既有模块向后兼容，严禁随意将公共常量或辅助类改回私有或删除。

### 🚨 红线 5：严禁“改完即交付”，必须完整编译与真机/模拟器闭环验证
- 每次修改代码后，必须在终端执行完整编译检查：
  ```powershell
  ./gradlew assembleRelease
  ```
- 确认构建输出 `BUILD SUCCESSFUL`；如有已连接的设备或模拟器，必须安装 APK、截屏检查 UI 渲染，验证状态栏避让、菜单交互及业务逻辑无异常后再交付用户。

### 🚨 红线 6：严格保持依赖纯净度与构建瘦身
- 引入任何第三方库必须经过充分评估，严禁引入未经混淆测试或与项目架构冲突的冗余库；不再使用的旧库（如 ZXing、废弃网络库）必须彻底清除依赖、混淆规则及全部关联代码，保持 Release 安装包极致轻量（维持纯 64 位 `arm64-v8a`，安装包控制在 10MB 左右）。

### 🚨 红线 7：严禁在自定义暗底卡片上盲目使用 `@color/on_primary`，认证首选后台 OkHttp 闭环
- **UI 配色禁忌**：Material Design 3 在深色模式下会将 `@color/on_primary` 转换为暗红色（如 `#561D22`），若在自定义深色卡片（如红色/绿色 Hero 卡片）中直接引用，将导致文字与背景完全同色而不可辨识。所有深底 Hero 卡片必须显式指定纯白（`#FFFFFF` / `#E0FFFFFF`）文字。
- **SSO 认证禁忌**：学校 CAS 登录页面在移动端存在复杂的 JS 校验（如语言检测重载、动态 eventId），依赖 WebView 前端注入 JS 提交表单极不稳定。必须优先采用 `JwglClient.preAuthCasService` 在后台通过 OkHttp 完成原子级换票，再通过 `CookieSyncHelper` 同步 Cookie 至 WebView 直接渲染目标页面，确保 100% 确定性。
