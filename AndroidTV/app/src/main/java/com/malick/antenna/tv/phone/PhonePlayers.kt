package com.malick.antenna.tv.phone

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.MainActivity
import com.malick.antenna.tv.StremioModel
import com.malick.antenna.tv.player.TvPlayer
import com.malick.antenna.tv.player.VodPlayer
import com.malick.antenna.tv.ui.Palette
import com.malick.antenna.tv.ui.clock
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.abs

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Players take the whole screen: landscape, system bars hidden (a swipe from the edge shows them for a moment). */
@Composable
private fun FullscreenEffect() {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(Unit) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }
}

/**
 * Touch controls shared by both players: tap shows the controls, double-tap a side skips 10 s, a vertical drag on the
 * right changes the volume and on the left the brightness; a horizontal swipe calls [onSwipe] (next / previous channel).
 */
private class GestureState {
    var hint by mutableStateOf<String?>(null)
    var hintTick by mutableIntStateOf(0)
    fun flash(text: String) { hint = text; hintTick++ }
}

@Composable
private fun Modifier.playerGestures(state: GestureState, onTap: () -> Unit, onDoubleTap: (left: Boolean) -> Unit, onSwipe: ((next: Boolean) -> Unit)?): Modifier {
    val context = LocalContext.current
    val activity = context.findActivity()
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val density = LocalDensity.current
    return this
        .pointerInput(Unit) {
            detectTapGestures(onTap = { onTap() }, onDoubleTap = { o -> onDoubleTap(o.x < size.width / 2) })
        }
        .pointerInput(onSwipe != null) {
            var start = Offset.Zero
            var total = Offset.Zero
            var axis = 0 // 0 undecided, 1 horizontal, 2 vertical
            var startVolume = 0
            var startBrightness = 0.5f
            detectDragGestures(
                onDragStart = { o ->
                    start = o; total = Offset.Zero; axis = 0
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    startBrightness = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f
                },
                onDrag = { change, d ->
                    change.consume()
                    total += d
                    if (axis == 0 && total.getDistance() > with(density) { 16.dp.toPx() }) axis = if (abs(total.x) > abs(total.y)) 1 else 2
                    if (axis == 2) {
                        val fraction = -total.y / (size.height * 0.8f)
                        if (start.x > size.width / 2) {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val v = (startVolume + fraction * max).toInt().coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                            state.flash("Volume ${v * 100 / max.coerceAtLeast(1)}%")
                        } else activity?.let { a ->
                            val b = (startBrightness + fraction).coerceIn(0.02f, 1f)
                            a.window.attributes = a.window.attributes.apply { screenBrightness = b }
                            state.flash("Brightness ${(b * 100).toInt()}%")
                        }
                    }
                },
                onDragEnd = {
                    if (axis == 1 && onSwipe != null && abs(total.x) > with(density) { 90.dp.toPx() }) onSwipe(total.x < 0)
                },
            )
        }
}

@Composable
private fun BoxScope.GestureHint(state: GestureState) {
    LaunchedEffect(state.hintTick) { if (state.hint != null) { delay(900); state.hint = null } }
    state.hint?.let {
        Text(it, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 18.dp, vertical = 10.dp))
    }
}

@Composable
private fun BoxScope.TopShade() = Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(120.dp)
    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))))

@Composable
private fun BoxScope.BottomShade() = Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(150.dp)
    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))

@Composable
private fun BigButton(icon: ImageVector, label: String, size: Int = 56, onClick: () -> Unit) =
    RoundIcon(icon, label, size = size.dp, background = Color.Black.copy(alpha = 0.35f), onClick = onClick)

@Composable
private fun seekColors() = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Color.White.copy(alpha = 0.25f))

// ---- Live TV

@Composable
fun PhoneLivePlayer(vm: AppViewModel) {
    val ph = vm.phone
    val player = vm.player
    val ch = vm.playing ?: return
    val activity = LocalContext.current.findActivity() as? MainActivity
    var controls by remember { mutableStateOf(true) }
    var ping by remember { mutableIntStateOf(0) }
    val gestures = remember { GestureState() }
    LaunchedEffect(controls, ping) { if (controls) { delay(4500); controls = false } }
    BackHandler { vm.stopPlaying() }
    FullscreenEffect()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    this.player = player.exo
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (ph.pip) return@Box

        Box(Modifier.fillMaxSize().playerGestures(
            gestures,
            onTap = { controls = !controls; ping++ },
            onDoubleTap = { left ->
                if (player.canRewind) { player.seekBy(if (left) -10_000 else 10_000); gestures.flash(if (left) "−10 s" else "+10 s") }
            },
            onSwipe = { next -> vm.zap(if (next) 1 else -1) },
        ))

        when (val st = player.state) {
            TvPlayer.State.Opening -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (player.isSwitching) ChannelBadge(ch, 56.dp)
                CircularProgressIndicator(color = Color.White)
            }
            is TvPlayer.State.Failed -> Column(
                Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.8f)).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Can't play ${ch.displayName}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(st.message, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                Button(onClick = { player.retry() }) { Text("Try again") }
            }
            else -> {}
        }
        GestureHint(gestures)

        if (controls) {
            TopShade(); BottomShade()
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().safeDrawingPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.stopPlaying() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White) }
                ChannelBadge(ch, 30.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(ch.displayName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    vm.schedule(ch).current?.let { now ->
                        Text("${now.title} · ${clock(now.start)}–${clock(now.end)}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.5.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { vm.toggleFavorite(ch) }) {
                    Icon(if (vm.isFavorite(ch)) Icons.Default.Star else AIcons.StarOutline, "Favorite", tint = if (vm.isFavorite(ch)) Palette.accent else Color.White)
                }
                CastButton(vm, Color.White) { tv -> ph.castChannel(ch, tv) }
                IconButton(onClick = { activity?.enterPip() }) { Icon(AIcons.Pip, "Picture in picture", tint = Color.White) }
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                BigButton(AIcons.SkipPrevious, "Previous channel", 48) { vm.zap(-1); ping++ }
                if (player.canRewind) BigButton(AIcons.Replay, "Back 10 seconds", 48) { player.seekBy(-10_000); ping++ }
                BigButton(if (player.state == TvPlayer.State.Paused) AIcons.Play else AIcons.Pause, "Pause", 68) { player.togglePause(); ping++ }
                if (player.canRewind) BigButton(AIcons.Forward, "Forward 10 seconds", 48) { player.seekBy(10_000); ping++ }
                BigButton(AIcons.SkipNext, "Next channel", 48) { vm.zap(1); ping++ }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
                val t = player.timeline
                if (t != null) {
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = dragging ?: (t.playhead - t.start).toFloat() / t.lengthMs,
                        onValueChange = { dragging = it; ping++ },
                        onValueChangeFinished = { dragging?.let { player.seekTo(t.start + (it * t.lengthMs).toLong()) }; dragging = null },
                        colors = seekColors(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (player.isBehindLive && t != null) {
                        Text("−${hms(t.behindMs)}", color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { player.goLive(); ping++ }) { Text("Go live", color = Palette.accent, fontWeight = FontWeight.SemiBold) }
                    } else {
                        Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.live).padding(horizontal = 6.dp, vertical = 2.dp))
                        Spacer(Modifier.weight(1f))
                        Text("Swipe sideways to change channel", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ---- Movies & Series

private enum class TrackMenu { Subtitles, Audio }

@Composable
fun PhoneMoviePlayer(vm: AppViewModel, p: StremioModel.Playback) {
    val ph = vm.phone
    val s = vm.stremio
    val player = s.player
    val activity = LocalContext.current.findActivity() as? MainActivity
    var controls by remember { mutableStateOf(true) }
    var ping by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<TrackMenu?>(null) }
    val gestures = remember { GestureState() }
    val loading = p.error == null && (p.step != null || player.state == VodPlayer.State.Opening ||
        (player.state == VodPlayer.State.Playing && player.buffering < 100f && player.time < 1000))
    LaunchedEffect(controls, ping, menu) { if (controls && menu == null) { delay(4500); controls = false } }
    // At the end of an episode, carry on with the next one.
    LaunchedEffect(player.state) { if (player.state == VodPlayer.State.Ended && p.next != null) s.playNext() }
    BackHandler { if (menu != null) menu = null else s.stopPlayback() }
    FullscreenEffect()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> VLCVideoLayout(ctx).also { player.attach(it) } },
            onRelease = { player.detach(it) },
            modifier = Modifier.fillMaxSize(),
        )
        if (ph.pip) return@Box

        Box(Modifier.fillMaxSize().playerGestures(
            gestures,
            onTap = { controls = !controls; menu = null; ping++ },
            onDoubleTap = { left -> player.seekBy(if (left) -10_000 else 10_000); gestures.flash(if (left) "−10 s" else "+10 s") },
            onSwipe = null,
        ))

        if (loading) MovieLoading(p, player)
        p.error?.let { message ->
            Column(
                Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.85f)).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Can't play this source", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(message, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                Button(onClick = { s.stopPlayback() }) { Text("Pick another source") }
            }
        }
        GestureHint(gestures)

        if (controls || loading) {
            TopShade()
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().safeDrawingPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { s.stopPlayback() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White) }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(p.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    p.subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (!loading) {
                    IconButton(onClick = { menu = TrackMenu.Subtitles }) { Icon(AIcons.Subtitles, "Subtitles", tint = Color.White) }
                    if (player.audioTracks.size > 1) TextButton(onClick = { menu = TrackMenu.Audio }) { Text("Audio", color = Color.White) }
                }
                CastButton(vm, Color.White) { tv -> ph.moveMovieToTv(tv) }
                if (!loading) IconButton(onClick = { activity?.enterPip() }) { Icon(AIcons.Pip, "Picture in picture", tint = Color.White) }
            }
        }
        if (controls && !loading && p.error == null) {
            BottomShade()
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.CenterVertically) {
                BigButton(AIcons.Replay, "Back 10 seconds", 52) { player.seekBy(-10_000); ping++ }
                BigButton(if (player.state == VodPlayer.State.Playing) AIcons.Pause else AIcons.Play, "Pause", 70) { player.togglePause(); ping++ }
                BigButton(AIcons.Forward, "Forward 10 seconds", 52) { player.seekBy(10_000); ping++ }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                val length = player.length
                Slider(
                    value = dragging ?: if (length > 0) player.time.toFloat() / length else 0f,
                    onValueChange = { dragging = it; ping++ },
                    onValueChangeFinished = { dragging?.let { player.seekTo((it * length).toLong()) }; dragging = null },
                    enabled = length > 0, colors = seekColors(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shown = dragging?.let { (it * length).toLong() } ?: player.time
                    Text("${hms(shown)} / ${hms(length)}", color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    p.next?.let { next ->
                        TextButton(onClick = { s.playNext() }) {
                            Text("Next: ${next.label}", color = Palette.accent, fontWeight = FontWeight.SemiBold)
                            Icon(AIcons.SkipNext, null, tint = Palette.accent, modifier = Modifier.padding(start = 4.dp).size(18.dp))
                        }
                    }
                }
            }
        }
        menu?.let { m -> TrackPanel(vm, p, m) { menu = null } }
    }
}

@Composable
private fun BoxScope.MovieLoading(p: StremioModel.Playback, player: VodPlayer) {
    val meta = p.detail.meta?.preview ?: p.detail.preview
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        (meta.background ?: meta.poster)?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.3f)
        }
        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(color = Palette.accent)
            val step = p.step ?: if (player.buffering in 0.1f..99.9f) "Buffering ${player.buffering.toInt()}%" else "Opening"
            Text(step, fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f))
            p.torrentStats?.let { st ->
                val line = if (!st.hasMetadata) "Looking for peers…"
                else "${st.peers} peers · ${speed(st.downloadBps)} · ${st.bufferedAheadBytes / 1048576} MB ready"
                Text(line, fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.55f), fontFamily = FontFamily.Monospace)
            }
            Text(p.stream.title, fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center)
        }
    }
}

private fun speed(bps: Int) = if (bps >= 1024 * 1024) "%.1f MB/s".format(bps / 1048576.0) else "${bps / 1024} KB/s"

/** Subtitles (the file's own and the add-ons') or audio tracks, in a panel on the right. */
@Composable
private fun BoxScope.TrackPanel(vm: AppViewModel, p: StremioModel.Playback, menu: TrackMenu, close: () -> Unit) {
    val s = vm.stremio
    val player = s.player
    val options: List<Triple<String, Boolean, () -> Unit>> = when (menu) {
        TrackMenu.Subtitles -> buildList {
            add(Triple("Off", player.subtitleTrack < 0) { s.subtitlesOff() })
            player.subtitleTracks.forEach { t -> add(Triple(t.name, t.id == player.subtitleTrack) { player.selectSubtitle(t.id) }) }
            p.subtitles.forEach { sub -> add(Triple(StremioModel.languageName(sub.lang) + " · from add-on", false) { s.chooseSubtitle(sub) }) }
        }
        TrackMenu.Audio -> player.audioTracks.map { t -> Triple(t.name, t.id == player.audioTrack) { player.selectAudio(t.id) } }
    }
    Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = close))
    Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(300.dp).background(Palette.surface.copy(alpha = 0.97f)).safeDrawingPadding()) {
        Text(if (menu == TrackMenu.Subtitles) "Subtitles" else "Audio", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        LazyColumn {
            items(options) { (label, on, pick) ->
                Row(Modifier.fillMaxWidth().clickable { pick(); close() }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (on) Palette.accent else Palette.fg)
                    if (on) Icon(Icons.Default.Check, null, tint = Palette.accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
