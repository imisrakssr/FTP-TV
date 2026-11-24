package com.israk.ftptv.data.sources

import com.israk.ftptv.data.Catalog
import com.israk.ftptv.data.Playable
import com.israk.ftptv.data.RawStream
import com.israk.ftptv.data.StreamRef
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** How a server is reached. Built-in ones were checked by hand in Nov 2026; users can add plain M3U lists. */
enum class SourceType { JSON_API, APPOLO, FLUSSONIC_PORTAL, VIDEOJS_PAGES, REDFORCE, FAMELACK, M3U }

data class SourceConfig(
    val id: String,
    val type: SourceType,
    val title: String,
    val url: String,
    val enabled: Boolean = true,
    val builtin: Boolean = true,
    /** Lower = tried earlier when nothing has been measured yet. */
    val basePriority: Int = 50,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type.name).put("title", title).put("url", url)
        .put("enabled", enabled).put("builtin", builtin).put("prio", basePriority)

    companion object {
        fun fromJson(o: JSONObject) = SourceConfig(
            o.getString("id"), SourceType.valueOf(o.getString("type")), o.getString("title"), o.getString("url"),
            o.optBoolean("enabled", true), o.optBoolean("builtin", false), o.optInt("prio", 50),
        )

        val builtins = listOf(
            SourceConfig("s1099", SourceType.JSON_API, "Live TV · 10.99.99.99", "http://10.99.99.99/", basePriority = 10),
            SourceConfig("flu60", SourceType.FLUSSONIC_PORTAL, "Live TV · 172.16.60.2", "http://172.16.60.2/", basePriority = 15),
            SourceConfig("funtime", SourceType.VIDEOJS_PAGES, "Funtime · 172.20.21.22",
                "http://172.20.21.22/live_tv.php?key=1 http://172.20.21.22/live_tv2.php?key=1 http://172.20.21.22/live_tv3.php?key=1",
                basePriority = 20),
            SourceConfig("appolo", SourceType.APPOLO, "Appolo TV · KSBNET", "http://tv.ksbnet.net:8099/", basePriority = 25),
            SourceConfig("redforce", SourceType.REDFORCE, "Red Force TV", "http://redforce.live/", basePriority = 30),
            SourceConfig("famelack", SourceType.FAMELACK, "Famelack (public)", "bd in pk sa", basePriority = 80),
        )
    }
}

interface Source {
    val config: SourceConfig
    val id: String get() = config.id
    suspend fun list(): List<RawStream>
    suspend fun resolve(ref: StreamRef): Playable

    companion object {
        fun create(c: SourceConfig): Source = when (c.type) {
            SourceType.JSON_API -> JsonApiSource(c)
            SourceType.APPOLO -> AppoloSource(c)
            SourceType.FLUSSONIC_PORTAL -> FlussonicPortalSource(c)
            SourceType.VIDEOJS_PAGES -> VideoJsSource(c)
            SourceType.REDFORCE -> RedforceSource(c)
            SourceType.FAMELACK -> FamelackSource(c)
            SourceType.M3U -> M3uSource(c)
        }
    }
}

private fun base(url: String) = url.trim().substringBefore('?').let { if (it.endsWith("/")) it else "$it/" }

/** 10.99.99.99: `/api/channels` gives every channel with a signed, ready-to-play URL (valid a few hours). */
class JsonApiSource(override val config: SourceConfig) : Source {
    private val b = base(config.url)
    private val fresh = ConcurrentHashMap<String, String>()
    @Volatile private var listedAt = 0L
    private val lock = Mutex()

    override suspend fun list(): List<RawStream> {
        val arr = JSONArray(Net.get(b + "api/channels", mapOf("Accept" to "application/json", "Referer" to b)))
        val out = ArrayList<RawStream>(arr.length())
        fresh.clear()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { o.optString("name") }
            val url = o.optString("url").takeIf { it.startsWith("http") } ?: continue
            fresh[id] = url
            val logo = o.optString("logo").takeIf { it.isNotBlank() }?.let { Net.absolute(b, it) }
            out += RawStream(o.optString("name"), StreamRef(config.id, id, url, o.optString("name")), logo)
        }
        listedAt = System.currentTimeMillis()
        return out
    }

    override suspend fun resolve(ref: StreamRef): Playable {
        lock.withLock {
            if (fresh[ref.key] == null || System.currentTimeMillis() - listedAt > 80 * 60_000L) list()
        }
        val url = fresh[ref.key] ?: throw IOException("Channel gone from server")
        return Playable(url, mapOf("Referer" to b), config.id)
    }
}

/** Appolo TV (KSBNET): Laravel site — channel list JSON, then a CSRF-protected token call per channel. */
class AppoloSource(override val config: SourceConfig) : Source {
    private val b = base(config.url)
    @Volatile private var csrf: String? = null
    @Volatile private var csrfAt = 0L
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val lock = Mutex()
    private val csrfRe = Regex("""<meta\s+name=["']csrf-token["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private suspend fun ensureCsrf(force: Boolean = false): String = lock.withLock {
        val c = csrf
        if (!force && c != null && System.currentTimeMillis() - csrfAt < 50 * 60_000L) return c
        val html = Net.get(b)
        val t = csrfRe.find(html)?.groupValues?.get(1) ?: throw IOException("No CSRF token")
        csrf = t; csrfAt = System.currentTimeMillis()
        t
    }

    override suspend fun list(): List<RawStream> {
        runCatching { ensureCsrf(force = true) }
        val o = JSONObject(Net.get(b + "channels/list", mapOf("Accept" to "application/json", "Referer" to b, "X-Requested-With" to "XMLHttpRequest")))
        val arr = o.optJSONArray("channels") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val slug = c.optString("slug").ifBlank { return@mapNotNull null }
            RawStream(c.optString("name"), StreamRef(config.id, slug, null, c.optString("name")), c.optString("logo").ifBlank { null })
        }
    }

    override suspend fun resolve(ref: StreamRef): Playable {
        cache[ref.key]?.let { (t, u) -> if (System.currentTimeMillis() - t < 4 * 60_000L) return Playable(u, mapOf("Referer" to b), config.id) }
        suspend fun call(token: String): String {
            val h = mapOf(
                "X-CSRF-TOKEN" to token, "Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest",
                "Referer" to b + "?channel=" + ref.key, "Origin" to b.trimEnd('/'),
            )
            return Net.post(b + "stream/token/" + ref.key, ByteArray(0).toRequestBody(null), h)
        }
        val body = try {
            call(ensureCsrf())
        } catch (e: HttpError) {
            if (e.code == 419 || e.code == 401 || e.code == 403) call(ensureCsrf(force = true))
            else if (e.code == 429) throw IOException("Appolo: too many requests, wait a minute")
            else throw e
        }
        val url = JSONObject(body).optString("play_url").ifBlank { throw IOException("No play URL") }
        val abs = Net.absolute(b, url)
        cache[ref.key] = System.currentTimeMillis() to abs
        return Playable(abs, mapOf("Referer" to b), config.id)
    }
}

/** 172.16.60.2: a grid of `play.php?stream=N` links; the player page embeds Flussonic with a 1-hour token. */
class FlussonicPortalSource(override val config: SourceConfig) : Source {
    private val b = base(config.url)
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()
    // <a href="img/play.php?stream=305" target="view"><img src="assets/images/Zee Bangla Cinema1664976800.png" alt="Zee-Bangla Cinema"></a>
    private val itemRe = Regex("""href\s*=\s*["'][^"']*play\.php\?stream=(\d+)["'][^>]*>\s*<img([^>]*)>""", RegexOption.IGNORE_CASE)
    private val altRe = Regex("""alt\s*=\s*["']([^"']+)["']""")
    private val srcRe = Regex("""src\s*=\s*["']([^"']+)["']""")

    override suspend fun list(): List<RawStream> {
        val html = Net.get(b)
        return itemRe.findAll(html).mapNotNull { m ->
            val id = m.groupValues[1]
            val attrs = m.groupValues[2]
            val img = srcRe.find(attrs)?.groupValues?.get(1)
            val name = altRe.find(attrs)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?: img?.let { Catalog.nameFromFile(it) } ?: return@mapNotNull null
            RawStream(name, StreamRef(config.id, id, null, name), img?.let { Net.absolute(b, it.replace(" ", "%20")) })
        }.toList()
    }

    override suspend fun resolve(ref: StreamRef): Playable {
        cache[ref.key]?.let { (t, u) -> if (System.currentTimeMillis() - t < 40 * 60_000L) return Playable(u, emptyMap(), config.id) }
        val page = b + "img/play.php?stream=" + ref.key
        val html = Net.get(page, mapOf("Referer" to b))
        val url = Net.extractStream(page, html, mapOf("Referer" to b)) ?: throw IOException("No stream on player page")
        cache[ref.key] = System.currentTimeMillis() to url
        return Playable(url, emptyMap(), config.id)
    }
}

/**
 * Funtime (172.20.21.22): video.js pages whose playlist is written into the page, or loaded by a
 * `$.ajax({url:'…channels.php', type:'POST', data:{video_id}})` call. The URLs are plain and permanent.
 */
class VideoJsSource(override val config: SourceConfig) : Source {
    private val pages = config.url.split(Regex("\\s+")).filter { it.startsWith("http") }
    private val entryRe = Regex(
        """name\s*:\s*['"]([^'"]+)['"]\s*,\s*sources\s*:\s*\[\s*\{\s*src\s*:\s*['"]([^'"]+)['"][^\]]*\]\s*,?\s*(?:thumbnail\s*:\s*\[\s*\{\s*src\s*:\s*['"]([^'"]+)['"])?""",
    )
    private val ajaxRe = Regex("""\$\.ajax\(\s*\{\s*url\s*:\s*['"]([^'"]+\.php)['"][\s\S]{0,160}?data\s*:\s*\{\s*video_id""")
    private val vidRe = Regex("""var\s+video_id\s*=\s*["']?([^"';\s]+)""")
    private val referer = ConcurrentHashMap<String, String>()

    private fun parse(text: String, page: String, out: MutableList<RawStream>) {
        for (m in entryRe.findAll(text)) {
            val name = m.groupValues[1].trim()
            val url = Net.absolute(page, m.groupValues[2])
            if (!url.startsWith("http")) continue
            referer[url] = page
            val logo = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { Net.absolute(page, it) }
            out += RawStream(name, StreamRef(config.id, url, url, name), logo)
        }
    }

    override suspend fun list(): List<RawStream> = coroutineScope {
        pages.map { page ->
            async {
                val out = ArrayList<RawStream>()
                runCatching {
                    val html = Net.get(page)
                    parse(html, page, out)
                    val ajax = ajaxRe.find(html)?.groupValues?.get(1)
                    if (ajax != null) {
                        val vid = vidRe.find(html)?.groupValues?.get(1) ?: "1"
                        val api = Net.absolute(page, ajax)
                        val res = Net.postForm(api, mapOf("video_id" to vid), mapOf("Referer" to page, "X-Requested-With" to "XMLHttpRequest"))
                        parse(res, page, out)
                    }
                }
                out
            }
        }.awaitAll().flatten().distinctBy { it.ref.key }.also {
            if (it.isEmpty()) throw IOException("No channels on Funtime pages")
        }
    }

    override suspend fun resolve(ref: StreamRef): Playable {
        val url = ref.directUrl ?: ref.key
        val page = referer[url] ?: pages.firstOrNull().orEmpty()
        return Playable(url, if (page.isNotEmpty()) mapOf("Referer" to page) else emptyMap(), config.id)
    }
}

/** Red Force TV: `player.php?stream=N` cards; the player page holds the stream (site was under maintenance in Nov 2026). */
class RedforceSource(override val config: SourceConfig) : Source {
    private val b = base(config.url)
    // <a onclick="view.location.href='player.php?stream=115'"><img src="assets/images/9XM MUSIC….png" alt="9XM MUSIC"></a>
    private val itemRe = Regex("""location\.href\s*=\s*['"]player\.php\?stream=(\d+)['"][^>]*>\s*<img([^>]*)>""", RegexOption.IGNORE_CASE)
    private val altRe = Regex("""alt\s*=\s*["']([^"']+)["']""")
    private val srcRe = Regex("""src\s*=\s*["']([^"']+)["']""")
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    override suspend fun list(): List<RawStream> {
        val html = Net.get(b)
        return itemRe.findAll(html).mapNotNull { m ->
            val attrs = m.groupValues[2]
            val src = srcRe.find(attrs)?.groupValues?.get(1)
            val name = altRe.find(attrs)?.groupValues?.get(1)?.trim() ?: src?.let { Catalog.nameFromFile(it) } ?: return@mapNotNull null
            RawStream(name, StreamRef(config.id, m.groupValues[1], null, name), src?.let { Net.absolute(b, it.replace(" ", "%20")) })
        }.distinctBy { it.ref.key }.toList()
    }

    override suspend fun resolve(ref: StreamRef): Playable {
        cache[ref.key]?.let { (t, u) -> if (System.currentTimeMillis() - t < 20 * 60_000L) return Playable(u, mapOf("Referer" to b), config.id) }
        val page = b + "player.php?stream=" + ref.key
        val html = Net.get(page, mapOf("Referer" to b))
        val url = Net.extractStream(page, html, mapOf("Referer" to b)) ?: throw IOException("No stream on player page")
        cache[ref.key] = System.currentTimeMillis() to url
        return Playable(url, mapOf("Referer" to b), config.id)
    }
}

/** Famelack's open data (public, free-to-air streams) — fills Pakistan & Islamic gaps; slower than BDIX. */
class FamelackSource(override val config: SourceConfig) : Source {
    private val countries = config.url.split(Regex("[\\s,]+")).filter { it.length == 2 }

    override suspend fun list(): List<RawStream> = coroutineScope {
        countries.map { cc ->
            async {
                runCatching {
                    val arr = JSONArray(Net.get("https://raw.githubusercontent.com/famelack/famelack-data/main/tv/countries/$cc.json", maxBytes = 8L * 1024 * 1024))
                    val out = ArrayList<RawStream>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optBoolean("isGeoBlocked", false)) continue
                        val name = o.optString("name")
                        // only names that are in our catalog are worth keeping (in.json alone has ~700)
                        if (Catalog.match(name) == null) continue
                        val streams = o.optJSONObject("sources")?.optJSONArray("streams") ?: continue
                        for (j in 0 until streams.length()) {
                            val u = streams.optString(j)
                            if (u.startsWith("http")) out += RawStream(name, StreamRef(config.id, u, u, name), null)
                        }
                    }
                    out
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    override suspend fun resolve(ref: StreamRef) = Playable(ref.directUrl ?: ref.key, emptyMap(), config.id)
}

/** Any plain M3U / M3U8 playlist the user adds in Settings. */
class M3uSource(override val config: SourceConfig) : Source {
    private val headers = ConcurrentHashMap<String, Map<String, String>>()
    private val logoRe = Regex("""tvg-logo\s*=\s*"([^"]*)"""")

    override suspend fun list(): List<RawStream> {
        val text = Net.get(config.url, maxBytes = 16L * 1024 * 1024)
        val out = ArrayList<RawStream>()
        var name: String? = null
        var logo: String? = null
        var h = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", true) -> {
                    name = line.substringAfterLast(',').trim()
                    logo = logoRe.find(line)?.groupValues?.get(1)?.ifBlank { null }
                }
                line.startsWith("#EXTVLCOPT:http-user-agent=", true) -> h["User-Agent"] = line.substringAfter('=')
                line.startsWith("#EXTVLCOPT:http-referrer=", true) -> h["Referer"] = line.substringAfter('=')
                line.startsWith("#") || line.isEmpty() -> Unit
                else -> {
                    val url = Net.absolute(config.url, line)
                    val n = name
                    if (n != null && url.startsWith("http")) {
                        if (h.isNotEmpty()) headers[url] = HashMap(h)
                        out += RawStream(n, StreamRef(config.id, url, url, n), logo)
                    }
                    name = null; logo = null; h = HashMap()
                }
            }
        }
        return out
    }

    override suspend fun resolve(ref: StreamRef): Playable {
        val u = ref.directUrl ?: ref.key
        return Playable(u, headers[u] ?: emptyMap(), config.id)
    }
}
