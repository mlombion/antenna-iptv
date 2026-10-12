package com.malick.antenna.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.data.Channel
import com.malick.antenna.tv.data.LiveCategory

@Composable
fun LiveScreen(vm: AppViewModel) {
    val list = vm.visibleChannels
    Row(Modifier.fillMaxSize()) {
        CategoryColumn(vm)
        ChannelColumn(vm, list, Modifier.weight(1.25f))
        Box(Modifier.weight(1f).fillMaxHeight()) { vm.selectedChannel?.let { ChannelDetail(vm, it) } }
    }
}

@Composable
private fun CategoryColumn(vm: AppViewModel) {
    LazyColumn(
        Modifier.width(220.dp).fillMaxHeight().background(Palette.surface),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { SectionLabel("Categories", Modifier.padding(start = 10.dp, bottom = 10.dp)) }
        item { CategoryRow(vm, LiveCategory.ALL, "All channels") }
        item { CategoryRow(vm, LiveCategory.FAVORITES, "Favorites") }
        items(vm.categories, key = { it.id }) { CategoryRow(vm, it.id, it.name) }
    }
}

@Composable
private fun CategoryRow(vm: AppViewModel, id: String, name: String) {
    val selected = vm.selectedCategory == id
    Surface(
        onClick = { vm.selectCategory(id) },
        modifier = Modifier.fillMaxWidth().height(40.dp)
            // Moving onto a category shows its channels straight away, like flipping through a guide.
            .onFocusChanged { if (it.isFocused) vm.selectCategory(id) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.accentSoft else Color.Transparent,
            contentColor = if (selected) Palette.accent else Palette.muted,
            focusedContainerColor = Palette.accent, focusedContentColor = Palette.accentInk,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${vm.channelsIn(id).size}", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun ChannelColumn(vm: AppViewModel, list: List<Channel>, modifier: Modifier) {
    val state = rememberLazyListState()
    val first = remember { FocusRequester() }
    Column(modifier.fillMaxHeight().padding(top = 28.dp)) {
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            Text(vm.categoryName(vm.selectedCategory), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.weight(1f))
            Text("${list.size} channels", fontSize = 13.sp, color = Palette.faint, fontFamily = FontFamily.Monospace)
        }
        if (list.isEmpty()) {
            Text(
                if (vm.selectedCategory == LiveCategory.FAVORITES) "No favorites yet. Hold OK on a channel to add it." else "No channels here.",
                color = Palette.muted, modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(state = state, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 4.dp, 12.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(list, key = { it.id }) { ch ->
                    ChannelRow(vm, ch, list, if (ch.id == list.first().id) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun ChannelRow(vm: AppViewModel, ch: Channel, list: List<Channel>, modifier: Modifier) {
    val sched = vm.schedule(ch)
    LaunchedEffect(ch.id) { vm.requestEpg(ch) }
    Surface(
        onClick = { vm.play(ch, list) },
        onLongClick = { vm.toggleFavorite(ch) },
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) vm.selectedChannelId = ch.id },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = tileColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = tileBorder(10.dp),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${ch.num}", fontSize = 12.sp, color = Palette.faint, fontFamily = FontFamily.Monospace, modifier = Modifier.width(30.dp))
            ChannelLogo(ch, 48.dp, 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(ch.displayName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    QualityBadge(ch.quality)
                    if (vm.isFavorite(ch)) Text("★", color = Palette.accent, fontSize = 12.sp)
                }
                Text(sched.current?.title ?: if (sched.isLoading) " " else "No guide information", fontSize = 13.sp,
                    color = if (sched.current == null) Palette.faint else Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                sched.current?.let { ProgressLine(it.progress(vm.now), Modifier.fillMaxWidth(), height = 2.5.dp) }
            }
            sched.current?.let {
                Text("${((it.end - vm.now) / 60000).coerceAtLeast(1)}m", fontSize = 12.sp, color = Palette.faint, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ChannelDetail(vm: AppViewModel, ch: Channel) {
    val sched = vm.schedule(ch)
    Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 32.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).channelBackdrop(ch),
            contentAlignment = Alignment.Center) {
            ChannelLogo(ch, 150.dp, 110.dp)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
            Row(Modifier.align(Alignment.TopStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveBadge(); QualityBadge(ch.quality)
            }
            Text("Press OK to watch", fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChannelLogo(ch, 56.dp, 42.dp)
            Column {
                Text(ch.displayName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Channel ${ch.num} · ${vm.categoryName(ch.categoryId)}", fontSize = 13.sp, color = Palette.muted)
            }
        }
        val cur = sched.current
        if (cur != null) {
            Text(cur.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(clock(cur.start), fontSize = 13.sp, color = Palette.muted, fontFamily = FontFamily.Monospace)
                ProgressLine(cur.progress(vm.now), Modifier.weight(1f))
                Text(clock(cur.end), fontSize = 13.sp, color = Palette.muted, fontFamily = FontFamily.Monospace)
            }
            if (cur.summary.isNotBlank()) Text(cur.summary, fontSize = 14.sp, color = Palette.muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
        } else if (!sched.isLoading) {
            Text("Live", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("This channel doesn't publish a TV guide.", color = Palette.muted)
        }
        if (sched.upcoming.isNotEmpty()) {
            SectionLabel("Coming up")
            Column {
                sched.upcoming.take(4).forEach { p ->
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Text(clock(p.start), fontSize = 13.sp, color = Palette.muted, fontFamily = FontFamily.Monospace, modifier = Modifier.width(64.dp))
                        Text(p.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("OK watch · hold OK to favorite", fontSize = 12.sp, color = Palette.faint)
    }
}
