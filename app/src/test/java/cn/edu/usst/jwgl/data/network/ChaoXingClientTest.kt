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
}
