package cn.edu.usst.jwgl.data.network

import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.CopyOnWriteArrayList

class MemoryCookieJar : CookieJar {
    private val lock = Any()
    private val cookieStore = ArrayList<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            for (cookie in cookies) {
                cookieStore.removeAll { 
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path 
                }
                cookieStore.add(cookie)
                Log.d("MemoryCookieJar", "Saved cookie: ${cookie.name} domain=${cookie.domain} path=${cookie.path}")
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val validCookies = ArrayList<Cookie>()
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val iterator = cookieStore.iterator()
            while (iterator.hasNext()) {
                val cookie = iterator.next()
                if (cookie.expiresAt < now) {
                    iterator.remove()
                } else if (cookie.matches(url)) {
                    validCookies.add(cookie)
                }
            }
        }
        Log.d("MemoryCookieJar", "Loaded ${validCookies.size} cookies for ${url.host}")
        return validCookies
    }

    fun clear() {
        synchronized(lock) {
            cookieStore.clear()
        }
        Log.d("MemoryCookieJar", "Cookie store cleared")
    }

    fun getAllCookies(): List<Cookie> = synchronized(lock) {
        ArrayList(cookieStore)
    }
}
