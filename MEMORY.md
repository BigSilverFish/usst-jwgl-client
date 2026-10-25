# USST JWGL (上海理工大学教务与移动教学助手) - 核心记忆与开发规范 (MEMORY.md)

> 本文件为项目的长期架构与开发备忘录。请在每次启动新会话或交接开发任务前优先通读本文件，严格遵守各项开发规范与架构设计约束。

---

## 一、 项目概况与基础信息

- **项目名称**：USST JWGL (上海理工大学教务与移动教学助手)
- **应用包名 (applicationId)**：`bsf.usst.jwgl`
- **当前版本**：`1.2.0` (`versionCode = 8`)
- **适用平台**：Android (支持手机双导航模式、平板大屏横屏侧栏常驻与竖屏适配)
- **技术栈**：
  - **语言与核心库**：Kotlin 1.9+、AndroidX、Material 3 (M3 组件)
  - **视图层**：ViewBinding、`BlurView` (毛玻璃顶栏与底栏)、自定义网格布局
  - **多媒体与扫码**：CameraX 1.3.4、Google ML Kit Barcode Scanning 17.3.0
  - **后台与调度**：AndroidX WorkManager 2.9.1、AlarmManager (强力时钟穿透提醒)、桌面小组件 (AppWidget)
  - **网络与解析**：OkHttp 4.x、Jsoup (教务 HTML 解析)、Gson、统一 WebVPN / CAS 单点登录认证
  - **安全存储**：EncryptedSharedPreferences (密码与核心学籍凭证加密存储)、标准 SharedPreferences (界面偏好)

---

## 二、 核心架构设计

项目采用轻量模块化、混合响应式的 MVVM / 客户端架构：

```
app/src/main/java/cn/edu/usst/jwgl/
├── data/
│   ├── api/            # 网络客户端 (JwglClient, TronClassClient 等)
│   ├── local/          # 缓存与数据管理 (DataCacheManager, TronClassDataCacheManager)
│   └── model/          # 数据实体类 (课表、成绩、空教室、考试、一网畅学作业/课程/签到)
├── ui/
│   ├── adapter/        # 课表、成绩、一网畅学课程/待办等 RecyclerView 适配器
│   ├── fragment/       # 一网畅学等内嵌与独立 Fragment
│   ├── view/           # 自定义课表视图 (TimetableView, GridLine 等)
│   ├── widget/         # 桌面今日课表小组件 (TodayScheduleWidgetProvider)
│   ├── MainActivity.kt # 主界面核心控制器 (兼顾手机与平板全功能管理)
│   ├── JwglWebActivity.kt # 统一教务 / 学工 / 直播 WebVPN 免密直通容器
│   └── tronclass/      # 一网畅学原生二级页面 (CourseDetail, Homework, Exam, Downloads, Login, Scanner)
└── util/
    ├── CourseReminderManager.kt  # 课前与考前日程提醒管理
    ├── RemoteConfigManager.kt    # 学期基准周次与云端动态配置
    ├── TimetableSettingHelper.kt # 课表与全局界面设置偏好
    ├── TronClassDdlWorker.kt     # 一网畅学 DDL 临期后台检查 Worker
    └── ThemeManager.kt           # 深色模式与主题管理
```

---

## 三、 已完成的核心功能

### 1. 课表系统 (Timetable)
- **全动态学期与周次渲染**：支持单双周过滤、本周高亮、学期切换、任意周切换。
- **自定义布局与时段分割**：支持“早晨/下午/晚上”时段分割线（可在设置中开闭）。
- **课表交互**：点击课程弹出完整课程与教师考勤信息；支持双击空白区域快速呼出侧栏。
- **桌面今日微组件**：支持无需打开 App 即可在手机/平板桌面查看当日待上课程与地点。

### 2. 学业与成绩管理 (Grades)
- **成绩与绩点统计**：按学期归类总学分、平均绩点 (GPA)、及格率、必修/选修统计。
- **专业与班级排名查询**：
  - 成绩页右上角提供专属圆形排名查询入口 (`btnQueryRanking`)，与导出证明、刷新按钮保持统一 Material 3 Tonal 36dp 风格。
  - **核心原理**：后台静默向教务系统请求“学生在读期间成绩排名证明 (官方盖章 PDF)” (`10252-xsxxwh-zypmzm`)，通过自主研发的高性能线性 CID/CMap 解压缩流扫描器 (`RankingPdfParser`) 在 15ms 内完成纯文本解码与排名句子语义提取，提取完成后在 `finally` 块中立即物理删除临时 PDF 文件。
  - **优雅弹窗展示**：28dp 大圆角 Material 3 卡片弹窗，精准展示专业名称、年级、红字大字号名次 (`82 /97`) 及加粗排位百分比 (`85%`)。
- **可信学业证明导出**：集成加权均分证明（中/英）、成绩排名证明、中英文可信电子成绩单等 5 大类 PDF 下载与分享。
- **自适应卡片展示**：手机端垂直流式排版，平板横屏自动切换为双列优雅网格卡片。

### 3. 一网畅学移动教学平台 (TronClass - 原生完整集成 1.2.0)
- **顶级导航 Tab**：一网畅学作为一级主功能常驻（手机底部 Tab 3 / 平板侧边栏）。
- **学生卡片与极速签到**：展示个人姓名学号、极速签到按钮、当前课堂活跃签到浮条。
- **多模式扫码与签到器**：
  - 基于 CameraX 与 Google ML Kit 打造的无死角扫码器 (`MlKitScannerActivity`)。
  - 支持雷达距离定位签到、数字口令签到、普通签到。
- **待办作业与测验列表**：解析待提交作业与测验列表，精准倒计时 DDL（按“剩 X 天 X 小时”彩色徽章标注），点击直达原生详情。
- **学期在读课程两列网格**：支持课程快速搜索、学期芯片单选过滤、课程基本信息及讲师展示。
- **原生二级页面**：
  - 课程大纲与课件浏览 (`TronClassCourseDetailActivity`)。
  - 作业详情与附件查看 (`TronClassHomeworkActivity`)。
  - 测验与考试信息 (`TronClassExamActivity`)。
  - 离线课件管理 (`TronClassDownloadActivity`)。
  - 后台 DDL 临期检测服务 (`TronClassDdlWorker`)。

### 4. 更多全校特色服务 (More Features)
- **全校开课检索·蹭课助手**：检索全校任意课程、授课教师、教室与排课时间。
- **空教室自习查询**：实时查询教学楼各时段空闲自习教室。
- **考试日程与考场**：期末/期中考试时间、考场地点、准考证号与倒计时。
- **成绩单与证明下载**：可信中英文成绩单与绩点证明安全调取。
- **学校官方系统直通车**：
  - 官方教务系统 (jwgl) 免重复登录直达。
  - 学工一体化 (new2017) 学生主页免密直通。
  - 课程直播/点播平台 (live) 课堂录播与视频点播直达。
  - *(注：已按规范移除旧版直通车中的一网畅学网页外链，统一收口至原生模块)*。

### 5. 个人与系统设置 (Profile & Settings)
- **个人学籍卡片**：展示学号、姓名、学院、专业、行政班级、学制年限。
- **外观与界面定制**：
  - 深色模式切换（跟随系统 / 浅色 / 深色）。
  - **侧栏顶部显示个人信息**：可在设置中自由开关，默认开启；关闭后侧栏顶部自动折叠留白。
  - 早中晚时段分割线开关、双击空白课表呼出侧栏开关。
- **课前与考前强力提醒**：课前提前时间自由配置、考前日程提醒、系统时钟穿透与保活自检指南。
- **自愈机制**：若本地缓存的学籍或课表异常，后台会在有效会话下静默拉取自愈。

---

## 四、 尚未解决的问题与后续路线图 (Roadmap & Pending Items)

1. **TronClass 视频课件在线播放与进度同步**：
   - 目前课件以文档/附件及基础视频播放为主，断点续传及向服务端精准上报播放进度的功能需进一步封装和测试。
2. **TronClass 作业附件在线上传提交**：
   - 目前已实现作业详情查看、要求说明、评语展示与附件下载；原生的文件选择、压缩与分块上传接口待进一步补全。
3. **正方教务网关验证码策略变化应对**：
   - 目前内置了高命中率的图形验证码处理与会话长效保活机制，若学校教务处升级极验验证码或滑块验证，需配合动态 Hook 调整。
4. **部分深度定制 Android 系统（MIUI/OriginOS/ColorOS）的后台自启动保活**：
   - 课前提醒与 WorkManager DDL 检测在锁屏过久时可能被系统强杀，需持续优化提醒保活指南，引导用户加入白名单。
5. **平板竖屏界面的局部自适应折叠**：
   - 平板横屏已实现 265dp 常驻侧栏 + 双列优雅适配；在极端长宽比的平板竖屏模式下，空教室与检索栏的个别控件留白仍有微调空间。

### 6. 一网畅学 (TronClass) 架构与性能演进 (1.2.0+)
- **扫码签到闪退防御**：
  - `MlKitScannerActivity`、`TronClassExamActivity`、`TronClassDownloadActivity`、`TronClassVideoPlayerActivity` 均已在 `AndroidManifest.xml` 显式注册，避免动态调用时抛出 `ActivityNotFoundException` 导致 App 崩溃。
- **离线优先 (Stale-While-Revalidate) 架构**：
  - 彻底杜绝“网络超时才走 catch 读取缓存”的假缓存机制。
  - 主页与课程详情页进入瞬间（0ms）即从 `TronClassDataCacheManager` 读取本地持久化缓存并直接渲染视图；后台静默同步数据（`silent: Boolean`），同步完成后平滑更新 UI，无任何卡顿和全屏加载圈。
  - 课程详情页解耦：核心章节活动与进度立刻呈现，次级接口（测验列表、班级成员、课程公告）后台异步填充，消除 `await()` 串行阻塞。
  - `BlurView` 延迟至视图初次绘制后初始化（`view.post`），消除进入页面时的掉帧与拖拽掉帧。
- **课表联动一网畅学（第一阶段已落地）**：
  - 在课表课程弹窗 (`CourseDetailDialog`) 中嵌入 `cardTronClassLink` 智能联动卡片。
  - 通过 `TronClassDataCacheManager.findMatchingCourse` 自动对齐正方教务课表与畅课教学班。
  - 动态关联畅课待办，若存在临近作业/测验，实时高亮展示最紧迫待办名及倒计时（如 `待办: 课内实验 (剩 78 天 22 小时)`）。
  - 点击卡片 0 延迟一键跳转对应课程的原生详情页 (`TronClassCourseDetailActivity`)。
- **28080 废弃静态网页资源彻底删除与 APK 瘦身**：
  - 执行 `git rm -rf app/src/main/assets/tronclass_mobile/`，彻底清除 258 个历史遗留 Web 资源文件（12.63 MB），杜绝无用静态资源打包。
  - 同步清理 `JwglWebActivity.kt` 中与 `10.1.15.15:28080`、`AndroidScannerBridge` 网页扫码注入相关的无用逻辑。
- **多任务课程二级页面黑屏与卡顿根治 (0ms 原生复用)**：
  - **根本原因**：`activity_tronclass_course_detail.xml` 中原使用 `NestedScrollView` 包裹 `RecyclerView`，导致多达 80~100 项复杂 CardView 节点在 `onViewCreated` 时被主线程一次性全部 measure 和 inflate，导致界面冻结 2~3 秒黑屏。
  - **解法**：去掉 `NestedScrollView`，采用纯全屏 `RecyclerView`；将顶部“学习进度与一键刷课”抽象为 `TYPE_PROGRESS` 首项 ViewHolder，与章节活动流式自然排布，恢复 120fps 高性能视图复用。
  - **L1 内存缓存**：在 `TronClassDataCacheManager` 增加 `ConcurrentHashMap` 级内存缓存，读取耗时降至 0.001ms，彻底规避频繁主线程 Gson 反序列化阻塞。

---

## 五、 开发过程中必须严格遵守的红线禁忌 (Critical Taboos)

### 🚨 禁忌 1：严禁使用脚本或自动化盲目篡改业务代码
- **绝不允许**使用不可靠的正则表达式、全局文本流替换脚本去批量修改 Kotlin / XML 文件。
- **历史惨痛教训**：曾因自动化替换脚本在 `MainActivity.kt` 的单行注释 `//` 后直接追加代码，导致多行核心业务逻辑被注释、花括号不匹配、class 被提前截断，引发 5000 余行代码编译崩溃。
- **规范**：必须通过语义清晰、经过行级审查的工具修改；涉及多行逻辑改动时，确保语法与闭包层级严格闭合。

### 🚨 禁忌 2：严禁使用任何 Python 脚本
- 本项目执行所有构建、ADB 调试、文件检索与自动化处理时，**必须使用系统原生 PowerShell 或批处理命令**，严禁使用任何 Python 脚本。

### 🚨 禁忌 3：严禁随意挪动或暂存未跟踪文件后遗忘恢复
- **历史惨痛教训**：上一轮调试平板侧栏时，曾将一网畅学相关文件移至临时 stash 目录避开报错，随后执行 `git checkout HEAD -- .` 导致一网畅学全套功能与 1.2.0 版本号“凭空消失”。
- **规范**：
  - 严禁将工程源码移出 Git 仓库目录。
  - 任何临时调试的错误必须就地修复，不得用“删除文件/转移文件”的方式欺骗构建系统。
  - 完成每个功能点后**必须立即执行 `git commit`**，保证所有劳动成果均有版本历史兜底。

### 🚨 禁忌 4：严禁破坏既有文档、注释与代码完整性
- 严禁擅自删减代码中已有的业务防御逻辑（如防崩溃 try-catch、学籍自愈逻辑、双端适配逻辑）。
- 保持既有接口向后兼容，不得随意将公共常量（如 `DEFAULT_TARGET_URL`, `XG_TARGET_URL`）改回 private。

### 🚨 禁忌 5：严禁“改完即交付”，必须完整编译与模拟器闭环验证
- 每次修改代码后，必须在终端执行：
  ```powershell
  .\gradlew.bat :app:assembleDebug
  ```
- 构建成功后必须通过 ADB 安装到模拟器，截屏检查 UI 渲染，验证旋转屏幕、点击按钮等交互无异常后再行交付。
