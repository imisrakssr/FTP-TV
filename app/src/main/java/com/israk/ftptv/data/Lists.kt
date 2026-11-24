package com.israk.ftptv.data

/** The channel lists the user can zap through: Favorites groups, All, and every category. */
object Lists {
    data class Entry(val key: String, val label: String, val isFav: Boolean = false)

    fun entries(groups: List<FavGroup>): List<Entry> =
        groups.map { Entry("fav:" + it.id, "★ " + it.name, isFav = true) } +
            Entry("all", "All channels") +
            Category.entries.map { Entry(it.name, it.label) }

    fun channels(key: String, all: List<Channel>, groups: List<FavGroup>): List<Channel> = when {
        key == "all" -> all
        key.startsWith("fav:") -> {
            val g = groups.firstOrNull { it.id == key.removePrefix("fav:") } ?: return emptyList()
            val byId = all.associateBy { it.id }
            g.ids.mapNotNull { byId[it] }
        }
        else -> {
            val cat = runCatching { Category.valueOf(key) }.getOrNull() ?: return all
            all.filter { cat in it.info.categories }
        }
    }

    fun label(key: String, groups: List<FavGroup>): String = entries(groups).firstOrNull { it.key == key }?.label ?: "All channels"
}
