package com.malick.antenna.tv.data.stremio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class StremioException(message: String, val signedOut: Boolean = false) : Exception(message)

/**
 * The Stremio account service, used the way Stremio's own apps use it: sign in with a link code
 * (so Facebook and Apple sign-in happen on Stremio's page, never here), then read the installed add-ons
 * and the library, and write watch progress back so every Stremio app stays in sync.
 */
class StremioApi {
    data class LinkCode(val code: String, val link: String)

    companion object {
        /** Overridable for the local test server; the real services otherwise. */
        @Volatile var apiBase = "https://api.strem.io/api/"
        @Volatile var linkBase = "https://link.stremio.com/api/v2/"

        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        private val json = "application/json".toMediaType()
    }

    // ---- Sign-in

    suspend fun createLink(): LinkCode {
        val r = get(linkBase + "create?type=Create").optJSONObject("result")
            ?: throw StremioException("Stremio didn't give a sign-in code. Try again in a moment.")
        return LinkCode(r.optString("code"), r.optString("link"))
    }

    /** The account key once someone signed in with the code; null while still waiting. */
    suspend fun readLink(code: String): String? =
        get(linkBase + "read?type=Read&code=$code").optJSONObject("result")?.optString("authKey")?.takeIf { it.isNotEmpty() }

    // ---- Account

    suspend fun user(authKey: String): StremioUser {
        val r = call("getUser", JSONObject().put("type", "GetUser").put("authKey", authKey)) as? JSONObject
            ?: throw StremioException("Stremio sent an unexpected answer.")
        return StremioUser(r.optString("email"), r.str("avatar"))
    }

    suspend fun addons(authKey: String): List<Addon> {
        val r = call("addonCollectionGet", JSONObject().put("type", "AddonCollectionGet").put("authKey", authKey).put("update", true)) as? JSONObject
        val arr = r?.optJSONArray("addons") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Addon::parse) }
    }

    suspend fun library(authKey: String): List<LibraryItem> {
        val r = call("datastoreGet", JSONObject().put("authKey", authKey).put("collection", "libraryItem")
            .put("ids", JSONArray()).put("all", true)) as? JSONArray ?: return emptyList()
        return (0 until r.length()).mapNotNull { i ->
            r.optJSONObject(i)?.takeIf { it.has("_id") && it.has("state") }?.let(::LibraryItem)
        }
    }

    suspend fun putLibrary(authKey: String, items: List<LibraryItem>) {
        if (items.isEmpty()) return
        call("datastorePut", JSONObject().put("authKey", authKey).put("collection", "libraryItem")
            .put("changes", JSONArray().apply { items.forEach { put(it.json) } }))
    }

    // ---- Plumbing

    private suspend fun call(method: String, body: JSONObject): Any? {
        val o = post(apiBase + method, body)
        o.optJSONObject("error")?.let { e ->
            val message = e.optString("message")
            val signedOut = e.optInt("code") == 1 || message.contains("session", ignoreCase = true)
            throw StremioException(if (signedOut) "Your Stremio sign-in expired. Sign in again." else "Stremio: $message", signedOut)
        }
        return o.opt("result")
    }

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url).build())
    }

    private suspend fun post(url: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url).post(body.toString().toRequestBody(json)).build())
    }

    private fun execute(request: Request): JSONObject {
        try {
            http.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful && text.isBlank()) throw StremioException("Stremio returned an error (HTTP ${r.code}).")
                return runCatching { JSONObject(text) }.getOrElse { throw StremioException("Stremio sent an unexpected answer.") }
            }
        } catch (e: IOException) {
            throw StremioException("Can't reach Stremio. Check the TV's internet connection.")
        }
    }
}

/** Fetches catalogs, details, sources and subtitles from add-ons over the Stremio add-on protocol. */
object AddonClient {
    private val http: OkHttpClient = StremioApi.http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun catalog(addon: Addon, catalog: CatalogDef, extra: List<Pair<String, String>> = emptyList()): List<MetaPreview> {
        val o = fetch(addon.url("catalog", catalog.type, catalog.id, extra))
        val arr = o.optJSONArray("metas") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let(MetaPreview::parse)?.let { if (it.type.isEmpty()) it.copy(type = catalog.type) else it }
        }
    }

    suspend fun meta(addon: Addon, type: String, id: String): MetaDetail? =
        fetch(addon.url("meta", type, id)).optJSONObject("meta")?.let(MetaDetail::parse)

    suspend fun streams(addon: Addon, type: String, videoId: String): List<Stream> {
        val arr = fetch(addon.url("stream", type, videoId)).optJSONArray("streams") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> Stream.parse(o, addon.name) } }
    }

    suspend fun subtitles(addon: Addon, type: String, videoId: String, extra: List<Pair<String, String>>): List<Subtitle> {
        val arr = fetch(addon.url("subtitles", type, videoId, extra)).optJSONArray("subtitles") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Subtitle::parse) }
    }

    private suspend fun fetch(url: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            JSONObject(r.body?.string().orEmpty())
        }
    }
}
