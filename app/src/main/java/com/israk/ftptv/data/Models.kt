package com.israk.ftptv.data

import org.json.JSONObject

enum class Category(val label: String) {
    ENTERTAINMENT("Entertainment"),
    MOVIES("Movies"),
    MUSIC("Music"),
    NEWS("News"),
    SPORTS("Sports"),
    KIDS("Kids"),
    DISCOVERY("Discovery"),
    ISLAMIC("Islamic"),
}

enum class Region(val label: String) { BD("Bangladesh"), KOLKATA("Kolkata"), HINDI("Hindi"), PAKISTAN("Pakistan"), ENGLISH("English"), INTL("International") }

/** A channel the app knows about (the allow-list). Anything a server has that isn't here is never shown. */
data class CatalogChannel(
    val id: String,
    val name: String,
    val categories: Set<Category>,
    val region: Region,
    val aliases: List<String>,
)

/**
 * One way to watch a channel on one server. [key] is what the server needs to give us a playable URL
 * (a stream id, a slug, or the URL itself when it never changes).
 */
data class StreamRef(
    val sourceId: String,
    val key: String,
    val directUrl: String? = null,
    val rawName: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("s", sourceId).put("k", key).put("u", directUrl ?: "").put("n", rawName)

    companion object {
        fun fromJson(o: JSONObject) = StreamRef(
            o.getString("s"), o.getString("k"), o.optString("u").ifBlank { null }, o.optString("n"),
        )
    }
}

/** A catalog channel plus every server that currently carries it. */
data class Channel(
    val info: CatalogChannel,
    val number: Int,
    val logo: String?,
    val streams: List<StreamRef>,
) {
    val id get() = info.id
    val name get() = info.name
}

/** What a server gives back for a stream: a URL plus any headers it insists on. */
data class Playable(val url: String, val headers: Map<String, String> = emptyMap(), val sourceId: String)

/** What a server listing returns before matching to the catalog. */
data class RawStream(val name: String, val ref: StreamRef, val logo: String? = null)
