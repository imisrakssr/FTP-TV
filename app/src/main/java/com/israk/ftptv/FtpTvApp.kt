package com.israk.ftptv

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.israk.ftptv.data.ChannelRepository
import com.israk.ftptv.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class FtpTvApp : Application(), ImageLoaderFactory {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One shared client: connection reuse makes channel zapping much faster on the local servers. */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .cookieJar(MemoryCookieJar())
            .build()
    }

    lateinit var prefs: Prefs
        private set
    lateinit var repo: ChannelRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        repo = ChannelRepository(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { http }
        .crossfade(false)
        .memoryCache {
            coil.memory.MemoryCache.Builder(this).maxSizePercent(0.08).build()
        }
        .diskCache {
            coil.disk.DiskCache.Builder().directory(cacheDir.resolve("logos")).maxSizeBytes(20L * 1024 * 1024).build()
        }
        .respectCacheHeaders(false)
        .build()

    companion object {
        lateinit var instance: FtpTvApp
            private set

        fun isTv(ctx: Context): Boolean {
            val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
            val pm = ctx.packageManager
            return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        }
    }
}

/** Session cookies (Laravel CSRF on the Appolo server) kept in memory for the app's lifetime. */
private class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableMap<String, Cookie>>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val m = store.getOrPut(url.host) { ConcurrentHashMap() }
        cookies.forEach { m[it.name] = it }
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host]?.values?.filter { it.expiresAt > now && it.matches(url) }.orEmpty()
    }
}
