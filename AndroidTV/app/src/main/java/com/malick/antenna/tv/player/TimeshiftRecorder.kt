package com.malick.antenna.tv.player

import android.content.Context
import android.util.Log
import com.malick.antenna.tv.data.XtreamClient
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Records the provider's live MPEG-TS stream to ~2 second segment files on the box, so the player can start
 * from any recent moment: pause, rewind, skip ahead, go back to live. Same design as the Mac and iPhone apps.
 * Byte offsets are global; arrival-time marks map a moment in the broadcast to an offset.
 * The provider sees one connection, however much you rewind.
 */
class TimeshiftRecorder(context: Context, private val url: String, private val maxAgeMs: Long) {
    sealed interface Upstream {
        data object Connecting : Upstream
        data object Streaming : Upstream
        data class Failed(val message: String) : Upstream
    }

    private class Segment(val file: File, val start: Long, var end: Long, val startTime: Long)
    private class Mark(val offset: Long, val time: Long)

    companion object {
        private const val TAG = "AntennaRewind"
        private const val SEGMENT_MS = 2_000L
        fun root(context: Context) = File(context.cacheDir, "timeshift")
        fun removeLeftovers(context: Context) { root(context).deleteRecursively() }
    }

    private val dir = File(root(context), UUID.randomUUID().toString()).apply { mkdirs() }
    private val lock = Object()
    private val segments = ArrayList<Segment>()
    private val marks = ArrayList<Mark>()
    private var writer: RandomAccessFile? = null
    private var alignBase = 0L
    private var forceRotate = false
    @Volatile var endOffset = 0L; private set
    @Volatile var upstream: Upstream = Upstream.Connecting; private set
    @Volatile private var closed = false
    @Volatile private var call: Call? = null

    val startOffset: Long get() = synchronized(lock) { segments.firstOrNull()?.start ?: 0L }
    val startTime: Long? get() = synchronized(lock) { marks.firstOrNull()?.time }
    val endTime: Long? get() = synchronized(lock) { marks.lastOrNull()?.time }

    fun start() {
        thread(name = "timeshift", isDaemon = true) { download() }
    }

    fun close() {
        closed = true
        call?.cancel()
        synchronized(lock) {
            runCatching { writer?.close() }
            writer = null
            lock.notifyAll()
        }
        dir.deleteRecursively()
    }

    // ---- Provider side

    private fun download() {
        var receivedAny = false
        var reconnects = 0
        var refusals = 0
        val refusalDelays = longArrayOf(500, 1000, 1500, 2000, 3000)
        while (!closed) {
            var lastError: String? = null
            var gotData = false
            try {
                val request = Request.Builder().url(url).header("User-Agent", XtreamClient.USER_AGENT).build()
                val c = XtreamClient.http.newBuilder().readTimeout(java.time.Duration.ofSeconds(20)).build().newCall(request)
                call = c
                c.execute().use { r ->
                    if (!r.isSuccessful) {
                        lastError = "The provider refused the stream (HTTP ${r.code})."
                    } else {
                        upstream = Upstream.Streaming
                        val source = r.body!!.source()
                        val buf = ByteArray(64 * 1024)
                        while (!closed) {
                            val n = source.read(buf)
                            if (n < 0) break
                            if (n > 0) { append(buf, n); gotData = true; receivedAny = true }
                        }
                    }
                }
            } catch (e: Exception) {
                if (closed) return
                lastError = lastError ?: e.message
            }
            if (closed) return
            if (receivedAny) {
                // A live stream never ends on its own: any end is a drop. Reconnect and keep recording.
                if (gotData) reconnects = 0
                if (reconnects >= 5) { upstream = Upstream.Failed(lastError ?: "The stream stopped."); wake(); return }
                reconnects++
                upstream = Upstream.Connecting
                synchronized(lock) { alignBase = endOffset; forceRotate = true }
                Log.i(TAG, "Stream dropped ($lastError), reconnect $reconnects")
                Thread.sleep(reconnects * 1000L)
            } else if (refusals < refusalDelays.size) {
                // Subscriptions with one connection refuse the new channel while the old one is closing.
                Log.i(TAG, "No video yet ($lastError), retry ${refusals + 1}")
                Thread.sleep(refusalDelays[refusals++])
            } else {
                upstream = Upstream.Failed(lastError ?: "The channel didn't send any video.")
                wake()
                return
            }
        }
    }

    private fun wake() = synchronized(lock) { lock.notifyAll() }

    private fun append(buf: ByteArray, count: Int) = synchronized(lock) {
        if (closed) return
        val now = System.currentTimeMillis()
        var off = 0
        val due = segments.lastOrNull()?.let { now - it.startTime >= SEGMENT_MS } ?: true
        if (writer == null || forceRotate || due) {
            if (writer != null && !forceRotate) {
                // Finish the current TS packet in this segment so the next starts on a packet boundary.
                val into = ((endOffset - alignBase) % 188).toInt()
                if (into != 0) {
                    val head = minOf(188 - into, count)
                    write(buf, 0, head, now)
                    off = head
                    if (off == count) return
                }
            }
            forceRotate = false
            rotate(now)
        }
        write(buf, off, count - off, now)
        trim(now)
        lock.notifyAll()
    }

    private fun write(buf: ByteArray, off: Int, len: Int, now: Long) {
        if (len <= 0) return
        if (marks.isEmpty()) marks += Mark(endOffset, now)
        writer?.write(buf, off, len)
        endOffset += len
        segments.last().end = endOffset
        val last = marks.last()
        if (now - last.time >= 250) marks += Mark(endOffset, now)
        else if (marks.size > 1) marks[marks.size - 1] = Mark(endOffset, maxOf(last.time, now))
    }

    private fun rotate(now: Long) {
        runCatching { writer?.close() }
        val file = File(dir, "seg-${segments.size + System.nanoTime()}.ts")
        writer = RandomAccessFile(file, "rw")
        segments += Segment(file, endOffset, endOffset, now)
    }

    private fun trim(now: Long) {
        while (segments.size > 2 && now - segments[1].startTime > maxAgeMs) {
            segments.removeAt(0).file.delete()
            val start = segments.first().start
            val keep = marks.indexOfFirst { it.offset >= start }
            if (keep > 0) repeat(keep) { marks.removeAt(0) }
        }
    }

    // ---- Player side

    /** Reads up to `len` bytes at `offset`, waiting up to `waitMs` at the live edge. Returns -1 when the stream is over. */
    fun read(offset: Long, dst: ByteArray, dstOff: Int, len: Int, waitMs: Long): Int {
        synchronized(lock) {
            if (offset >= endOffset && !closed && upstream !is Upstream.Failed) lock.wait(waitMs)
            if (closed) return -1
            val o = maxOf(offset, segments.firstOrNull()?.start ?: 0L)
            if (o >= endOffset) return if (upstream is Upstream.Failed) -1 else 0
            val seg = segments.firstOrNull { it.start <= o && o < it.end } ?: return 0
            val n = minOf(len.toLong(), seg.end - o).toInt()
            RandomAccessFile(seg.file, "r").use { f ->
                f.seek(o - seg.start)
                return f.read(dst, dstOff, n).coerceAtLeast(0)
            }
        }
    }

    /** Skips forward if the requested offset was already dropped from the recording. */
    fun clamp(offset: Long): Long = maxOf(offset, startOffset)

    /** Byte offset of the broadcast at `time`, snapped to a 188-byte TS packet. */
    fun offsetFor(time: Long): Long = synchronized(lock) {
        val m = marks.lastOrNull { it.time <= time } ?: return segments.firstOrNull()?.start ?: 0L
        val base = if (m.offset >= alignBase) alignBase else 0L
        maxOf(segments.firstOrNull()?.start ?: 0L, m.offset - (m.offset - base) % 188)
    }

    fun timeFor(offset: Long): Long = synchronized(lock) {
        marks.firstOrNull { it.offset >= offset }?.time ?: marks.lastOrNull()?.time ?: System.currentTimeMillis()
    }
}
