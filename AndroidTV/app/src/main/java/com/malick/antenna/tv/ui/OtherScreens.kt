package com.malick.antenna.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.data.Channel
import com.malick.antenna.tv.data.LiveCategory
import com.malick.antenna.tv.data.Programme
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(vm: AppViewModel) {
    val featured = vm.recentChannels.firstOrNull() ?: vm.favoriteChannels.firstOrNull() ?: vm.channels.firstOrNull()
    LazyColumn(contentPadding = PaddingValues(top = 28.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        if (featured != null) item { Hero(vm, featured) }
        item { Rail(vm, "Recently watched", vm.recentChannels) }
        item { Rail(vm, "Your favorites", vm.favoriteChannels) }
        items(vm.categories.take(4), key = { it.id }) { cat -> Rail(vm, cat.name, vm.channelsIn(cat.id).take(24)) }
    }
}

@Composable
private fun Hero(vm: AppViewModel, ch: Channel) {
    val sched = vm.schedule(ch)
    LaunchedEffect(ch.id) { vm.requestEpg(ch) }
    Surface(
        onClick = { vm.play(ch) },
        modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth().height(300.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Palette.surface, focusedContainerColor = Palette.surface),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = tileBorder(18.dp),
    ) {
        Box(Modifier.fillMaxSize().channelBackdrop(ch)) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 70.dp)) { ChannelLogo(ch, 220.dp, 160.dp) }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF0080A0E), Color(0x8C080A0E), Color.Transparent))))
            Column(Modifier.align(Alignment.BottomStart).padding(32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveBadge(); Text("${ch.displayName} · ${ch.num}", color = Palette.muted, fontSize = 13.sp); QualityBadge(ch.quality)
                }
                Text(sched.current?.title ?: ch.displayName, fontSize = 36.sp, fontWeight = FontWeight.Black, maxLines = 2)
                sched.current?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProgressLine(it.progress(vm.now), Modifier.width(200.dp))
                        Text("${((it.end - vm.now) / 60000).coerceAtLeast(1)} min left", color = Color(0xFFC3CAD6), fontSize = 13.sp)
                    }
                }
                Text("Press OK to watch live", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Rail(vm: AppViewModel, title: String, list: List<Channel>) {
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 32.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(list, key = { it.id }) { ChannelCard(vm, it, list) }
        }
    }
}

@Composable
fun FavoritesScreen(vm: AppViewModel) {
    val favs = vm.favoriteChannels
    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        Text("Favorites", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 32.dp))
        if (favs.isEmpty()) {
            Text("No favorites yet. In Live TV, hold OK on a channel to add it here.", color = Palette.muted,
                modifier = Modifier.padding(32.dp))
        } else {
            LazyVerticalGrid(GridCells.Adaptive(240.dp), contentPadding = PaddingValues(32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(favs, key = { it.id }) { ChannelCard(vm, it, favs, width = 260.dp) }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val info = vm.accountInfo
    LazyColumn(contentPadding = PaddingValues(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.SemiBold) }
        item {
            Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(vm.account?.name ?: "", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("${vm.account?.username} · ${vm.account?.server}", color = Palette.muted, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("${vm.channels.size} channels · ${info?.status ?: "—"}" +
                    (info?.expiry?.let { " · until " + android.text.format.DateFormat.format("d MMM yyyy", it * 1000) } ?: "") +
                    (info?.maxConnections?.let { " · $it screen" + if (it > 1) "s" else "" } ?: ""), color = Palette.muted, fontSize = 13.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { scope.launch { vm.refresh(silent = false) } }) { Text("Refresh channels") }
                Button(onClick = { vm.setHideAdultCategories(!vm.hideAdult) }) {
                    Text(if (vm.hideAdult) "Adult categories: hidden" else "Adult categories: shown")
                }
                Button(onClick = {
                    vm.storage.format = if (vm.storage.format == "ts") "m3u8" else "ts"
                    vm.showToast("Stream format: ${if (vm.storage.format == "ts") "MPEG-TS" else "HLS"}")
                }) { Text("Switch stream format") }
                Button(onClick = {
                    val next = when (vm.storage.rewindMinutes) { 0 -> 15; 15 -> 30; else -> 0 }
                    vm.storage.rewindMinutes = next
                    vm.showToast(if (next == 0) "Rewind off" else "Rewind keeps the last $next minutes")
                }) { Text(if (vm.storage.rewindMinutes == 0) "Rewind: off" else "Rewind: ${vm.storage.rewindMinutes} min") }
                Button(onClick = { vm.signOut() }, colors = ButtonDefaults.colors(containerColor = Palette.live.copy(alpha = 0.2f))) {
                    Text("Sign out")
                }
            }
        }
        item {
            val st = vm.stremio
            Column(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Palette.surface).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Movies & Series · Stremio", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(if (st.signedIn) "Signed in as ${st.email ?: "your Stremio account"} · ${st.addons.size} add-ons · ${st.libraryItems.size} in your library"
                    else "Not signed in. Open Movies & Series to sign in with your Stremio account.", color = Palette.muted, fontSize = 13.sp)
                if (st.signedIn) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { scope.launch { st.refresh(); vm.showToast("Stremio library and add-ons refreshed") } }) { Text("Refresh library & add-ons") }
                    Button(onClick = { st.signOut() }, colors = ButtonDefaults.colors(containerColor = Palette.live.copy(alpha = 0.2f))) {
                        Text("Sign out of Stremio")
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Remote")
                listOf(
                    "OK" to "Watch the selected channel · hold to add or remove a favorite",
                    "Up / Down" to "Change channel while watching (also Channel + / −)",
                    "0–9" to "Go to a channel number",
                    "Left / Right" to "Rewind or skip 10 seconds while watching",
                    "OK" to "Controls while watching: channel list, −30 s, pause, +30 s, live",
                    "Back" to "Close the player",
                ).forEach { (k, v) ->
                    Row { Text(k, color = Palette.accent, fontSize = 14.sp, modifier = Modifier.width(110.dp)); Text(v, color = Palette.muted, fontSize = 14.sp) }
                }
            }
        }
    }
}
