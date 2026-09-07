# USST 教务系统 Android 原生客户端 (usst-jwgl-client)

基于 Material Design 3 设计的上海理工大学教务系统 Android 原生客户端，提供高效流畅的课表、成绩与学籍查询体验。

## ✨ 特性一览

- **现代 Material Design 3 设计**：
  - 适配 Android 12+ 动态取色与精致卡片布局。
  - 完整支持浅色模式、深色模式与跟随系统模式。
- **智能课表系统**：
  - 支持全学期周次无缝切换，高亮今日及当前周次，日期（月/日）动态对齐。
  - 支持多时间段排课（同一门课程不同星期、不同节次与不同教室）。
  - 支持单时段精准管理：支持**本周单次临时取消（停课）**、**单时段删除**及**整门课程删除**。
  - 支持自定义课程添加与智能解析、冲突检测。
  - 课程详情智能展示 8 位课程代码、考核形式（考试/考查）与学分错行排版。
- **课前智能提醒**：
  - 支持课前 15 分钟上课提醒，采用 Android AlarmManager 精准定时唤醒，极简轻量，**不常驻后台消耗电量**。
- **成绩与绩点分析**：
  - 实时同步教务成绩，自动计算加权平均绩点 (GPA)。
  - 支持排名展示与学期筛选。
- **学籍与个人信息**：
  - 快速查看专业、班级、学籍状态与个人资料。
- **离线缓存**：
  - 离线持久化存储课表与成绩数据，弱网或无网络环境下秒开。

## 🛠️ 技术栈

- **语言**：Kotlin
- **架构**：MVVM / 原生 Android 组件
- **UI 规范**：Material Design 3 (Material Components for Android)
- **网络与解析**：OkHttp3, Gson / org.json
- **构建工具**：Gradle 8.8 / AGP 8.8.0 / Kotlin 2.0+

## 📦 构建运行

1. 克隆本仓库：
   `ash
   git clone https://github.com/BigSilverFish/usst-jwgl-client.git
   `
2. 使用 Android Studio 打开项目。
3. 连接 Android 设备或启动虚拟机，运行 ./gradlew assembleDebug。
