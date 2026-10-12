package com.malick.antenna.tv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.player.TvPlayer
import kotlinx.coroutines.delay

private enum class Control(val label: String) { Channels("Channels"), Back30("−30 s"), Pause("Pause"), Forward30("+30 s"), Live("Live") }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: AppViewModel) {
    val player = vm.player
    val focus = remember { FocusRequester() }
    var showInfo by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(Control.Pause) }
    var infoPing by remember { mutableIntStateOf(0) }
    var showList by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    var skipFlash by remember { mutableStateOf<String?>(null) }

    val controls = if (player.canRewind) Control.entries else listOf(Control.Channels, Control.Pause)

    fun poke() { showInfo = true; infoPing++ }
    fun seek(ms: Long) {
        if (!player.canRewind) { poke(); return }
        player.seekBy(ms)
        skipFlash = if (ms < 0) "−${-ms / 1000} s" else "+${ms / 1000} s"
        poke()
    }
    fun activate(c: Control) {
        when (c) {
            Control.Channels -> { showControls = false; showList = true }
            Control.Back30 -> seek(-30_000)
            Control.Pause -> player.togglePause()
            Control.Forward30 -> seek(30_000)
            Control.Live -> player.goLive()
        }
        poke()
    }

    // Info and controls hide after a few seconds of no buttons (not while paused).
    LaunchedEffect(infoPing) {
        delay(if (showControls) 6000 else 4000)
        if (player.state != TvPlayer.State.Paused) { showInfo = false; showControls = false }
    }
    LaunchedEffect(skipFlash) { if (skipFlash != null) { delay(900); skipFlash = null } }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1200)
        digits.toIntOrNull()?.let { vm.tune(it) }
        digits = ""
        poke()
    }
    LaunchedEffect(showList) { if (!showList) runCatching { focus.requestFocus() } }
    LaunchedEffect(player.isBehindLive) { poke() }

    BackHandler {
        when {
            showList -> showList = false
            showControls -> showControls = false
            showInfo -> showInfo = false
            else -> vm.stopPlaying()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (showList || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                when {
                    code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN -> { vm.zap(-1); poke(); true }
                    code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_UP -> { vm.zap(1); poke(); true }
                    showControls && code == KeyEvent.KEYCODE_DPAD_LEFT -> {
                        selected = controls[(controls.indexOf(selected) - 1).coerceAtLeast(0)]; poke(); true
                    }
                    showControls && code == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        selected = controls[(controls.indexOf(selected) + 1).coerceAtMost(controls.lastIndex)]; poke(); true
                    }
                    code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-10_000); true }
                    code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(10_000); true }
                    code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER -> {
                        when {
                            player.state is TvPlayer.State.Failed -> player.retry()
                            showControls -> activate(selected)
                            else -> { showControls = true; selected = if (selected in controls) selected else Control.Pause }
                        }
                        poke(); true
                    }
                    code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        player.togglePause(); poke(); true
                    }
                    code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { digits = (digits + (code - KeyEvent.KEYCODE_0)).takeLast(4); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
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
            modifier = Modifier.fillMaxSize(),
        )

        val ch = vm.playing
        if (player.state == TvPlayer.State.Opening) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (player.isSwitching) 0.9f else 0.35f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (player.isSwitching && ch != null) ChannelLogo(ch, 120.dp, 88.dp)
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
        (player.state as? TvPlayer.State.Failed)?.let { f ->
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Can't play this channel", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("${f.message} Press OK to try again, or Up / Down for another channel.", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            }
        }
        if (player.state == TvPlayer.State.Paused && !showControls) {
            Text(if (player.canRewind) "❚❚  Paused · OK for controls" else "❚❚  Paused · OK to resume live", color = Color.White, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 20.dp, vertical = 10.dp))
        }
        skipFlash?.let {
            Text(it, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 26.dp, vertical = 12.dp))
        }

        if ((showInfo || showControls) && ch != null) {
            val sched = vm.schedule(ch)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.92f)))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 40.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ChannelLogo(ch, 80.dp, 60.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${ch.num}", fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White)
                            Text(ch.displayName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            val t = player.timeline
                            if (player.isBehindLive && t != null) BehindBadge(t.behindMs) else LiveBadge()
                            QualityBadge(ch.quality)
                            if (player.resolution.isNotEmpty()) Text(player.resolution, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace)
                        }
                        Text(sched.current?.title ?: ch.displayName, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                        sched.upcoming.firstOrNull()?.let {
                            Text("Next at ${clock(it.start)} · ${it.title}", fontSize = 13.sp, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
                        }
                    }
                }
                player.timeline?.let { RewindBar(it, live = !player.isBehindLive) }
                if (showControls) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        controls.forEach { c ->
                            val on = c == selected
                            val label = if (c == Control.Pause && player.state == TvPlayer.State.Paused) "Play" else c.label
                            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                color = if (on) Palette.accentInk else Color.White,
                                modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(if (on) Palette.accent else Color.White.copy(alpha = 0.14f))
                                    .padding(horizontal = 18.dp, vertical = 9.dp))
                        }
                    }
                }
            }
            Text(
                if (player.canRewind) "Up / Down channel · Left / Right 10 s · OK controls · Back close"
                else "Up / Down channel · OK controls · 0–9 go to number · Back close",
                fontSize = 13.sp, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.TopEnd).padding(32.dp),
            )
        }

        if (digits.isNotEmpty()) {
            Text(digits, fontSize = 48.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 40.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 18.dp, vertical = 8.dp))
        }

        if (showList) ChannelListOverlay(vm) { showList = false }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun BehindBadge(behindMs: Long) {
    Text("−" + formatDuration(behindMs), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.22f)).padding(horizontal = 7.dp, vertical = 3.dp))
}

/** The recorded window of the current channel and where playback is within it. */
@Composable
private fun RewindBar(t: TvPlayer.Timeline, live: Boolean) {
    val fraction = if (live) 1f else ((t.playhead - t.start).toFloat() / t.lengthMs).coerceIn(0f, 1f)
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("−" + formatDuration(t.lengthMs), fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f), fontFamily = FontFamily.Monospace)
        Box(Modifier.weight(1f).height(16.dp).onSizeChanged { widthPx = it.width }, contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f)))
            Box(Modifier.fillMaxWidth(fraction).height(5.dp).clip(RoundedCornerShape(50)).background(if (live) Palette.live else Palette.accent))
            val x = with(density) { (widthPx * fraction).toDp() } - 7.dp
            Box(Modifier.offset(x = x.coerceAtLeast(0.dp)).size(14.dp).clip(CircleShape).background(Color.White).border(1.dp, Color.Black.copy(alpha = 0.3f), CircleShape))
        }
        Text(if (live) "LIVE" else "−" + formatDuration(t.behindMs), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (live) Palette.live else Color.White, fontFamily = FontFamily.Monospace, modifier = Modifier.width(64.dp))
    }
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun ChannelListOverlay(vm: AppViewModel, close: () -> Unit) {
    val list = vm.zapList
    val start = list.indexOfFirst { it.id == vm.playing?.id }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    val current = remember { FocusRequester() }
    Box(Modifier.fillMaxHeight().width(420.dp).background(Color(0xE60A0C10))) {
        LazyColumn(state = state, contentPadding = PaddingValues(16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { SectionLabel("Channels", Modifier.padding(start = 8.dp, bottom = 8.dp)) }
            items(list, key = { it.id }) { ch ->
                val sched = vm.schedule(ch)
                LaunchedEffect(ch.id) { vm.requestEpg(ch) }
                Surface(
                    onClick = { vm.play(ch, list); close() },
                    modifier = Modifier.fillMaxWidth().then(if (ch.id == vm.playing?.id) Modifier.focusRequester(current) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = tileColors(),
                    border = tileBorder(8.dp),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${ch.num}", fontSize = 12.sp, color = Palette.faint, fontFamily = FontFamily.Monospace, modifier = Modifier.width(36.dp))
                        ChannelLogo(ch, 40.dp, 30.dp)
                        Column {
                            Text(ch.displayName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(sched.current?.title ?: " ", fontSize = 12.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { current.requestFocus() } }
}
