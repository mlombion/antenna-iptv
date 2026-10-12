package com.malick.antenna.tv.player

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

/**
 * Plays movies and episodes with VLC, which handles everything add-ons turn up: MKV, HEVC, DTS and AC3
 * audio, and subtitles inside the file. Live TV keeps using ExoPlayer (`TvPlayer`) for its rewind buffer.
 */
class VodPlayer(private val context: Context) {
    sealed interface State {
        data object Idle : State
        data object Opening : State
        data object Playing : State
        data object Paused : State
        data object Ended : State
        data class Failed(val message: String) : State
    }

    data class Track(val id: Int, val name: String)

    companion object {
        /** Debug builds only: the emulator's video decoder crashes when playback stalls, so tests can avoid it. */
        @Volatile var softwareDecodingOnly = false
    }

    var state by mutableStateOf<State>(State.Idle); private set
    var time by mutableLongStateOf(0L); private set
    var length by mutableLongStateOf(0L); private set
    /** 0–100 while VLC fills its buffer. */
    var buffering by mutableFloatStateOf(100f); private set
    var audioTracks by mutableStateOf<List<Track>>(emptyList()); private set
    var subtitleTracks by mutableStateOf<List<Track>>(emptyList()); private set
    var audioTrack by mutableIntStateOf(-1); private set
    var subtitleTrack by mutableIntStateOf(-1); private set

    private val libVlc by lazy {
        LibVLC(context, arrayListOf("--http-reconnect", "--no-stats", "--audio-time-stretch", "--freetype-rel-fontsize=16"))
    }
    private var player: MediaPlayer? = null
    private var layout: VLCVideoLayout? = null

    private fun player(): MediaPlayer = player ?: MediaPlayer(libVlc).also { p ->
        player = p
        p.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Opening -> state = State.Opening
                MediaPlayer.Event.Buffering -> buffering = e.buffering
                MediaPlayer.Event.Playing -> { state = State.Playing; refreshTracks() }
                MediaPlayer.Event.Paused -> state = State.Paused
                MediaPlayer.Event.EndReached -> state = State.Ended
                MediaPlayer.Event.EncounteredError -> state = State.Failed("This source stopped working. Try another one.")
                MediaPlayer.Event.TimeChanged -> time = e.timeChanged
                MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> refreshTracks()
            }
        }
        layout?.let { p.attachViews(it, null, true, false) }
    }

    fun open(url: String, startMs: Long, userAgent: String? = null, referrer: String? = null, torrent: Boolean = false) {
        val p = player()
        val media = Media(libVlc, Uri.parse(url))
        media.setHWDecoderEnabled(!softwareDecodingOnly, false)
        media.addOption(":network-caching=${if (torrent) 6000 else 3000}")
        if (startMs > 0) media.addOption(":start-time=" + String.format(Locale.US, "%.1f", startMs / 1000.0))
        userAgent?.let { media.addOption(":http-user-agent=$it") }
        referrer?.let { media.addOption(":http-referrer=$it") }
        p.media = media
        media.release()
        time = startMs
        length = 0
        buffering = 0f
        audioTracks = emptyList(); subtitleTracks = emptyList()
        state = State.Opening
        p.play()
    }

    fun attach(view: VLCVideoLayout) {
        layout = view
        player?.let { it.detachViews(); it.attachViews(view, null, true, false); it.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT }
    }

    fun detach(view: VLCVideoLayout) {
        if (layout !== view) return
        layout = null
        player?.detachViews()
    }

    fun togglePause() {
        val p = player ?: return
        when (state) {
            State.Playing -> p.pause()
            State.Paused -> p.play()
            State.Ended -> { p.stop(); p.play() }
            else -> {}
        }
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val target = if (length > 0) ms.coerceIn(0, length - 1000) else ms.coerceAtLeast(0)
        p.time = target
        time = target
    }

    fun seekBy(ms: Long) = seekTo(time + ms)

    fun selectAudio(id: Int) { player?.setAudioTrack(id); audioTrack = id }

    fun selectSubtitle(id: Int) { player?.setSpuTrack(id); subtitleTrack = id }

    /** Adds a subtitle file from an add-on (OpenSubtitles and others) and shows it. */
    fun addSubtitle(url: String) {
        player?.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(url), true)
    }

    private fun refreshTracks() {
        val p = player ?: return
        audioTracks = p.audioTracks?.filter { it.id >= 0 }?.map { Track(it.id, it.name) }.orEmpty()
        subtitleTracks = p.spuTracks?.filter { it.id >= 0 }?.map { Track(it.id, it.name) }.orEmpty()
        audioTrack = p.audioTrack
        subtitleTrack = p.spuTrack
    }

    fun stop() {
        player?.stop()
        state = State.Idle
        time = 0; length = 0
    }

    fun release() {
        player?.let { it.setEventListener(null); it.stop(); it.detachViews(); it.release() }
        player = null
    }
}
