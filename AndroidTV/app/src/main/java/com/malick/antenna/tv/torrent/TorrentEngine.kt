package com.malick.antenna.tv.torrent

import android.content.Context
import android.util.Log
import com.malick.antenna.tv.data.stremio.uriComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.libtorrent4j.FileStorage
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.torrent_info
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

class TorrentException(message: String) : Exception(message)

/**
 * Streams torrent sources on the box itself, like Stremio's streaming server: downloads the chosen file
 * in order, with the pieces just ahead of the playhead fetched first, and serves it to the player over a
 * local HTTP address that supports seeking. Only one torrent at a time; its data is deleted when you stop.
 */
object TorrentEngine {
    private const val TAG = "AntennaTorrent"
    private const val MB = 1024L * 1024

    /** Public trackers added to every torrent; the add-on's own trackers and DHT come first. */
    private val extraTrackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
    )
    private val videoExtensions = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "wmv", "mpg", "mpeg", "flv")

    data class Opened(val url: String, val fileName: String, val size: Long)

    /** What the loading screen shows while a torrent starts. */
    data class Stats(val hasMetadata: Boolean, val peers: Int, val seeds: Int, val downloadBps: Int, val bufferedAheadBytes: Long)

    private class Active(
        val hash: String, @Volatile var handle: TorrentHandle, @Volatile var dir: File, val fileIndex: Int, val relativePath: String,
        val size: Long, val fileOffset: Long, val pieceLength: Int, val firstPiece: Int, val lastPiece: Int,
        /** Low-storage mode: how much may be on disk before what's been watched is deleted (null = keep everything). */
        val budget: Long?,
    ) {
        @Volatile var focusPiece = -1
        /** Bumped each time low-storage mode starts over in a fresh folder. */
        @Volatile var generation = 0
        val file: File get() = File(dir, relativePath)
        // A longer look-ahead in low-storage mode, since nothing beyond it is downloaded.
        val windowPieces = if (budget == null) (48 * MB / pieceLength).toInt().coerceIn(6, 120)
            else (minOf(160 * MB, budget / 3) / pieceLength).toInt().coerceIn(4, 400)
        fun pieceAt(pos: Long) = ((fileOffset + pos) / pieceLength).toInt()
    }

    /** Debug builds only: forces low-storage mode with this budget, for testing with small files. */
    @Volatile var devBudgetMB = 0L

    private lateinit var root: File
    private var session: SessionManager? = null
    private var server: ServerSocket? = null
    @Volatile private var current: Active? = null
    @Volatile private var pending: TorrentHandle? = null

    fun init(context: Context) {
        root = File(context.cacheDir, "torrents")
        // Whatever an earlier run left behind (it was deleted on stop unless the app was killed).
        thread(isDaemon = true) { root.deleteRecursively(); root.mkdirs() }
    }

    @Synchronized
    private fun session(): SessionManager {
        session?.let { return it }
        val settings = SettingsPack()
            .connectionsLimit(200)
            .activeDownloads(2)
            .activeSeeds(2)
            .activeLimit(4)
        val params = SessionParams(settings)
        // 32-bit boxes can't map large files into memory; plain file reads and writes work everywhere.
        params.setPosixDiskIO()
        return SessionManager(false).also { it.start(params); session = it }
    }

    /**
     * Starts a torrent and returns a local address the player can open. [fileIdx] comes from the add-on;
     * without it the episode (or the largest video) is picked from the file names.
     */
    suspend fun open(
        infoHash: String, fileIdx: Int?, sources: List<String>, season: Int?, episode: Int?,
        onStep: (String) -> Unit,
    ): Opened = withContext(Dispatchers.IO) {
        stop()
        val hash = infoHash.lowercase()
        val sm = session()
        val trackers = sources.filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") } + extraTrackers
        val magnet = "magnet:?xt=urn:btih:$hash" + trackers.distinct().joinToString("") { "&tr=" + uriComponent(it) }
        val dir = File(root, hash).apply { mkdirs() }

        val ec = error_code()
        val params = libtorrent.parse_magnet_uri(magnet, ec)
        if (ec.value() != 0) throw TorrentException("This source's link is broken. Try another one.")
        params.setSave_path(dir.absolutePath)
        params.setFlags(params.getFlags().or_(TorrentFlags.SEQUENTIAL_DOWNLOAD))
        val swigHandle = sm.swig().add_torrent(params, ec)
        if (ec.value() != 0 || swigHandle == null || !swigHandle.is_valid()) throw TorrentException("Couldn't start this torrent. Try another source.")
        val handle = TorrentHandle(swigHandle)
        pending = handle

        onStep("Finding people sharing this file")
        var waited = 0
        while (!handle.status().hasMetadata()) {
            if (pending !== handle) throw TorrentException("Stopped.")
            if (waited >= 45_000) {
                remove(handle, dir)
                throw TorrentException("No one is sharing this file right now. Try another source.")
            }
            delay(200); waited += 200
        }
        val info = handle.torrentFile() ?: throw TorrentException("Couldn't read this torrent. Try another source.")
        val files = info.files()
        val index = pickFile(files, fileIdx, season, episode)
        if (index == null) { remove(handle, dir); throw TorrentException("This torrent doesn't contain a video. Try another source.") }
        handle.prioritizeFiles(Array(files.numFiles()) { if (it == index) Priority.DEFAULT else Priority.IGNORE })

        val size = files.fileSize(index)
        val free = dir.usableSpace
        // Bigger than the free space: stream it in low-storage mode, deleting what's been watched as it goes.
        val margin = 500 * MB
        val budget = if (devBudgetMB > 0) devBudgetMB * MB else when {
            size <= free - margin -> null
            free - margin >= 400 * MB -> free - margin
            else -> { remove(handle, dir); throw TorrentException("The TV only has ${gb(free)} free, not enough to stream this. Free up some space and try again.") }
        }

        val offset = files.fileOffset(index)
        val pieceLength = info.pieceLength()
        val active = Active(
            hash, handle, dir, index, files.filePath(index), size, offset, pieceLength,
            (offset / pieceLength).toInt(), ((offset + size - 1) / pieceLength).toInt(), budget,
        )
        if (budget != null) handle.prioritizePieces(windowPriorities(info.numPieces(), active.firstPiece, active.windowPieces))
        pending = null
        current = active
        // Players read the start first, and MKV and MP4 files often keep their index at the very end.
        focus(active, active.firstPiece, force = true)
        for (p in maxOf(active.firstPiece, active.lastPiece - 1)..active.lastPiece) handle.setPieceDeadline(p, 1500)

        val port = ensureServer()
        val name = files.fileName(index)
        Opened("http://127.0.0.1:$port/$hash/$index/${uriComponent(name)}", name, size)
    }

    fun stats(): Stats? {
        val h = current?.handle ?: pending ?: return null
        return runCatching {
            val s = h.status()
            val a = current
            Stats(s.hasMetadata(), s.numPeers(), s.numSeeds(), s.downloadPayloadRate(), a?.let { bufferedAhead(it) } ?: 0)
        }.getOrNull()
    }

    /** Stops the torrent and deletes its data. */
    fun stop() {
        pending?.let { h -> pending = null; remove(h, null) }
        val a = current ?: return
        current = null
        remove(a.handle, File(root, a.hash))
    }

    private fun remove(handle: TorrentHandle, dir: File?) {
        runCatching { session?.remove(handle, SessionHandle.DELETE_FILES) }
        if (dir != null) thread(isDaemon = true) { Thread.sleep(3000); if (current?.dir != dir) dir.deleteRecursively() }
    }

    private fun pickFile(files: FileStorage, fileIdx: Int?, season: Int?, episode: Int?): Int? {
        val n = files.numFiles()
        if (fileIdx != null && fileIdx in 0 until n) return fileIdx
        val videos = (0 until n).filter { files.fileName(it).substringAfterLast('.', "").lowercase() in videoExtensions }
        if (videos.isEmpty()) return null
        if (season != null && episode != null) {
            val patterns = listOf(
                Regex("(?i)s0*${season}[ ._-]*e0*${episode}(?!\\d)"),
                Regex("(?i)(?<!\\d)0*${season}x0*${episode}(?!\\d)"),
            )
            videos.filter { i -> patterns.any { it.containsMatchIn(files.fileName(i)) } }
                .maxByOrNull { files.fileSize(it) }?.let { return it }
        }
        return videos.maxByOrNull { files.fileSize(it) }
    }

    /** Low-storage mode swaps the torrent while players are reading, so a stale handle just means "not yet". */
    private fun has(a: Active, piece: Int) = runCatching { a.handle.havePiece(piece) }.getOrDefault(false)

    /** True when the file is bigger than the free space and is being streamed in low-storage mode. */
    val isLowStorage get() = current?.budget != null

    private fun windowPriorities(pieces: Int, first: Int, count: Int) =
        Array(pieces) { if (it >= first && it < first + count) Priority.TOP_PRIORITY else Priority.IGNORE }

    /**
     * Low-storage mode: once the data on disk nears the budget, start the torrent over in a fresh folder from the
     * playhead. This deletes what's been watched; the look-ahead is fetched again, so playback pauses for a moment.
     */
    @Synchronized
    private fun freeSpaceIfNeeded(a: Active, piece: Int) {
        val budget = a.budget ?: return
        val done = runCatching { a.handle.status().totalWantedDone() }.getOrDefault(0L)
        if (done + a.windowPieces.toLong() * a.pieceLength <= budget || current !== a) return
        val sm = session ?: return
        // A copy: the handle's own metadata goes away with the torrent.
        val info = runCatching { a.handle.torrentFile()?.let { TorrentInfo(torrent_info(it.swig())) } }.getOrNull() ?: return
        val old = a.dir
        val fresh = File(File(root, a.hash), "part${a.generation + 1}").apply { mkdirs() }
        // The metadata has no trackers (they came with the magnet link), so carry them and the connected peers over.
        val trackers = (runCatching { a.handle.trackers().map { it.url() } }.getOrDefault(emptyList()) + extraTrackers).distinct()
        val peers = runCatching {
            a.handle.peerInfo().mapNotNull { p ->
                val s = p.ip(); val i = s.lastIndexOf(':')
                if (i <= 0) null else runCatching { TcpEndpoint(s.substring(0, i).trim('[', ']'), s.substring(i + 1).toInt()) }.getOrNull()
            }
        }.getOrDefault(emptyList())
        runCatching { sm.remove(a.handle, SessionHandle.DELETE_FILES) }
        // The same torrent can't be added again until the old one is gone.
        var waited = 0
        while (sm.find(info.infoHash())?.isValid == true && waited < 5000) { Thread.sleep(50); waited += 50 }
        val files = Array(info.numFiles()) { if (it == a.fileIndex) Priority.DEFAULT else Priority.IGNORE }
        // Adding is asynchronous; ask again if it hasn't shown up (the old one may have been mid-removal).
        var h: TorrentHandle? = null
        for (attempt in 0 until 3) {
            runCatching { sm.download(info, fresh, null, files, peers, TorrentFlags.SEQUENTIAL_DOWNLOAD) }
            waited = 0
            h = sm.find(info.infoHash())
            while ((h == null || !h.isValid) && waited < 2000) { Thread.sleep(50); waited += 50; h = sm.find(info.infoHash()) }
            if (h?.isValid == true) break
        }
        if (h == null || !h.isValid) { Log.w(TAG, "Low-storage restart failed"); return }
        h.prioritizePieces(windowPriorities(info.numPieces(), piece, a.windowPieces))
        trackers.forEach { url -> runCatching { h.addTracker(AnnounceEntry(url)) } }
        a.handle = h
        a.dir = fresh
        a.generation++
        a.focusPiece = -1
        if (old.name.startsWith("part")) thread(isDaemon = true) { Thread.sleep(3000); old.deleteRecursively() }
    }

    /** Asks for the pieces just ahead of [piece] first, most urgent nearest the playhead. */
    private fun focus(a: Active, piece: Int, force: Boolean = false) {
        if (!force && piece == a.focusPiece) return
        freeSpaceIfNeeded(a, piece)
        val jumped = a.focusPiece < 0 || piece < a.focusPiece - 2 || piece > a.focusPiece + a.windowPieces
        a.focusPiece = piece
        runCatching {
            if (jumped) a.handle.clearPieceDeadlines()
            for (k in 0 until a.windowPieces) {
                val p = piece + k
                if (p > a.lastPiece) break
                if (!a.handle.havePiece(p)) {
                    // In low-storage mode nothing outside the look-ahead is wanted, so ask for these explicitly.
                    if (a.budget != null) a.handle.piecePriority(p, Priority.TOP_PRIORITY)
                    a.handle.setPieceDeadline(p, 250 + k * 150)
                }
            }
        }
    }

    private fun bufferedAhead(a: Active): Long {
        val start = if (a.focusPiece >= 0) a.focusPiece else a.firstPiece
        var p = start
        while (p <= a.lastPiece && p < start + 400 && has(a, p)) p++
        return (p - start).toLong() * a.pieceLength
    }

    // ---- Local HTTP server the player reads from

    @Synchronized
    private fun ensureServer(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(name = "torrent-http", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching { serve(client) }.onFailure { if (it !is SocketException) Log.w(TAG, "Serve failed", it) }
                    runCatching { client.close() }
                }
            }
        }
        return s.localPort
    }

    private fun serve(client: Socket) {
        client.soTimeout = 120_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: return
        val segments = parts.getOrNull(1)?.trim('/')?.split('/') ?: return
        val out = client.getOutputStream()
        val a = current
        if (a == null || segments.size < 2 || segments[0] != a.hash || segments[1].toIntOrNull() != a.fileIndex) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
        }

        var start = 0L
        var end = a.size - 1
        val partial = range?.startsWith("bytes=") == true
        if (partial) {
            val spec = range!!.removePrefix("bytes=").substringBefore(',')
            val from = spec.substringBefore('-').trim()
            val to = spec.substringAfter('-', "").trim()
            if (from.isEmpty()) {
                start = (a.size - (to.toLongOrNull() ?: 0)).coerceAtLeast(0)
            } else {
                start = from.toLongOrNull() ?: 0
                to.toLongOrNull()?.let { end = minOf(it, a.size - 1) }
            }
            if (start >= a.size) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${a.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
        }
        val length = end - start + 1
        val type = when (a.file.extension.lowercase()) {
            "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"
            "ts", "m2ts" -> "video/mp2t"; "mov" -> "video/quicktime"; else -> "video/x-matroska"
        }
        val header = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: $type\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\n")
            if (partial) append("Content-Range: bytes $start-$end/${a.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray())
        if (method == "HEAD") return
        stream(a, start, end, out)
    }

    private fun stream(a: Active, start: Long, end: Long, out: OutputStream) {
        val buffer = ByteArray(256 * 1024)
        var pos = start
        var file: RandomAccessFile? = null
        var generation = a.generation
        try {
            while (pos <= end) {
                if (current !== a) return
                val piece = a.pieceAt(pos)
                focus(a, piece)
                // Low-storage mode moved the data to a fresh folder: read from the new file.
                if (a.generation != generation) { runCatching { file?.close() }; file = null; generation = a.generation }
                var waited = 0
                while (!has(a, piece)) {
                    if (current !== a) return
                    if (waited > 120_000) return
                    Thread.sleep(40); waited += 40
                }
                val pieceEnd = (piece + 1).toLong() * a.pieceLength - a.fileOffset
                val n = minOf(buffer.size.toLong(), pieceEnd - pos, end - pos + 1).toInt()
                val f = file ?: RandomAccessFile(a.file, "r").also { file = it }
                f.seek(pos)
                val read = f.read(buffer, 0, n)
                if (read <= 0) { Thread.sleep(40); continue }
                out.write(buffer, 0, read)
                pos += read
            }
            out.flush()
        } finally {
            runCatching { file?.close() }
        }
    }

    private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
}
