package cn.edu.usst.jwgl.util

import android.content.ActivityNotFoundException
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
 * 超星学习通触发访问限制或高频风控保护异常
 */
class ChaoXingRiskControlException(
    val cooldownUntilMillis: Long,
    message: String
) : IOException(message)

/**
 * 转换任何 Throwable 为用户友好、可读性高的中文诊断信息，
 * 避免将底层类名或原始堆栈透出给用户。
 */
fun Throwable.getFriendlyErrorMessage(fallback: String = "操作失败，请稍后重试"): String {
    return when (this) {
        is AppAuthExpiredException -> message ?: "登录状态已失效，请重新登录"
        is ChaoXingRiskControlException -> message ?: "超星学习通触发访问保护，已暂时暂停请求"
        is ActivityNotFoundException -> "未找到可处理此操作或打开此文件的应用"
        is SecurityException -> "缺少必要的系统权限，请前往应用设置授予"
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
                msg.contains("failed to connect", ignoreCase = true) -> "无法连接至服务器，请检查网络"
                msg.contains("unexpected end of stream", ignoreCase = true) -> "服务器传输中断，请重试"
                else -> fallback
            }
        }
        else -> {
            val raw = message?.trim()
            if (raw.isNullOrBlank() || raw.startsWith("java.") || raw.contains("Exception") || raw.contains("No Activity found")) {
                fallback
            } else {
                raw
            }
        }
    }
}
