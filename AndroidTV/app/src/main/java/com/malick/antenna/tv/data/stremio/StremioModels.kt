package com.malick.antenna.tv.data.stremio

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

// Stremio's data, as its own apps read it: add-ons (the open add-on protocol) and the account's library.

data class ManifestResource(val name: String, val types: List<String>?, val idPrefixes: List<String>?)

data class CatalogExtra(val name: String, val isRequired: Boolean, val options: List<String>)

data class CatalogDef(val addonId: String, val type: String, val id: String, val name: String, val extras: List<CatalogExtra>) {
    val key get() = "$addonId/$type/$id"
    val supportsSearch get() = extras.any { it.name == "search" }
    /** Catalogs that need a search term or a genre can't be shown as a plain row. */
    val needsInput get() = extras.any { it.isRequired }
}

data class Addon(
    val transportUrl: String,
    val id: String,
    val name: String,
    val version: String,
    val types: List<String>,
    val idPrefixes: List<String>?,
    val resources: List<ManifestResource>,
    val catalogs: List<CatalogDef>,
    val adult: Boolean,
    val configurationRequired: Boolean,
    /** The descriptor as the account returned it, for the offline cache. */
    val raw: JSONObject,
) {
    /** The manifest rule Stremio uses: a resource's own types and id prefixes, or else the add-on's. */
    fun provides(resource: String, type: String, id: String): Boolean {
        if (configurationRequired) return false
        val r = resources.firstOrNull { it.name == resource } ?: return false
        val types = r.types ?: this.types
        val prefixes = r.idPrefixes ?: this.idPrefixes
        if (type !in types) return false
        return prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) }
    }

    fun url(resource: String, type: String, id: String, extra: List<Pair<String, String>> = emptyList()): String {
        val base = transportUrl.substringBeforeLast("/manifest.json")
        val tail = if (extra.isEmpty()) "" else "/" + extra.joinToString("&") { (k, v) -> uriComponent(k) + "=" + uriComponent(v) }
        return "$base/$resource/${uriComponent(type)}/${uriComponent(id)}$tail.json"
    }

    companion object {
        fun parse(descriptor: JSONObject): Addon? {
            val m = descriptor.optJSONObject("manifest") ?: return null
            val transport = descriptor.optString("transportUrl").ifEmpty { return null }
            val id = m.optString("id")
            val types = m.optJSONArray("types").strings()
            val resources = buildList {
                val arr = m.optJSONArray("resources") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    when (val r = arr.opt(i)) {
                        is String -> add(ManifestResource(r, null, null))
                        is JSONObject -> add(ManifestResource(r.optString("name"),
                            r.optJSONArray("types")?.strings(), r.optJSONArray("idPrefixes")?.strings()))
                    }
                }
            }
            val catalogs = buildList {
                val arr = m.optJSONArray("catalogs") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val extras = mutableListOf<CatalogExtra>()
                    c.optJSONArray("extra")?.let { ex ->
                        for (j in 0 until ex.length()) {
                            val e = ex.optJSONObject(j) ?: continue
                            extras += CatalogExtra(e.optString("name"), e.optBoolean("isRequired"), e.optJSONArray("options").strings())
                        }
                    }
                    // Older add-ons list extras as plain names.
                    val required = c.optJSONArray("extraRequired").strings()
                    c.optJSONArray("extraSupported").strings().forEach { n ->
                        if (extras.none { it.name == n }) extras += CatalogExtra(n, n in required, emptyList())
                    }
                    add(CatalogDef(id, c.optString("type"), c.optString("id"), c.optString("name").ifEmpty { c.optString("id") }, extras))
                }
            }
            val hints = m.optJSONObject("behaviorHints")
            return Addon(transport, id, m.optString("name", id), m.optString("version"), types,
                m.optJSONArray("idPrefixes")?.strings(), resources, catalogs,
                hints?.optBoolean("adult") == true, hints?.optBoolean("configurationRequired") == true, descriptor)
        }
    }
}

data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val posterShape: String,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: String?,
    val genres: List<String>,
    val runtime: String?,
) {
    companion object {
        fun parse(o: JSONObject): MetaPreview? {
            val id = o.optString("id").ifEmpty { return null }
            return MetaPreview(
                id, o.optString("type"), o.optString("name").ifEmpty { id },
                o.str("poster"), o.optString("posterShape", "poster"), o.str("background"), o.str("logo"),
                o.str("description"), o.str("releaseInfo") ?: o.str("year"), o.str("imdbRating"),
                o.optJSONArray("genres").strings().ifEmpty { o.optJSONArray("genre").strings() }, o.str("runtime"),
            )
        }
    }
}

data class Video(
    val id: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val released: Long?,
    val thumbnail: String?,
    val overview: String?,
) {
    val label: String get() = if (season != null && episode != null) "S$season · E$episode" else title

    companion object {
        fun parse(o: JSONObject): Video? {
            val id = o.optString("id").ifEmpty { return null }
            return Video(
                id, o.str("title") ?: o.str("name") ?: id,
                o.optInt("season", -1).takeIf { it >= 0 }, o.optInt("episode", o.optInt("number", -1)).takeIf { it >= 0 },
                o.str("released")?.let(::parseDate), o.str("thumbnail"), o.str("overview") ?: o.str("description"),
            )
        }
    }
}

data class MetaDetail(val preview: MetaPreview, val videos: List<Video>) {
    val seasons: List<Int> get() = videos.mapNotNull { it.season }.distinct().sorted().let { s -> if (s.size > 1 && s.first() == 0) s.drop(1) + 0 else s }
    fun episodes(season: Int) = videos.filter { it.season == season }.sortedBy { it.episode ?: 0 }
    /** The video after this one, in watching order. */
    fun next(after: String): Video? {
        val ordered = videos.filter { (it.season ?: 0) > 0 }.sortedWith(compareBy({ it.season }, { it.episode })).ifEmpty { videos }
        val i = ordered.indexOfFirst { it.id == after }
        return if (i >= 0) ordered.getOrNull(i + 1) else null
    }

    companion object {
        fun parse(o: JSONObject): MetaDetail? {
            val p = MetaPreview.parse(o) ?: return null
            val videos = buildList {
                val arr = o.optJSONArray("videos") ?: JSONArray()
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(Video::parse)?.let(::add)
            }
            return MetaDetail(p, videos)
        }
    }
}

data class Stream(
    val json: JSONObject,
    val addonName: String,
    val name: String?,
    val description: String?,
    val url: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val sources: List<String>,
    val ytId: String?,
    val externalUrl: String?,
    val filename: String?,
    val videoSize: Long?,
    val bingeGroup: String?,
    val headers: Map<String, String>,
) {
    val isTorrent get() = infoHash != null
    val playable get() = url != null || infoHash != null
    /** "Torrentio\n4k HDR" → "Torrentio 4k HDR", for one-line labels. */
    val title: String get() = (name ?: addonName).replace('\n', ' ').trim()

    companion object {
        fun parse(o: JSONObject, addonName: String): Stream? {
            val hints = o.optJSONObject("behaviorHints")
            val headers = mutableMapOf<String, String>()
            hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { r -> r.keys().forEach { k -> headers[k] = r.optString(k) } }
            val s = Stream(
                o, addonName, o.str("name"), o.str("description") ?: o.str("title"), o.str("url"),
                o.str("infoHash")?.lowercase(), o.optInt("fileIdx", -1).takeIf { it >= 0 },
                (o.optJSONArray("sources") ?: o.optJSONArray("announce")).strings(),
                o.str("ytId"), o.str("externalUrl"), hints?.str("filename"),
                hints?.optLong("videoSize", -1)?.takeIf { it > 0 }, hints?.str("bingeGroup"), headers,
            )
            return if (s.playable || s.ytId != null || s.externalUrl != null) s else null
        }
    }
}

data class Subtitle(val id: String, val lang: String, val url: String) {
    companion object {
        fun parse(o: JSONObject): Subtitle? {
            val url = o.str("url") ?: return null
            return Subtitle(o.optString("id", url), o.optString("lang", "Unknown"), url)
        }
    }
}

/** A library entry exactly as Stremio stores it; unknown fields are kept so writing it back never loses anything. */
class LibraryItem(val json: JSONObject) {
    val id: String get() = json.optString("_id")
    val name: String get() = json.optString("name")
    val type: String get() = json.optString("type")
    val poster: String? get() = json.str("poster")
    val posterShape: String get() = json.optString("posterShape", "poster")
    val removed: Boolean get() = json.optBoolean("removed")
    val temp: Boolean get() = json.optBoolean("temp")
    val mtime: Long get() = json.str("_mtime")?.let(::parseDate) ?: 0
    private val state: JSONObject get() = json.optJSONObject("state") ?: JSONObject()
    val timeOffset: Long get() = state.optLong("timeOffset")
    val duration: Long get() = state.optLong("duration")
    val videoId: String? get() = state.str("video_id")
    val lastWatched: Long get() = state.str("lastWatched")?.let(::parseDate) ?: 0
    val progress: Float get() = if (duration > 0) (timeOffset.toFloat() / duration).coerceIn(0f, 1f) else 0f

    /** Stremio's own rule for the Continue Watching row. */
    val inContinueWatching get() = type != "other" && (!removed || temp) && timeOffset > 0
    val inLibrary get() = !removed && !temp

    /** Season and episode from a series video id like "tt0386676:5:1". */
    val episodeLabel: String?
        get() {
            val parts = videoId?.split(':') ?: return null
            if (parts.size < 3) return null
            val s = parts[parts.size - 2].toIntOrNull() ?: return null
            val e = parts.last().toIntOrNull() ?: return null
            return "S$s · E$e"
        }

    fun copy(edit: (JSONObject, JSONObject) -> Unit): LibraryItem {
        val o = JSONObject(json.toString())
        val st = o.optJSONObject("state") ?: JSONObject().also { o.put("state", it) }
        edit(o, st)
        o.put("_mtime", formatDate(System.currentTimeMillis()))
        return LibraryItem(o)
    }

    companion object {
        /** A new entry for something you started watching that isn't in your library (Stremio does the same). */
        fun create(meta: MetaPreview, inLibrary: Boolean): LibraryItem {
            val now = formatDate(System.currentTimeMillis())
            return LibraryItem(JSONObject()
                .put("_id", meta.id).put("name", meta.name).put("type", meta.type)
                .put("poster", meta.poster ?: JSONObject.NULL).put("posterShape", meta.posterShape)
                .put("removed", !inLibrary).put("temp", !inLibrary)
                .put("_ctime", now).put("_mtime", now)
                .put("state", JSONObject().put("lastWatched", now).put("timeWatched", 0).put("timeOffset", 0)
                    .put("overallTimeWatched", 0).put("timesWatched", 0).put("flaggedWatched", 0).put("duration", 0)
                    .put("video_id", JSONObject.NULL).put("watched", JSONObject.NULL).put("noNotif", false))
                .put("behaviorHints", JSONObject().put("defaultVideoId", JSONObject.NULL).put("featuredVideoId", JSONObject.NULL)
                    .put("hasScheduledVideos", false)))
        }
    }
}

data class StremioUser(val email: String, val avatar: String?)

// ---- helpers

internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

internal fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> (opt(i) as? String)?.takeIf { it.isNotEmpty() } }
}

/** RFC 3339 dates as Stremio writes them ("2026-10-11T18:25:00.123Z"); also plain dates. */
fun parseDate(s: String): Long? = runCatching { Instant.parse(s).toEpochMilli() }
    .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
    .recoverCatching { java.time.LocalDate.parse(s.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
    .getOrNull()

fun formatDate(ms: Long): String = Instant.ofEpochMilli(ms).toString()

/** JavaScript's encodeURIComponent, which add-on URLs are built with. */
fun uriComponent(s: String): String {
    val keep = "-_.!~*'()"
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xFF).toChar()
        if (c.isLetterOrDigit() && c.code < 128 || c in keep) sb.append(c) else sb.append('%').append("%02X".format(b.toInt() and 0xFF))
    }
    return sb.toString()
}
