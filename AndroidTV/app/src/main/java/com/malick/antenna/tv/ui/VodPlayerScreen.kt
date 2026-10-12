package com.malick.antenna.tv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.StremioModel
import com.malick.antenna.tv.player.VodPlayer
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout

private enum class VodControl { PlayPause, Back30, Forward30, Subtitles, Audio, Next }
private enum class Menu { Subtitles, Audio }

@Composable
fun VodPlayerScreen(vm: AppViewModel, p: StremioModel.Playback) {
    val s = vm.stremio
    val player = s.player
    val focus = remember { FocusRequester() }
    // The info bar (title, time) shows on any button; the buttons row only after OK, so Left / Right keep seeking until then.
    var showInfo by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }
    var ping by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(VodControl.PlayPause) }
    var menu by remember { mutableStateOf<Menu?>(null) }
    var skipFlash by remember { mutableStateOf<String?>(null) }
    val layout = remember { mutableStateOf<VLCVideoLayout?>(null) }

    val controls = VodControl.entries.filter { it != VodControl.Next || p.next != null }
    val loading = p.error == null && (p.step != null || player.state == VodPlayer.State.Opening ||
        (player.state == VodPlayer.State.Playing && player.buffering < 100f && player.time < 1000))
    val ended = player.state == VodPlayer.State.Ended

    fun poke() { showInfo = true; ping++ }
    fun seek(ms: Long) {
        player.seekBy(ms)
        skipFlash = if (ms < 0) "−${-ms / 1000} s" else "+${ms / 1000} s"
        poke()
    }
    fun activate(c: VodControl) {
        when (c) {
            VodControl.PlayPause -> player.togglePause()
            VodControl.Back30 -> seek(-30_000)
            VodControl.Forward30 -> seek(30_000)
            VodControl.Subtitles -> menu = Menu.Subtitles
            VodControl.Audio -> menu = Menu.Audio
            VodControl.Next -> s.playNext()
        }
        poke()
    }

    LaunchedEffect(ping) {
        delay(5000)
        if (player.state == VodPlayer.State.Playing && menu == null) { showInfo = false; showControls = false }
    }
    LaunchedEffect(skipFlash) { if (skipFlash != null) { delay(900); skipFlash = null } }
    LaunchedEffect(menu) { if (menu == null) runCatching { focus.requestFocus() } }

    BackHandler {
        when {
            menu != null -> menu = null
            showControls -> showControls = false
            showInfo && player.state == VodPlayer.State.Playing -> showInfo = false
            else -> s.stopPlayback()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (menu != null || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                when {
                    ended && p.next != null && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) -> { s.playNext(); true }
                    code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN -> { poke(); true }
                    showControls && code == KeyEvent.KEYCODE_DPAD_LEFT -> {
                        selected = controls[(controls.indexOf(selected) - 1).coerceAtLeast(0)]; poke(); true
                    }
                    showControls && code == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        selected = controls[(controls.indexOf(selected) + 1).coerceAtMost(controls.lastIndex)]; poke(); true
                    }
                    code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-10_000); true }
                    code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(10_000); true }
                    code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER -> {
                        if (showControls) activate(if (selected in controls) selected else VodControl.PlayPause)
                        else { showControls = true; selected = VodControl.PlayPause; poke() }
                        true
                    }
                    code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        player.togglePause(); poke(); true
                    }
                    code == KeyEvent.KEYCODE_MEDIA_NEXT && p.next != null -> { s.playNext(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(factory = { ctx -> VLCVideoLayout(ctx).also { layout.value = it; player.attach(it) } }, modifier = Modifier.fillMaxSize())

        if (loading) LoadingCover(p, player)
        p.error?.let { message ->
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.8f)).padding(28.dp).width(560.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Can't play this source", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(message, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                Text("Press Back to pick another source.", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
            }
        }
        if (ended && p.next != null) {
            Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.8f)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Up next", fontSize = 13.sp, color = Palette.accent, fontWeight = FontWeight.Bold)
                Text("${p.next!!.label} · ${p.next!!.title}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Press OK to play it, or Back to stop.", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
            }
        }
        skipFlash?.let {
            Text(it, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 26.dp, vertical = 12.dp))
        }

        if ((showInfo || showControls || player.state == VodPlayer.State.Paused) && !loading && p.error == null) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.92f)))))
            Column(Modifier.align(Alignment.TopStart).padding(40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                p.subtitle?.let { Text(it, fontSize = 15.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1) }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 40.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SeekBar(player.time, player.length)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showControls) controls.forEach { c ->
                        val on = c == selected
                        val label = when (c) {
                            VodControl.PlayPause -> if (player.state == VodPlayer.State.Paused) "Play" else "Pause"
                            VodControl.Back30 -> "−30 s"
                            VodControl.Forward30 -> "+30 s"
                            VodControl.Subtitles -> "Subtitles"
                            VodControl.Audio -> "Audio"
                            VodControl.Next -> "Next episode"
                        }
                        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = if (on) Palette.accentInk else Color.White,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (on) Palette.accent else Color.White.copy(alpha = 0.14f))
                                .padding(horizontal = 18.dp, vertical = 9.dp))
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    p.torrentStats?.takeIf { it.peers > 0 }?.let { st ->
                        Text("${st.peers} peers · ${speed(st.downloadBps)}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Text(if (showControls) "Left / Right choose · OK select · Back hide" else "Left / Right 10 s · OK controls · Back close", fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.TopEnd).padding(40.dp))
        }

        when (menu) {
            Menu.Subtitles -> TrackMenu(
                title = "Subtitles",
                options = buildList {
                    add(Triple("off", "Off", player.subtitleTrack < 0))
                    player.subtitleTracks.forEach { add(Triple("t${it.id}", it.name, it.id == player.subtitleTrack)) }
                    p.subtitles.forEach { add(Triple("a${it.id}", StremioModel.languageName(it.lang) + " · from add-on", false)) }
                },
                close = { menu = null },
            ) { key ->
                when {
                    key == "off" -> s.subtitlesOff()
                    key.startsWith("t") -> player.selectSubtitle(key.drop(1).toInt())
                    else -> p.subtitles.firstOrNull { "a${it.id}" == key }?.let { s.chooseSubtitle(it) }
                }
                menu = null
            }
            Menu.Audio -> TrackMenu(
                title = "Audio",
                options = player.audioTracks.map { Triple("t${it.id}", it.name, it.id == player.audioTrack) }
                    .ifEmpty { listOf(Triple("none", "Only one audio track", true)) },
                close = { menu = null },
            ) { key ->
                if (key.startsWith("t")) player.selectAudio(key.drop(1).toInt())
                menu = null
            }
            null -> {}
        }
    }

    DisposableEffect(Unit) { onDispose { layout.value?.let(player::detach) } }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** Poster, title and what's happening while a source starts (torrents take a few seconds). */
@Composable
private fun LoadingCover(p: StremioModel.Playback, player: VodPlayer) {
    val meta = p.detail.meta?.preview ?: p.detail.preview
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        (meta.background ?: meta.poster)?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.3f)
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(p.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
            p.subtitle?.let { Text(it, fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f)) }
            CircularProgressIndicator(color = Palette.accent, modifier = Modifier.padding(top = 8.dp))
            val step = p.step ?: if (player.buffering in 0.1f..99.9f) "Buffering ${player.buffering.toInt()}%" else "Opening"
            Text(step, fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f))
            p.torrentStats?.let { st ->
                val line = if (!st.hasMetadata) "Looking for peers…"
                else "${st.peers} peers · ${speed(st.downloadBps)} · ${mb(st.bufferedAheadBytes)} ready"
                Text(line, fontSize = 13.sp, color = Color.White.copy(alpha = 0.55f), fontFamily = FontFamily.Monospace)
            }
            Text(p.stream.title, fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(560.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun SeekBar(time: Long, length: Long) {
    val fraction = if (length > 0) (time.toFloat() / length).coerceIn(0f, 1f) else 0f
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(hms(time), fontSize = 13.sp, color = Color.White, fontFamily = FontFamily.Monospace)
        Box(Modifier.weight(1f).height(16.dp).onSizeChanged { widthPx = it.width }, contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f)))
            Box(Modifier.fillMaxWidth(fraction).height(5.dp).clip(RoundedCornerShape(50)).background(Palette.accent))
            val x = with(density) { (widthPx * fraction).toDp() } - 7.dp
            Box(Modifier.offset(x = x.coerceAtLeast(0.dp)).size(14.dp).clip(CircleShape).background(Color.White).border(1.dp, Color.Black.copy(alpha = 0.3f), CircleShape))
        }
        Text(if (length > 0) "−" + hms(length - time) else "--:--", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TrackMenu(title: String, options: List<Triple<String, String, Boolean>>, close: () -> Unit, choose: (String) -> Unit) {
    val first = remember { FocusRequester() }
    val target = options.firstOrNull { it.third }?.first ?: options.firstOrNull()?.first
    BackHandler { close() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(440.dp).background(Color(0xF00A0C10))) {
            LazyColumn(contentPadding = PaddingValues(18.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { SectionLabel(title, Modifier.padding(start = 8.dp, bottom = 10.dp)) }
                items(options, key = { it.first }) { (key, label, on) ->
                    Surface(
                        onClick = { choose(key) },
                        modifier = Modifier.fillMaxWidth().then(if (key == target) Modifier.focusRequester(first) else Modifier),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = tileColors(),
                        border = tileBorder(8.dp),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (on) Text("✓", color = Palette.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
}

private fun hms(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0).toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun speed(bps: Int) = if (bps >= 1024 * 1024) "%.1f MB/s".format(bps / 1048576.0) else "${bps / 1024} KB/s"
private fun mb(bytes: Long) = "${bytes / 1048576} MB"
