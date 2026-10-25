package cn.edu.usst.jwgl.util

import android.util.Log
import android.webkit.CookieManager
import cn.edu.usst.jwgl.data.network.JwglClient
import okhttp3.Cookie

object CookieSyncHelper {
    private const val TAG = "CookieSyncHelper"

    /**
     * 将 JwglClient (OkHttp) 中的 CAS SSO 与教务会话 Cookie 同步至 Android 系统的 WebView CookieManager
     */
    fun syncJwglCookiesToWebView() {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val cookies = JwglClient.getCookies()
            if (cookies.isEmpty()) {
                Log.d(TAG, "No Jwgl cookies to sync")
                return
            }

            for (cookie in cookies) {
                val cleanDomain = cookie.domain.trimStart('.')
                val secureAttr = if (cookie.secure) "; Secure" else ""
                val httpOnlyAttr = if (cookie.httpOnly) "; HttpOnly" else ""

                // 1. 针对具体域名和路径注入
                val originUrl = "https://$cleanDomain${cookie.path}"
                val exactCookieStr = "${cookie.name}=${cookie.value}; Path=${cookie.path}$secureAttr$httpOnlyAttr"
                cookieManager.setCookie(originUrl, exactCookieStr)

                // 2. 针对根路径注入，使子路径如 /authserver/login 继承
                val rootCookieStr = "${cookie.name}=${cookie.value}; Path=/$secureAttr$httpOnlyAttr"
                cookieManager.setCookie("https://$cleanDomain/", rootCookieStr)

                // 3. 针对 USST 核心认证相关各子域广播关键凭证（特别是 CASTGC 和 JSESSIONID）
                if (cleanDomain.endsWith("usst.edu.cn")) {
                    val domainCookieStr = "${cookie.name}=${cookie.value}; Domain=.usst.edu.cn; Path=/$secureAttr$httpOnlyAttr"
                    cookieManager.setCookie("https://usst.edu.cn/", domainCookieStr)
                    cookieManager.setCookie("https://ids6.usst.edu.cn/", rootCookieStr)
                    cookieManager.setCookie("https://ehall.usst.edu.cn/", rootCookieStr)
                    cookieManager.setCookie("https://1906.usst.edu.cn/", rootCookieStr)
                    cookieManager.setCookie("https://1906.usst.edu.cn:8600/", rootCookieStr)
                }
            }

            // 预配置 LOCALE，避免 CAS 语言重载循环
            cookieManager.setCookie(
                "https://ids6.usst.edu.cn/",
                "org.springframework.web.servlet.i18n.CookieLocaleResolver.LOCALE=zh_CN; Path=/; Secure"
            )
            cookieManager.flush()
            Log.d(TAG, "Successfully synced ${cookies.size} cookies to Android WebView CookieManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync cookies to WebView", e)
        }
    }

    /**
     * 将 Android 系统 WebView CookieManager 中的 Cookie 同步至 JwglClient (OkHttp)
     */
    fun syncWebViewCookiesToJwgl() {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.flush()

            val targets = listOf(
                "https://jwgl.usst.edu.cn/jwglxt/" to "jwgl.usst.edu.cn",
                "https://jwgl.usst.edu.cn/" to "jwgl.usst.edu.cn",
                "https://ids6.usst.edu.cn/authserver/" to "ids6.usst.edu.cn",
                "https://ids6.usst.edu.cn/" to "ids6.usst.edu.cn",
                "https://ehall.usst.edu.cn/" to "ehall.usst.edu.cn",
                "https://usst.edu.cn/" to "usst.edu.cn"
            )

            val cookiesToAdd = mutableListOf<Cookie>()
            for ((url, domain) in targets) {
                val cookieHeader = cookieManager.getCookie(url) ?: continue
                val parts = cookieHeader.split(";")
                for (part in parts) {
                    val trimmed = part.trim()
                    if (trimmed.isEmpty()) continue
                    val eqIdx = trimmed.indexOf('=')
                    if (eqIdx > 0) {
                        val name = trimmed.substring(0, eqIdx).trim()
                        val value = trimmed.substring(eqIdx + 1).trim()
                        try {
                            val cookie = Cookie.Builder()
                                .name(name)
                                .value(value)
                                .domain(domain)
                                .path("/")
                                .build()
                            cookiesToAdd.add(cookie)
                        } catch (e: Exception) {
                            Log.w(TAG, "Skipping invalid cookie format: $name", e)
                        }
                    }
                }
            }

            if (cookiesToAdd.isNotEmpty()) {
                JwglClient.addCookies(cookiesToAdd)
                Log.d(TAG, "Successfully synced ${cookiesToAdd.size} cookies from WebView to JwglClient")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync cookies from WebView to JwglClient", e)
        }
    }
}
