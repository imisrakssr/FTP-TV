package com.israk.ftptv.data

import com.israk.ftptv.FtpTvApp
import com.israk.ftptv.data.sources.Net
import com.israk.ftptv.data.sources.Source
import com.israk.ftptv.data.sources.SourceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class SourceStatus(val config: SourceConfig, val ok: Boolean?, val channels: Int, val ms: Int, val error: String?)

class ChannelRepository(private val app: FtpTvApp) {

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _status = MutableStateFlow<List<SourceStatus>>(emptyList())
    val status: StateFlow<List<SourceStatus>> = _status

    private val sources = ConcurrentHashMap<String, Source>()
    private val scores: MutableMap<String, Pair<Int, Int>> = ConcurrentHashMap(app.prefs.sourceScores())
    private val cacheFile = File(app.filesDir, "channels.json")
    private var refreshJob: Job? = null

    init {
        app.appScope.launch(Dispatchers.IO) {
            runCatching { loadCache() }.getOrNull()?.let { if (_channels.value.isEmpty()) _channels.value = it }
            refresh()
        }
    }

    fun sourceFor(id: String): Source? {
        val cfg = app.prefs.sources().firstOrNull { it.id == id } ?: return null
        val cur = sources[id]
        if (cur != null && cur.config == cfg) return cur
        return Source.create(cfg).also { sources[id] = it }
    }

    fun sourceTitle(id: String) = app.prefs.sources().firstOrNull { it.id == id }?.title ?: id

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = app.appScope.launch(Dispatchers.IO) {
            _loading.value = true
            try { doRefresh() } finally { _loading.value = false }
        }
    }

    private suspend fun doRefresh(): Unit = coroutineScope {
        val cfgs = app.prefs.sources().filter { it.enabled }
        _status.value = cfgs.map { SourceStatus(it, null, 0, 0, null) }
        val results = cfgs.map { cfg ->
            async {
                val t0 = System.currentTimeMillis()
                val r = runCatching { withTimeoutOrNull(20_000) { sourceFor(cfg.id)!!.list() } ?: throw java.io.IOException("Timed out") }
                val ms = (System.currentTimeMillis() - t0).toInt()
                cfg to (r to ms)
            }
        }.awaitAll()

        val previous = _channels.value
        val merged = LinkedHashMap<String, MutableList<StreamRef>>()
        val logos = HashMap<String, String>()
        val statuses = ArrayList<SourceStatus>()
        val failed = HashSet<String>()
        for ((cfg, pair) in results) {
            val (r, ms) = pair
            val list = r.getOrNull()
            if (list == null) {
                failed += cfg.id
                statuses += SourceStatus(cfg, false, 0, ms, r.exceptionOrNull()?.message ?: "failed")
                continue
            }
            // one stream on a server belongs to one channel only (some portals link two cards to one stream)
            val usedKeys = HashSet<String>()
            var count = 0
            for (s in list) {
                val ch = Catalog.match(s.name) ?: continue
                if (!usedKeys.add(s.ref.key)) continue
                val refs = merged.getOrPut(ch.id) { ArrayList() }
                if (refs.none { it.sourceId == s.ref.sourceId && it.key == s.ref.key }) { refs += s.ref; count++ }
                if (s.logo != null && ch.id !in logos) logos[ch.id] = s.logo
            }
            statuses += SourceStatus(cfg, true, count, ms, null)
            if (count > 0) noteSpeed(cfg.id, ms / 4, ok = true) // listing time is a rough hint, real play times win later
        }
        // servers that didn't answer this time: keep their old entries at the back, they may be back soon
        for (ch in previous) for (ref in ch.streams) if (ref.sourceId in failed) {
            val refs = merged.getOrPut(ch.id) { ArrayList() }
            if (refs.none { it.sourceId == ref.sourceId && it.key == ref.key }) refs += ref
            if (ch.logo != null && ch.id !in logos) logos[ch.id] = ch.logo
        }
        _status.value = statuses
        if (merged.isEmpty() && previous.isNotEmpty()) return@coroutineScope
        val built = build(merged, logos)
        _channels.value = built
        runCatching { saveCache(built) }
    }

    private fun build(merged: Map<String, List<StreamRef>>, logos: Map<String, String>): List<Channel> =
        Catalog.all.mapIndexedNotNull { i, info ->
            val refs = merged[info.id]?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            Channel(info, i + 1, logos[info.id], refs)
        }

    // ── picking the fastest server ──

    private fun score(sourceId: String): Int {
        val (ms, fails) = scores[sourceId] ?: ((app.prefs.sources().firstOrNull { it.id == sourceId }?.basePriority ?: 50) * 20 to 0)
        return ms + fails * 1500
    }

    fun noteSpeed(sourceId: String, ms: Int, ok: Boolean) {
        val (old, fails) = scores[sourceId] ?: (ms to 0)
        scores[sourceId] = if (ok) ((old * 2 + ms) / 3) to (fails - 1).coerceAtLeast(0) else old to (fails + 1).coerceAtMost(6)
        app.prefs.saveSourceScores(scores)
    }

    /** All streams of a channel, best first (a server the user pinned for this channel goes first). */
    fun ordered(ch: Channel): List<StreamRef> {
        val pin = app.prefs.pinnedSource(ch.id)
        return ch.streams.withIndex().sortedWith(compareBy<IndexedValue<StreamRef>>({ if (pin != null && keyOf(it.value) == pin) 0 else 1 }, { score(it.value.sourceId) }, { it.index }))
            .map { it.value }
    }

    fun keyOf(r: StreamRef) = r.sourceId + "|" + r.key

    suspend fun resolve(ref: StreamRef): Playable = withContext(Dispatchers.IO) {
        val src = sourceFor(ref.sourceId) ?: throw java.io.IOException("Server removed")
        src.resolve(ref)
    }

    /**
     * Asks the best few servers at once and returns the first one that actually serves a live playlist.
     * With only one server there's nothing to race: the player finds out itself.
     */
    suspend fun fastest(refs: List<StreamRef>, skip: Set<String>): Pair<StreamRef, Playable>? = coroutineScope {
        val cand = refs.filter { keyOf(it) !in skip }
        if (cand.isEmpty()) return@coroutineScope null
        if (cand.size == 1 || !app.prefs.autoServer) {
            val r = cand.first()
            return@coroutineScope runCatching { r to resolve(r) }.getOrNull()
                ?: cand.drop(1).firstNotNullOfOrNull { c -> runCatching { c to resolve(c) }.getOrNull() }
        }
        // race in waves of 3 so a dead server never makes the user wait for the others
        for (wave in cand.chunked(3)) {
            val results = KChannel<Pair<StreamRef, Playable>?>(wave.size)
            val jobs = wave.map { r ->
                launch(Dispatchers.IO) {
                    val t0 = System.currentTimeMillis()
                    val res = try {
                        val p = resolve(r)
                        val ok = withTimeoutOrNull(4500) { Net.probe(p.url, p.headers) } == true
                        if (ok) { noteSpeed(r.sourceId, (System.currentTimeMillis() - t0).toInt(), true); r to p }
                        else { noteSpeed(r.sourceId, 0, false); null }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        noteSpeed(r.sourceId, 0, false); null
                    }
                    results.trySend(res)
                }
            }
            var winner: Pair<StreamRef, Playable>? = null
            for (k in wave.indices) {
                val v = results.receive()
                if (v != null) { winner = v; break }
            }
            jobs.forEach { it.cancel() }
            if (winner != null) return@coroutineScope winner
        }
        null
    }

    // ── cache (instant start) ──

    private fun saveCache(list: List<Channel>) {
        val a = JSONArray()
        list.forEach { ch ->
            val refs = JSONArray(); ch.streams.forEach { refs.put(it.toJson()) }
            a.put(JSONObject().put("id", ch.id).put("logo", ch.logo ?: "").put("refs", refs))
        }
        cacheFile.writeText(a.toString())
    }

    private fun loadCache(): List<Channel> {
        if (!cacheFile.exists()) return emptyList()
        val a = JSONArray(cacheFile.readText())
        val merged = HashMap<String, List<StreamRef>>()
        val logos = HashMap<String, String>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val refs = o.getJSONArray("refs")
            merged[o.getString("id")] = (0 until refs.length()).map { StreamRef.fromJson(refs.getJSONObject(it)) }
            o.optString("logo").takeIf { it.isNotBlank() }?.let { logos[o.getString("id")] = it }
        }
        val enabled = app.prefs.sources().filter { it.enabled }.map { it.id }.toSet()
        return build(merged.mapValues { (_, v) -> v.filter { it.sourceId in enabled } }, logos)
    }
}
