package com.malick.antenna.tv.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class XtreamException(message: String) : Exception(message) {
    companion object {
        val badUrl = XtreamException("That server URL doesn't look right. It usually looks like http://example.com:8080.")
        val unreachable = XtreamException("Can't reach the server. Check the URL and your internet connection.")
        val badCredentials = XtreamException("The server rejected that username or password.")
        val badResponse = XtreamException("The server answered, but not like an IPTV server. Check the URL.")
        fun inactive(status: String) = XtreamException("Your subscription is ${status.lowercase()}. Contact your provider to renew it.")
        fun http(code: Int) = XtreamException("The server returned an error (HTTP $code). Try again in a moment.")
    }
}

/** Talks to an Xtream Codes `player_api.php` endpoint. */
class XtreamClient(val server: HttpUrl, val username: String, val password: String) {

    companion object {
        /** Same agent VLC sends, which providers accept. */
        const val USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"

        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        /** Accepts "host:port", "http://host:port/", or a full get.php / player_api.php link. */
        fun normalize(raw: String): HttpUrl? {
            var s = raw.trim()
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "http://$s"
            val url = s.toHttpUrlOrNull() ?: return null
            return url.newBuilder().encodedPath("/").query(null).fragment(null).username("").password("").build()
        }
    }

    private fun api(action: String?, vararg extra: Pair<String, String>): HttpUrl =
        server.newBuilder().addPathSegment("player_api.php")
            .addQueryParameter("username", username)
            .addQueryParameter("password", password)
            .apply { if (action != null) addQueryParameter("action", action) }
            .apply { extra.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()

    private suspend fun fetch(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        try {
            http.newCall(request).execute().use { r ->
                if (r.code == 401 || r.code == 403) throw XtreamException.badCredentials
                if (!r.isSuccessful) throw XtreamException.http(r.code)
                r.body?.string() ?: ""
            }
        } catch (e: XtreamException) {
            throw e
        } catch (e: IOException) {
            throw XtreamException.unreachable
        }
    }

    suspend fun authenticate(): AccountInfo {
        val text = fetch(api(null)).trim()
        if (text.isEmpty() || text.startsWith("[")) throw XtreamException.badCredentials
        val root = runCatching { JSONObject(text) }.getOrElse { throw XtreamException.badResponse }
        val u = root.optJSONObject("user_info") ?: throw XtreamException.badCredentials
        if (u.int("auth") != 1) throw XtreamException.badCredentials
        val formats = u.optJSONArray("allowed_output_formats")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
        val info = AccountInfo(u.str("status") ?: "Unknown", u.long("exp_date")?.takeIf { it > 0 }, u.int("max_connections"), formats)
        if (!info.isActive) throw XtreamException.inactive(info.status)
        return info
    }

    suspend fun liveCategories(): List<LiveCategory> {
        val arr = runCatching { JSONArray(fetch(api("get_live_categories"))) }.getOrElse { throw XtreamException.badResponse }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.str("category_id") ?: return@mapNotNull null
            LiveCategory(id, o.str("category_name") ?: "Untitled")
        }
    }

    suspend fun liveStreams(): List<Channel> = withContext(Dispatchers.Default) {
        val arr = runCatching { JSONArray(fetch(api("get_live_streams"))) }.getOrElse { throw XtreamException.badResponse }
        val seen = HashSet<Int>()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.int("stream_id") ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val name = o.str("name") ?: "Channel $id"
            val (clean, quality) = Channel.clean(name)
            Channel(
                id = id,
                num = o.int("num") ?: (i + 1),
                name = name,
                displayName = clean,
                quality = quality,
                iconUrl = o.str("stream_icon")?.takeIf { it.startsWith("http") },
                categoryId = o.str("category_id") ?: "",
                epgId = o.str("epg_channel_id")?.takeIf { it.isNotEmpty() },
            )
        }
    }

    suspend fun shortEpg(streamId: Int, limit: Int = 14): List<Programme> {
        val text = fetch(api("get_short_epg", "stream_id" to streamId.toString(), "limit" to limit.toString()))
        val list = runCatching { JSONObject(text).optJSONArray("epg_listings") }.getOrNull() ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val start = o.long("start_timestamp") ?: return@mapNotNull null
            val end = o.long("stop_timestamp") ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            Programme(unbase64(o.str("title") ?: ""), unbase64(o.str("description") ?: ""), start * 1000, end * 1000)
        }.sortedBy { it.start }
    }

    fun streamUrl(channel: Channel, ext: String): String =
        server.newBuilder().addPathSegment("live").addPathSegment(username).addPathSegment(password)
            .addPathSegment("${channel.id}.$ext").build().toString()

    /** Short EPG titles are base64 on most panels, plain text on a few. */
    private fun unbase64(s: String): String {
        if (s.isEmpty()) return s
        return runCatching { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8) }
            .getOrNull()?.takeIf { it.isNotBlank() && !it.contains('�') } ?: s
    }
}
