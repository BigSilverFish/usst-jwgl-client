package cn.edu.usst.jwgl.ui.settings

import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.util.RollcallAlertMode

object ProfileSettingsListBuilder {

    fun formatFontSizeLabel(itemHeight: Int?): String {
        return when (itemHeight) {
            48 -> "紧凑"
            56 -> "标准"
            64 -> "宽松"
            72 -> "大号"
            else -> "标准"
        }
    }

    fun build(state: ProfileSettingsState): List<SettingsRowModel> {
        val out = ArrayList<SettingsRowModel>(24)

        if (!state.isTabletLandscape) {
            val cleanName = state.studentName.trim().ifEmpty { "学生用户" }
            val avatarInitial = cleanName.firstOrNull { it.isLetter() }?.toString() ?: "理"
            val summaryParts = buildList {
                add("学号 ${state.studentId.ifBlank { "--" }}")
                if (state.college.isNotBlank() && state.college != "--") {
                    add(state.college)
                }
            }
            out += SettingsRowModel.ProfileCardRow(
                avatarInitial = avatarInitial,
                studentName = cleanName,
                roleBadge = state.roleBadge.ifBlank { "本科生 · 在读" },
                summary = summaryParts.joinToString(" · "),
                showSwitchAccount = !state.isGuestMode,
                isArchiveExpanded = state.isArchiveExpanded,
                college = state.college.ifBlank { "未知学院" },
                major = state.major.ifBlank { "未知专业" },
                className = state.className.ifBlank { "--" },
                gradeDisplay = state.gradeDisplay.ifBlank { "--" },
                gender = state.gender.ifBlank { "--" }
            )
        }

        // 1. 外观与课表
        val appearanceItems = buildList {
            add(
                SettingsRowModel.ActionRow(
                    id = SettingsItemId.DARK_MODE,
                    title = "深色模式",
                    subtitle = null,
                    valueText = state.darkModeLabel,
                    iconRes = R.drawable.ic_palette,
                    iconTone = IconTone.PRIMARY
                )
            )
            if (!state.isGuestMode && !state.isTabletLandscape) {
                add(
                    SettingsRowModel.ActionRow(
                        id = SettingsItemId.PHONE_NAV_MODE,
                        title = "导航栏模式",
                        subtitle = if (state.isBottomBarMode) "底部四栏导航" else "左侧抽屉导航",
                        valueText = state.phoneNavModeLabel,
                        iconRes = R.drawable.ic_layers,
                        iconTone = IconTone.BLUE
                    )
                )
            }
            if (!state.isGuestMode && !state.isTabletLandscape && !state.isBottomBarMode) {
                add(
                    SettingsRowModel.SwitchRow(
                        id = SettingsItemId.DOUBLE_TAP_DRAWER,
                        title = "双击课表空白处呼出侧栏",
                        subtitle = "双击课表无课区域快速打开侧边栏",
                        isChecked = state.isDoubleTapDrawerEnabled,
                        iconRes = R.drawable.ic_gesture,
                        iconTone = IconTone.BLUE
                    )
                )
            }
            if (!state.isGuestMode && (state.isTabletLandscape || !state.isBottomBarMode)) {
                add(
                    SettingsRowModel.SwitchRow(
                        id = SettingsItemId.DRAWER_PROFILE_HEADER,
                        title = "侧栏顶部显示个人信息",
                        subtitle = "在侧边栏顶部展示姓名、学号与院系卡片",
                        isChecked = state.isDrawerProfileHeaderEnabled,
                        iconRes = R.drawable.ic_nav_profile,
                        iconTone = IconTone.BLUE
                    )
                )
            }
            add(
                SettingsRowModel.ActionRow(
                    id = SettingsItemId.TIMETABLE_FONT_SIZE,
                    title = "课表格子大小",
                    subtitle = "调节课表行高与字体显示密度",
                    valueText = state.timetableFontSizeLabel,
                    iconRes = R.drawable.ic_format_size,
                    iconTone = IconTone.BLUE
                )
            )
            add(
                SettingsRowModel.SwitchRow(
                    id = SettingsItemId.DAY_PART_DIVIDERS,
                    title = "课表区分时段",
                    subtitle = "显示上午 / 下午 / 晚上分界线",
                    isChecked = state.isDayPartDividersEnabled,
                    iconRes = R.drawable.ic_schedule,
                    iconTone = IconTone.BLUE
                )
            )
        }
        appendGroup(out, "appearance", "外观与课表", appearanceItems)

        // 2. 日程与作业提醒
        val reminderItems = buildList {
            add(
                SettingsRowModel.SwitchRow(
                    id = SettingsItemId.COURSE_REMINDER,
                    title = "课前提醒",
                    subtitle = if (state.isCourseReminderEnabled) null else "已关闭",
                    isChecked = state.isCourseReminderEnabled,
                    paramChipText = if (state.isCourseReminderEnabled) "提前 ${state.courseAdvanceLabel}" else null,
                    iconRes = R.drawable.ic_schedule,
                    iconTone = IconTone.ORANGE
                )
            )
            add(
                SettingsRowModel.SwitchRow(
                    id = SettingsItemId.EXAM_REMINDER,
                    title = "考前提醒",
                    subtitle = if (state.isExamReminderEnabled) null else "已关闭",
                    isChecked = state.isExamReminderEnabled,
                    paramChipText = if (state.isExamReminderEnabled) "提前 ${state.examAdvanceLabel}" else null,
                    iconRes = R.drawable.ic_exam,
                    iconTone = IconTone.ORANGE
                )
            )
            add(
                SettingsRowModel.SwitchRow(
                    id = SettingsItemId.DDL_24H,
                    title = "作业截止预警 (24h)",
                    subtitle = if (state.isDdl24hEnabled) "作业与测验截止前 24 小时首轮提醒" else "已关闭",
                    isChecked = state.isDdl24hEnabled,
                    iconRes = R.drawable.ic_homework,
                    iconTone = IconTone.ORANGE
                )
            )
            add(
                SettingsRowModel.SwitchRow(
                    id = SettingsItemId.DDL_URGENT,
                    title = "作业临期提醒",
                    subtitle = if (state.isDdlUrgentEnabled) null else "已关闭",
                    isChecked = state.isDdlUrgentEnabled,
                    paramChipText = if (state.isDdlUrgentEnabled) "提前 ${state.ddlUrgentHours} 小时" else null,
                    iconRes = R.drawable.ic_homework,
                    iconTone = IconTone.ORANGE
                )
            )
        }
        appendGroup(out, "reminders", "日程与作业提醒", reminderItems)

        // 3. 课堂签到
        val rollcallItems = buildList {
            add(
                SettingsRowModel.SwitchRow(
                    id = SettingsItemId.ROLLCALL_MONITOR,
                    title = "上课签到后台监听",
                    subtitle = if (state.isRollcallMonitorEnabled) {
                        "上课时后台检测签到活动，发现后即时提醒"
                    } else {
                        "已关闭 · 开启后可配置学习通监听与提醒方式"
                    },
                    isChecked = state.isRollcallMonitorEnabled,
                    iconRes = R.drawable.ic_radar,
                    iconTone = IconTone.GREEN
                )
            )
            if (state.isRollcallMonitorEnabled) {
                val (cxBadge, cxTone, cxSubtitle) = when {
                    !state.isChaoXingMonitorEnabled -> Triple(
                        null,
                        StatusTone.NEUTRAL,
                        "按课表锁定当前课程 50~60s 低频探测，防风控"
                    )
                    state.isChaoXingCircuitOpen -> Triple(
                        "风控冷却中",
                        StatusTone.DANGER,
                        "当前处于学习通风控冷却期，已暂缓请求"
                    )
                    !state.isChaoXingLoggedIn -> Triple(
                        "未登录",
                        StatusTone.WARNING,
                        "请先在一网畅学页授权登录超星学习通"
                    )
                    else -> Triple(
                        null,
                        StatusTone.NEUTRAL,
                        "仅锁定当前课程 50~60s 低频探测，发现即提醒"
                    )
                }
                add(
                    SettingsRowModel.SwitchRow(
                        id = SettingsItemId.ROLLCALL_CHAOXING,
                        title = "同步监听学习通签到",
                        subtitle = cxSubtitle,
                        isChecked = state.isChaoXingMonitorEnabled,
                        statusBadgeText = cxBadge,
                        statusBadgeTone = cxTone,
                        iconRes = R.drawable.ic_school,
                        iconTone = IconTone.GREEN
                    )
                )

                val alertValue = if (state.rollcallAlertMode == RollcallAlertMode.BANNER_ONLY) "仅横幅" else "强弹窗"
                val alertSubtitle = when (state.rollcallAlertMode) {
                    RollcallAlertMode.POPUP -> if (state.hasOverlayPermission) {
                        "亮屏居中弹窗 + 锁屏唤醒"
                    } else {
                        "锁屏唤醒提醒（授权悬浮窗后支持桌面居中弹窗）"
                    }
                    RollcallAlertMode.BANNER_ONLY -> "顶部轻量悬浮横幅 + 高优通知"
                }
                add(
                    SettingsRowModel.ActionRow(
                        id = SettingsItemId.ROLLCALL_ALERT_MODE,
                        title = "签到提醒方式",
                        subtitle = alertSubtitle,
                        valueText = alertValue,
                        iconRes = R.drawable.ic_notifications,
                        iconTone = IconTone.GREEN
                    )
                )

                if (state.isDeveloperMode) {
                    add(
                        SettingsRowModel.SwitchRow(
                            id = SettingsItemId.ROLLCALL_AUTO_SIGN,
                            title = "课堂签到自动提交",
                            subtitle = if (state.isRollcallAutoSignEnabled) null else "已关闭 · 仅提醒，不自动提交签到",
                            isChecked = state.isRollcallAutoSignEnabled,
                            paramChipText = if (state.isRollcallAutoSignEnabled) {
                                "策略：${state.autoSignStrategyLabel}"
                            } else {
                                null
                            },
                            iconRes = R.drawable.ic_bolt,
                            iconTone = IconTone.GREEN
                        )
                    )
                }
            }
            if (state.isDeveloperMode) {
                add(
                    SettingsRowModel.ActionRow(
                        id = SettingsItemId.LIVE_QR_TEST,
                        title = "直播抓帧签到测试",
                        subtitle = "连接智慧录播并测试大屏抽帧与二维码识别",
                        iconRes = R.drawable.ic_live_video,
                        iconTone = IconTone.GREEN
                    )
                )
            }
        }
        appendGroup(out, "rollcall", "课堂签到", rollcallItems)

        // 4. 提醒保障与诊断
        val diagnosticBadgeText = if (state.missingDiagnosticCount <= 0) {
            "状态正常"
        } else {
            "${state.missingDiagnosticCount} 项待优化"
        }
        val diagnosticBadgeTone = if (state.missingDiagnosticCount <= 0) {
            StatusTone.SUCCESS
        } else {
            StatusTone.WARNING
        }
        val reliabilityItems = listOf(
            SettingsRowModel.SwitchRow(
                id = SettingsItemId.POWERFUL_REMINDER,
                title = "高优先级闹钟模式",
                subtitle = if (state.isPowerfulReminderEnabled) {
                    "系统级闹钟准时触发 · 状态栏显示 ⏰ 图标"
                } else {
                    "标准模式 · 息屏较久时可能出现轻微延迟"
                },
                isChecked = state.isPowerfulReminderEnabled,
                iconRes = R.drawable.ic_bolt,
                iconTone = IconTone.PURPLE
            ),
            SettingsRowModel.ActionRow(
                id = SettingsItemId.NOTIFICATION_DIAGNOSTIC,
                title = "提醒与后台运行自检",
                subtitle = "横幅、锁屏显示、悬浮窗与电池优化白名单",
                badgeText = diagnosticBadgeText,
                badgeTone = diagnosticBadgeTone,
                iconRes = R.drawable.ic_shield,
                iconTone = IconTone.PURPLE
            )
        )
        appendGroup(out, "reliability", "提醒保障与诊断", reliabilityItems)

        // 5. 系统与关于
        val systemItems = mutableListOf<SettingsRowModel>()
        when (state.devAddonStatus) {
            cn.edu.usst.jwgl.devapi.DevAddonStatus.ACTIVE,
            cn.edu.usst.jwgl.devapi.DevAddonStatus.DISABLED_BY_USER -> {
                val ver = state.devAddonVersionName?.takeIf { it.isNotBlank() }?.let { "v$it" } ?: "已安装"
                val subtitle = when {
                    state.devAddonRequiresRestart -> "$ver · 扩展包有更新，建议重启应用以生效"
                    state.isDevAddonUserEnabled -> "$ver · 已自动激活扩展功能"
                    else -> "$ver · 已暂停扩展功能"
                }
                systemItems += SettingsRowModel.SwitchRow(
                    id = SettingsItemId.DEV_ADDON_STATUS,
                    title = "开发者扩展包",
                    subtitle = subtitle,
                    isChecked = state.isDevAddonUserEnabled,
                    statusBadgeText = if (state.isDevAddonUserEnabled) "已启用" else "已停用",
                    statusBadgeTone = if (state.isDevAddonUserEnabled) StatusTone.SUCCESS else StatusTone.NEUTRAL,
                    iconRes = R.drawable.ic_bolt,
                    iconTone = IconTone.PRIMARY
                )
            }
            cn.edu.usst.jwgl.devapi.DevAddonStatus.INCOMPATIBLE_API -> {
                systemItems += SettingsRowModel.ActionRow(
                    id = SettingsItemId.DEV_ADDON_STATUS,
                    title = "开发者扩展包版本不兼容",
                    subtitle = "当前扩展包接口版本与主程序不匹配，请更新扩展包",
                    badgeText = "需更新",
                    badgeTone = StatusTone.WARNING,
                    iconRes = R.drawable.ic_bolt,
                    iconTone = IconTone.ORANGE
                )
            }
            cn.edu.usst.jwgl.devapi.DevAddonStatus.SIGNATURE_MISMATCH -> {
                systemItems += SettingsRowModel.ActionRow(
                    id = SettingsItemId.DEV_ADDON_STATUS,
                    title = "开发者扩展包签名不匹配",
                    subtitle = "扩展包签名与主程序不一致，已拒绝加载",
                    badgeText = "签名异常",
                    badgeTone = StatusTone.DANGER,
                    iconRes = R.drawable.ic_bolt,
                    iconTone = IconTone.ORANGE
                )
            }
            cn.edu.usst.jwgl.devapi.DevAddonStatus.LOAD_ERROR -> {
                val detail = state.devAddonErrorMessage?.takeIf { it.isNotBlank() } ?: "扩展包加载异常，请检查主程序与扩展包版本"
                systemItems += SettingsRowModel.ActionRow(
                    id = SettingsItemId.DEV_ADDON_STATUS,
                    title = "开发者扩展包加载失败",
                    subtitle = detail,
                    badgeText = "加载失败",
                    badgeTone = StatusTone.DANGER,
                    iconRes = R.drawable.ic_bolt,
                    iconTone = IconTone.ORANGE
                )
            }
            else -> Unit
        }
        systemItems += listOf(
            SettingsRowModel.ActionRow(
                id = SettingsItemId.CHECK_UPDATE,
                title = "检查更新与同步校历",
                subtitle = state.semesterSummary.ifBlank { "同步最新版本与学期基准周配置" },
                valueText = state.appVersionLabel,
                iconRes = R.drawable.ic_system_update,
                iconTone = IconTone.NEUTRAL
            ),
            SettingsRowModel.ActionRow(
                id = SettingsItemId.GITHUB_REPO,
                title = "开源项目",
                subtitle = "BigSilverFish/usst-jwgl-client",
                valueText = "GitHub",
                iconRes = R.drawable.ic_globe,
                iconTone = IconTone.NEUTRAL,
                isExternalLink = true
            ),
            SettingsRowModel.ActionRow(
                id = SettingsItemId.DISCLAIMER,
                title = "作者声明与隐私说明",
                subtitle = "非官方 · 仅供学习 · 保护隐私",
                iconRes = R.drawable.ic_shield,
                iconTone = IconTone.NEUTRAL
            )
        )
        appendGroup(out, "system", "系统与关于", systemItems)

        if (!state.isTabletLandscape) {
            out += SettingsRowModel.DangerButtonRow(SettingsItemId.LOGOUT)
        }

        return out
    }

    private fun appendGroup(
        out: MutableList<SettingsRowModel>,
        key: String,
        title: String,
        items: List<SettingsRowModel>
    ) {
        if (items.isEmpty()) return
        out += SettingsRowModel.SectionHeaderRow(key = key, title = title)
        val lastIndex = items.lastIndex
        items.forEachIndexed { index, item ->
            val pos = when {
                items.size == 1 -> GroupPosition.SINGLE
                index == 0 -> GroupPosition.TOP
                index == lastIndex -> GroupPosition.BOTTOM
                else -> GroupPosition.MIDDLE
            }
            out += when (item) {
                is SettingsRowModel.ActionRow -> item.copy(position = pos)
                is SettingsRowModel.SwitchRow -> item.copy(position = pos)
                else -> item
            }
        }
    }
}
