package com.malick.antenna.tv.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.malick.antenna.tv.cast.CastService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/**
 * "Antenna Connect" from the phone: finds Antenna TV apps on the Wi-Fi (`_antenna._tcp`), sends them a channel,
 * movie or episode, and reads what they're playing so the phone can be the remote. Same protocol as the iPhone app.
 */
class TvLink(context: Context, private val scope: CoroutineScope) {
    data class Device(val name: String, val host: String, val port: Int) {
        val id get() = name
        /** "Antenna · Living room" → "Living room". */
        val displayName get() = name.removePrefix("Antenna · ")
    }

    data class Track(val key: String, val label: String, val on: Boolean)

    data class Vod(
        val id: String, val type: String, val title: String, val subtitle: String?, val poster: String?,
        val state: String, val step: String?, val error: String?, val positionMs: Long, val durationMs: Long,
        val hasNext: Boolean, val subtitles: List<Track>, val audio: List<Track>,
    )

    data class Timeline(val startMs: Long, val endMs: Long, val playheadMs: Long, val behindMs: Long, val live: Boolean) {
        val lengthMs get() = (endMs - startMs).coerceAtLeast(1)
    }

    data class Playing(val streamId: Int, val num: Int, val name: String, val title: String?)

    data class Status(
        val name: String, val signedIn: Boolean, val state: String, val playing: Playing?, val timeline: Timeline?,
        val vod: Vod?, val stremio: Boolean, val pointer: Boolean,
    ) {
        val isPlaying get() = vod != null || (playing != null && state != "idle" && state != "closed")
    }

    var devices by mutableStateOf<List<Device>>(emptyList()); private set
    var isBrowsing by mutableStateOf(false); private set
    /** The TV this phone is controlling, once something was sent to it. */
    var active by mutableStateOf<Device?>(null); private set
    var status by mutableStateOf<Status?>(null); private set
    var isSending by mutableStateOf(false); private set
    /** Polling stops while the app is in the background. */
    var foreground = true

    private val nsd = context.getSystemService(NsdManager::class.java)
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var pollJob: Job? = null
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    // ---- Discovery

    fun startBrowsing() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { scope.launch { isBrowsing = true } }
            override fun onDiscoveryStopped(type: String) { scope.launch { isBrowsing = false } }
            override fun onStartDiscoveryFailed(type: String, code: Int) { scope.launch { isBrowsing = false; discovery = null } }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { scope.launch { resolveQueue += info; resolveNext() } }
            override fun onServiceLost(info: NsdServiceInfo) {
                scope.launch { devices = devices.filterNot { it.name == info.serviceName } }
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices(CastService.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discovery = null }
    }

    fun stopBrowsing() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }

    /** Debug builds: a TV the phone can't discover on its own (an emulator behind its own network). */
    fun addManual(name: String, host: String, port: Int) {
        val d = Device(name, host, port)
        devices = (devices.filterNot { it.name == name } + d).sortedBy { it.displayName.lowercase() }
    }

    /** Older Android versions resolve one service at a time. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, code: Int) { scope.launch { resolving = false; resolveNext() } }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    val addresses = if (Build.VERSION.SDK_INT >= 34) s.hostAddresses else listOfNotNull(s.host)
                    val host = (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
                    scope.launch {
                        resolving = false
                        if (host != null) {
                            val d = Device(s.serviceName, host.substringBefore('%'), s.port)
                            devices = (devices.filterNot { it.name == d.name } + d).sortedBy { it.displayName.lowercase() }
                            if (active?.name == d.name) active = d
                        }
                        resolveNext()
                    }
                }
            })
        }.onFailure { resolving = false }
    }

    // ---- Commands

    /** Sends a live channel. The account goes along so a TV that isn't signed in can use the same subscription. */
    suspend fun playChannel(streamId: Int, account: JSONObject?, device: Device): String? {
        val body = JSONObject().put("streamId", streamId)
        account?.let { body.put("account", it) }
        return send(device, body) { code -> "The TV didn't accept the channel ($code)." }
    }

    /** Sends a movie or episode and the source to play; the TV plays the source itself. */
    suspend fun playStremio(body: JSONObject, device: Device): String? =
        send(device, JSONObject().put("stremio", body)) { code ->
            if (code == 404) "Update Antenna on the TV to play movies and series." else "The TV didn't accept it ($code)."
        }

    private suspend fun send(device: Device, body: JSONObject, fallback: (Int) -> String): String? {
        isSending = true
        try {
            val (code, json) = post(device, "play", body) ?: return "Couldn't reach the TV. Make sure it's on and on the same Wi-Fi."
            if (code !in 200..299) return json?.optString("error")?.ifEmpty { null } ?: fallback(code)
            active = device
            startPolling()
            return null
        } finally {
            isSending = false
        }
    }

    fun command(action: String, extra: JSONObject = JSONObject()) {
        val d = active ?: return
        extra.put("action", action)
        scope.launch {
            post(d, "command", extra)
            // Show the effect straight away instead of waiting for the next poll.
            delay(400)
            refreshStatus()
        }
    }

    /** Lets go of the TV, stopping what it plays if asked. */
    fun disconnect(stopTv: Boolean) {
        if (stopTv) command("stop")
        pollJob?.cancel()
        active = null
        status = null
    }

    /** Starts following a TV's status without sending anything (the remote's now-playing card). */
    fun watch(device: Device) {
        if (active?.name == device.name) return
        active = device
        status = null
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                if (foreground) refreshStatus()
                delay(2000)
            }
        }
    }

    suspend fun refreshStatus() {
        val d = active ?: return
        val json = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url("http://${d.host}:${d.port}/status").build()).execute().use { r ->
                    JSONObject(r.body?.string().orEmpty())
                }
            }.getOrNull()
        } ?: return
        if (active?.name != d.name) return
        val s = parseStatus(json)
        if (s != status) status = s
    }

    private suspend fun post(d: Device, path: String, body: JSONObject): Pair<Int, JSONObject?>? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("http://${d.host}:${d.port}/$path")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r -> r.code to runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull() }
        }.getOrNull()
    }

    fun release() {
        stopBrowsing()
        pollJob?.cancel()
    }

    companion object {
        private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

        private fun tracks(arr: JSONArray?): List<Track> = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            arr!!.optJSONObject(i)?.let { Track(it.optString("key"), it.optString("label"), it.optBoolean("on")) }
        }

        fun parseStatus(o: JSONObject) = Status(
            name = o.optString("name"),
            signedIn = o.optBoolean("signedIn"),
            state = o.optString("state", "idle"),
            playing = o.optJSONObject("playing")?.let { Playing(it.optInt("streamId"), it.optInt("num"), it.optString("name"), it.text("title")) },
            timeline = o.optJSONObject("timeline")?.let {
                Timeline(it.optLong("startMs"), it.optLong("endMs"), it.optLong("playheadMs"), it.optLong("behindMs"), it.optBoolean("live", true))
            },
            vod = o.optJSONObject("vod")?.let {
                Vod(it.optString("id"), it.optString("type"), it.optString("title"), it.text("subtitle"), it.text("poster"),
                    it.optString("state"), it.text("step"), it.text("error"), it.optLong("positionMs"), it.optLong("durationMs"),
                    it.optBoolean("hasNext"), tracks(it.optJSONArray("subtitles")), tracks(it.optJSONArray("audio")))
            },
            stremio = o.optBoolean("stremio"),
            pointer = o.optBoolean("pointer"),
        )
    }
}
