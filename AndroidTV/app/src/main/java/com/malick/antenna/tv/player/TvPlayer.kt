package com.malick.antenna.tv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.malick.antenna.tv.data.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One ExoPlayer for the whole app, so changing channel never rebuilds the video pipeline.
 * Uses the box's hardware decoders (HEVC, Dolby) through Android's media framework.
 * MPEG-TS channels play through an on-box recording (`TimeshiftRecorder`) so they can be paused and rewound.
 */
@OptIn(UnstableApi::class)
class TvPlayer(private val context: Context) {
    sealed interface State {
        data object Idle : State
        data object Opening : State
        data object Playing : State
        data object Paused : State
        data class Failed(val message: String) : State
    }

    /** The rewindable window: what's recorded, and where playback is within it. */
    data class Timeline(val start: Long, val end: Long, val playhead: Long) {
        val behindMs get() = (end - playhead).coerceAtLeast(0)
        val lengthMs get() = (end - start).coerceAtLeast(1)
    }

    var state by mutableStateOf<State>(State.Idle); private set
    /** True until the new channel shows a frame; the screen hides the previous channel's last frame meanwhile. */
    var isSwitching by mutableStateOf(false); private set
    var resolution by mutableStateOf(""); private set
    var timeline by mutableStateOf<Timeline?>(null); private set
    val canRewind get() = timeline != null
    /** True after starting a channel or pressing Live, until you rewind or pause. */
    var followingLive by mutableStateOf(true); private set
    /**
     * "Live" follows what you did rather than the measured gap: providers send video in bursts, so the gap to the
     * newest data swings by several seconds and judging by it alone made the badge and the info bar flicker.
     */
    val isBehindLive: Boolean
        get() {
            val t = timeline ?: return false
            return if (followingLive) t.behindMs > 30_000 else t.behindMs > 2_000
        }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var url: String? = null
    private var fallbackUrl: String? = null
    private var triedFallback = false
    private var hasPlayed = false
    private var reconnects = 0
    /** Providers that allow one connection refuse the new channel for a moment while the old one closes. */
    private var refusals = 0
    private var retryJob: Job? = null
    private var timeoutJob: Job? = null
    private var monitorJob: Job? = null

    private var recorder: TimeshiftRecorder? = null
    private var rewindMinutes = 0
    /** Broadcast time at the start of the current local playback; ExoPlayer's position counts from there. */
    private var anchorTime = 0L

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(XtreamClient.USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)

    private val tsExtractors = DefaultExtractorsFactory().setTsExtractorFlags(
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
    )

    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Start fast: a second of video is enough to begin; keep up to 30 s ahead once running.
                .setBufferDurationsMs(3_000, 30_000, 1_000, 2_000)
                .build(),
        )
        .build()
        .apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(s: Int) = sync()
                override fun onIsPlayingChanged(isPlaying: Boolean) = sync()
                override fun onRenderedFirstFrame() {
                    isSwitching = false
                    hasPlayed = true
                    reconnects = 0
                    timeoutJob?.cancel()
                    sync()
                }
                override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                    resolution = if (size.width > 0) "${size.width}×${size.height}" else ""
                }
                override fun onPlayerError(error: PlaybackException) = failOrRetry(error)
            })
        }

    fun prepareSwitch() {
        retryJob?.cancel()
        state = State.Opening
        isSwitching = true
    }

    fun load(url: String, fallback: String?, bufferMs: Int, rewindMinutes: Int) {
        stopRecorder()
        this.url = url
        fallbackUrl = fallback
        triedFallback = false
        hasPlayed = false
        reconnects = 0
        refusals = 0
        followingLive = true
        this.rewindMinutes = rewindMinutes
        if (rewindMinutes > 0 && url.endsWith(".ts")) {
            val rec = TimeshiftRecorder(context, url, rewindMinutes * 60_000L)
            recorder = rec
            rec.start()
            openLocal(null)
            startMonitor()
        } else {
            open(url)
        }
    }

    fun retry() {
        reconnects = 0
        val u = url ?: return
        load(u, fallbackUrl, 3000, rewindMinutes)
    }

    /** With the recording, pause holds the picture and resumes where you stopped. Without it, resume jumps to live. */
    fun togglePause() {
        when (state) {
            State.Playing -> { exo.pause(); state = State.Paused; if (recorder != null) followingLive = false }
            State.Paused -> if (recorder != null) { exo.play(); state = State.Playing } else url?.let(::open)
            else -> {}
        }
    }

    // ---- Rewind

    fun seekBy(ms: Long) {
        val t = timeline ?: return
        seekTo(t.playhead + ms)
    }

    fun seekTo(time: Long) {
        val rec = recorder ?: return
        val start = rec.startTime ?: return
        val end = rec.endTime ?: return
        if (time >= end - 2_000) {
            // Skipping ahead past live: already there, nothing to do.
            if (isBehindLive || state == State.Paused) goLive()
            return
        }
        followingLive = false
        openLocal(rec.offsetFor(maxOf(time, start + 500)))
    }

    fun goLive() {
        val rec = recorder ?: return
        val end = rec.endTime ?: return
        followingLive = true
        // Start just inside the recording so the picture is back at once instead of waiting for new data.
        openLocal(rec.offsetFor(end - 3_000))
    }

    fun stop() {
        retryJob?.cancel(); timeoutJob?.cancel()
        url = null
        stopRecorder()
        exo.stop()
        exo.clearMediaItems()
        state = State.Idle
        isSwitching = false
    }

    fun release() { stop(); exo.release() }

    // ---- Internals

    private fun stopRecorder() {
        monitorJob?.cancel()
        recorder?.close()
        recorder = null
        timeline = null
    }

    private fun openLocal(fromOffset: Long?) {
        val rec = recorder ?: return
        retryJob?.cancel()
        state = State.Opening
        anchorTime = fromOffset?.let(rec::timeFor) ?: System.currentTimeMillis()
        val factory = DataSource.Factory { TimeshiftDataSource(rec) }
        val uri = Uri.parse("timeshift://local?n=${System.nanoTime()}" + (fromOffset?.let { "&from=$it" } ?: ""))
        val source = ProgressiveMediaSource.Factory(factory, tsExtractors)
            .createMediaSource(MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.VIDEO_MP2T).build())
        exo.setMediaSource(source)
        exo.prepare()
        exo.play()
        armTimeout()
    }

    private fun open(target: String) {
        retryJob?.cancel()
        state = State.Opening
        val item = MediaItem.Builder().setUri(target).apply {
            // Let ExoPlayer pick the right extractor straight away instead of sniffing.
            if (target.endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
            else if (target.endsWith(".ts")) setMimeType(MimeTypes.VIDEO_MP2T)
        }.build()
        exo.setMediaItem(item)
        exo.prepare()
        exo.play()
        armTimeout()
    }

    private fun armTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(20_000)
            if (state == State.Opening) failOrRetry(null)
        }
    }

    /** Keeps the timeline current and notices a recording that never got started. */
    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                val rec = recorder ?: break
                val up = rec.upstream
                if (up is TimeshiftRecorder.Upstream.Failed && !hasPlayed) {
                    // The provider won't give us MPEG-TS for this channel: play the other format directly, without rewind.
                    val fallback = fallbackUrl
                    stopRecorder()
                    if (fallback != null && !triedFallback) { triedFallback = true; url = fallback; open(fallback) }
                    else state = State.Failed(up.message)
                    break
                }
                val start = rec.startTime
                val end = rec.endTime
                if (start != null && end != null) {
                    val playhead = if (hasPlayed) anchorTime + exo.currentPosition else anchorTime
                    timeline = Timeline(start, maxOf(end, start), playhead.coerceIn(start, maxOf(end, start)))
                }
                delay(500)
            }
        }
    }

    private fun sync() {
        if (url == null) return
        state = when {
            exo.isPlaying -> State.Playing
            exo.playbackState == Player.STATE_BUFFERING -> State.Opening
            exo.playbackState == Player.STATE_READY && !exo.playWhenReady -> State.Paused
            exo.playbackState == Player.STATE_ENDED -> { failOrRetry(null); return }
            else -> state
        }
    }

    private fun failOrRetry(error: PlaybackException?) {
        if (url == null) return
        timeoutJob?.cancel()
        recorder?.let { rec ->
            // Playing from the recording: pick up again at the same moment (or restart if it's gone).
            if (reconnects < 3 && rec.upstream !is TimeshiftRecorder.Upstream.Failed) {
                reconnects++
                val at = timeline?.playhead
                retryJob = scope.launch { delay(reconnects * 700L); openLocal(at?.let(rec::offsetFor)) }
                return
            }
        }
        val refused = error?.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        if (!hasPlayed && refused && refusals < 5) {
            val delays = longArrayOf(500, 1000, 1500, 2000, 3000)
            retryJob = scope.launch { delay(delays[refusals++]); url?.let(::open) }
            return
        }
        if (!hasPlayed && !triedFallback && fallbackUrl != null) {
            // Some providers only serve one of the two formats; try the other before giving up.
            triedFallback = true
            stopRecorder()
            url = fallbackUrl
            retryJob = scope.launch { delay(500); url?.let(::open) }
            return
        }
        val max = if (hasPlayed) 3 else 1
        if (reconnects < max) {
            reconnects++
            state = State.Opening
            retryJob = scope.launch { delay(reconnects * 1000L); url?.let(::open) }
        } else {
            exo.stop()
            stopRecorder()
            state = State.Failed(
                if (error?.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) "The provider refused the stream."
                else "This channel isn't responding right now.",
            )
        }
    }
}
