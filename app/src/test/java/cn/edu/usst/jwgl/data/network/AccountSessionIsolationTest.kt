package cn.edu.usst.jwgl.data.network

import android.content.ContextWrapper
import android.content.SharedPreferences
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.model.ChaoXingHomeworkDetail
import cn.edu.usst.jwgl.data.model.TronClassBulletin
import okhttp3.Cookie
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class AccountSessionIsolationTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val map = ConcurrentHashMap<String, Any>()
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = key != null && map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clearFlag = false
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { if (key != null) pending[key] = values?.toSet() }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { if (key != null) pending[key] = null }
            override fun clear(): SharedPreferences.Editor = apply { clearFlag = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearFlag) map.clear()
                for ((k, v) in pending) {
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeAppContext : ContextWrapper(null) {
        private val prefsMap = ConcurrentHashMap<String, SharedPreferences>()
        override fun getApplicationContext(): android.content.Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefsMap.getOrPut(name ?: "default") { FakeSharedPreferences() }
        }
    }

    @After
    fun tearDown() {
        JwglClient.clearSession()
    }

    @Test
    fun jwglClient_getCookiesForStudent_rejectsCrossAccountCastgcReuse() {
        val cookie = Cookie.Builder()
            .name("CASTGC")
            .value("TGT-account-a")
            .domain("ids6.usst.edu.cn")
            .path("/")
            .build()

        JwglClient.clearSession()
        JwglClient.addCookies(listOf(cookie), ownerStudentId = "2435055230")

        assertEquals("2435055230", JwglClient.getSessionOwnerStudentId())
        assertEquals(1, JwglClient.getCookiesForStudent("2435055230").size)
        assertTrue(JwglClient.getCookiesForStudent("2435055999").isEmpty())
        assertTrue(JwglClient.getCookiesForStudent("").isEmpty())

        JwglClient.clearSession()
        assertNull(JwglClient.getSessionOwnerStudentId())
        assertTrue(JwglClient.getCookiesForStudent("2435055230").isEmpty())
    }

    @Test
    fun tronClassDataCacheManager_isolatesHomeBulletinsReadsAndChaoXingDetailsByStudentId() {
        val fakeContext = FakeAppContext()
        val cacheMgr = TronClassDataCacheManager(fakeContext)

        cacheMgr.saveHomeBulletins(
            listOf(TronClassBulletin(id = 1L, title = "A同学公告")),
            studentId = "2435055230"
        )
        cacheMgr.saveHomeBulletins(
            listOf(TronClassBulletin(id = 2L, title = "B同学公告")),
            studentId = "2435055999"
        )

        assertEquals(listOf("A同学公告"), cacheMgr.getCachedHomeBulletins("2435055230").map { it.title })
        assertEquals(listOf("B同学公告"), cacheMgr.getCachedHomeBulletins("2435055999").map { it.title })

        cacheMgr.saveCourseReads(100L, mapOf(10L to "full"), studentId = "2435055230")
        assertEquals("full", cacheMgr.getCachedCourseReads(100L, "2435055230")[10L])
        assertTrue(cacheMgr.getCachedCourseReads(100L, "2435055999").isEmpty())

        val detailA = ChaoXingHomeworkDetail(
            workId = "999",
            title = "实验一",
            statusText = "已提交",
            score = "95",
            deadline = "2026-12-01"
        )
        cacheMgr.saveChaoXingHomeworkDetail("999", detailA, studentId = "2435055230")
        assertEquals("已提交", cacheMgr.getCachedChaoXingHomeworkDetail("999", "2435055230")?.statusText)
        assertNull(cacheMgr.getCachedChaoXingHomeworkDetail("999", "2435055999"))
    }
}
