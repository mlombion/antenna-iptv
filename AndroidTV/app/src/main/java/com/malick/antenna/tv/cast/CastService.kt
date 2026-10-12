package com.malick.antenna.tv.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.malick.antenna.tv.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * "Antenna Connect": keeps a small control server running on the home network and announces it over
 * Bonjour/NSD as `_antenna._tcp`, so the iPhone and Mac apps can find this TV and send it a channel.
 * Runs as a foreground service so it keeps listening when the app isn't on screen; it opens the app itself
 * when a phone sends something to play.
 */
class CastService : Service() {
    companion object {
        const val SERVICE_TYPE = "_antenna._tcp"
        const val EXTRA_STREAM = "cast_stream_id"
        const val EXTRA_ACCOUNT = "cast_account"
        private const val TAG = "AntennaCast"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, CastService::class.java)) }
                .onFailure { Log.w(TAG, "Couldn't start cast service", it) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("cast", "Play from phone", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, "cast")
            .setContentTitle("Antenna IPTV")
            .setContentText("Ready to play channels sent from your phone")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        runCatching { server?.close() }
        super.onDestroy()
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun startServer() {
        val socket = runCatching { ServerSocket(8781) }.getOrElse { runCatching { ServerSocket(0) }.getOrNull() } ?: return
        server = socket
        thread(name = "cast-server", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(client) }; runCatching { client.close() } }
            }
        }
        val info = NsdServiceInfo().apply {
            serviceName = "Antenna · ${deviceName()}"
            serviceType = SERVICE_TYPE
            port = socket.localPort
        }
        nsd = getSystemService(NsdManager::class.java)
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i(TAG, "Announced as ${s.serviceName}") }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) { Log.w(TAG, "NSD registration failed $code") }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
        }
        nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
    }

    private fun handle(client: Socket) {
        // Only answer devices on the home network.
        if (!client.inetAddress.isSiteLocalAddress && !client.inetAddress.isLoopbackAddress) return
        client.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        var length = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val body = if (length > 0) {
            // Content-Length counts UTF-8 bytes; the reader gives characters (source names often contain emoji).
            val sb = StringBuilder()
            var bytes = 0
            while (bytes < length.coerceAtMost(262_144)) {
                val c = reader.read()
                if (c < 0) break
                sb.append(c.toChar())
                bytes += when { c < 0x80 -> 1; c < 0x800 -> 2; c in 0xD800..0xDFFF -> 2; else -> 3 }
            }
            runCatching { JSONObject(sb.toString()) }.getOrNull()
        } else null
        val path = requestLine.split(' ').getOrNull(1) ?: "/"

        val (code, json) = when {
            requestLine.startsWith("GET") && path.startsWith("/status") -> 200 to status()
            requestLine.startsWith("POST") && path.startsWith("/play") && body != null -> play(body)
            requestLine.startsWith("POST") && path.startsWith("/command") && body != null -> {
                onMain { CastBridge.handler?.command(body.optString("action"), body) }
                200 to JSONObject().put("ok", true)
            }
            else -> 404 to JSONObject().put("error", "Not found")
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        client.getOutputStream().apply {
            write("HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    private fun status(): JSONObject =
        onMain { CastBridge.handler?.status() } ?: JSONObject().put("name", deviceName()).put("signedIn", false).put("state", "closed")
            .put("pointer", com.malick.antenna.tv.pointer.PointerService.running)

    private fun play(body: JSONObject): Pair<Int, JSONObject> {
        body.optJSONObject("stremio")?.let { return playStremio(it) }
        val streamId = body.optInt("streamId", -1)
        if (streamId < 0) return 400 to JSONObject().put("error", "Missing channel")
        val account = body.optJSONObject("account")?.let {
            CastAccount(it.optString("name", "My IPTV"), it.optString("server"), it.optString("username"),
                it.optString("password").takeIf { p -> p.isNotEmpty() })
        }
        // Bring the app to the screen (it may be closed or behind another app).
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val handler = CastBridge.handler
        if (handler == null) {
            intent.putExtra(EXTRA_STREAM, streamId)
            account?.let { intent.putExtra(EXTRA_ACCOUNT, JSONObject().put("name", it.name).put("server", it.server)
                .put("username", it.username).put("password", it.password ?: "").toString()) }
            runCatching { startActivity(intent) }.onFailure {
                return 503 to JSONObject().put("error", "Open Antenna IPTV on the TV, then try again.")
            }
            return 202 to JSONObject().put("ok", true).put("starting", true)
        }
        runCatching { startActivity(intent) }
        // castPlay answers null for success, so wrap it to tell success apart from a timeout.
        val outcome = runBlocking { withContext(Dispatchers.Main) { withTimeoutOrNull(25_000) { listOf(handler.castPlay(streamId, account)) } } }
        val error = if (outcome == null) "The TV took too long to answer." else outcome.first()
        return if (error == null) 200 to JSONObject().put("ok", true) else 409 to JSONObject().put("error", error)
    }

    private fun playStremio(body: JSONObject): Pair<Int, JSONObject> {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
        // A closed app needs a moment to start before it can take the title.
        var handler = CastBridge.handler
        var waited = 0
        while (handler == null && waited < 15_000) { Thread.sleep(250); waited += 250; handler = CastBridge.handler }
        if (handler == null) return 503 to JSONObject().put("error", "Open Antenna IPTV on the TV, then try again.")
        val outcome = runBlocking { withContext(Dispatchers.Main) { withTimeoutOrNull(25_000) { listOf(handler.castStremio(body)) } } }
        val error = if (outcome == null) "The TV took too long to answer." else outcome.first()
        return if (error == null) 200 to JSONObject().put("ok", true) else 409 to JSONObject().put("error", error)
    }

    private fun <T> onMain(block: () -> T): T? =
        runCatching { runBlocking { withContext(Dispatchers.Main) { block() } } }.getOrNull()
}

/** Starts the listener when the box turns on or Antenna is updated, so phones can find the TV without opening the app first. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // At boot, and after Antenna is updated (an update stops its services).
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) CastService.start(context)
    }
}
