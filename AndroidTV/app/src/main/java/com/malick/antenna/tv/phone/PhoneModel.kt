package com.malick.antenna.tv.phone

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.StremioModel
import com.malick.antenna.tv.data.Channel
import com.malick.antenna.tv.data.stremio.Stream
import com.malick.antenna.tv.data.stremio.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class PhoneTab { Home, Live, Movies, Remote, Settings }

/** The phone's own state: the TVs it can send to, the remote's connection, and which screen shows. */
class PhoneModel(private val vm: AppViewModel, app: Application, private val scope: CoroutineScope) {
    val tv = TvLink(app, scope)
    val pointer = PointerLink(scope)

    var tab by mutableStateOf(PhoneTab.Home)
    /** The activity is showing as a floating picture-in-picture window. */
    var pip by mutableStateOf(false)
    /** A live channel or a movie fills the screen (picture in picture takes over when you leave the app). */
    val playerOpen get() = vm.playing != null || vm.stremio.playback != null

    // ---- Play on TV

    private fun accountJson(): JSONObject? = vm.account?.let {
        JSONObject().put("name", it.name).put("server", it.server).put("username", it.username).put("password", it.password)
    }

    fun castChannel(c: Channel, device: TvLink.Device) {
        // Free the subscription's connection before the TV asks for it.
        vm.stopPlaying()
        scope.launch {
            val error = tv.playChannel(c.id, accountJson(), device)
            vm.showToast(error ?: "Playing ${c.displayName} on ${device.displayName}")
        }
    }

    fun castStremio(d: StremioModel.Detail, video: Video?, stream: Stream, device: TvLink.Device, startAt: Long? = null) {
        val s = vm.stremio
        val meta = d.meta?.preview ?: d.preview
        val videoId = video?.id ?: d.sourcesVideoId ?: d.preview.id
        val body = JSONObject().put("type", meta.type).put("id", meta.id).put("videoId", videoId).put("name", meta.name)
            .put("addon", stream.addonName).put("stream", stream.json)
        meta.poster?.let { body.put("poster", it) }
        meta.background?.let { body.put("background", it) }
        video?.title?.takeIf { it.isNotBlank() }?.let { body.put("episodeTitle", it) }
        s.authKey?.let { body.put("authKey", it) }
        startAt?.let { body.put("startAt", it) }
        s.stopPlayback()
        vm.stopPlaying()
        scope.launch {
            val error = tv.playStremio(body, device)
            vm.showToast(error ?: "Playing on ${device.displayName}")
        }
    }

    /** The movie playing on the phone carries on on the TV, from the same moment. */
    fun moveMovieToTv(device: TvLink.Device) {
        val p = vm.stremio.playback ?: return
        castStremio(p.detail, p.detail.meta?.videos?.firstOrNull { it.id == p.videoId } ?: p.video, p.stream, device,
            startAt = vm.stremio.player.time.takeIf { it > 5_000 })
    }

    fun release() {
        tv.release()
        pointer.disconnect()
    }
}
