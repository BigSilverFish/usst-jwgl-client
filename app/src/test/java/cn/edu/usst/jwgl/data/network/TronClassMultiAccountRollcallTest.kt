package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.data.model.AccountProfile
import cn.edu.usst.jwgl.data.model.MultiAccountRollcallResult
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TronClassMultiAccountRollcallTest {

    private val gson = Gson()

    @Test
    fun testMultiAccountRollcallResultSerialization() {
        val r1 = MultiAccountRollcallResult(
            studentId = "20210001",
            studentName = "张三",
            success = true,
            message = "口令签到成功！状态: 已签到",
            statusName = "已签到"
        )

        val json = gson.toJson(r1)
        val deserialized = gson.fromJson(json, MultiAccountRollcallResult::class.java)

        assertEquals("20210001", deserialized.studentId)
        assertEquals("张三", deserialized.studentName)
        assertTrue(deserialized.success)
        assertEquals("口令签到成功！状态: 已签到", deserialized.message)
        assertEquals("已签到", deserialized.statusName)
    }

    @Test
    fun testDeviceIdSeparationLogic() {
        // 模拟 TronClassPreferences 中 getDeviceId(studentId) 的逻辑
        val map = mutableMapOf<String, String>()

        fun getDeviceId(studentId: String): String {
            val key = "${studentId}_tc_device_id"
            return map.getOrPut(key) { UUID.randomUUID().toString() }
        }

        val devUser1 = getDeviceId("2100000001")
        val devUser2 = getDeviceId("2100000002")

        // 验证不同账号分配到的设备码互不相同（破除一机一签限制）
        assertNotEquals(devUser1, devUser2)

        // 验证同一账号重复获取返回固定值（符合稳定设备指纹特征）
        val devUser1Again = getDeviceId("2100000001")
        assertEquals(devUser1, devUser1Again)
    }

    @Test
    fun testMultiAccountSummaryFormatting() {
        val results = listOf(
            MultiAccountRollcallResult("2021001", "张三", true, "签到成功", "已出勤"),
            MultiAccountRollcallResult("2021002", "李四", true, "签到成功", "已出勤"),
            MultiAccountRollcallResult("2021003", "王五", false, "未选此门课程", "")
        )

        val successCount = results.count { it.success }
        val totalCount = results.size

        assertEquals(2, successCount)
        assertEquals(3, totalCount)

        val title = if (successCount == totalCount) {
            "🎉 全员签到成功 ($successCount/$totalCount)"
        } else {
            "📋 多账号签到结果 ($successCount/$totalCount 成功)"
        }

        assertEquals("📋 多账号签到结果 (2/3 成功)", title)
    }

    @Test
    fun orderAccountsPrimaryFirst_placesActiveAccountFirstRegardlessOfRecency() {
        val friendNewer = AccountProfile(studentId = "2435055999", studentName = "好友", lastActiveTime = 9000L)
        val selfOlder = AccountProfile(studentId = "2435055230", studentName = "自己", lastActiveTime = 1000L)
        val thirdFriend = AccountProfile(studentId = "2435055888", studentName = "同学B", lastActiveTime = 5000L)

        val ordered = MultiAccountRollcallResult.orderAccountsPrimaryFirst(
            listOf(friendNewer, thirdFriend, selfOlder),
            currentStudentId = "2435055230"
        )

        assertEquals(listOf("2435055230", "2435055999", "2435055888"), ordered.map { it.studentId })
    }

    @Test
    fun isPrimaryAccountSuccess_doesNotTreatFriendSuccessAsSelfSuccess() {
        val friendSucceededSelfFailed = listOf(
            MultiAccountRollcallResult("2435055999", "好友", true, "已确认签到状态：已到"),
            MultiAccountRollcallResult("2435055230", "自己", false, "畅课授权失败: 登录失效")
        )
        assertFalse(MultiAccountRollcallResult.isPrimaryAccountSuccess(friendSucceededSelfFailed, "2435055230"))

        val selfSucceededFriendFailed = listOf(
            MultiAccountRollcallResult("2435055230", "自己", true, "已确认签到状态：已到"),
            MultiAccountRollcallResult("2435055999", "好友", false, "未选此课")
        )
        assertTrue(MultiAccountRollcallResult.isPrimaryAccountSuccess(selfSucceededFriendFailed, "2435055230"))

        val selfMissingFromResults = listOf(
            MultiAccountRollcallResult("2435055999", "好友", true, "已确认签到状态：已到")
        )
        assertFalse(MultiAccountRollcallResult.isPrimaryAccountSuccess(selfMissingFromResults, "2435055230"))
    }

    @Test
    fun isSessionOwnerMatching_validatesExpectedStudentId() {
        assertTrue(TronClassAuthApi.isSessionOwnerMatching("2435055230", """{"id":1,"user_no":"2435055230"}"""))
        assertTrue(TronClassAuthApi.isSessionOwnerMatching("2435055230", """{"user":{"user_no":"2435055230"}}"""))
        assertFalse(TronClassAuthApi.isSessionOwnerMatching("2435055230", """{"id":2,"user_no":"2435055999"}"""))
        assertFalse(TronClassAuthApi.isSessionOwnerMatching("2435055230", "not-json"))
    }

    @Test
    fun cleanAuthClient_neverInjectsExistingSessionHeaderIntoLoginExchange() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"session_id":"new_sid"}"""))
            server.start()

            val authClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("X-Requested-With", "XMLHttpRequest")
                            .build()
                    )
                }
                .build()

            val req = Request.Builder()
                .url(server.url("/api/login?login=access_token"))
                .post("""{"access_token":"abc"}""".toRequestBody())
                .build()

            authClient.newCall(req).execute().use { assertEquals(200, it.code) }
            val recorded = server.takeRequest()
            assertNull(recorded.getHeader("x-session-id"))
            assertNull(recorded.getHeader("Cookie"))
        }
    }
}
