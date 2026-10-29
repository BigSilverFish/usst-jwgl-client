package cn.edu.usst.jwgl.util

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.network.ChaoXingClient
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

                // 3. 只有全局 SSO 凭证（如 CASTGC、iPlanetDirectoryPro）或原域本就是根域通配的 Cookie 才允许跨子域广播
                // 严禁将 ids6/jwgl/live 专用的 JSESSIONID 等局部 Session 覆盖到其他子域，否则会破坏各子系统的登录态
                val isSsoGlobalCookie = cookie.name.equals("CASTGC", ignoreCase = true) ||
                                       cookie.name.equals("iPlanetDirectoryPro", ignoreCase = true) ||
                                       cookie.domain.startsWith(".") ||
                                       cookie.name.contains("SSO", ignoreCase = true)

                if (isSsoGlobalCookie) {
                    if (cleanDomain.endsWith("webvpn.usst.edu.cn")) {
                        val webvpnCookieStr = "${cookie.name}=${cookie.value}; Domain=.webvpn.usst.edu.cn; Path=/$secureAttr$httpOnlyAttr"
                        cookieManager.setCookie("https://webvpn.usst.edu.cn/", webvpnCookieStr)
                        cookieManager.setCookie("https://new2017.webvpn.usst.edu.cn/", webvpnCookieStr)
                        cookieManager.setCookie("https://ids6-443.webvpn.usst.edu.cn/", webvpnCookieStr)
                    }
                    if (cleanDomain.endsWith("usst.edu.cn")) {
                        val domainCookieStr = "${cookie.name}=${cookie.value}; Domain=.usst.edu.cn; Path=/$secureAttr$httpOnlyAttr"
                        cookieManager.setCookie("https://usst.edu.cn/", domainCookieStr)
                        cookieManager.setCookie("https://ids6.usst.edu.cn/", domainCookieStr)
                        cookieManager.setCookie("https://ids.usst.edu.cn/", domainCookieStr)
                        cookieManager.setCookie("https://cas.usst.edu.cn/", domainCookieStr)
                        cookieManager.setCookie("https://jwgl.usst.edu.cn/", domainCookieStr)
                        cookieManager.setCookie("https://live.usst.edu.cn/", domainCookieStr)
                        cookieManager.setCookie("https://ehall.usst.edu.cn/", domainCookieStr)
                        cookieManager.setCookie("https://new2017.usst.edu.cn/", domainCookieStr)
                        cookieManager.setCookie("https://new2017.webvpn.usst.edu.cn/", domainCookieStr)
                        cookieManager.setCookie("https://ids6-443.webvpn.usst.edu.cn/", domainCookieStr)
                        cookieManager.setCookie("https://webvpn.usst.edu.cn/", domainCookieStr)
                        cookieManager.setCookie("https://1906.usst.edu.cn/", domainCookieStr)
                    }
                }

                if (cookie.name.equals("ASP.NET_SessionId", ignoreCase = true)) {
                    cookieManager.setCookie("https://new2017.webvpn.usst.edu.cn/", exactCookieStr)
                    cookieManager.setCookie("https://new2017.usst.edu.cn/", exactCookieStr)
                }
            }

            // 预配置 LOCALE，避免 CAS 语言重载循环
            val localeCookie = "org.springframework.web.servlet.i18n.CookieLocaleResolver.LOCALE=zh_CN; Path=/; Secure"
            cookieManager.setCookie("https://ids6.usst.edu.cn/", localeCookie)
            cookieManager.setCookie("https://ids6-443.webvpn.usst.edu.cn/", localeCookie)
            cookieManager.setCookie("https://webvpn.usst.edu.cn/", localeCookie)
            cookieManager.setCookie("https://cas.usst.edu.cn/", localeCookie)
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
                "https://cas.usst.edu.cn/authserver/" to "cas.usst.edu.cn",
                "https://cas.usst.edu.cn/" to "cas.usst.edu.cn",
                "https://live.usst.edu.cn/" to "live.usst.edu.cn",
                "https://new2017.usst.edu.cn/" to "new2017.usst.edu.cn",
                "https://new2017.webvpn.usst.edu.cn/" to "new2017.webvpn.usst.edu.cn",
                "https://ids6-443.webvpn.usst.edu.cn/" to "ids6-443.webvpn.usst.edu.cn",
                "https://webvpn.usst.edu.cn/" to "webvpn.usst.edu.cn",
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

    /**
     * 将 ChaoXingPreferences 中保存的超星 Cookie 注入至 Android WebView CookieManager
     */
    fun syncChaoXingCookiesToWebView(context: Context, studentId: String? = null) {
        try {
            val prefs = ChaoXingPreferences(context)
            val rawCookies = prefs.getCookies(studentId)
            if (rawCookies.isNullOrBlank()) {
                Log.d(TAG, "No ChaoXing cookies found to sync")
                return
            }
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val pairs = rawCookies.split(";")
            val endpoints = listOf(
                "https://chaoxing.com/",
                "https://mooc1.chaoxing.com/",
                "https://mooc2-ans.chaoxing.com/",
                "https://mobilelearn.chaoxing.com/",
                "https://passport2.chaoxing.com/",
                "http://usst.fanya.chaoxing.com/",
                "https://fanya.chaoxing.com/"
            )

            for (pair in pairs) {
                val trimmed = pair.trim()
                if (trimmed.isEmpty()) continue
                val idx = trimmed.indexOf('=')
                if (idx <= 0) continue
                val key = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim()

                // 1. 全局通配根域广播
                val domainCookie = "$key=$value; Domain=.chaoxing.com; Path=/"
                cookieManager.setCookie("https://chaoxing.com/", domainCookie)

                // 2. 核心子域显式注入 (不带 Secure 属性以兼容 HTTP 泛雅端点)
                val exactCookie = "$key=$value; Path=/"
                for (ep in endpoints) {
                    cookieManager.setCookie(ep, exactCookie)
                }
            }

            // 补充兜底 fid=207，确保上理泛雅与机构认证绝对生效
            val fidCookie = "fid=207; Domain=.chaoxing.com; Path=/"
            cookieManager.setCookie("https://chaoxing.com/", fidCookie)

            cookieManager.flush()
            Log.d(TAG, "Successfully synced ChaoXing cookies to WebView CookieManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync ChaoXing cookies to WebView", e)
        }
    }

    /**
     * 将 WebView 中的超星最新 Cookie 反向同步回 ChaoXingPreferences 与 ChaoXingClient
     */
    fun syncWebViewCookiesToChaoXing(context: Context, studentId: String? = null) {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.flush()
            val cxCookie = cookieManager.getCookie("https://mooc1.chaoxing.com/")
                ?: cookieManager.getCookie("https://chaoxing.com/")
            if (!cxCookie.isNullOrBlank()) {
                ChaoXingClient(context).injectCookies(cxCookie, studentId)
                Log.d(TAG, "Successfully updated ChaoXing cookies from WebView back to preferences")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync WebView cookies back to ChaoXing", e)
        }
    }
}
