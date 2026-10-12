package com.malick.antenna.tv.data

import org.json.JSONArray
import org.json.JSONObject

data class AccountInfo(
    val status: String,
    val expiry: Long?,
    val maxConnections: Int?,
    val formats: List<String>,
) {
    val isActive get() = status.equals("active", ignoreCase = true)
}

data class LiveCategory(val id: String, val name: String) {
    val isAdult: Boolean
        get() = name.lowercase().let { n -> listOf("adult", "xxx", "18+", "+18", "porn").any { it in n } }

    companion object {
        const val ALL = "__all"
        const val FAVORITES = "__fav"
    }
}

data class Channel(
    val id: Int,
    val num: Int,
    val name: String,
    val displayName: String,
    val quality: String?,
    val iconUrl: String?,
    val categoryId: String,
    val epgId: String?,
) {
    /** Stable tint per channel, same idea as the Apple apps. */
    val hue: Float get() = (name.fold(2166136261L) { h, c -> ((h xor c.code.toLong()) * 16777619L) and 0xFFFFFFFFL } % 360).toFloat()

    val monogram: String
        get() {
            if (displayName.length <= 5) return displayName.uppercase()
            val sb = StringBuilder()
            for (w in displayName.split(' ', '-').filter { it.isNotEmpty() }) {
                if (w.all { it.isDigit() }) sb.append(w) else {
                    sb.append(w.first().uppercaseChar())
                    if ('+' in w) sb.append('+')
                }
            }
            return sb.toString().take(4)
        }

    fun matches(q: String) = displayName.contains(q, ignoreCase = true) || num.toString() == q

    companion object {
        private val prefix = Regex("""^\|?\s*[A-Z]{2,4}\s*[|:]\|?\s*""")
        private val tags = listOf(
            Regex("""\b(4K|UHD|2160P?)\b""", RegexOption.IGNORE_CASE) to "4K",
            Regex("""\b(FHD|1080P?)\b""", RegexOption.IGNORE_CASE) to "FHD",
            Regex("""\b(HD|720P?)\b""", RegexOption.IGNORE_CASE) to "HD",
            Regex("""\bSD\b""", RegexOption.IGNORE_CASE) to "SD",
            Regex("""\b(HEVC|H265|H\.265)\b""", RegexOption.IGNORE_CASE) to null,
        )

        /** "FR| TF1 FHD" -> ("TF1", "FHD"). */
        fun clean(raw: String): Pair<String, String?> {
            var n = raw.trim().replaceFirst(prefix, "")
            var quality: String? = null
            for ((re, label) in tags) {
                val m = re.find(n) ?: continue
                if (quality == null && label != null) quality = label
                n = n.removeRange(m.range)
            }
            n = n.replace(Regex("""[\[(]\s*[\])]"""), "").replace(Regex("""\s{2,}"""), " ").trim(' ', '|', '-', ':', '•')
            return (n.ifEmpty { raw }) to quality
        }
    }
}

data class Programme(val title: String, val summary: String, val start: Long, val end: Long) {
    fun isOn(now: Long) = now in start until end
    fun progress(now: Long) = if (end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
}

sealed interface EpgState {
    data object Loading : EpgState
    data class Loaded(val list: List<Programme>) : EpgState
    data object Unavailable : EpgState
    data object Failed : EpgState
}

data class Lineup(val account: AccountInfo, val categories: List<LiveCategory>, val channels: List<Channel>, val savedAt: Long)

// Lenient JSON helpers: Xtream panels send the same field as "12", 12 or 12.0.
internal fun JSONObject.str(key: String): String? = when (val v = opt(key)) {
    null, JSONObject.NULL -> null
    is Number -> if (v.toDouble() % 1.0 == 0.0) v.toLong().toString() else v.toString()
    else -> v.toString()
}

internal fun JSONObject.int(key: String): Int? = when (val v = opt(key)) {
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull() ?: v.toDoubleOrNull()?.toInt()
    is Boolean -> if (v) 1 else 0
    else -> null
}

internal fun JSONObject.long(key: String): Long? = when (val v = opt(key)) {
    is Number -> v.toLong()
    is String -> v.trim().toLongOrNull() ?: v.toDoubleOrNull()?.toLong()
    else -> null
}

// Serialization for the on-disk lineup cache.
internal fun Lineup.toJson(): String = JSONObject().apply {
    put("savedAt", savedAt)
    put("account", JSONObject().apply {
        put("status", account.status); put("expiry", account.expiry ?: JSONObject.NULL)
        put("maxConnections", account.maxConnections ?: JSONObject.NULL); put("formats", JSONArray(account.formats))
    })
    put("categories", JSONArray().apply { categories.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
    put("channels", JSONArray().apply {
        channels.forEach {
            put(JSONObject().put("id", it.id).put("num", it.num).put("name", it.name).put("displayName", it.displayName)
                .put("quality", it.quality ?: JSONObject.NULL).put("icon", it.iconUrl ?: JSONObject.NULL)
                .put("cat", it.categoryId).put("epg", it.epgId ?: JSONObject.NULL))
        }
    })
}.toString()

internal fun lineupFromJson(text: String): Lineup? = runCatching {
    val o = JSONObject(text)
    val a = o.getJSONObject("account")
    val formats = a.optJSONArray("formats")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
    val cats = o.getJSONArray("categories").let { arr -> List(arr.length()) { arr.getJSONObject(it) } }
        .map { LiveCategory(it.getString("id"), it.getString("name")) }
    val chans = o.getJSONArray("channels").let { arr -> List(arr.length()) { arr.getJSONObject(it) } }.map {
        Channel(it.getInt("id"), it.getInt("num"), it.getString("name"), it.getString("displayName"), it.str("quality"),
            it.str("icon"), it.getString("cat"), it.str("epg"))
    }
    Lineup(AccountInfo(a.getString("status"), a.long("expiry"), a.int("maxConnections"), formats), cats, chans, o.getLong("savedAt"))
}.getOrNull()
