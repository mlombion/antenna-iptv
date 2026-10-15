package com.malick.antenna.tv.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.data.Channel
import com.malick.antenna.tv.data.LiveCategory
import com.malick.antenna.tv.ui.Palette
import com.malick.antenna.tv.ui.ProgressLine
import com.malick.antenna.tv.ui.clock

@Composable
fun PhoneLive(vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val q = query.trim()
    val list = if (q.isEmpty()) vm.visibleChannels else vm.channelsIn(LiveCategory.ALL).filter { it.matches(q) }
    LaunchedEffect(vm.selectedCategory, q.isEmpty()) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Live TV", fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 10.dp))
        SearchField(query, { query = it }, "Search ${vm.channels.size} channels", Modifier.padding(horizontal = 16.dp))
        if (q.isEmpty()) {
            val cats = listOf(LiveCategory.ALL to "All", LiveCategory.FAVORITES to "Favorites") + vm.categories.map { it.id to it.name }
            val catState = rememberLazyListState()
            LazyRow(state = catState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cats, key = { it.first }) { (id, name) -> Pill(name, vm.selectedCategory == id) { vm.selectCategory(id) } }
            }
        } else Box(Modifier.height(12.dp))
        if (list.isEmpty()) {
            EmptyState(
                if (q.isEmpty()) Icons.Default.Star else Icons.Default.Search,
                if (q.isEmpty()) "Nothing here yet" else "No channels match \"$q\"",
                if (q.isEmpty() && vm.selectedCategory == LiveCategory.FAVORITES) "Hold a channel to add it to your favorites." else "Try another name or a channel number.",
            )
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { ch -> ChannelRow(vm, ch, list) }
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(Palette.surface2).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = Palette.muted, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            if (value.isEmpty()) Text(hint, color = Palette.faint, fontSize = 15.sp, maxLines = 1)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = Palette.fg, fontSize = 15.sp),
                cursorBrush = SolidColor(Palette.accent), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) Icon(Icons.Default.Clear, "Clear", tint = Palette.muted, modifier = Modifier.size(20.dp).clickable { onChange("") })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(vm: AppViewModel, ch: Channel, list: List<Channel>) {
    val ph = vm.phone
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(ch.id) { vm.requestEpg(ch) }
    val now = vm.schedule(ch).current
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = { vm.play(ch, list) }, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelBadge(ch, 40.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${ch.num}", color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                    Text(ch.displayName, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    ch.quality?.let {
                        Text(it, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Palette.muted,
                            modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp)).background(Palette.surface3).padding(horizontal = 4.dp, vertical = 1.dp))
                    }
                }
                if (now != null) {
                    Text("${clock(now.start)} · ${now.title}", fontSize = 13.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ProgressLine(now.progress(vm.now), Modifier.padding(top = 5.dp).width(120.dp), height = 2.dp)
                }
            }
            if (vm.isFavorite(ch)) Icon(Icons.Default.Star, "Favorite", tint = Palette.accent, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Watch on this phone") }, leadingIcon = { Icon(AIcons.Play, null) }, onClick = { menu = false; vm.play(ch, list) })
            TvMenuItems(vm) { tv -> menu = false; ph.castChannel(ch, tv) }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (vm.isFavorite(ch)) "Remove from favorites" else "Add to favorites") },
                leadingIcon = { Icon(if (vm.isFavorite(ch)) Icons.Default.Star else AIcons.StarOutline, null) },
                onClick = { menu = false; vm.toggleFavorite(ch) },
            )
        }
    }
}

/** "Play on <TV>" entries for every Antenna TV on the Wi-Fi. */
@Composable
fun TvMenuItems(vm: AppViewModel, onPick: (TvLink.Device) -> Unit) {
    val tv = vm.phone.tv
    LaunchedEffect(Unit) { tv.startBrowsing() }
    if (tv.devices.isEmpty()) {
        DropdownMenuItem(text = { Text("No TV found on this Wi-Fi", color = Palette.faint) }, leadingIcon = { Icon(AIcons.Tv, null, tint = Palette.faint) },
            onClick = {}, enabled = false)
    }
    tv.devices.forEach { d ->
        DropdownMenuItem(text = { Text("Play on ${d.displayName}") }, leadingIcon = { Icon(AIcons.Cast, null) }, onClick = { onPick(d) })
    }
}

/** A cast button that opens the list of TVs. */
@Composable
fun CastButton(vm: AppViewModel, tint: androidx.compose.ui.graphics.Color = Palette.fg, onPick: (TvLink.Device) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(AIcons.Cast, "Play on TV", tint = tint) }
        DropdownMenu(open, { open = false }) { TvMenuItems(vm) { open = false; onPick(it) } }
    }
}

// ---- Home

@Composable
fun PhoneHome(vm: AppViewModel) {
    val s = vm.stremio
    val ph = vm.phone
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AIcons.LiveTv, null, tint = Palette.accent, modifier = Modifier.size(26.dp))
                Text("Antenna", fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 10.dp).weight(1f))
                IconButton(onClick = { ph.tab = PhoneTab.Remote }) { Icon(AIcons.Remote, "Remote", tint = Palette.fg) }
            }
        }
        val continueWatching = s.continueWatching
        if (s.signedIn && continueWatching.isNotEmpty()) item {
            SectionTitle("Continue watching")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(continueWatching, key = { it.id }) { item ->
                    PosterTile(item.poster, item.name, item.episodeLabel, item.progress, wide = false) { s.openDetail(item); ph.tab = PhoneTab.Movies }
                }
            }
        }
        val recents = vm.recentChannels
        if (recents.isNotEmpty()) item {
            SectionTitle("Recently watched")
            ChannelStrip(vm, recents)
        }
        val favorites = vm.favoriteChannels
        if (favorites.isNotEmpty()) item {
            SectionTitle("Favorites")
            ChannelStrip(vm, favorites)
        }
        if (recents.isEmpty() && favorites.isEmpty()) item {
            EmptyState(AIcons.LiveTv, "Welcome", "Your ${vm.channels.size} channels are in Live TV. The ones you watch and your favorites show up here.",
                "Open Live TV") { ph.tab = PhoneTab.Live }
        }
        if (!s.signedIn) item {
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface)
                .clickable { ph.tab = PhoneTab.Movies }.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Movies & Series", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Sign in with your Stremio account to watch your library and add-ons here, or send them to the TV.",
                    fontSize = 14.sp, color = Palette.muted)
            }
        }
    }
}

@Composable
private fun ChannelStrip(vm: AppViewModel, list: List<Channel>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.id }) { ch ->
            LaunchedEffect(ch.id) { vm.requestEpg(ch) }
            val now = vm.schedule(ch).current
            Column(Modifier.width(150.dp).clip(RoundedCornerShape(14.dp)).background(Palette.surface).clickable { vm.play(ch, list) }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChannelBadge(ch, 36.dp)
                Text(ch.displayName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(now?.title ?: " ", fontSize = 12.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ProgressLine(now?.progress(vm.now) ?: 0f, Modifier.fillMaxWidth(), height = 2.dp)
            }
        }
    }
}

@Composable
fun PosterTile(poster: String?, title: String, caption: String?, progress: Float?, wide: Boolean, width: androidx.compose.ui.unit.Dp = 112.dp, onClick: () -> Unit) {
    val w = if (wide) width * 1.6f else width
    Column(Modifier.width(w).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth().height(if (wide) w * 0.5625f else w * 1.5f).clip(RoundedCornerShape(10.dp)).background(Palette.surface2)) {
            if (poster == null) Text(title, fontSize = 13.sp, color = Palette.muted, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            coil.compose.AsyncImage(model = poster, contentDescription = title, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp).background(Palette.accent))
            }
        }
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (caption != null) Text(caption, fontSize = 11.5.sp, color = Palette.muted, maxLines = 1)
    }
}

