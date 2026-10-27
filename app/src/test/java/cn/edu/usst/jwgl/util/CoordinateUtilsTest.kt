package cn.edu.usst.jwgl.util

import org.junit.Assert.*
import org.junit.Test

class CoordinateUtilsTest {

    @Test
    fun testBuildingCount() {
        // 15 本部北校区 + 4 本部南校区 + 8 军工路1100号校区 + 2 外校区 = 29
        assertEquals(29, CoordinateUtils.USST_BUILDINGS.size)
    }

    @Test
    fun testFindMatchingBuilding_Jungong1100Buildings() {
        val qx = CoordinateUtils.findMatchingBuilding("1100号-勤学楼302")
        assertNotNull(qx)
        assertEquals("勤学楼", qx?.name)
        assertEquals(31.30242, qx?.latitude ?: 0.0, 0.0001)

        val bx = CoordinateUtils.findMatchingBuilding("博学楼105")
        assertNotNull(bx)
        assertEquals("博学楼", bx?.name)
        assertEquals(31.30244, bx?.latitude ?: 0.0, 0.0001)

        val lx = CoordinateUtils.findMatchingBuilding("乐学楼201")
        assertNotNull(lx)
        assertEquals("乐学楼", lx?.name)
        assertEquals(31.30221, lx?.latitude ?: 0.0, 0.0001)

        val gw = CoordinateUtils.findMatchingBuilding("格物楼401")
        assertNotNull(gw)
        assertEquals("格物楼", gw?.name)
        assertEquals(31.30272, gw?.latitude ?: 0.0, 0.0001)

        val dl = CoordinateUtils.findMatchingBuilding("1100号图书馆报告厅")
        assertNotNull(dl)
        assertEquals("达理楼", dl?.name)
        assertEquals(31.30219, dl?.latitude ?: 0.0, 0.0001)

        val hd = CoordinateUtils.findMatchingBuilding("1100学生活动中心二楼")
        assertNotNull(hd)
        assertEquals("1100学生活动中心", hd?.name)

        val jt = CoordinateUtils.findMatchingBuilding("1100申阶梯1")
        assertNotNull(jt)
        assertEquals("1100申阶梯", jt?.name)

        val ty = CoordinateUtils.findMatchingBuilding("1100体育活动中心乒乓房")
        assertNotNull(ty)
        assertEquals("1100体育活动中心", ty?.name)
    }

    @Test
    fun testFindMatchingBuilding_CoverageRouting_Building1() {
        // Explicit directions
        assertEquals("一教东", CoordinateUtils.findMatchingBuilding("一教东201")?.name)
        assertEquals("一教西", CoordinateUtils.findMatchingBuilding("一教西102")?.name)
        assertEquals("一教中", CoordinateUtils.findMatchingBuilding("一教中101")?.name)

        // Room number based routing
        // Offset <= 19 -> 一教东
        assertEquals("一教东", CoordinateUtils.findMatchingBuilding("一教101")?.name)
        assertEquals("一教东", CoordinateUtils.findMatchingBuilding("一教 204")?.name)
        assertEquals("一教东", CoordinateUtils.findMatchingBuilding("一教116")?.name)

        // Offset 20..35 -> 一教中
        assertEquals("一教中", CoordinateUtils.findMatchingBuilding("一教221")?.name)
        assertEquals("一教中", CoordinateUtils.findMatchingBuilding("第一教学楼330")?.name)

        // Offset >= 36 -> 一教西
        assertEquals("一教西", CoordinateUtils.findMatchingBuilding("一教340")?.name)
        assertEquals("一教西", CoordinateUtils.findMatchingBuilding("第一教学楼150")?.name)

        // Default when no room number or direction
        assertEquals("一教中", CoordinateUtils.findMatchingBuilding("第一教学楼")?.name)
        assertEquals("一教中", CoordinateUtils.findMatchingBuilding("一教")?.name)
    }

    @Test
    fun testFindMatchingBuilding_CoverageRouting_Building3And4() {
        // Building 3
        assertEquals("三教西", CoordinateUtils.findMatchingBuilding("三教西105")?.name)
        assertEquals("三教东", CoordinateUtils.findMatchingBuilding("三教东201")?.name)
        assertEquals("三教西", CoordinateUtils.findMatchingBuilding("三教106")?.name) // offset 6 <= 10
        assertEquals("三教东", CoordinateUtils.findMatchingBuilding("三教217")?.name) // offset 17 >= 11
        assertEquals("三教东", CoordinateUtils.findMatchingBuilding("三教")?.name)

        // Building 4
        assertEquals("四教西", CoordinateUtils.findMatchingBuilding("四教西201")?.name)
        assertEquals("四教东", CoordinateUtils.findMatchingBuilding("四教东302")?.name)
        assertEquals("四教西", CoordinateUtils.findMatchingBuilding("四教105")?.name) // offset 5 <= 12
        assertEquals("四教东", CoordinateUtils.findMatchingBuilding("四教221")?.name) // offset 21 >= 13
        assertEquals("四教东", CoordinateUtils.findMatchingBuilding("四教")?.name)
    }

    @Test
    fun testFindMatchingBuilding_IndependentComprehensiveBuildings() {
        // Zone A
        assertEquals("综合楼A区", CoordinateUtils.findMatchingBuilding("综合楼A区201")?.name)
        assertEquals("综合楼A区", CoordinateUtils.findMatchingBuilding("综A101")?.name)
        assertEquals("综合楼A区", CoordinateUtils.findMatchingBuilding("综合A204")?.name)

        // Zone B
        assertEquals("综合楼B区", CoordinateUtils.findMatchingBuilding("综合楼B区105")?.name)
        assertEquals("综合楼B区", CoordinateUtils.findMatchingBuilding("综B203")?.name)

        // Zone C
        assertEquals("综合楼C区", CoordinateUtils.findMatchingBuilding("综合楼C区317")?.name)
        assertEquals("综合楼C区", CoordinateUtils.findMatchingBuilding("综C110")?.name)

        // Zone D
        assertEquals("综合楼D区", CoordinateUtils.findMatchingBuilding("综合楼D区402")?.name)
        assertEquals("综合楼D区", CoordinateUtils.findMatchingBuilding("综D301")?.name)

        // Fallback for general 综合楼 without zone
        assertEquals("综合楼A区", CoordinateUtils.findMatchingBuilding("教学综合楼")?.name)
    }

    @Test
    fun testFindMatchingBuilding_OtherPoints() {
        val zy = CoordinateUtils.findMatchingBuilding("卓越楼101")
        assertEquals("卓越楼", zy?.name)
        assertEquals("本部南校区", zy?.campus)

        val yj = CoordinateUtils.findMatchingBuilding("一教中201")
        assertEquals("一教中", yj?.name)
        assertEquals("本部北校区", yj?.campus)

        val sj = CoordinateUtils.findMatchingBuilding("四教西101")
        assertEquals("四教西", sj?.name)
        assertEquals("本部南校区", sj?.campus)

        assertEquals("公共实验楼", CoordinateUtils.findMatchingBuilding("公共实验楼302")?.name)
        assertEquals("计算中心", CoordinateUtils.findMatchingBuilding("计算中心机房")?.name)
        assertEquals("光仪所", CoordinateUtils.findMatchingBuilding("光仪所201")?.name)
        assertEquals("国合楼", CoordinateUtils.findMatchingBuilding("国合楼报告厅")?.name)
        assertEquals("580体育活动中心", CoordinateUtils.findMatchingBuilding("580体育活动中心")?.name)
        assertEquals("580体育活动中心", CoordinateUtils.findMatchingBuilding("体育活动中心")?.name)
        assertEquals("新体育健身中心", CoordinateUtils.findMatchingBuilding("新体育健身中心")?.name)
        assertEquals("北校田径场", CoordinateUtils.findMatchingBuilding("北校田径场")?.name)
    }

    @Test
    fun testFindMatchingBuilding_NewCampuses() {
        val fx = CoordinateUtils.findMatchingBuilding("复兴路教学楼301")
        assertNotNull(fx)
        assertEquals("复兴路教学楼", fx?.name)
        assertEquals("复兴路校区", fx?.campus)
        assertEquals(31.211925, fx?.latitude ?: 0.0, 0.000001)
        assertEquals(121.460448, fx?.longitude ?: 0.0, 0.000001)

        val yk = CoordinateUtils.findMatchingBuilding("营口路教学楼102")
        assertNotNull(yk)
        assertEquals("营口路教学楼", yk?.name)
        assertEquals("营口路校区", yk?.campus)
        assertEquals(31.289567, yk?.latitude ?: 0.0, 0.000001)
        assertEquals(121.534146, yk?.longitude ?: 0.0, 0.000001)
    }
}
