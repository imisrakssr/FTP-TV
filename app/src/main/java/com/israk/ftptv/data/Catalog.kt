package com.israk.ftptv.data

import com.israk.ftptv.data.Category.DISCOVERY
import com.israk.ftptv.data.Category.ENTERTAINMENT
import com.israk.ftptv.data.Category.ISLAMIC
import com.israk.ftptv.data.Category.KIDS
import com.israk.ftptv.data.Category.MOVIES
import com.israk.ftptv.data.Category.MUSIC
import com.israk.ftptv.data.Category.NEWS
import com.israk.ftptv.data.Category.SPORTS
import java.net.URLDecoder

/**
 * The allow-list. Order here = channel numbers. Rules (agreed with Israk):
 * Bangladesh almost all · India only Bangla + Hindi · Pakistan · relevant English · Islamic.
 * News = Bangladesh only. Sports = cricket & football (+ Eurosport/beIN/SSC kept). No food, TLC or Travel XP.
 */
object Catalog {

    private fun c(id: String, name: String, region: Region, cats: Set<Category>, vararg aliases: String) =
        CatalogChannel(id, name, cats, region, listOf(name) + aliases)

    private val ENT = setOf(ENTERTAINMENT)
    private val NWS = setOf(NEWS)
    private val SPT = setOf(SPORTS)
    private val MOV = setOf(MOVIES)
    private val MUS = setOf(MUSIC)
    private val KID = setOf(KIDS)
    private val DOC = setOf(DISCOVERY)
    private val ISL = setOf(ISLAMIC)

    val all: List<CatalogChannel> = listOf(
        // ── Bangladesh: entertainment ──
        c("btv", "BTV", Region.BD, ENT, "BTV National", "BTV World", "BTV.HD", "Bangladesh Television"),
        c("ntv", "NTV", Region.BD, ENT, "NTV Live", "NTVLive1"),
        c("rtv", "RTV", Region.BD, ENT),
        c("banglavision", "Bangla Vision", Region.BD, ENT, "BanglaV", "Banglavision"),
        c("boishakhi", "Boishakhi TV", Region.BD, ENT, "Boisakhi TV", "Baishakhi TV", "Boishakhi"),
        c("atnbangla", "ATN Bangla", Region.BD, ENT, "Atnbd", "atnbangla"),
        c("channeli", "Channel i", Region.BD, ENT, "ChannelI", "Channel I HD"),
        c("maasranga", "Maasranga TV", Region.BD, ENT, "Masranga", "Maasranga", "Mashranga"),
        c("deepto", "Deepto TV", Region.BD, ENT, "Deeptho", "Deepto", "DeeptoTV"),
        c("bijoy", "Bijoy TV", Region.BD, ENT, "Bijoy"),
        c("nagorik", "Nagorik TV", Region.BD, ENT, "Nagorik"),
        c("gtv", "Gazi TV (GTV)", Region.BD, ENT + SPT, "GTV", "Gazi TV", "GTV Live", "Gazi"),
        c("etv", "Ekushey TV (ETV)", Region.BD, ENT, "ETV", "ETV Bangla", "Ekushey TV", "Ekushey"),
        c("banglatv", "Bangla TV", Region.BD, ENT),
        c("deshtv", "Desh TV", Region.BD, ENT, "DeshTV"),
        c("channel9", "Channel 9", Region.BD, ENT, "Ch9"),
        c("mohona", "Mohona TV", Region.BD, ENT, "Mohona"),
        c("mytv", "My TV", Region.BD, ENT, "MyTV"),
        c("asiantv", "Asian TV", Region.BD, ENT, "Asiah TV", "Asian"),
        c("globaltv", "Global TV", Region.BD, ENT, "Global Television"),
        c("satv", "SA TV", Region.BD, ENT),
        c("ananda", "Ananda TV", Region.BD, ENT),
        c("channels", "Channel S", Region.BD, ENT),
        c("sangsad", "Sangsad TV", Region.BD, ENT, "Sangsad Television"),
        c("gseries", "Gseries Drama", Region.BD, ENT, "G Series Drama"),

        // ── Bangladesh: news (the only news allowed) ──
        c("somoy", "Somoy TV", Region.BD, NWS, "Somoy News TV", "somynews", "SomoyTV", "Somoy News"),
        c("jamuna", "Jamuna TV", Region.BD, NWS, "JamunaTv", "Jamuna"),
        c("channel24", "Channel 24", Region.BD, NWS, "Ch24", "Channel24"),
        c("ekattor", "Ekattor TV", Region.BD, NWS, "Ekattor", "ektatur", "71 TV"),
        c("independent", "Independent TV", Region.BD, NWS, "Independent Television", "indopendettv"),
        c("ekhon", "Ekhon TV", Region.BD, NWS, "Eakhon TV", "EKHONTV", "Ekhon"),
        c("dbc", "DBC News", Region.BD, NWS, "DBC", "Dbc News HD"),
        c("news24", "News24", Region.BD, NWS, "News 24", "NEWS.24.HD", "NEWS24LIVE"),
        c("atnnews", "ATN News", Region.BD, NWS, "atnnews"),

        // ── Kolkata (India, Bangla) ──
        c("zeebangla", "Zee Bangla", Region.KOLKATA, ENT, "Zee-Bangla"),
        c("starjalsha", "Star Jalsha", Region.KOLKATA, ENT, "Star Jolsha", "Star-Jalsha"),
        c("colorsbangla", "Colors Bangla", Region.KOLKATA, ENT, "Colors-Bangla"),
        c("sunbangla", "Sun Bangla", Region.KOLKATA, ENT),
        c("enterr10", "Enterr10 Bangla", Region.KOLKATA, ENT, "Enter10 Bangla", "Enterr 10", "Enterr 10 Bangla", "Enter 10 Bangla"),
        c("sonyaath", "Sony Aath", Region.KOLKATA, ENT, "Sony Aat", "Sony Art", "SonyAath", "Sony-Aath"),

        // ── Hindi ──
        c("starplus", "Star Plus", Region.HINDI, ENT, "Star-plus", "StarPlus"),
        c("starbharat", "Star Bharat", Region.HINDI, ENT),
        c("zeetv", "Zee TV", Region.HINDI, ENT),
        c("colors", "Colors", Region.HINDI, ENT, "Colors Hindi", "Colors-Hindi", "Colors TV"),
        c("sonyent", "Sony Entertainment", Region.HINDI, ENT, "Sony Ent", "SonyEnt", "Sony TV", "SonyEntertainmentTelevision"),
        c("sonysab", "Sony SAB", Region.HINDI, ENT, "SAB TV"),

        // ── Pakistan ──
        c("humtv", "Hum TV", Region.PAKISTAN, ENT),
        c("aplus", "A-Plus TV", Region.PAKISTAN, ENT, "A Plus", "APlus TV"),
        c("zindagi", "Zindagi TV", Region.PAKISTAN, ENT, "Zindagi"),

        // ── English entertainment ──
        c("axn", "AXN", Region.ENGLISH, ENT),
        c("zeecafe", "Zee Cafe", Region.ENGLISH, ENT),

        // ── Movies ──
        c("jalshamovies", "Jalsha Movies", Region.KOLKATA, MOV, "Jalsha-Movies"),
        c("zeebanglacinema", "Zee Bangla Cinema", Region.KOLKATA, MOV),
        c("colorsbanglacinema", "Colors Bangla Cinema", Region.KOLKATA, MOV),
        c("stargold", "Star Gold", Region.HINDI, MOV, "Star-Gold"),
        c("stargold2", "Star Gold 2", Region.HINDI, MOV, "STAR-GOLD2-HD", "Star Gold2"),
        c("stargoldselect", "Star Gold Select", Region.HINDI, MOV, "Star Gold Sel", "STAR-GOLD-SEL-HD"),
        c("zeecinema", "Zee Cinema", Region.HINDI, MOV, "Zee-Cinema"),
        c("sonymax", "Sony Max", Region.HINDI, MOV, "Max 1", "Sony Max 1"),
        c("sonymax2", "Sony Max 2", Region.HINDI, MOV, "Max 2"),
        c("colorscineplex", "Colors Cineplex", Region.HINDI, MOV, "ColorCineplex", "Colors Cineplex HD"),
        c("andpictures", "&Pictures", Region.HINDI, MOV, "And Pictures", "And Picture", "And Picturs", "Ant Picturs", "&Picture"),
        c("starmovies", "Star Movies", Region.ENGLISH, MOV, "Star-Movie", "Star Movie", "STARMovies", "Star-Movies"),
        c("starmoviesselect", "Star Movies Select", Region.ENGLISH, MOV, "Star Movies Sel"),
        c("sonypix", "Sony Pix", Region.ENGLISH, MOV, "sony-Pix"),
        c("moviesnow", "Movies Now", Region.ENGLISH, MOV),
        c("mnplus", "MN+", Region.ENGLISH, MOV, "MN Plus", "MNPlus"),
        c("mnx", "MNX", Region.ENGLISH, MOV),
        c("andprive", "&Prive", Region.ENGLISH, MOV, "And Prive"),
        c("andflix", "&flix", Region.ENGLISH, MOV, "And Flix"),
        c("romedynow", "Romedy Now", Region.ENGLISH, MOV),

        // ── Music ──
        c("9xm", "9XM", Region.HINDI, MUS, "9XM Music"),
        c("zing", "Zing", Region.HINDI, MUS, "Zing Music"),
        c("zoom", "Zoom", Region.HINDI, MUS, "Zoom Music"),
        c("sangeetbangla", "Sangeet Bangla", Region.KOLKATA, MUS, "sangeetbangla"),
        c("mtvindia", "MTV India", Region.HINDI, MUS, "MTV"),
        c("musicindia", "Music India", Region.HINDI, MUS),

        // ── Sports ──
        c("tsports", "T Sports", Region.BD, SPT, "T-Sports", "Tsports", "Tspots", "Tspots 1", "Tspots 2", "Tspots 3", "Tspots 4", "Tspots 5", "Tspots 6", "T Sports Bangladesh"),
        c("tsports2", "T Sports 2", Region.BD, SPT, "T Sports HD 2", "T-Sports 2"),
        c("starsports1", "Star Sports 1", Region.INTL, SPT, "Star-sports1", "Star Sports", "StarSports1"),
        c("starsports2", "Star Sports 2", Region.INTL, SPT, "Star-Sports2", "StarSports2"),
        c("starsports3", "Star Sports 3", Region.INTL, SPT, "StarSports 3", "Star-Sports 3"),
        c("starsel1", "Star Sports Select 1", Region.INTL, SPT, "Star Sports Sel1", "Star Sports Sel 1", "Star Select 1", "Star Sports SL 1", "Star-Sports-Select1"),
        c("starsel2", "Star Sports Select 2", Region.INTL, SPT, "Star Sports Sel 2", "Star Sports Sel2", "Star Select 2", "Star Sports SL 2", "Star-Sports-Select2"),
        c("sonysports1", "Sony Sports Ten 1", Region.INTL, SPT, "Sony Sports 1", "Sony Ten 1", "SonyTen 1", "sonyten1", "SonyTenSports1"),
        c("sonysports2", "Sony Sports Ten 2", Region.INTL, SPT, "Sony Sports 2", "Sony Ten 2", "SonyTen 2", "sonyten2", "SonyTenSports2"),
        c("sonysports3", "Sony Sports Ten 3", Region.INTL, SPT, "Sony Sports 3", "Sony Ten 3", "sonyten3", "SONY-3", "SonyTenCricket", "TenCricket"),
        c("sonysports4", "Sony Sports Ten 4", Region.INTL, SPT, "Sony Sports 4", "Sony Ten 4", "sonyten4"),
        c("sonysports5", "Sony Sports Ten 5", Region.INTL, SPT, "Sony Sports 5", "Sony Ten 5", "sonyten5", "SONY-5HD"),
        c("ptvsports", "PTV Sports", Region.PAKISTAN, SPT, "PTV", "PTV 1", "PTV 2", "PTV-Sports"),
        c("asports", "A Sports", Region.PAKISTAN, SPT, "Asports", "A-Sports"),
        c("willow", "Willow", Region.INTL, SPT, "Willow Sports", "Willow Cricket"),
        c("fastsports", "Fast Sports", Region.INTL, SPT),
        c("unite8", "Unite8 Sports", Region.INTL, SPT, "Unite8 Sports 1", "Unite 8"),
        c("tntsports1", "TNT Sports 1", Region.INTL, SPT),
        c("tntsports2", "TNT Sports 2", Region.INTL, SPT),
        c("tntsports3", "TNT Sports 3", Region.INTL, SPT),
        c("tntsports4", "TNT Sports 4", Region.INTL, SPT),
        c("eurosport", "Eurosport", Region.INTL, SPT, "Eurosports", "Euro Sports"),
        c("bein1", "beIN Sports 1", Region.INTL, SPT, "Bein 1", "BeinSports", "Bein Sports", "Bein1"),
        c("ssc", "SSC Sports", Region.INTL, SPT, "SSC"),
        c("ssc5", "SSC Sports 5", Region.INTL, SPT, "SSC 5"),

        // ── Kids ──
        c("duronto", "Duronto TV", Region.BD, KID, "Duranta TV", "Duronto"),
        c("cartoonnetwork", "Cartoon Network", Region.INTL, KID, "Carton Network", "CartoonNetwork", "CN"),
        c("pogo", "Pogo", Region.INTL, KID),
        c("nick", "Nick", Region.INTL, KID, "Nickelodeon"),
        c("nickjr", "Nick Jr", Region.INTL, KID, "Nick Junior"),
        c("sonic", "Sonic", Region.INTL, KID),
        c("hungama", "Hungama", Region.INTL, KID, "Hungama TV"),
        c("superhungama", "Super Hungama", Region.INTL, KID),
        c("sonyyay", "Sony Yay", Region.INTL, KID, "Sony Yay!"),
        c("disney", "Disney Channel", Region.INTL, KID, "Disney", "Disney Kids"),
        c("disneyjr", "Disney Junior", Region.INTL, KID, "Disney Juniar", "Disney-junior", "Disney Jr"),
        c("balbharat", "ETV Bal Bharat", Region.INTL, KID, "Bal Bharat"),

        // ── Discovery / Nat Geo / science ──
        c("discovery", "Discovery", Region.INTL, DOC, "Discovery Channel", "Discovey"),
        c("natgeo", "Nat Geo", Region.INTL, DOC, "NatGeo", "National Geographic", "National Geography"),
        c("natgeowild", "Nat Geo Wild", Region.INTL, DOC, "NatGeo Wild"),
        c("animalplanet", "Animal Planet", Region.INTL, DOC, "AnimalPlannet"),
        c("history", "History TV18", Region.INTL, DOC, "History TV 18", "History TV", "History"),
        c("sonybbcearth", "Sony BBC Earth", Region.INTL, DOC),
        c("bbcearth", "BBC Earth", Region.INTL, DOC, "BBCEarth"),
        c("andxplor", "&Xplor", Region.INTL, DOC, "And Xplor", "And Xplore", "Xplor"),
        c("lovenature", "Love Nature", Region.INTL, DOC),

        // ── Islamic ──
        c("peacetv", "Peace TV Bangla", Region.BD, ISL, "Peace TV"),
        c("madani", "Madani Channel Bangla", Region.BD, ISL, "Madani TV", "Madani Channel"),
        c("makkah", "Makkah Live (Quran TV)", Region.INTL, ISL, "Al Quran Al Kareem TV", "Saudi Quran", "Quran TV"),
        c("madinah", "Madinah Live (Sunnah TV)", Region.INTL, ISL, "Al Sunnah Al Nabawiyah TV", "Saudi Sunnah", "Sunnah TV"),
    )

    private val byKey: Map<String, CatalogChannel> = buildMap {
        for (ch in all) for (a in ch.aliases) for (k in variants(a)) putIfAbsent(k, ch)
    }

    fun match(rawName: String): CatalogChannel? {
        for (k in variants(rawName)) byKey[k]?.let { return it }
        return null
    }

    private val stop = setOf("hd", "fhd", "uhd", "sd", "hq", "live", "bdix", "nix", "backup", "official", "1080p", "720p", "hevc", "new", "channel_")
    private val digitHd = Regex("^(\\d+)hd$")

    /** A name reduced to a few comparable keys: "SONY-SPORTS.5HD" → sonysports5, "Jamunatv" → jamunatv / jamuna. */
    fun variants(name: String): List<String> {
        val tokens = name.lowercase()
            .replace("&", " and ")
            .replace("+", " plus ")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() && it !in stop }
            .map { t -> digitHd.matchEntire(t)?.groupValues?.get(1) ?: t }
        if (tokens.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        fun add(s: String) {
            var k = s
            while (k.length > 4 && k.endsWith("hd")) k = k.dropLast(2)
            if (k.isNotEmpty()) out += k
        }
        val full = tokens.joinToString("")
        add(full)
        val noTv = tokens.filter { it != "tv" && it != "television" }.joinToString("")
        add(noTv)
        if (full.length > 4 && full.endsWith("tv")) add(full.dropLast(2))
        return out.toList()
    }

    /** "Zee%20Bangla%20Cinema1664976800.png" → "Zee Bangla Cinema". */
    fun nameFromFile(path: String): String {
        val file = runCatching { URLDecoder.decode(path.substringAfterLast('/'), "UTF-8") }.getOrDefault(path.substringAfterLast('/'))
        return file.substringBeforeLast('.')
            .replace(Regex("\\d{6,}"), "")
            .replace('-', ' ').replace('_', ' ')
            .trim()
    }
}
