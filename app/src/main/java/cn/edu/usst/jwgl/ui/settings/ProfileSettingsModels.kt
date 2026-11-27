package cn.edu.usst.jwgl.ui.settings

import cn.edu.usst.jwgl.util.RollcallAlertMode

enum class SettingsItemId {
    PROFILE_CARD,
    DARK_MODE,
    PHONE_NAV_MODE,
    DOUBLE_TAP_DRAWER,
    DRAWER_PROFILE_HEADER,
    TIMETABLE_FONT_SIZE,
    DAY_PART_DIVIDERS,
    COURSE_REMINDER,
    EXAM_REMINDER,
    DDL_24H,
    DDL_URGENT,
    ROLLCALL_MONITOR,
    ROLLCALL_CHAOXING,
    ROLLCALL_ALERT_MODE,
    ROLLCALL_AUTO_SIGN,
    LIVE_QR_TEST,
    POWERFUL_REMINDER,
    NOTIFICATION_DIAGNOSTIC,
    CHECK_UPDATE,
    GITHUB_REPO,
    DISCLAIMER,
    LOGOUT
}

enum class GroupPosition {
    SINGLE,
    TOP,
    MIDDLE,
    BOTTOM
}

enum class IconTone {
    PRIMARY,
    BLUE,
    GREEN,
    ORANGE,
    PURPLE,
    NEUTRAL
}

enum class StatusTone {
    SUCCESS,
    WARNING,
    DANGER,
    ACCENT,
    NEUTRAL
}

data class ProfileSettingsState(
    val studentName: String,
    val studentId: String,
    val roleBadge: String,
    val college: String,
    val major: String,
    val className: String,
    val gradeDisplay: String,
    val gender: String,
    val isArchiveExpanded: Boolean,
    val isGuestMode: Boolean,
    val isTabletLandscape: Boolean,
    val isBottomBarMode: Boolean,
    val isDeveloperMode: Boolean,
    val darkModeLabel: String,
    val phoneNavModeLabel: String,
    val isDoubleTapDrawerEnabled: Boolean,
    val isDrawerProfileHeaderEnabled: Boolean,
    val timetableFontSizeLabel: String,
    val isDayPartDividersEnabled: Boolean,
    val isCourseReminderEnabled: Boolean,
    val courseAdvanceLabel: String,
    val isExamReminderEnabled: Boolean,
    val examAdvanceLabel: String,
    val isDdl24hEnabled: Boolean,
    val isDdlUrgentEnabled: Boolean,
    val ddlUrgentHours: Int,
    val isRollcallMonitorEnabled: Boolean,
    val isChaoXingMonitorEnabled: Boolean,
    val isChaoXingLoggedIn: Boolean,
    val isChaoXingCircuitOpen: Boolean,
    val rollcallAlertMode: RollcallAlertMode,
    val hasOverlayPermission: Boolean,
    val isRollcallAutoSignEnabled: Boolean,
    val autoSignStrategyLabel: String,
    val isPowerfulReminderEnabled: Boolean,
    val missingDiagnosticCount: Int,
    val appVersionLabel: String,
    val semesterSummary: String
)

sealed class SettingsRowModel {
    abstract val stableId: String

    data class ProfileCardRow(
        val id: SettingsItemId = SettingsItemId.PROFILE_CARD,
        val avatarInitial: String,
        val studentName: String,
        val roleBadge: String,
        val summary: String,
        val showSwitchAccount: Boolean,
        val isArchiveExpanded: Boolean,
        val college: String,
        val major: String,
        val className: String,
        val gradeDisplay: String,
        val gender: String
    ) : SettingsRowModel() {
        override val stableId: String = "profile_card"
    }

    data class SectionHeaderRow(
        val key: String,
        val title: String
    ) : SettingsRowModel() {
        override val stableId: String = "header_$key"
    }

    data class ActionRow(
        val id: SettingsItemId,
        val title: String,
        val subtitle: String?,
        val valueText: String? = null,
        val badgeText: String? = null,
        val badgeTone: StatusTone = StatusTone.NEUTRAL,
        val iconRes: Int,
        val iconTone: IconTone,
        val isExternalLink: Boolean = false,
        val position: GroupPosition = GroupPosition.SINGLE
    ) : SettingsRowModel() {
        override val stableId: String = "action_${id.name}"
    }

    data class SwitchRow(
        val id: SettingsItemId,
        val title: String,
        val subtitle: String?,
        val isChecked: Boolean,
        val statusBadgeText: String? = null,
        val statusBadgeTone: StatusTone = StatusTone.NEUTRAL,
        val paramChipText: String? = null,
        val iconRes: Int,
        val iconTone: IconTone,
        val position: GroupPosition = GroupPosition.SINGLE
    ) : SettingsRowModel() {
        override val stableId: String = "switch_${id.name}"
    }

    data class DangerButtonRow(
        val id: SettingsItemId = SettingsItemId.LOGOUT
    ) : SettingsRowModel() {
        override val stableId: String = "danger_${id.name}"
    }
}
