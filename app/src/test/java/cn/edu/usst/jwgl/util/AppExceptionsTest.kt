package cn.edu.usst.jwgl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AppExceptionsTest {

    @Test
    fun testFriendlyMessageForNetworkExceptions() {
        val unknownHost = UnknownHostException("api.usst.edu.cn")
        assertTrue(unknownHost.getFriendlyErrorMessage().contains("无法解析服务器地址"))

        val timeout = SocketTimeoutException("Read timed out")
        assertTrue(timeout.getFriendlyErrorMessage().contains("超时"))

        val connectErr = ConnectException("Connection refused")
        assertTrue(connectErr.getFriendlyErrorMessage().contains("无法连接至服务器"))

        val authErr = AppAuthExpiredException()
        assertEquals("登录状态已失效，请重新登录", authErr.getFriendlyErrorMessage())

        val parseErr = AppDataParseException()
        assertTrue(parseErr.getFriendlyErrorMessage().contains("数据解析失败"))

        val server500 = AppNetworkException(statusCode = 500, message = "Server Error")
        assertTrue(server500.getFriendlyErrorMessage().contains("500"))

        val generic = Exception("Custom test message")
        assertEquals("Custom test message", generic.getFriendlyErrorMessage())
    }
}
