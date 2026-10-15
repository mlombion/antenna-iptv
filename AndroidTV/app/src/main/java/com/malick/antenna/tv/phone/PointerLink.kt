package com.malick.antenna.tv.phone

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/**
 * The phone remote's line to a TV's pointer service (port 8782): cursor moves, clicks, scrolling, the remote's
 * buttons and typing, for the whole TV. Moves are gathered and sent up to 120 times a second.
 */
class PointerLink(private val scope: CoroutineScope) {
    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data object Connected : State
        data class Failed(val message: String) : State
    }

    companion object { const val PORT = 8782 }

    var state by mutableStateOf<State>(State.Idle); private set
    var device by mutableStateOf<TvLink.Device?>(null); private set
    /** True when the app on the TV can't be pointed at (YouTube): movement then steps the highlight. */
    var arrowMode by mutableStateOf(false); private set

    private var socket: Socket? = null
    private var writer: Job? = null
    private val lock = Any()
    private val queue = ArrayList<String>()
    private var moveX = 0f
    private var moveY = 0f
    private var scrollX = 0f
    private var scrollY = 0f

    fun connect(to: TvLink.Device) {
        if (device == to && (state == State.Connected || state == State.Connecting)) return
        disconnect()
        device = to
        state = State.Connecting
        scope.launch {
            val s = withContext(Dispatchers.IO) {
                runCatching {
                    Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(to.host, PORT), 5000) }
                }.getOrNull()
            }
            if (device != to) { s?.let { runCatching { it.close() } }; return@launch }
            if (s == null) {
                state = State.Failed("The phone remote is off on this TV. On the TV, open Antenna → Settings → Phone remote, " +
                    "or Settings → Accessibility → Antenna phone remote, and turn it on.")
                return@launch
            }
            socket = s
            state = State.Connected
            synchronized(lock) { queue.clear(); queue += "show" }
            startWriter(s)
            startReader(s)
        }
    }

    private fun startWriter(s: Socket) {
        writer = scope.launch(Dispatchers.IO) {
            val out: OutputStream = runCatching { s.getOutputStream() }.getOrNull() ?: return@launch
            while (isActive && socket === s) {
                delay(8)
                val lines = synchronized(lock) { flushPending(); queue.toList().also { queue.clear() } }
                if (lines.isEmpty()) continue
                val ok = runCatching { out.write(lines.joinToString("") { it + "\n" }.toByteArray()); out.flush() }.isSuccess
                if (!ok) { withContext(Dispatchers.Main) { lost(s) }; break }
            }
        }
    }

    /** Reads the TV's lines: "mode arrows" / "mode pointer". */
    private fun startReader(s: Socket) {
        scope.launch(Dispatchers.IO) {
            val reader = runCatching { s.getInputStream().bufferedReader() }.getOrNull() ?: return@launch
            while (true) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                if (line.startsWith("mode ")) withContext(Dispatchers.Main) { if (socket === s) arrowMode = line == "mode arrows" }
            }
            withContext(Dispatchers.Main) { lost(s) }
        }
    }

    private fun lost(s: Socket) {
        if (socket !== s) return
        runCatching { s.close() }
        socket = null
        writer?.cancel()
        arrowMode = false
        state = State.Failed("Lost the connection to the TV.")
    }

    fun disconnect() {
        writer?.cancel()
        socket?.let { s -> scope.launch(Dispatchers.IO) { runCatching { s.close() } } }
        socket = null
        device = null
        arrowMode = false
        state = State.Idle
    }

    // ---- Commands

    /** Cursor movement in dp (the TV scales it to its screen, as with the iPhone's points). */
    fun move(dx: Float, dy: Float) = synchronized(lock) { moveX += dx; moveY += dy }
    /** A two-finger drag; the TV swipes the same way, so content follows the fingers. */
    fun scroll(dx: Float, dy: Float) = synchronized(lock) { scrollX += dx; scrollY += dy }
    fun click() = send("c", flushFirst = true)
    fun longPress() = send("lc", flushFirst = true)
    fun center() { synchronized(lock) { moveX = 0f; moveY = 0f }; send("center") }
    /** back, home, up, down, left, right, ok, recents, notifications */
    fun key(name: String) = send("k $name")
    /** up, down, mute */
    fun volume(dir: String) = send("v $dir")
    fun type(text: String) = send("t " + Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))
    fun enter() = send("enter")

    private fun send(line: String, flushFirst: Boolean = false) {
        if (state != State.Connected) return
        synchronized(lock) {
            if (flushFirst) flushPending()
            queue += line
        }
    }

    /** Call with the lock held. */
    private fun flushPending() {
        if (moveX != 0f || moveY != 0f) {
            queue += String.format(Locale.US, "m %.1f %.1f", moveX, moveY)
            moveX = 0f; moveY = 0f
        }
        if (scrollX != 0f || scrollY != 0f) {
            queue += String.format(Locale.US, "s %.1f %.1f", scrollX, scrollY)
            scrollX = 0f; scrollY = 0f
        }
    }
}
