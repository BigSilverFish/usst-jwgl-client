package cn.edu.usst.jwgl.data.network

import android.content.ContextWrapper
import android.content.SharedPreferences
import cn.edu.usst.jwgl.data.local.RollcallSubmissionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class RollcallVerificationTest {

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

    @Test
    fun parseStudentRollcallsVerification_avoidsFalsePositivesFromTopStatusOrSubsetList() {
        val topLevelOnly = """{"status":"on_call_fine","student_rollcalls":[]}"""
        assertEquals(
            RollcallVerificationState.UNAVAILABLE,
            parseStudentRollcallsVerification(topLevelOnly, "2435055230")
        )

        val subsetAllFineWithoutSelf = """
            {
              "status": "on_call_fine",
              "student_rollcalls": [
                {"user_no": "2435055999", "status": "on_call_fine"},
                {"user_no": "2435055888", "status": "on_call_fine"}
              ]
            }
        """.trimIndent()
        assertEquals(
            RollcallVerificationState.UNAVAILABLE,
            parseStudentRollcallsVerification(subsetAllFineWithoutSelf, "2435055230")
        )
    }

    @Test
    fun parseStudentRollcallsVerification_distinguishesConfirmedAndAbsentForTargetStudent() {
        val absentBody = """
            {
              "student_rollcalls": [
                {"user_no": "2435055999", "rollcall_status": "on_call_fine"},
                {"user_no": "2435055230", "rollcall_status": "absent"}
              ]
            }
        """.trimIndent()
        assertEquals(
            RollcallVerificationState.NOT_CONFIRMED,
            parseStudentRollcallsVerification(absentBody, "2435055230")
        )

        val confirmedBody = """
            {
              "student_rollcalls": [
                {"student_no": "2435055999", "student_status": "absent"},
                {"student_no": "2435055230", "student_status": "on_call_fine"}
              ]
            }
        """.trimIndent()
        assertEquals(
            RollcallVerificationState.CONFIRMED,
            parseStudentRollcallsVerification(confirmedBody, "2435055230")
        )
    }

    @Test
    fun invalidateConfirmed_clearsStaleConfirmedWithoutTouchingUnknownOrRejected() {
        val store = RollcallSubmissionStore(FakeAppContext())
        assertTrue(store.claim("2435055230", 1001L))
        assertTrue(store.record("2435055230", 1001L, "confirmed"))
        assertFalse(store.claim("2435055230", 1001L))

        assertTrue(store.invalidateConfirmed("2435055230", 1001L))
        assertNull(store.state("2435055230", 1001L))
        assertTrue(store.claim("2435055230", 1001L))

        // Unknown remains protected against double-send
        assertTrue(store.invalidateConfirmed("2435055230", 1001L))
        assertEquals("unknown", store.state("2435055230", 1001L))
    }

    @Test
    fun sessionVerifiedCache_tracksAndInvalidatesPerAccountAndSessionId() {
        TronClassAuthApi.invalidateVerifiedSession("2435055230")
        assertFalse(TronClassAuthApi.isSessionVerified("2435055230", "sid-1"))

        TronClassAuthApi.markSessionVerified("2435055230", "sid-1")
        assertTrue(TronClassAuthApi.isSessionVerified("2435055230", "sid-1"))
        assertFalse(TronClassAuthApi.isSessionVerified("2435055230", "sid-2"))
        assertFalse(TronClassAuthApi.isSessionVerified("2435055999", "sid-1"))

        TronClassAuthApi.invalidateVerifiedSession("2435055230")
        assertFalse(TronClassAuthApi.isSessionVerified("2435055230", "sid-1"))
    }
}
