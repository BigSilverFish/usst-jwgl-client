package cn.edu.usst.jwgl.util

import cn.edu.usst.jwgl.data.model.TronClassRollcall
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
        // 未指定教室或空字符串 -> 回退至本部北校区核心第一教学楼
        val emptyMatch = CoordinateUtils.findMatchingBuilding("")
        assertNull(emptyMatch)

        val fallback = emptyMatch ?: CoordinateUtils.USST_BUILDINGS.find { it.id == "b_516_1j_m" } ?: CoordinateUtils.USST_BUILDINGS.first()
        assertNotNull(fallback)
        assertEquals("一教中", fallback.name)
        assertEquals("本部北校区", fallback.campus)
        assertEquals(31.29464, fallback.latitude, 0.0001)
        assertEquals(121.55350, fallback.longitude, 0.0001)
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
}
