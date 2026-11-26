package cn.edu.usst.jwgl.util

import cn.edu.usst.jwgl.data.model.ChaoXingActiveItem
import cn.edu.usst.jwgl.data.model.ChaoXingCourse
import cn.edu.usst.jwgl.data.model.ChaoXingSignDetail
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.service.RollcallMonitorService
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class RollcallMonitorTest {

    @Test
    fun testScheduledClassSession_isDuring_withBuffers() {
        val baseCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = baseCal.timeInMillis // 08:00

        val endCal = (baseCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 35)
        }
        val endTime = endCal.timeInMillis // 09:35

        val bufferStartTime = startTime - RollcallScheduleHelper.BUFFER_PRE_CLASS_MINUTES * 60 * 1000L // 07:50
        val bufferEndTime = endTime + RollcallScheduleHelper.BUFFER_POST_CLASS_MINUTES * 60 * 1000L // 09:40

        val session = ScheduledClassSession(
            courseName = "高等数学",
            room = "卓越楼315",
            teacher = "张教授",
            startNode = 1,
            endNode = 2,
            startTimeMillis = startTime,
            endTimeMillis = endTime,
            bufferStartTimeMillis = bufferStartTime,
            bufferEndTimeMillis = bufferEndTime
        )

        // 07:49 (提前11分钟，未进入缓冲区) -> false
        assertFalse(session.isDuring(bufferStartTime - 60 * 1000L))

        // 07:50 (提前10分钟，刚好进入预警缓冲区) -> true
        assertTrue(session.isDuring(bufferStartTime))

        // 08:00 (上课开始) -> true
        assertTrue(session.isDuring(startTime))

        // 08:45 (第一小节下课) -> true
        assertTrue(session.isDuring(startTime + 45 * 60 * 1000L))

        // 09:35 (下课时间点) -> true
        assertTrue(session.isDuring(endTime))

        // 09:40 (下课后延迟5分钟内) -> true
        assertTrue(session.isDuring(bufferEndTime))

        // 09:41 (超过下课后5分钟缓冲区) -> false
        assertFalse(session.isDuring(bufferEndTime + 60 * 1000L))
    }

    @Test
    fun testRadarRollcall_buildingMatching_fromClassroom() {
        // 1. 卓越楼
        val bZy = CoordinateUtils.findMatchingBuilding("卓越楼315")
        assertNotNull(bZy)
        assertEquals("卓越楼", bZy?.name)
        assertEquals("本部南校区", bZy?.campus)

        // 2. 第一教学楼 (房间号201在东侧范围)
        val b1j = CoordinateUtils.findMatchingBuilding("一教201")
        assertNotNull(b1j)
        assertEquals("一教东", b1j?.name)
        assertEquals("本部北校区", b1j?.campus)

        // 3. 复兴路教学楼
        val bFxl = CoordinateUtils.findMatchingBuilding("复兴路教学楼302")
        assertNotNull(bFxl)
        assertEquals("复兴路教学楼", bFxl?.name)
        assertEquals("复兴路校区", bFxl?.campus)

        // 4. 营口路教学楼
        val bYkl = CoordinateUtils.findMatchingBuilding("营口路教学楼101")
        assertNotNull(bYkl)
        assertEquals("营口路教学楼", bYkl?.name)
        assertEquals("营口路校区", bYkl?.campus)

        // 5. 综合楼
        val bZh = CoordinateUtils.findMatchingBuilding("综合楼B402")
        assertNotNull(bZh)
        assertTrue(bZh?.name?.contains("综合楼") == true)
        assertEquals("本部北校区", bZh?.campus)

        // 6. 四教
        val b4j = CoordinateUtils.findMatchingBuilding("四教201")
        assertNotNull(b4j)
        assertTrue(b4j?.name?.contains("四教") == true)
        assertEquals("本部南校区", b4j?.campus)

        // 7. 国合楼
        val bGh = CoordinateUtils.findMatchingBuilding("国合楼501")
        assertNotNull(bGh)
        assertTrue(bGh?.name?.contains("国合楼") == true)
        assertEquals("本部南校区", bGh?.campus)
    }

    @Test
    fun testRadarRollcall_fallbackBuilding() {
        // 未指定教室或空字符串 -> 回退至本部北校区核心第一教学楼东
        val emptyMatch = CoordinateUtils.findMatchingBuilding("")
        assertNull(emptyMatch)

        val fallback = emptyMatch ?: CoordinateUtils.USST_BUILDINGS.find { it.id == "b_516_1j_e" } ?: CoordinateUtils.USST_BUILDINGS.first()
        assertNotNull(fallback)
        assertEquals("一教东", fallback.name)
        assertEquals("本部北校区", fallback.campus)
        assertEquals(31.291731, fallback.latitude, 0.0001)
        assertEquals(121.556084, fallback.longitude, 0.0001)
    }

    @Test
    fun testTronClassRollcall_typeRecognition() {
        val radarRollcall = TronClassRollcall(
            id = 123456L,
            type = "radar",
            isRadar = true,
            courseName = "大学物理"
        )
        assertEquals("radar", radarRollcall.getNormalizedType())
        assertEquals(123456L, radarRollcall.getEffectiveId())

        val numberRollcall = TronClassRollcall(
            id = 234567L,
            type = "number",
            isNumber = true,
            courseName = "工程制图"
        )
        assertEquals("number", numberRollcall.getNormalizedType())

        val qrRollcall = TronClassRollcall(
            id = 345678L,
            type = "qr_code",
            courseName = "电路原理"
        )
        assertEquals("qr_code", qrRollcall.getNormalizedType())
    }

    @Test
    fun testSelectActionableRollcalls_filtersExpiredInvalidAndHandled() {
        val activeUnhandled = TronClassRollcall(id = 101L, type = "number", isExpired = false)
        val expired = TronClassRollcall(id = 102L, type = "number", isExpired = true)
        val alreadyHandled = TronClassRollcall(id = 103L, type = "radar", isExpired = false)
        val invalidId = TronClassRollcall(id = 0L, rollcallId = -1L, type = "qr_code", isExpired = false)

        val result = RollcallMonitorService.selectActionableRollcalls(
            list = listOf(activeUnhandled, expired, alreadyHandled, invalidId),
            isHandled = { it == 103L }
        )

        assertEquals(listOf(101L), result.map { it.getEffectiveId() })
    }

    @Test
    fun testRollcallPopupRouter_decideRoutesSafely() {
        assertEquals(
            RollcallPopupRoute.NOTIFICATION_ONLY,
            RollcallPopupRouter.decide(
                popupEnabled = false,
                hasResumedActivity = true,
                isScreenInteractive = true,
                isKeyguardLocked = false,
                canDrawOverlays = true
            )
        )
        assertEquals(
            RollcallPopupRoute.NOTIFICATION_ONLY,
            RollcallPopupRouter.decide(
                popupEnabled = true,
                hasResumedActivity = true,
                isScreenInteractive = false,
                isKeyguardLocked = false,
                canDrawOverlays = true
            )
        )
        assertEquals(
            RollcallPopupRoute.NOTIFICATION_ONLY,
            RollcallPopupRouter.decide(
                popupEnabled = true,
                hasResumedActivity = true,
                isScreenInteractive = true,
                isKeyguardLocked = true,
                canDrawOverlays = true
            )
        )
        assertEquals(
            RollcallPopupRoute.FOREGROUND_ACTIVITY,
            RollcallPopupRouter.decide(
                popupEnabled = true,
                hasResumedActivity = true,
                isScreenInteractive = true,
                isKeyguardLocked = false,
                canDrawOverlays = false
            )
        )
        assertEquals(
            RollcallPopupRoute.BACKGROUND_OVERLAY,
            RollcallPopupRouter.decide(
                popupEnabled = true,
                hasResumedActivity = false,
                isScreenInteractive = true,
                isKeyguardLocked = false,
                canDrawOverlays = true
            )
        )
        assertEquals(
            RollcallPopupRoute.NOTIFICATION_ONLY,
            RollcallPopupRouter.decide(
                popupEnabled = true,
                hasResumedActivity = false,
                isScreenInteractive = true,
                isKeyguardLocked = false,
                canDrawOverlays = false
            )
        )
    }

    @Test
    fun testChannelAvailability_respectsGroupBlockedAndImportance() {
        assertFalse(NotificationDiagnosticHelper.evaluateChannelAvailability(null, 1, false))
        assertFalse(NotificationDiagnosticHelper.evaluateChannelAvailability(0, 1, false))
        assertFalse(NotificationDiagnosticHelper.evaluateChannelAvailability(3, 4, false))
        assertFalse(NotificationDiagnosticHelper.evaluateChannelAvailability(4, 4, true))
        assertTrue(NotificationDiagnosticHelper.evaluateChannelAvailability(4, 4, false))
    }

    @Test
    fun testRollcallPermissionSnapshot_needsGuidanceAndMissingItems() {
        val ready = NotificationDiagnosticHelper.RollcallPermissionSnapshot(
            notificationsEnabled = true,
            alertChannelHigh = true,
            overlayGranted = true,
            ignoringBatteryOptimizations = true,
            fullScreenIntentGranted = true
        )
        assertFalse(ready.needsGuidance)
        assertTrue(ready.missingItems().isEmpty())

        val partial = NotificationDiagnosticHelper.RollcallPermissionSnapshot(
            notificationsEnabled = true,
            alertChannelHigh = false,
            overlayGranted = false,
            ignoringBatteryOptimizations = true,
            fullScreenIntentGranted = true
        )
        assertTrue(partial.needsGuidance)
        assertEquals(2, partial.missingItems().size)

        val missingFullScreen = NotificationDiagnosticHelper.RollcallPermissionSnapshot(
            notificationsEnabled = true,
            alertChannelHigh = true,
            overlayGranted = true,
            ignoringBatteryOptimizations = true,
            fullScreenIntentGranted = false
        )
        assertTrue(missingFullScreen.needsGuidance)
        assertEquals(1, missingFullScreen.missingItems().size)
    }

    @Test
    fun testShouldWakeScreenBeforeNotify_neverPreWakesForFullScreenIntent() {
        assertFalse(RollcallNotificationHelper.shouldWakeScreenBeforeNotify(silent = false, hasFullScreenIntent = true))
        assertFalse(RollcallNotificationHelper.shouldWakeScreenBeforeNotify(silent = true, hasFullScreenIntent = true))
        assertFalse(RollcallNotificationHelper.shouldWakeScreenBeforeNotify(silent = true, hasFullScreenIntent = false))
        assertTrue(RollcallNotificationHelper.shouldWakeScreenBeforeNotify(silent = false, hasFullScreenIntent = false))
    }

    @Test
    fun testSelectChaoXingCoursesForSession_matchesExactAndContainsAndCapsLimit() {
        val exact = ChaoXingCourse(courseId = "1", classId = "10", courseName = "高等数学A（1）")
        val partial1 = ChaoXingCourse(courseId = "2", classId = "20", courseName = "高等数学A提高班")
        val partial2 = ChaoXingCourse(courseId = "3", classId = "30", courseName = "高等数学A习题课")
        val duplicate = ChaoXingCourse(courseId = "1", classId = "10", courseName = "高等数学A（1）")
        val unrelated = ChaoXingCourse(courseId = "4", classId = "40", courseName = "大学物理")

        val matched = RollcallMonitorService.selectChaoXingCoursesForSession(
            courses = listOf(partial1, unrelated, exact, duplicate, partial2),
            sessionCourseName = "高等数学 A"
        )
        assertEquals(2, matched.size)
        assertEquals("1", matched[0].courseId)
        assertEquals("2", matched[1].courseId)

        val none = RollcallMonitorService.selectChaoXingCoursesForSession(
            courses = listOf(exact, partial1),
            sessionCourseName = "信号与系统"
        )
        assertTrue(none.isEmpty())
    }

    @Test
    fun testSelectActionableChaoXingSigns_filtersSignedExpiredAndHandled() {
        val course = ChaoXingCourse(courseId = "1", classId = "10", courseName = "数据结构")
        val activeUnhandled = ChaoXingActiveItem.createSynthetic(activeId = 1001L, signTypeName = "手势签到")
        val alreadySigned = ChaoXingActiveItem.createSynthetic(activeId = 1002L, signTypeName = "普通签到").apply {
            signDetail = ChaoXingSignDetail(activeId = 1002L, isAlreadySigned = true)
        }
        val expiredDetail = ChaoXingActiveItem.createSynthetic(activeId = 1003L, signTypeName = "位置签到").apply {
            signDetail = ChaoXingSignDetail(activeId = 1003L, isExpired = true)
        }
        val inactiveStatus = ChaoXingActiveItem.createSynthetic(activeId = 1004L, signTypeName = "普通签到", status = 2)
        val nonSignActivity = ChaoXingActiveItem.createSynthetic(activeId = 1005L, signTypeName = "投票", activeType = 4)
        val alreadyHandled = ChaoXingActiveItem.createSynthetic(activeId = 1006L, signTypeName = "口令签到")

        val result = RollcallMonitorService.selectActionableChaoXingSigns(
            signs = listOf(
                course to activeUnhandled,
                course to alreadySigned,
                course to expiredDetail,
                course to inactiveStatus,
                course to nonSignActivity,
                course to alreadyHandled
            ),
            isHandled = { it == 1006L }
        )

        assertEquals(listOf(1001L), result.map { it.second.id })
    }
}
