package cn.edu.usst.jwgl.data.local

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScheduleCacheCryptoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class MockTestContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getPackageName(): String = "cn.edu.usst.jwgl"
        override fun getCacheDir(): File = baseDir
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun testEncryptAndDecryptRoundTrip() {
        val mockContext = MockTestContext(tempFolder.root)
        val targetFile = File(tempFolder.root, "test_schedule.enc")

        val sampleScheduleJson = """
            [
                {
                    "kcmc": "高等数学A(1)",
                    "jsdd": "博学楼418",
                    "lsmc": "王彦花",
                    "zj": 5,
                    "jc": 1,
                    "jccd": 2,
                    "zc": "3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18"
                },
                {
                    "kcmc": "大学英语(2)",
                    "jsdd": "一教201",
                    "lsmc": "张老师",
                    "zj": 1,
                    "jc": 3,
                    "jccd": 2,
                    "zc": "1,2,3,4,5,6,7,8,9,10"
                }
            ]
        """.trimIndent()

        // 1. 加密写入
        ScheduleCacheCrypto.encryptAndWrite(mockContext, targetFile, sampleScheduleJson)
        assertTrue(targetFile.exists())
        assertTrue(targetFile.length() > 0)

        // 验证文件开头包含 "USST" 魔数
        val header = targetFile.inputStream().use {
            val buf = ByteArray(4)
            it.read(buf)
            buf
        }
        assertArrayEquals(byteArrayOf(0x55, 0x53, 0x53, 0x54), header)

        // 2. 解密读取
        val decrypted = ScheduleCacheCrypto.readAndDecrypt(mockContext, targetFile)
        assertNotNull(decrypted)
        assertEquals(sampleScheduleJson, decrypted)
    }

    @Test
    fun testLegacyPlaintextCompatibility() {
        val mockContext = MockTestContext(tempFolder.root)
        val legacyFile = File(tempFolder.root, "legacy_schedule.json")
        val plainText = "{\"time\":12345,\"items\":\"[]\"}"
        legacyFile.writeText(plainText, Charsets.UTF_8)

        // 读取未加密的旧版文件
        val readResult = ScheduleCacheCrypto.readAndDecrypt(mockContext, legacyFile)
        assertEquals(plainText, readResult)
    }
}
