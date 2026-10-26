package cn.edu.usst.jwgl.util

import org.junit.Assert.*
import org.junit.Test

class CoordinateUtilsTest {

    @Test
    fun testBuildingCount() {
        // 18 from 516 campus + 8 from 1100 campus = 26
        assertEquals(26, CoordinateUtils.USST_BUILDINGS.size)
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
    fun testFindMatchingBuilding_MainCampusBuildings() {
        val b1m = CoordinateUtils.findMatchingBuilding("一教101")
        assertNotNull(b1m)
        assertEquals("一教中", b1m?.name)

        val b1e = CoordinateUtils.findMatchingBuilding("一教东201")
        assertNotNull(b1e)
        assertEquals("一教东", b1e?.name)

        val b1w = CoordinateUtils.findMatchingBuilding("一教西102")
        assertNotNull(b1w)
        assertEquals("一教西", b1w?.name)

        val b3e = CoordinateUtils.findMatchingBuilding("三教201")
        assertNotNull(b3e)
        assertEquals("三教东", b3e?.name)

        val b3w = CoordinateUtils.findMatchingBuilding("三教西105")
        assertNotNull(b3w)
        assertEquals("三教西", b3w?.name)

        val b4e = CoordinateUtils.findMatchingBuilding("四教302")
        assertNotNull(b4e)
        assertEquals("四教东", b4e?.name)

        val b4w = CoordinateUtils.findMatchingBuilding("四教西201")
        assertNotNull(b4w)
        assertEquals("四教西", b4w?.name)

        val zha = CoordinateUtils.findMatchingBuilding("综合楼A区201")
        assertNotNull(zha)
        assertEquals("综合楼A区", zha?.name)

        val zhd = CoordinateUtils.findMatchingBuilding("教学综合楼301")
        assertNotNull(zhd)
        assertEquals("综合楼D区", zhd?.name)

        val zy = CoordinateUtils.findMatchingBuilding("卓越楼101")
        assertNotNull(zy)
        assertEquals("卓越楼", zy?.name)

        val ggsy = CoordinateUtils.findMatchingBuilding("公共实验楼302")
        assertNotNull(ggsy)
        assertEquals("公共实验楼", ggsy?.name)

        val jsz = CoordinateUtils.findMatchingBuilding("计算中心机房")
        assertNotNull(jsz)
        assertEquals("计算中心", jsz?.name)

        val gys = CoordinateUtils.findMatchingBuilding("光仪所201")
        assertNotNull(gys)
        assertEquals("光仪所", gys?.name)

        val gh = CoordinateUtils.findMatchingBuilding("国合楼报告厅")
        assertNotNull(gh)
        assertEquals("国合楼", gh?.name)

        val ty = CoordinateUtils.findMatchingBuilding("体育活动中心")
        assertNotNull(ty)
        assertEquals("体育活动中心", ty?.name)

        val xty = CoordinateUtils.findMatchingBuilding("新体育健身中心")
        assertNotNull(xty)
        assertEquals("新体育健身中心", xty?.name)
    }
}
