# USST JWGL 自主选课功能备份与恢复指南

本目录及 Git 分支归档了上海理工大学教务系统（JWGLXT）“自主选课”全部逆向接口、数据模型、UI 交互与配置代码。

---

## 一、备份保留机制

为了确保主分支代码简洁、合规且不影响其他正常功能，选课功能代码已从当前主程序中移除，但已通过以下 **三重保障** 永久保留，未来可随时一键恢复：

1. **Git 专属归档分支**：
   - 分支名称：`feature/course-selection`
   - 该分支包含完整的自主选课实现（包含多轮次选课时间倒计时、高级时段/教室/学院多维度筛选、即时抢课提交、二次确认防误触、已选清单与退选等）。
2. **Git 永久标签（Tag）**：
   - 标签名称：`v1.0.0-with-course-selection`
   - 指向包含完整选课功能的 Commit (`ba68c25`)。
3. **源码文件副本（本目录 `src/`）**：
   - `docs/backup_course_selection/src/` 中存放了所有已剥离的独立源代码及布局文件，无需切换 Git 分支即可随时查看或手工拷回。

---

## 二、未来恢复选课功能的操作方法

### 方式 1：通过 Git 分支一键合并/恢复（推荐）
在项目根目录运行以下 Git 命令即可将选课功能完整恢复到当前分支：
```bash
# 查看选课分支状态
git log feature/course-selection -n 1

# 合并或拉取选课分支的全部变更
git merge feature/course-selection
```
或者如需新建独立分支运行带选课功能的版本：
```bash
git checkout -b restore-selection v1.0.0-with-course-selection
```

---

### 方式 2：手动从备份源码拷回（文件级恢复）

若只需恢复部分代码，可将 `docs/backup_course_selection/src/` 下的文件拷回主工程对应目录：

1. **数据模型与网络服务**：
   - `docs/backup_course_selection/src/CourseSelectionModels.kt` ➜ `app/src/main/java/cn/edu/usst/jwgl/data/model/`
   - `docs/backup_course_selection/src/CourseSelectionService.kt` ➜ `app/src/main/java/cn/edu/usst/jwgl/data/network/`

2. **UI 适配器与弹窗**：
   - `docs/backup_course_selection/src/selection/` 目录 ➜ `app/src/main/java/cn/edu/usst/jwgl/ui/`

3. **布局文件与图标**：
   - `docs/backup_course_selection/src/*.xml`（布局文件） ➜ `app/src/main/res/layout/`
   - `docs/backup_course_selection/src/ic_nav_selection.xml` ➜ `app/src/main/res/drawable/`

4. **主界面与入口恢复**：
   - 在 `layout_main_drawer.xml` 中将 `drawerNavSelection` 的 `visibility` 改为 `visible`。
   - 在 `MainActivity.kt` 中恢复 `MainTab.COURSE_SELECTION` 对应的点击跳转与页面初始化逻辑。

---

## 三、涉及的核心接口与逆向逻辑备忘

- **选课主页初始化**：
  `POST /jwglxt/xsxk/zzxkyqq_cxZzxkYqqIndex.html?gnmkdm=N253512`
  解析选课控制参数（`xkkz_id`, `kklxdm`, `firstKklxdm`, `njdm_id`, `zyh_id` 等）及选课轮次（`xklc`）。
- **选课分类标签与初始数据**：
  `POST /jwglxt/xsxk/zzxkyqq_cxZzxkYqqPartDisplay.html`
- **课程列表检索**：
  `POST /jwglxt/xsxk/zzxkyqq_cxZzxkYqqDisplay.html`
- **教学班详情与余量查询**：
  `POST /jwglxt/xsxk/zzxkyqq_cxZzxkYqqKkcxx.html`
- **执行抢课/选课提交**：
  `POST /jwglxt/xsxk/zzxkyqq_xkxkXk.html`
  （关键参数：`jxb_ids`, `kcmc`, `kch_id`, `qz` 等）。
- **执行退课提交**：
  `POST /jwglxt/xsxk/zzxkyqq_tuike.html`
  （关键参数：`jxb_ids`, `kcmc` 等）。
- **已选课程清单查询**：
  `POST /jwglxt/xsxk/zzxkyqq_cxZzxkYqqYxkc.html`
