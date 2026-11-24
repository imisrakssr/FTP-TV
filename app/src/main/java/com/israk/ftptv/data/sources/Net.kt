package com.israk.ftptv.data.sources

import com.israk.ftptv.FtpTvApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpError(val code: Int, msg: String) : IOException(msg)

/** Tiny HTTP helpers; every call is cancellable so a channel switch drops the old requests at once. */
object Net {
    const val UA = "Mozilla/5.0 (Linux; Android 9; MiTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    private val client get() = FtpTvApp.instance.http

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Long = 4L * 1024 * 1024): String =
        text(Request.Builder().url(url).applyHeaders(headers).get().build(), maxBytes)

    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): String {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return text(Request.Builder().url(url).applyHeaders(headers).post(body).build())
    }

    suspend fun post(url: String, body: RequestBody, headers: Map<String, String> = emptyMap()): String =
        text(Request.Builder().url(url).applyHeaders(headers).post(body).build())

    private fun Request.Builder.applyHeaders(h: Map<String, String>): Request.Builder {
        header("User-Agent", h["User-Agent"] ?: UA)
        h.forEach { (k, v) -> if (k != "User-Agent") header(k, v) }
        return this
    }

    private suspend fun text(req: Request, maxBytes: Long = 4L * 1024 * 1024): String {
        val resp = execute(req)
        return withContext(Dispatchers.IO) {
            resp.use { r ->
                if (!r.isSuccessful) throw HttpError(r.code, "HTTP ${r.code}")
                val src = r.body?.source() ?: return@use ""
                src.request(maxBytes)
                val n = minOf(src.buffer.size, maxBytes)
                src.buffer.readUtf8(n)
            }
        }
    }

    /** Reads only the first few KB: enough to tell a live playlist from an error page. */
    suspend fun probe(url: String, headers: Map<String, String>): Boolean {
        val req = Request.Builder().url(url).applyHeaders(headers).get().build()
        val resp = execute(req)
        return withContext(Dispatchers.IO) {
            resp.use { r ->
                if (!r.isSuccessful) return@use false
                val type = r.header("Content-Type").orEmpty().lowercase()
                if (type.startsWith("video/") || type.contains("mp2t") || type.contains("octet-stream") && !url.contains(".m3u8")) return@use true
                val src = r.body?.source() ?: return@use false
                src.request(4096)
                val head = src.buffer.readUtf8(minOf(src.buffer.size, 4096))
                head.contains("#EXTM3U") || head.contains("#EXT-X")
            }
        }
    }

    suspend fun execute(req: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    /** Resolves a link from a page against that page ("skynet@8080/x.php", "/api", "//host/x"). */
    fun absolute(base: String, link: String): String {
        val l = link.trim().replace("&amp;", "&")
        if (l.startsWith("http://") || l.startsWith("https://")) return l
        val b = base.toHttpUrlOrNull() ?: return l
        if (l.startsWith("//")) return "${b.scheme}:$l"
        if (l.startsWith("/")) return "${b.scheme}://${b.host}${if (b.port != defaultPort(b.scheme)) ":" + b.port else ""}$l"
        // relative path: keep it literal (some paths contain '@' which URL resolvers mangle)
        val dir = base.substringBefore('?').substringBeforeLast('/') + "/"
        return dir + l
    }

    private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

    /** "http://h:8088//102/embed.html?token=x" → "http://h:8088/102/index.m3u8?token=x" (Flussonic). */
    fun flussonicHls(embed: String): String {
        val u = embed.replace("&amp;", "&")
        val scheme = u.substringBefore("://")
        var rest = u.substringAfter("://")
        val hostPart = rest.substringBefore('/')
        var path = rest.removePrefix(hostPart)
        while (path.contains("//")) path = path.replace("//", "/")
        rest = hostPart + path
        return "$scheme://" + rest.replace("/embed.html", "/index.m3u8")
    }

    private val m3u8Re = Regex("""(https?:)?//[^"'\s<>()\\]+?\.m3u8[^"'\s<>()\\]*""")
    private val iframeRe = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val sourceRe = Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)

    /** Finds a playable URL in a player page: direct .m3u8, a Flussonic embed, or one iframe deeper. */
    suspend fun extractStream(pageUrl: String, html: String, headers: Map<String, String>, depth: Int = 0): String? {
        sourceRe.find(html)?.let { return absolute(pageUrl, it.groupValues[1]) }
        m3u8Re.find(html)?.let { val v = it.value; return if (v.startsWith("//")) "http:$v" else v }
        val frame = iframeRe.find(html)?.groupValues?.get(1)?.let { absolute(pageUrl, it) } ?: return null
        if (frame.contains("/embed.html")) return flussonicHls(frame)
        if (depth >= 1) return null
        val inner = runCatching { get(frame, headers + ("Referer" to pageUrl)) }.getOrNull() ?: return null
        return extractStream(frame, inner, headers, depth + 1)
    }
}
