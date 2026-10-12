package com.malick.antenna.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.data.Channel
import com.malick.antenna.tv.data.LiveCategory
import com.malick.antenna.tv.data.Programme

private const val HALF_HOUR = 30 * 60_000L
private const val WINDOW = 5 * HALF_HOUR // two and a half hours on screen

/** What the highlighted block is, shown above the grid. */
private data class Highlight(val channel: Channel, val programme: Programme?)

/**
 * Timeline guide: channels down the side, programmes laid out against the clock, a red line at now.
 * Left / Right past the first or last block moves the window half an hour.
 */
@Composable
fun GuideScreen(vm: AppViewModel) {
    val catId = vm.guideCategory ?: if (vm.favorites.isNotEmpty()) LiveCategory.FAVORITES else vm.categories.firstOrNull()?.id ?: LiveCategory.ALL
    val list = vm.channelsIn(catId)
    val floorNow = vm.now - vm.now % HALF_HOUR
    var windowStart by remember { mutableLongStateOf(floorNow) }
    var highlight by remember { mutableStateOf<Highlight?>(null) }
    val minStart = floorNow - 2 * HALF_HOUR
    val maxStart = floorNow + 24 * HALF_HOUR

    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        Row(Modifier.padding(horizontal = 32.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("TV Guide", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(android.text.format.DateFormat.format("EEEE d MMMM", windowStart).toString(), fontSize = 14.sp, color = Palette.muted,
                modifier = Modifier.padding(bottom = 4.dp))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val cats = listOf(LiveCategory(LiveCategory.FAVORITES, "Favorites")) + vm.categories
            items(cats, key = { it.id }) { cat ->
                val on = cat.id == catId
                Surface(
                    onClick = { vm.guideCategory = cat.id },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (on) Palette.fg else Palette.surface2, contentColor = if (on) Palette.bg else Palette.muted,
                        focusedContainerColor = Palette.accent, focusedContentColor = Palette.accentInk,
                    ),
                ) { Text(cat.name, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
            }
        }

        // Details of the highlighted programme.
        Column(Modifier.padding(horizontal = 32.dp).height(84.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val h = highlight
            if (h != null) {
                Text(h.programme?.title ?: h.channel.displayName, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    h.channel.displayName + (h.programme?.let { " · ${clock(it.start)} – ${clock(it.end)}" + if (it.isOn(vm.now)) " · on now" else "" } ?: ""),
                    fontSize = 13.sp, color = Palette.accent,
                )
                h.programme?.summary?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 13.sp, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (list.isEmpty()) {
            Text("Nothing to show here. Pick another category.", color = Palette.muted, modifier = Modifier.padding(32.dp))
            return@Column
        }

        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp)) {
            val channelWidth = 190.dp
            val gridWidth = maxWidth - channelWidth
            val perMs = gridWidth / WINDOW.toFloat()
            Column {
                // Time axis.
                Box(Modifier.padding(start = channelWidth).height(30.dp).fillMaxWidth()) {
                    for (i in 0 until (WINDOW / HALF_HOUR).toInt()) {
                        val t = windowStart + i * HALF_HOUR
                        Text(clock(t), fontSize = 12.sp, color = Palette.muted, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.offset(x = perMs * (t - windowStart).toFloat() + 6.dp, y = 6.dp))
                    }
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(list, key = { it.id }) { ch ->
                        GuideRow(vm, ch, list, windowStart, perMs, channelWidth, gridWidth,
                            onHighlight = { highlight = it },
                            onEdge = { forward ->
                                val next = windowStart + if (forward) HALF_HOUR else -HALF_HOUR
                                if (next in minStart..maxStart) { windowStart = next; true } else false
                            })
                    }
                }
            }
            // The red "now" line across the grid.
            val nowX = perMs * (vm.now - windowStart).toFloat()
            if (vm.now in windowStart until windowStart + WINDOW) {
                Box(Modifier.offset(x = channelWidth + nowX, y = 26.dp).width(2.dp).fillMaxHeight().background(Palette.live))
            }
        }
    }
}

@Composable
private fun GuideRow(
    vm: AppViewModel, ch: Channel, list: List<Channel>, windowStart: Long, perMs: Dp, channelWidth: Dp, gridWidth: Dp,
    onHighlight: (Highlight) -> Unit, onEdge: (forward: Boolean) -> Boolean,
) {
    LaunchedEffect(ch.id) { vm.requestEpg(ch) }
    val windowEnd = windowStart + WINDOW
    val progs = vm.programmes(ch).filter { it.end > windowStart && it.start < windowEnd }
    Row(Modifier.height(58.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(channelWidth).padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${ch.num}", fontSize = 12.sp, color = Palette.faint, fontFamily = FontFamily.Monospace, modifier = Modifier.width(30.dp))
            ChannelLogo(ch, 40.dp, 30.dp)
            Text(ch.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.width(gridWidth).fillMaxHeight()) {
            if (progs.isEmpty()) {
                GuideBlock(
                    title = if (vm.schedule(ch).isLoading) "Loading…" else "No guide information",
                    subtitle = "OK to watch", live = false, past = false, progress = 0f,
                    modifier = Modifier.width(gridWidth - 4.dp).fillMaxHeight().padding(vertical = 3.dp),
                    onFocus = { onHighlight(Highlight(ch, null)) },
                    onClick = { vm.play(ch, list) },
                    onKey = { forward -> onEdge(forward) },
                )
            }
            progs.forEachIndexed { i, p -> androidx.compose.runtime.key(p.start) {
                val x0 = maxOf(p.start, windowStart)
                val x1 = minOf(p.end, windowEnd)
                val live = p.isOn(vm.now)
                GuideBlock(
                    title = p.title,
                    subtitle = "${clock(p.start)} – ${clock(p.end)}",
                    live = live, past = p.end <= vm.now, progress = if (live) p.progress(vm.now) else 0f,
                    modifier = Modifier.offset(x = perMs * (x0 - windowStart).toFloat())
                        .width((perMs * (x1 - x0).toFloat() - 4.dp).coerceAtLeast(8.dp)).fillMaxHeight().padding(vertical = 3.dp),
                    onFocus = { onHighlight(Highlight(ch, p)) },
                    onClick = { vm.play(ch, list) },
                    // Only the first and last blocks move the window; others just move focus.
                    onKey = { forward -> if ((forward && i == progs.lastIndex) || (!forward && i == 0)) onEdge(forward) else false },
                )
            } }
        }
    }
}

@Composable
private fun GuideBlock(
    title: String, subtitle: String, live: Boolean, past: Boolean, progress: Float, modifier: Modifier,
    onFocus: () -> Unit, onClick: () -> Unit, onKey: (forward: Boolean) -> Boolean,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> onKey(true)
                    KeyEvent.KEYCODE_DPAD_LEFT -> onKey(false)
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(7.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (live) Palette.accentSoft else Palette.surface2,
            contentColor = Palette.fg.copy(alpha = if (past) 0.5f else 1f),
            focusedContainerColor = Palette.accent, focusedContentColor = Palette.accentInk,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (live && progress > 0f) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Palette.accent.copy(alpha = 0.12f)))
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                    color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.65f))
            }
        }
    }
}
