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

    @Test
    fun testTryAddObservationRejectsTooCloseAndOffCampusPoints() {
        val obs = mutableListOf<TrilaterationSolver.AnchorObservation>()
        val first = TrilaterationSolver.AnchorObservation(31.291731, 121.556084, 150.0, "一教东")
        assertTrue(TrilaterationSolver.tryAddObservation(obs, first))

        // 相距约 35m (< 80m) 应拒绝
        val tooClose = TrilaterationSolver.AnchorObservation(31.291900, 121.556300, 120.0, "近邻点")
        assertFalse(TrilaterationSolver.tryAddObservation(obs, tooClose))

        // 距离 > 3000m（校外远程）应拒绝
        val offCampus = TrilaterationSolver.AnchorObservation(31.290008, 121.552855, 4500.0, "校外点")
        assertFalse(TrilaterationSolver.tryAddObservation(obs, offCampus))

        // 相距 ~360m 且在 3000m 内，应接收
        val second = TrilaterationSolver.AnchorObservation(31.290008, 121.552855, 220.0, "综合楼A")
        assertTrue(TrilaterationSolver.tryAddObservation(obs, second))
        assertEquals(2, obs.size)
    }

    @Test
    fun testReuseTwoObservationsNeedsOnlyOneBestAnchor() {
        val trueLat = 31.293200
        val trueLng = 121.554200
        val obs = mutableListOf(
            TrilaterationSolver.AnchorObservation(
                latitude = 31.291731,
                longitude = 121.556084,
                distanceMeters = CoordinateUtils.calculateDistance(31.291731, 121.556084, trueLat, trueLng),
                name = "本机定位"
            ),
            TrilaterationSolver.AnchorObservation(
                latitude = 31.290008,
                longitude = 121.552855,
                distanceMeters = CoordinateUtils.calculateDistance(31.290008, 121.552855, trueLat, trueLng),
                name = "综合楼A"
            )
        )

        val candidates = TrilaterationSolver.getCandidateAnchors("一教201", "软件工程")
        val next = TrilaterationSolver.selectNextBestAnchor(obs, candidates)
        assertNotNull("已有2个有效点时应只挑选出1个最优补测锚点", next)

        val added = TrilaterationSolver.tryAddObservation(
            obs,
            TrilaterationSolver.AnchorObservation(
                latitude = next!!.latitude,
                longitude = next.longitude,
                distanceMeters = CoordinateUtils.calculateDistance(next.latitude, next.longitude, trueLat, trueLng),
                name = next.name
            )
        )
        assertTrue(added)
        assertEquals(3, obs.size)

        val solved = TrilaterationSolver.solve(obs)
        assertTrue(TrilaterationSolver.isAcceptableSolution(solved, obs))
        val err = CoordinateUtils.calculateDistance(solved!!.latitude, solved.longitude, trueLat, trueLng)
        assertTrue("仅补测1点后解算误差应小于 2m，实际: ${err}m", err < 2.0)
    }

    @Test
    fun testSelectNextBestAnchorSkipsCollinearAndTooCloseCandidates() {
        val b1 = CoordinateUtils.CampusBuilding("b1", "点1", "测试", 31.2900, 121.5500, emptyList())
        val b2 = CoordinateUtils.CampusBuilding("b2", "点2", "测试", 31.2900, 121.5540, emptyList())
        // 共线点（与前两点同纬度）
        val collinear = CoordinateUtils.CampusBuilding("col", "共线", "测试", 31.2900, 121.5580, emptyList())
        // 过近点（距 b1 仅 ~20m）
        val tooClose = CoordinateUtils.CampusBuilding("near", "太近", "测试", 31.2901, 121.5501, emptyList())
        // 良好三角点（北偏 ~330m）
        val good = CoordinateUtils.CampusBuilding("good", "三角点", "测试", 31.2930, 121.5520, emptyList())

        val obs = listOf(
            TrilaterationSolver.AnchorObservation(b1.latitude, b1.longitude, 100.0, b1.name),
            TrilaterationSolver.AnchorObservation(b2.latitude, b2.longitude, 200.0, b2.name)
        )

        val selected = TrilaterationSolver.selectNextBestAnchor(obs, listOf(collinear, tooClose, good))
        assertEquals("good", selected?.id)
    }

    @Test
    fun testIsAcceptableSolutionRejectsHighRmse() {
        val obs = listOf(
            TrilaterationSolver.AnchorObservation(31.291731, 121.556084, 100.0, "A")
        )
        val bad = TrilaterationSolver.SolveResult(31.2918, 121.5561, residualRmse = 75.0)
        val ok = TrilaterationSolver.SolveResult(31.2918, 121.5561, residualRmse = 12.0)

        assertFalse(TrilaterationSolver.isAcceptableSolution(bad, obs))
        assertTrue(TrilaterationSolver.isAcceptableSolution(ok, obs))
    }
}
