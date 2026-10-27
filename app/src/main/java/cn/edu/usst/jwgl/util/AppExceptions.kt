package cn.edu.usst.jwgl.util

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 应用级网络与业务异常基类
 */
open class AppException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 网络请求异常（携带 HTTP 响应码及可选 URL）
 */
class AppNetworkException(
    val statusCode: Int = -1,
    message: String,
    cause: Throwable? = null,
    val url: String? = null
) : AppException(message, cause)

/**
 * 用户认证或会话过期异常（提示需重新登录）
 */
class AppAuthExpiredException(
    message: String = "登录状态已失效，请重新登录",
    cause: Throwable? = null
) : AppException(message, cause)

/**
 * 数据或 JSON 解析异常
 */
class AppDataParseException(
    message: String = "数据解析异常",
    cause: Throwable? = null
) : AppException(message, cause)

/**
 * 转换任何 Throwable 为用户友好、可读性高的中文诊断信息，
 * 同时保留技术诊断信息（如状态码与异常类型）以便排查。
 */
fun Throwable.getFriendlyErrorMessage(fallback: String = "操作失败，请稍后重试"): String {
    return when (this) {
        is AppAuthExpiredException -> message ?: "登录状态已失效，请重新登录"
        is UnknownHostException -> "无法解析服务器地址，请检查手机网络是否已连接"
        is SocketTimeoutException -> "网络请求超时，服务器响应过慢，请稍后重试"
        is ConnectException -> "无法连接至服务器，请检查校园网/VPN或校园服务器状态"
        is SSLException -> "网络安全握手失败，请确认系统时间或网络安全证书"
        is AppDataParseException -> "数据解析失败，可能是接口结构调整或返回了异常页面"
        is AppNetworkException -> {
            when (statusCode) {
                401, 403 -> "权限不足或登录已过期 (${statusCode})"
                404 -> "请求资源不存在 (404)"
                500, 502, 503, 504 -> "学校服务器繁忙或正在维护 (${statusCode})"
                else -> message ?: "网络请求失败 (${statusCode})"
            }
        }
        is IOException -> {
            val msg = message ?: ""
            when {
                msg.contains("timeout", ignoreCase = true) -> "网络连接超时，请重试"
                msg.contains("closed", ignoreCase = true) -> "网络连接意外中断，请重试"
                else -> "网络传输错误: ${msg.ifBlank { "未知I/O异常" }}"
            }
        }
        else -> message?.takeIf { it.isNotBlank() } ?: fallback
    }
}
