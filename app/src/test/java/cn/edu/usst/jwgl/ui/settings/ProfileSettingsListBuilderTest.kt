package cn.edu.usst.jwgl.ui.settings

import cn.edu.usst.jwgl.util.RollcallAlertMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSettingsListBuilderTest {

    private fun baseState(
        isArchiveExpanded: Boolean = false,
        isGuestMode: Boolean = false,
        isTabletLandscape: Boolean = false,
        isBottomBarMode: Boolean = false,
        isDeveloperMode: Boolean = false,
        isCourseReminderEnabled: Boolean = true,
        isExamReminderEnabled: Boolean = true,
        isDdl24hEnabled: Boolean = true,
        isDdlUrgentEnabled: Boolean = true,
        isRollcallMonitorEnabled: Boolean = false,
        isChaoXingMonitorEnabled: Boolean = false,
        isChaoXingLoggedIn: Boolean = false,
        isChaoXingCircuitOpen: Boolean = false,
        isRollcallAutoSignEnabled: Boolean = true,
        missingDiagnosticCount: Int = 0
    ): ProfileSettingsState {
        return ProfileSettingsState(
            studentName = "张三",
            studentId = "2235060101",
            roleBadge = "本科生 · 在读",
            college = "光电信息与计算机工程学院",
            major = "计算机科学与技术",
            className = "计算机2201",
            gradeDisplay = "2022级 (4年制)",
            gender = "男",
            isArchiveExpanded = isArchiveExpanded,
            isGuestMode = isGuestMode,
            isTabletLandscape = isTabletLandscape,
            isBottomBarMode = isBottomBarMode,
            isDeveloperMode = isDeveloperMode,
            darkModeLabel = "跟随系统",
            phoneNavModeLabel = if (isBottomBarMode) "底栏模式" else "侧栏模式",
            isDoubleTapDrawerEnabled = true,
            isDrawerProfileHeaderEnabled = true,
            timetableFontSizeLabel = "标准",
            isDayPartDividersEnabled = true,
            isCourseReminderEnabled = isCourseReminderEnabled,
            courseAdvanceLabel = "15 分钟",
            isExamReminderEnabled = isExamReminderEnabled,
            examAdvanceLabel = "30 分钟",
            isDdl24hEnabled = isDdl24hEnabled,
            isDdlUrgentEnabled = isDdlUrgentEnabled,
            ddlUrgentHours = 2,
            isRollcallMonitorEnabled = isRollcallMonitorEnabled,
            isChaoXingMonitorEnabled = isChaoXingMonitorEnabled,
            isChaoXingLoggedIn = isChaoXingLoggedIn,
            isChaoXingCircuitOpen = isChaoXingCircuitOpen,
            rollcallAlertMode = RollcallAlertMode.POPUP,
            hasOverlayPermission = true,
            isRollcallAutoSignEnabled = isRollcallAutoSignEnabled,
            autoSignStrategyLabel = "有10人签到时 (默认)",
            isPowerfulReminderEnabled = true,
            missingDiagnosticCount = missingDiagnosticCount,
            appVersionLabel = "v1.3.1",
            semesterSummary = "2026-2027-1 · 第 1 周 2026-09-07"
        )
    }

    private fun List<SettingsRowModel>.ids(): Set<SettingsItemId> = mapNotNull {
        when (it) {
            is SettingsRowModel.ProfileCardRow -> it.id
            is SettingsRowModel.ActionRow -> it.id
            is SettingsRowModel.SwitchRow -> it.id
            is SettingsRowModel.DangerButtonRow -> it.id
            is SettingsRowModel.SectionHeaderRow -> null
        }
    }.toSet()

    @Test
    fun `phone drawer mode shows drawer-specific options and profile card`() {
        val rows = ProfileSettingsListBuilder.build(
            baseState(isTabletLandscape = false, isBottomBarMode = false, isGuestMode = false)
        )
        val ids = rows.ids()
        assertTrue(SettingsItemId.PROFILE_CARD in ids)
        assertTrue(SettingsItemId.PHONE_NAV_MODE in ids)
        assertTrue(SettingsItemId.DOUBLE_TAP_DRAWER in ids)
        assertTrue(SettingsItemId.DRAWER_PROFILE_HEADER in ids)
        assertTrue(SettingsItemId.TIMETABLE_FONT_SIZE in ids)
        assertTrue(SettingsItemId.LOGOUT in ids)
    }

    @Test
    fun `phone bottom bar mode hides drawer-only toggles`() {
        val rows = ProfileSettingsListBuilder.build(
            baseState(isTabletLandscape = false, isBottomBarMode = true, isGuestMode = false)
        )
        val ids = rows.ids()
        assertTrue(SettingsItemId.PHONE_NAV_MODE in ids)
        assertFalse(SettingsItemId.DOUBLE_TAP_DRAWER in ids)
        assertFalse(SettingsItemId.DRAWER_PROFILE_HEADER in ids)
        assertTrue(SettingsItemId.TIMETABLE_FONT_SIZE in ids)
    }

    @Test
    fun `tablet landscape omits phone profile card, nav mode, and bottom logout`() {
        val rows = ProfileSettingsListBuilder.build(
            baseState(isTabletLandscape = true, isBottomBarMode = false, isGuestMode = false)
        )
        val ids = rows.ids()
        assertFalse(SettingsItemId.PROFILE_CARD in ids)
        assertFalse(SettingsItemId.PHONE_NAV_MODE in ids)
        assertFalse(SettingsItemId.DOUBLE_TAP_DRAWER in ids)
        assertTrue(SettingsItemId.DRAWER_PROFILE_HEADER in ids)
        assertFalse(SettingsItemId.LOGOUT in ids)
    }

    @Test
    fun `guest mode hides account switch and navigation controls`() {
        val rows = ProfileSettingsListBuilder.build(
            baseState(isGuestMode = true, isTabletLandscape = false)
        )
        val ids = rows.ids()
        assertFalse(SettingsItemId.PHONE_NAV_MODE in ids)
        assertFalse(SettingsItemId.DOUBLE_TAP_DRAWER in ids)
        assertFalse(SettingsItemId.DRAWER_PROFILE_HEADER in ids)

        val profileCard = rows.filterIsInstance<SettingsRowModel.ProfileCardRow>().single()
        assertFalse(profileCard.showSwitchAccount)
        assertEquals("张", profileCard.avatarInitial)
    }

    @Test
    fun `rollcall monitor off folds subitems while keeping dev live qr test`() {
        val nonDevRows = ProfileSettingsListBuilder.build(
            baseState(isRollcallMonitorEnabled = false, isDeveloperMode = false)
        )
        val nonDevIds = nonDevRows.ids()
        assertTrue(SettingsItemId.ROLLCALL_MONITOR in nonDevIds)
        assertFalse(SettingsItemId.ROLLCALL_CHAOXING in nonDevIds)
        assertFalse(SettingsItemId.ROLLCALL_ALERT_MODE in nonDevIds)
        assertFalse(SettingsItemId.ROLLCALL_AUTO_SIGN in nonDevIds)
        assertFalse(SettingsItemId.LIVE_QR_TEST in nonDevIds)

        val devRows = ProfileSettingsListBuilder.build(
            baseState(isRollcallMonitorEnabled = false, isDeveloperMode = true)
        )
        val devIds = devRows.ids()
        assertFalse(SettingsItemId.ROLLCALL_AUTO_SIGN in devIds)
        assertTrue(SettingsItemId.LIVE_QR_TEST in devIds)
    }

    @Test
    fun `rollcall monitor on expands chaoxing, alert mode, and dev auto sign`() {
        val rows = ProfileSettingsListBuilder.build(
            baseState(
                isRollcallMonitorEnabled = true,
                isChaoXingMonitorEnabled = true,
                isChaoXingLoggedIn = false,
                isDeveloperMode = true
            )
        )
        val ids = rows.ids()
        assertTrue(SettingsItemId.ROLLCALL_CHAOXING in ids)
        assertTrue(SettingsItemId.ROLLCALL_ALERT_MODE in ids)
        assertTrue(SettingsItemId.ROLLCALL_AUTO_SIGN in ids)
        assertTrue(SettingsItemId.LIVE_QR_TEST in ids)

        val cxRow = rows.filterIsInstance<SettingsRowModel.SwitchRow>()
            .first { it.id == SettingsItemId.ROLLCALL_CHAOXING }
        assertEquals("未登录", cxRow.statusBadgeText)
        assertEquals(StatusTone.WARNING, cxRow.statusBadgeTone)
    }

    @Test
    fun `chaoxing circuit open shows danger badge`() {
        val rows = ProfileSettingsListBuilder.build(
            baseState(
                isRollcallMonitorEnabled = true,
                isChaoXingMonitorEnabled = true,
                isChaoXingCircuitOpen = true
            )
        )
        val cxRow = rows.filterIsInstance<SettingsRowModel.SwitchRow>()
            .first { it.id == SettingsItemId.ROLLCALL_CHAOXING }
        assertEquals("风控冷却中", cxRow.statusBadgeText)
        assertEquals(StatusTone.DANGER, cxRow.statusBadgeTone)
    }

    @Test
    fun `parameter chips appear only when corresponding switch is enabled`() {
        val enabledRows = ProfileSettingsListBuilder.build(
            baseState(
                isCourseReminderEnabled = true,
                isExamReminderEnabled = true,
                isDdlUrgentEnabled = true,
                isRollcallMonitorEnabled = true,
                isDeveloperMode = true,
                isRollcallAutoSignEnabled = true
            )
        ).filterIsInstance<SettingsRowModel.SwitchRow>().associateBy { it.id }

        assertEquals("提前 15 分钟", enabledRows[SettingsItemId.COURSE_REMINDER]?.paramChipText)
        assertEquals("提前 30 分钟", enabledRows[SettingsItemId.EXAM_REMINDER]?.paramChipText)
        assertEquals("提前 2 小时", enabledRows[SettingsItemId.DDL_URGENT]?.paramChipText)
        assertNotNull(enabledRows[SettingsItemId.ROLLCALL_AUTO_SIGN]?.paramChipText)

        val disabledRows = ProfileSettingsListBuilder.build(
            baseState(
                isCourseReminderEnabled = false,
                isExamReminderEnabled = false,
                isDdlUrgentEnabled = false,
                isRollcallMonitorEnabled = true,
                isDeveloperMode = true,
                isRollcallAutoSignEnabled = false
            )
        ).filterIsInstance<SettingsRowModel.SwitchRow>().associateBy { it.id }

        assertNull(disabledRows[SettingsItemId.COURSE_REMINDER]?.paramChipText)
        assertNull(disabledRows[SettingsItemId.EXAM_REMINDER]?.paramChipText)
        assertNull(disabledRows[SettingsItemId.DDL_URGENT]?.paramChipText)
        assertNull(disabledRows[SettingsItemId.ROLLCALL_AUTO_SIGN]?.paramChipText)
    }

    @Test
    fun `diagnostic health badge reflects missing permission count`() {
        val okRow = ProfileSettingsListBuilder.build(baseState(missingDiagnosticCount = 0))
            .filterIsInstance<SettingsRowModel.ActionRow>()
            .first { it.id == SettingsItemId.NOTIFICATION_DIAGNOSTIC }
        assertEquals("状态正常", okRow.badgeText)
        assertEquals(StatusTone.SUCCESS, okRow.badgeTone)

        val warnRow = ProfileSettingsListBuilder.build(baseState(missingDiagnosticCount = 2))
            .filterIsInstance<SettingsRowModel.ActionRow>()
            .first { it.id == SettingsItemId.NOTIFICATION_DIAGNOSTIC }
        assertEquals("2 项待优化", warnRow.badgeText)
        assertEquals(StatusTone.WARNING, warnRow.badgeTone)
    }

    @Test
    fun `every section has contiguous valid group positions`() {
        val rows = ProfileSettingsListBuilder.build(
            baseState(isRollcallMonitorEnabled = false, isDeveloperMode = false)
        )
        // Collect slices between SectionHeaderRow and non-item rows
        val groups = mutableListOf<List<GroupPosition>>()
        var current = mutableListOf<GroupPosition>()
        for (row in rows) {
            when (row) {
                is SettingsRowModel.SectionHeaderRow -> {
                    if (current.isNotEmpty()) {
                        groups += current
                        current = mutableListOf()
                    }
                }
                is SettingsRowModel.ActionRow -> current += row.position
                is SettingsRowModel.SwitchRow -> current += row.position
                else -> {
                    if (current.isNotEmpty()) {
                        groups += current
                        current = mutableListOf()
                    }
                }
            }
        }
        if (current.isNotEmpty()) groups += current

        assertEquals(5, groups.size)
        for (g in groups) {
            if (g.size == 1) {
                assertEquals(listOf(GroupPosition.SINGLE), g)
            } else {
                assertEquals(GroupPosition.TOP, g.first())
                assertEquals(GroupPosition.BOTTOM, g.last())
                for (i in 1 until g.lastIndex) {
                    assertEquals(GroupPosition.MIDDLE, g[i])
                }
            }
        }
    }

    @Test
    fun `formatFontSizeLabel maps heights accurately`() {
        assertEquals("紧凑", ProfileSettingsListBuilder.formatFontSizeLabel(48))
        assertEquals("标准", ProfileSettingsListBuilder.formatFontSizeLabel(56))
        assertEquals("宽松", ProfileSettingsListBuilder.formatFontSizeLabel(64))
        assertEquals("大号", ProfileSettingsListBuilder.formatFontSizeLabel(72))
        assertEquals("标准", ProfileSettingsListBuilder.formatFontSizeLabel(null))
        assertEquals("标准", ProfileSettingsListBuilder.formatFontSizeLabel(99))
    }
}
