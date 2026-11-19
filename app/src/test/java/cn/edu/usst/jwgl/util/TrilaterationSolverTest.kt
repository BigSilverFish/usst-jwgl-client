package cn.edu.usst.jwgl.util

import org.junit.Assert.*
import org.junit.Test

class TrilaterationSolverTest {

    @Test
    fun testTrilaterationClosedFormAndRefine() {
        // 假设教师真实位置在一教东附近的某个位置
        val trueTeacherLat = 31.291731
        val trueTeacherLng = 121.556084

        // 选取 3 个不共线锚点
        val anchors = TrilaterationSolver.getCampusAnchors()
        assertEquals(3, anchors.size)

        // 根据真实位置计算到 3 个锚点的理论球面大圆距离
        val obs = anchors.map { anchor ->
            val dist = CoordinateUtils.calculateDistance(
                anchor.latitude, anchor.longitude,
                trueTeacherLat, trueTeacherLng
            )
            TrilaterationSolver.AnchorObservation(
                latitude = anchor.latitude,
                longitude = anchor.longitude,
                distanceMeters = dist,
                name = anchor.name
            )
        }

        // 调用解算器反解教师位置
        val result = TrilaterationSolver.solve(obs)
        assertNotNull("解算结果不应为空", result)

        val solved = result!!
        val errorMeters = CoordinateUtils.calculateDistance(
            solved.latitude, solved.longitude,
            trueTeacherLat, trueTeacherLng
        )

        // 验证反解误差在 2 米以内（高精度收敛）
        assertTrue("反解误差应小于 2 米，实际误差: ${errorMeters}m", errorMeters < 2.0)
        assertTrue("残差均方根误差应小于 2 米，实际 RMSE: ${solved.residualRmse}m", solved.residualRmse < 2.0)
    }

    @Test
    fun testInsufficientObservationsReturnsNull() {
        val obs = listOf(
            TrilaterationSolver.AnchorObservation(31.2917, 121.5560, 50.0, "Point1"),
            TrilaterationSolver.AnchorObservation(31.2900, 121.5528, 80.0, "Point2")
        )
        assertNull(TrilaterationSolver.solve(obs))
    }

    @Test
    fun testCampusAnchorsForDifferentCampuses() {
        val mainCampus = TrilaterationSolver.getCampusAnchors("一教101", "高等数学")
        assertEquals(3, mainCampus.size)

        val campus1100 = TrilaterationSolver.getCampusAnchors("博学楼201", "大学英语")
        assertEquals(3, campus1100.size)
        assertTrue(campus1100.any { it.name.contains("博学") })

        val fuxing = TrilaterationSolver.getCampusAnchors("复兴路教学楼", "工程制图")
        assertEquals(3, fuxing.size)
        assertTrue(fuxing.any { it.name.contains("复兴") })
    }
}
