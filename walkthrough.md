# 课前提醒隔离修复、防闪退加固与 WakeUP 风格课表管理升级

本次升级重点解决了用户反馈的三个核心痛点：
1. **课前提醒混杂多学期课程**：彻底修复历史学期课程闹钟残留与混合调度问题，明确引入“当前生效主课表”概念；
2. **应用运行闪退问题全面排查加固**：针对精准闹钟权限异常、Window BadToken 异常、布局除零异常、JSON 解析异常全面加固，并接入全局 Crash 日志记录器；
3. **借鉴 WakeUP 课程表模式重构课表管理**：提供独立的课表基本设置（支持通过日历组件自定义开学日期、学期总周数、一键切换当前生效课表）与结构化“课程库”管理（卡片式查看所有时段、单时段删除、本周临时停课、整门删除与一键恢复已删课程）。

---

## 1. 核心问题根因分析与解决方案

### 1.1 课前提醒跨学期混杂严重谬误
- **根本原因**：
  此前只要在课表页面切换浏览过历史学期（如 2024-2025 学年、2025-2026 学年），系统均会把当前展示的课程直接传入调度器；同时因未事先清理旧闹钟，导致系统 `AlarmManager` 内残存了多学期的同名 requestCode 闹钟，在满足条件时全部并发触发。
- **解决方案**：
  - 在 [`CourseReminderManager.kt`](file:///C:/Users/Bill%20Gates/.gemini/antigravity/scratch/usst_jwgl_app/app/src/main/java/cn/edu/usst/jwgl/util/CourseReminderManager.kt) 中引入唯一的 `active_semester_key`（如 `"2026_3"`）。
  - **调度前无条件清空旧闹钟**：`scheduleUpcomingReminders` 启动时首先调用 `cancelAllReminders(context)`，杜绝孤儿闹钟残留。
  - **只调度当前生效主课表**：严格从本地缓存与自定义课程中只筛选出属于 `getActiveSemesterKey()` 的课程进行调度；切换浏览历史学期时不再篡改后台提醒。

### 1.2 应用频繁闪退（Crash）全面防御
1. **精确闹钟 SecurityException**：
   - Android 12+ (API 31+) 在未声明或未被授予精确闹钟权限时，直接调用 `setExactAndAllowWhileIdle` 会报致命崩溃；
   - 增加 `canScheduleExactAlarms()` 检查，若无权限自动优雅降级为 `setAndAllowWhileIdle` / `set`，并在闹钟设置外层包覆 `try-catch (e: Throwable)`。
2. **Activity 销毁时的 WindowManager.BadTokenException**：
   - 异步刷新或延迟弹出 Dialog 时若用户切出应用或按返回键，会因 Token 失效闪退；
   - 在 [`MainActivity.kt`](file:///C:/Users/Bill%20Gates/.gemini/antigravity/scratch/usst_jwgl_app/app/src/main/java/cn/edu/usst/jwgl/ui/MainActivity.kt) 中扩展了 `safeShow()` 统一守卫，严格校验 `!isFinishing && !isDestroyed`。
3. **课表网格绘制除零异常 (ArithmeticException)**：
   - 在 [`TimetableView.kt`](file:///C:/Users/Bill%20Gates/.gemini/antigravity/scratch/usst_jwgl_app/app/src/main/java/cn/edu/usst/jwgl/ui/view/TimetableView.kt) 的重叠计算与列宽计算中加入 `colWidth = if (columnCount > 0) totalWidth / columnCount else dp2px(40)` 与 `overlapping.size.coerceAtLeast(1)`。
4. **教务系统及缓存非规范 JSON 解析保护**：
   - 在 [`JwglClient.kt`](file:///C:/Users/Bill%20Gates/.gemini/antigravity/scratch/usst_jwgl_app/app/src/main/java/cn/edu/usst/jwgl/data/network/JwglClient.kt) 与 [`CustomCourseManager.kt`](file:///C:/Users/Bill%20Gates/.gemini/antigravity/scratch/usst_jwgl_app/app/src/main/java/cn/edu/usst/jwgl/data/local/CustomCourseManager.kt) 中将 `.getJSONObject(i)` 全面更换为安全非崩溃的 `.optJSONObject(i) ?: continue`。
5. **全局异常捕获保护**：
   - 在 [`UsstApplication.kt`](file:///C:/Users/Bill%20Gates/.gemini/antigravity/scratch/usst_jwgl_app/app/src/main/java/cn/edu/usst/jwgl/UsstApplication.kt) 中注册了全局未捕获异常处理器，将未预期的异常堆栈保存至本地 Prefs，避免直接闪退退出。

---

## 2. 借鉴 WakeUP 课程表模式重构课表管理

### 2.1 课表基本设置 (Schedule Settings)
- **生效课表概念 (Active Schedule)**：
  - 课表顶部操作栏新增“课表管理”按钮（Vector 调节图标 `ic_tune`）。
  - 当浏览非生效课表时，课表顶部展示横幅提示：`正在浏览非生效课表 (生效课表: 2026-2027 第1学期)`，并提供右侧一键按钮【设为主课表】。
- **学期日程自定义设置**：
  - **开学日期（第 1 周周一）自定义**：点击【选择日期】呼出 Material Design 3 `MaterialDatePicker`，选择任意开学周的日期后自动换算对齐为该周周一；支持【重置为系统预设】。
  - **总周数调节**：支持单选 16 周 / 18 周 / 20 周 / 24 周，课表周次滑动条随之动态缩放。
  - **课前提醒快捷开关**：直接在课表管理页同步开启/关闭课前 15 分钟免后台提醒。

### 2.2 课程库管理 (Course Library)
- 点击 Tab 切换至“课程库管理”：
  - 汇总统计当前学期课程总数与总时段数。
  - 卡片式展示每门课程（课程色块、课程名称、8 位课程代码、考察形式【考试/考查】、学分、任课教师）。
  - **各时段细化管理**：
    - 展示具体的星期、节次、周次范围及教室；
    - 支持【本周停课】（将当前周临时从该时段排除）与【恢复上课】；
    - 支持【删时段】（仅删除课程的特定某天上课安排，不影响其他时段）；
    - 支持【删除整门课程】。
  - **一键恢复误删/停课**：
    - 当有删除课程或临时停课时段时，显示【恢复已删/停课 (X)】按钮，点击可一键重置恢复。

---

## 3. 验证与测试结果

### 3.1 自动化单元测试
在 [`SemesterHelperTest.kt`](file:///C:/Users/Bill%20Gates/.gemini/antigravity/scratch/usst_jwgl_app/app/src/test/java/cn/edu/usst/jwgl/util/SemesterHelperTest.kt) 中编写了针对周次算法与日期生成的测试：
```powershell
.\gradlew.bat testDebugUnitTest
```
- 测试用例：
  - `testCalculateCurrentWeekWithCustomDates`: 验证自定义开学日期及周数边界约束计算；
  - `testGetDatesForWeek`: 验证指定周次的 7 天公历日期生成。
- **测试结果**：`BUILD SUCCESSFUL in 21s`，所有测试全部通过。

### 3.2 编译与代码推送
- 完整打包构建：`.\gradlew.bat assembleDebug`，编译耗时 25s，无任何编译或资源错误（`BUILD SUCCESSFUL`）。
- Git 提交并推送至 GitHub 远程仓库：
  - Commit ID: `d27146b`
  - 仓库地址: `https://github.com/BigSilverFish/usst-jwgl-client.git` (main 分支)。
