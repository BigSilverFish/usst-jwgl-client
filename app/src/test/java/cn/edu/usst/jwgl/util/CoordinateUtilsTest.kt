package cn.edu.usst.jwgl.util

import org.junit.Assert.*
import org.junit.Test

class CoordinateUtilsTest {

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
    }

    @Test
    fun testFindMatchingBuilding_MainCampusBuildings() {
        val b1 = CoordinateUtils.findMatchingBuilding("一教101")
        assertNotNull(b1)
        assertEquals("第一教学楼", b1?.name)

        val b2 = CoordinateUtils.findMatchingBuilding("二教202")
        assertNotNull(b2)
        assertEquals("第二教学楼", b2?.name)
    }
}
