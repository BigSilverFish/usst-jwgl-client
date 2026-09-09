package cn.edu.usst.jwgl.data.network

import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.CopyOnWriteArrayList

class MemoryCookieJar : CookieJar {
    private val cookieStore = CopyOnWriteArrayList<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            // Remove matching old cookie
            cookieStore.removeAll { 
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path 
            }
            cookieStore.add(cookie)
            Log.d("MemoryCookieJar", "Saved cookie: ${cookie.name}=${cookie.value} domain=${cookie.domain} path=${cookie.path}")
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val validCookies = ArrayList<Cookie>()
        val now = System.currentTimeMillis()

        for (cookie in cookieStore) {
            if (cookie.expiresAt < now) {
                cookieStore.remove(cookie)
            } else if (cookie.matches(url)) {
                validCookies.add(cookie)
            }
        }
        Log.d("MemoryCookieJar", "Loaded ${validCookies.size} cookies for $url")
        return validCookies
    }

    fun clear() {
        cookieStore.clear()
        Log.d("MemoryCookieJar", "Cookie store cleared")
    }

    fun getAllCookies(): List<Cookie> = cookieStore.toList()
}
