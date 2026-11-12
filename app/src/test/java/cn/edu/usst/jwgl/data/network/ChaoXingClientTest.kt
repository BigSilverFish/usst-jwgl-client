package cn.edu.usst.jwgl.data.network

import com.google.gson.Gson
import cn.edu.usst.jwgl.data.model.ChaoXingClazzDataResponse
import cn.edu.usst.jwgl.data.model.ChaoXingLoginResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ChaoXingClientTest {

    private val gson = Gson()

    @Test
    fun testAesEncryptionAndDecryption() {
        val testRawString = "2335010123"
        val encryptedBase64 = ChaoXingClient.encryptAes(testRawString)

        assertNotNull(encryptedBase64)
        assertTrue(encryptedBase64.isNotBlank())

        // 使用相同算法解密还原并校验
        val keyBytes = "u2oh6Vu^HWe4_AES".toByteArray(StandardCharsets.UTF_8)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(keyBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

        val cipherBytes = Base64.getDecoder().decode(encryptedBase64)
        val decryptedBytes = cipher.doFinal(cipherBytes)
        val decryptedText = String(decryptedBytes, StandardCharsets.UTF_8)

        assertEquals(testRawString, decryptedText)
    }

    @Test
    fun testLoginResponseParsing() {
        val successJson = """
            {
                "status": true,
                "msg2": "登录成功",
                "url": "https://i.chaoxing.com"
            }
        """.trimIndent()

        val resp = gson.fromJson(successJson, ChaoXingLoginResponse::class.java)
        assertTrue(resp.status)
        assertEquals("登录成功", resp.msg)
        assertEquals("https://i.chaoxing.com", resp.url)

        val failJson = """
            {
                "status": false,
                "msg2": "用户名或密码错误"
            }
        """.trimIndent()

        val failResp = gson.fromJson(failJson, ChaoXingLoginResponse::class.java)
        assertEquals(false, failResp.status)
        assertEquals("用户名或密码错误", failResp.msg)
    }

    @Test
    fun testClazzDataResponseParsing() {
        val clazzJson = """
            {
                "result": 1,
                "msg": "获取成功",
                "channelList": [
                    {
                        "content": {
                            "id": "12345678",
                            "name": "大学物理",
                            "course": {
                                "data": [
                                    {
                                        "id": "c1001",
                                        "name": "大学物理A",
                                        "imageurl": "https://p.ananas.chaoxing.com/star3/123.jpg",
                                        "teacherfactor": "张教授"
                                    }
                                ]
                            }
                        }
                    }
                ]
            }
        """.trimIndent()

        val parsed = gson.fromJson(clazzJson, ChaoXingClazzDataResponse::class.java)
        assertEquals(1, parsed.result)
        assertNotNull(parsed.channelList)
        assertEquals(1, parsed.channelList?.size)
        val courseData = parsed.channelList?.first()?.content?.course?.data?.first()
        assertEquals("c1001", courseData?.id)
        assertEquals("大学物理A", courseData?.name)
        assertEquals("张教授", courseData?.teacherfactor)
    }

    @Test
    fun testParseDdlMillis() {
        val fullTime = "2026-11-05 23:59:00"
        val millis = ChaoXingClient.parseDdlMillis("截止时间：$fullTime")
        assertNotNull(millis)
        assertTrue(millis!! > 0)

        val prefixTime = "截止:2026-12-31 18:00"
        val m2 = ChaoXingClient.parseDdlMillis(prefixTime)
        assertNotNull(m2)
        assertTrue(m2!! > 0)
    }

    @Test
    fun testNoticeResponseParsing() {
        val noticeJson = """
            {
                "result": 1,
                "msg": "success",
                "notices": {
                    "lastGetId": "999888",
                    "list": [
                        {
                            "id": 1001,
                            "title": "关于期末大作业的补充通知",
                            "content": "<p>请各位同学于下周日前提交</p>",
                            "createrName": "李老师",
                            "completeTime": 1730000000000,
                            "sourceName": "高等数学"
                        }
                    ]
                }
            }
        """.trimIndent()

        val parsed = gson.fromJson(noticeJson, cn.edu.usst.jwgl.data.model.ChaoXingNoticeResponse::class.java)
        assertEquals(1, parsed.result)
        assertEquals(1, parsed.notices?.list?.size)
        val item = parsed.notices?.list?.first()
        assertEquals("关于期末大作业的补充通知", item?.title)
        assertEquals("高等数学", item?.sourceName)
        assertEquals(1730000000000L, item?.completeTime)
    }

    @Test
    fun testActiveSignParsing() {
        val signJson = """
            {
                "result": 1,
                "msg": "成功",
                "data": {
                    "activeList": [
                        {
                            "id": 88001122,
                            "activeType": 2,
                            "status": 1,
                            "nameOne": "签到",
                            "nameTwo": "[普通签到]",
                            "nameFour": "进行中",
                            "createtime": 1730102400000
                        },
                        {
                            "id": 88001123,
                            "activeType": 4,
                            "status": 1,
                            "nameOne": "抢答"
                        }
                    ]
                }
            }
        """.trimIndent()

        val parsed = gson.fromJson(signJson, cn.edu.usst.jwgl.data.model.ChaoXingActiveListResponse::class.java)
        assertEquals(1, parsed.result)
        val list = parsed.data?.activeList
        assertNotNull(list)
        assertEquals(2, list?.size)

        val activeSign = list?.find { it.isSignActivity() && it.isActive() }
        assertNotNull(activeSign)
        assertEquals(88001122L, activeSign?.id)
        assertEquals("[普通签到]", activeSign?.nameTwo)
    }

    @Test
    fun testUnifiedTodoSortOrder() {
        val t1 = cn.edu.usst.jwgl.data.model.UnifiedTodo(
            unifiedId = "cx_1",
            platform = cn.edu.usst.jwgl.data.model.LearningPlatform.CHAOXING,
            originalTodoId = "1",
            courseId = "c1",
            originalCourseId = "1",
            courseName = "高数",
            cleanCourseName = "高数",
            title = "作业1",
            cleanTitle = "作业1",
            deadlineMillis = 2000L
        )

        val t2 = cn.edu.usst.jwgl.data.model.UnifiedTodo(
            unifiedId = "tc_2",
            platform = cn.edu.usst.jwgl.data.model.LearningPlatform.TRONCLASS,
            originalTodoId = "2",
            courseId = "c2",
            originalCourseId = "2",
            courseName = "线代",
            cleanCourseName = "线代",
            title = "作业2",
            cleanTitle = "作业2",
            deadlineMillis = 1000L
        )

        val list = mutableListOf(t1, t2)
        list.sortBy { it.deadlineMillis ?: Long.MAX_VALUE }

        assertEquals("tc_2", list[0].unifiedId)
        assertEquals("cx_1", list[1].unifiedId)
    }

    @Test
    fun testChaoXingSsoJsRedirectParsing() {
        val htmlSample = """
            <!DOCTYPE html>
            <html>
            <head><title>跳转中...</title></head>
            <body></body>
            <script>
                window.location = "https:\/\/ids6.usst.edu.cn\/authserver\/login?service=https%3A%2F%2Ftyrzfw.chaoxing.com%2Fauth3%2Fusst%2Fcas%2Findex\u0026time=1730000000";
            </script>
            </html>
        """.trimIndent()

        val parsed = ChaoXingSsoClient.parseJsRedirect(htmlSample)
        assertNotNull(parsed)
        assertEquals(
            "https://ids6.usst.edu.cn/authserver/login?service=https%3A%2F%2Ftyrzfw.chaoxing.com%2Fauth3%2Fusst%2Fcas%2Findex&time=1730000000",
            parsed
        )
    }

    @Test
    fun testChaoXingSsoUnescapeJs() {
        val raw = """https:\/\/i.chaoxing.com\/base\u0026query=1"""
        val unescaped = ChaoXingSsoClient.unescapeJs(raw)
        assertEquals("https://i.chaoxing.com/base&query=1", unescaped)
    }

    @Test
    fun testResolveSignTypeDisplayName_qrNotMisclassifiedAsPhoto() {
        val templateHtml = """
            <html>
              <head><script>var photoMode = "photo"; function uploadPhoto() {}</script></head>
              <body>
                <input type="hidden" id="ifPhoto" value="0" />
                <div class="main">扫码签到</div>
              </body>
            </html>
        """.trimIndent()

        // otherId=2 时即使 HTML 脚本里存在 "photo" 也必须判定为二维码签到
        assertEquals(
            "二维码签到",
            ChaoXingClient.resolveSignTypeDisplayName(
                otherId = 2,
                initialHintName = "扫码签到",
                ifPhoto = false,
                preHtml = templateHtml,
                cleanBody = "扫码签到",
                finalUrl = "https://mobilelearn.chaoxing.com/widget/sign/pcStuSignController/preSign?activeId=1"
            )
        )

        // 即使 otherId 暂时未拿到，initialHintName 包含“扫码”且仅模板脚本含 photo，也必须判定为二维码签到
        assertEquals(
            "二维码签到",
            ChaoXingClient.resolveSignTypeDisplayName(
                otherId = -1,
                initialHintName = "扫码签到",
                ifPhoto = false,
                preHtml = templateHtml,
                cleanBody = "扫码签到",
                finalUrl = "https://mobilelearn.chaoxing.com/widget/sign/pcStuSignController/preSign?activeId=1"
            )
        )
    }

    @Test
    fun testResolveSignTypeDisplayName_allTypes() {
        // 真实拍照签到：ifPhoto=true 或隐藏表单 ifPhoto=1
        assertEquals(
            "拍照签到",
            ChaoXingClient.resolveSignTypeDisplayName(
                otherId = 0,
                ifPhoto = true,
                cleanBody = "请拍照签到"
            )
        )
        assertEquals(
            "拍照签到",
            ChaoXingClient.resolveSignTypeDisplayName(
                otherId = 0,
                ifPhoto = false,
                preHtml = """<input type="hidden" value="1" id="ifPhoto">""",
                cleanBody = "签到"
            )
        )

        // 手势、位置
        assertEquals("手势签到", ChaoXingClient.resolveSignTypeDisplayName(otherId = 3))
        assertEquals("位置签到", ChaoXingClient.resolveSignTypeDisplayName(otherId = 4))

        // otherId=5 默认是口令签到；仅当可见正文或 URL 明确是拍照时才为拍照签到
        assertEquals(
            "口令签到",
            ChaoXingClient.resolveSignTypeDisplayName(otherId = 5, cleanBody = "请输入签到码")
        )
        assertEquals(
            "拍照签到",
            ChaoXingClient.resolveSignTypeDisplayName(otherId = 5, cleanBody = "请拍照")
        )

        // 普通签到不被脚本里残留的 photo 字样误伤
        assertEquals(
            "普通签到",
            ChaoXingClient.resolveSignTypeDisplayName(
                otherId = 0,
                preHtml = """<script>const hasPhoto = false;</script><input id="ifPhoto" value="0">""",
                cleanBody = "点击签到"
            )
        )
    }

    @Test
    fun testChaoXingActiveItemResolvedSignTypeName() {
        val qrJson = """{"id": 1, "activeType": 2, "status": 1, "otherId": 2, "nameOne": "扫码签到", "nameTwo": "签到"}"""
        val qrItem = gson.fromJson(qrJson, cn.edu.usst.jwgl.data.model.ChaoXingActiveItem::class.java)
        assertEquals("二维码签到", qrItem.resolvedSignTypeName)

        val qrByIdJson = """{"id": 2, "activeType": 2, "status": 1, "otherId": 2, "nameTwo": "签到"}"""
        val qrByIdItem = gson.fromJson(qrByIdJson, cn.edu.usst.jwgl.data.model.ChaoXingActiveItem::class.java)
        assertEquals("二维码签到", qrByIdItem.resolvedSignTypeName)

        val codeJson = """{"id": 3, "activeType": 2, "status": 1, "otherId": 5, "nameTwo": "签到"}"""
        val codeItem = gson.fromJson(codeJson, cn.edu.usst.jwgl.data.model.ChaoXingActiveItem::class.java)
        assertEquals("口令签到", codeItem.resolvedSignTypeName)
    }
}
