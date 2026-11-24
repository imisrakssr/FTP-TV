package com.israk.ftptv.data

import android.content.Context
import com.israk.ftptv.data.sources.SourceConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class FavGroup(val id: String, val name: String, val ids: List<String>)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("ftptv", Context.MODE_PRIVATE)

    // ── look & feel ──
    var clockOn: Boolean
        get() = sp.getBoolean("clock_on", true)
        set(v) { sp.edit().putBoolean("clock_on", v).apply(); bump() }
    /** 10..100 (%). */
    var clockOpacity: Int
        get() = sp.getInt("clock_opacity", 70)
        set(v) { sp.edit().putInt("clock_opacity", v.coerceIn(10, 100)).apply(); bump() }
    var clock24h: Boolean
        get() = sp.getBoolean("clock_24h", false)
        set(v) { sp.edit().putBoolean("clock_24h", v).apply(); bump() }
    /** 0 fit, 1 stretch, 2 zoom. */
    var aspect: Int
        get() = sp.getInt("aspect", 0)
        set(v) { sp.edit().putInt("aspect", v).apply(); bump() }
    /** 0 = fast zapping (small buffer), 1 = stable (bigger buffer for weak links). */
    var bufferMode: Int
        get() = sp.getInt("buffer", 0)
        set(v) { sp.edit().putInt("buffer", v).apply(); bump() }
    var startWithLast: Boolean
        get() = sp.getBoolean("start_last", true)
        set(v) { sp.edit().putBoolean("start_last", v).apply() }
    var autoServer: Boolean
        get() = sp.getBoolean("auto_server", true)
        set(v) { sp.edit().putBoolean("auto_server", v).apply() }

    var lastChannel: String?
        get() = sp.getString("last_channel", null)
        set(v) { sp.edit().putString("last_channel", v).apply() }
    /** Category key the channel list for Up/Down uses: "all", "fav:<groupId>", or a Category name. */
    var lastList: String
        get() = sp.getString("last_list", "all") ?: "all"
        set(v) { sp.edit().putString("last_list", v).apply() }

    /** Bumped on every visible setting change so open screens can re-apply. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private fun bump() { _version.value++ }

    // ── servers ──
    fun sources(): List<SourceConfig> {
        val raw = sp.getString("sources", null)
        val saved = runCatching {
            val a = JSONArray(raw ?: "[]")
            (0 until a.length()).map { SourceConfig.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        // built-ins always present (new ones appear after an update), user's on/off kept
        val builtins = SourceConfig.builtins.map { b -> saved.firstOrNull { it.id == b.id }?.let { b.copy(enabled = it.enabled) } ?: b }
        return builtins + saved.filter { !it.builtin }
    }

    fun saveSources(list: List<SourceConfig>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        sp.edit().putString("sources", a.toString()).apply()
    }

    fun setSourceEnabled(id: String, on: Boolean) = saveSources(sources().map { if (it.id == id) it.copy(enabled = on) else it })

    fun addM3u(title: String, url: String) {
        val id = "m3u_" + UUID.randomUUID().toString().take(8)
        saveSources(sources() + SourceConfig(id, com.israk.ftptv.data.sources.SourceType.M3U, title, url, enabled = true, builtin = false, basePriority = 40))
    }

    fun removeSource(id: String) = saveSources(sources().filterNot { it.id == id && !it.builtin })

    /** Measured speed per server (ms, smoothed) and recent failures; used to pick the fastest server. */
    fun sourceScores(): MutableMap<String, Pair<Int, Int>> {
        val o = runCatching { JSONObject(sp.getString("scores", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val m = HashMap<String, Pair<Int, Int>>()
        o.keys().forEach { k -> val a = o.optJSONArray(k); if (a != null) m[k] = a.optInt(0) to a.optInt(1) }
        return m
    }

    fun saveSourceScores(m: Map<String, Pair<Int, Int>>) {
        val o = JSONObject(); m.forEach { (k, v) -> o.put(k, JSONArray().put(v.first).put(v.second)) }
        sp.edit().putString("scores", o.toString()).apply()
    }

    /** Server the user picked by hand for a channel (overrides automatic choice until changed). */
    fun pinnedSource(channelId: String): String? = sp.getString("pin_$channelId", null)
    fun setPinnedSource(channelId: String, sourceKey: String?) {
        val e = sp.edit()
        if (sourceKey == null) e.remove("pin_$channelId") else e.putString("pin_$channelId", sourceKey)
        e.apply()
    }

    // ── favorites ──
    private val _favs = MutableStateFlow(loadFavs())
    val favorites: StateFlow<List<FavGroup>> = _favs

    private fun loadFavs(): List<FavGroup> {
        val raw = sp.getString("favorites", null) ?: return listOf(FavGroup("default", "Favorites", emptyList()))
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val ids = o.getJSONArray("ids")
                FavGroup(o.getString("id"), o.getString("name"), (0 until ids.length()).map { ids.getString(it) })
            }
        }.getOrDefault(emptyList()).ifEmpty { listOf(FavGroup("default", "Favorites", emptyList())) }
    }

    fun setFavorites(groups: List<FavGroup>) {
        val a = JSONArray()
        groups.forEach { g -> a.put(JSONObject().put("id", g.id).put("name", g.name).put("ids", JSONArray(g.ids))) }
        sp.edit().putString("favorites", a.toString()).apply()
        _favs.value = groups
    }

    fun isFavorite(channelId: String) = _favs.value.any { channelId in it.ids }
    fun groupsOf(channelId: String) = _favs.value.filter { channelId in it.ids }

    fun toggleInGroup(groupId: String, channelId: String) {
        setFavorites(_favs.value.map { g ->
            if (g.id != groupId) g else if (channelId in g.ids) g.copy(ids = g.ids - channelId) else g.copy(ids = g.ids + channelId)
        })
    }

    fun addGroup(name: String): FavGroup {
        val g = FavGroup(UUID.randomUUID().toString().take(8), name.ifBlank { "Group" }, emptyList())
        setFavorites(_favs.value + g)
        return g
    }

    fun renameGroup(id: String, name: String) = setFavorites(_favs.value.map { if (it.id == id) it.copy(name = name) else it })

    fun deleteGroup(id: String) {
        val left = _favs.value.filterNot { it.id == id }
        setFavorites(left.ifEmpty { listOf(FavGroup("default", "Favorites", emptyList())) })
    }

    fun moveGroup(id: String, delta: Int) {
        val l = _favs.value.toMutableList()
        val i = l.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in l.indices) return
        l.add(j, l.removeAt(i))
        setFavorites(l)
    }

    fun moveInGroup(groupId: String, channelId: String, delta: Int) {
        setFavorites(_favs.value.map { g ->
            if (g.id != groupId) return@map g
            val ids = g.ids.toMutableList()
            val i = ids.indexOf(channelId); val j = i + delta
            if (i < 0 || j !in ids.indices) return@map g
            ids.add(j, ids.removeAt(i))
            g.copy(ids = ids)
        })
    }

    fun moveToGroup(fromId: String, toId: String, channelId: String) {
        setFavorites(_favs.value.map { g ->
            when (g.id) {
                fromId -> g.copy(ids = g.ids - channelId)
                toId -> if (channelId in g.ids) g else g.copy(ids = g.ids + channelId)
                else -> g
            }
        })
    }
}
