package cn.edu.usst.jwgl.data.network

import okhttp3.Cookie
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LappCollegeFilterTest {

    @Test
    fun `science college does not match management colleges`() {
        assertTrue(LappClient.matchesCollege("理学院", "理学院"))
        assertTrue(LappClient.matchesCollege(" 理学院 ", "理学院"))
        assertFalse(LappClient.matchesCollege("管理学院", "理学院"))
        assertFalse(LappClient.matchesCollege("管理学院专业学位教育中心", "理学院"))
    }

    @Test
    fun `management college matches both main and professional degree center`() {
        assertTrue(LappClient.matchesCollege("管理学院", "管理学院"))
        assertTrue(LappClient.matchesCollege("管理学院专业学位教育中心", "管理学院"))
        assertFalse(LappClient.matchesCollege("理学院", "管理学院"))
    }

    @Test
    fun `empty course college does not leak into specific college filter`() {
        assertFalse(LappClient.matchesCollege("", "理学院"))
        assertFalse(LappClient.matchesCollege("   ", "管理学院"))
    }

    @Test
    fun `all or blank selected college matches everything`() {
        assertTrue(LappClient.matchesCollege("理学院", ""))
        assertTrue(LappClient.matchesCollege("管理学院", "全部学院"))
        assertTrue(LappClient.matchesCollege("", "全部学院"))
    }

    @Test
    fun `syncCookiesFromJwgl filters non-lapp JSESSIONID cookies`() {
        LappClient.clearCookies()
        val jwglJsessionId = Cookie.Builder()
            .name("JSESSIONID")
            .value("mock_jwgl_session")
            .domain("jwgl.usst.edu.cn")
            .path("/")
            .build()
        val ssoCastgc = Cookie.Builder()
            .name("CASTGC")
            .value("mock_castgc")
            .domain("usst.edu.cn")
            .path("/")
            .build()

        LappClient.syncCookiesFromJwgl(listOf(jwglJsessionId, ssoCastgc))
        assertFalse(LappClient.hasLappSession())

        val lappJsessionId = Cookie.Builder()
            .name("JSESSIONID")
            .value("mock_lapp_session")
            .domain("lapp.usst.edu.cn")
            .path("/")
            .build()
        LappClient.syncCookiesFromJwgl(listOf(lappJsessionId))
        assertTrue(LappClient.hasLappSession())
        LappClient.clearCookies()
    }

    @Test
    fun `poisoned verified empty cache self heals from preset`() {
        assertTrue(LappClient.shouldSelfHealWithPreset(localSize = 0, locallyVerified = true, presetSize = 9024))
        assertTrue(LappClient.shouldSelfHealWithPreset(localSize = 100, locallyVerified = true, presetSize = 9024))
        assertTrue(LappClient.shouldSelfHealWithPreset(localSize = null, locallyVerified = false, presetSize = 9024))
        assertFalse(LappClient.shouldSelfHealWithPreset(localSize = 9024, locallyVerified = true, presetSize = 9024))
        assertFalse(LappClient.shouldSelfHealWithPreset(localSize = 0, locallyVerified = true, presetSize = 0))
    }

    @Test
    fun `upstream empty or severely truncated sync never overwrites valid cache`() {
        assertTrue(LappClient.shouldRejectUpstreamScheduleOverwrite(fetchedSize = 0, existingSize = 9024))
        assertTrue(LappClient.shouldRejectUpstreamScheduleOverwrite(fetchedSize = 0, existingSize = 0))
        assertTrue(LappClient.shouldRejectUpstreamScheduleOverwrite(fetchedSize = 1000, existingSize = 9024))
        assertFalse(LappClient.shouldRejectUpstreamScheduleOverwrite(fetchedSize = 9050, existingSize = 9024))
        assertFalse(LappClient.shouldRejectUpstreamScheduleOverwrite(fetchedSize = 300, existingSize = 0))
    }
}
