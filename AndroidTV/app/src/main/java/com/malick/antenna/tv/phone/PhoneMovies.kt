package com.malick.antenna.tv.phone

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.StremioModel
import com.malick.antenna.tv.data.stremio.LibraryItem
import com.malick.antenna.tv.data.stremio.MetaPreview
import com.malick.antenna.tv.data.stremio.Stream
import com.malick.antenna.tv.data.stremio.Video
import com.malick.antenna.tv.ui.Palette

@Composable
fun PhoneMovies(vm: AppViewModel) {
    val s = vm.stremio
    val d = s.detail
    when {
        !s.signedIn -> StremioSignIn(s)
        d != null -> TitlePage(vm, d)
        else -> Board(vm)
    }
}

// ---- Sign-in: Stremio's own page, in the browser; the app notices by itself once you're in

@Composable
private fun StremioSignIn(s: StremioModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { s.startSignIn() }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Movies & Series", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Sign in with your Stremio account to bring your library, Continue Watching and add-ons here. What you watch is " +
            "saved back to your account, so the Stremio apps stay in sync.", color = Palette.muted, fontSize = 14.5.sp)
        s.signInError?.let {
            Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp)
            Button(onClick = { s.startSignIn() }) { Text("Try again") }
        }
        val code = s.linkCode
        if (code == null) {
            if (s.signInError == null) CircularProgressIndicator(color = Palette.accent)
        } else {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. Open Stremio's sign-in page and sign in as usual (Facebook, Apple or email).", fontSize = 14.sp, color = Palette.fg)
                Button(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.link))) } },
                    modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Open the sign-in page", fontWeight = FontWeight.SemiBold) }
                Text("2. Come back here: it updates by itself once you're in. The page may ask for this code:", fontSize = 14.sp, color = Palette.fg)
                Text(code.code, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Palette.accent, letterSpacing = 6.sp)
            }
        }
    }
}

// ---- The board: Continue Watching, your library, then every add-on catalog

@Composable
private fun Board(vm: AppViewModel) {
    val s = vm.stremio
    var query by rememberSaveable { mutableStateOf(s.searchQuery) }
    val listState = rememberLazyListState()
    val searching = query.trim().length >= 2
    BackHandler(enabled = query.isNotEmpty()) { query = ""; s.search("") }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = listState, contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(key = "header") {
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Movies & Series", fontSize = 28.sp, fontWeight = FontWeight.Black)
                SearchField(query, { query = it; s.search(it) }, "Search movies and series")
                if (!searching) Text(when {
                    s.loadError != null -> s.loadError!!
                    s.isRefreshing && s.addons.isEmpty() -> "Loading your Stremio library and add-ons…"
                    else -> "${s.addons.size} add-ons · ${s.libraryItems.size} in your library"
                }, fontSize = 12.5.sp, color = if (s.loadError != null) Color(0xFFFF8A80) else Palette.faint)
            }
        }
        if (searching) searchRows(s) else boardRows(s)
    }
}

private fun LazyListScope.boardRows(s: StremioModel) {
    val cw = s.continueWatching
    if (cw.isNotEmpty()) item(key = "cw") {
        Shelf("Continue watching", null) { items(cw, key = { "cw:" + it.id }) { LibraryTile(s, it, true) } }
    }
    val lib = s.libraryItems
    if (lib.isNotEmpty()) item(key = "lib") {
        Shelf("Your library", null) { items(lib.take(60), key = { "lib:" + it.id }) { LibraryTile(s, it, false) } }
    }
    items(s.boardCatalogs, key = { it.second.key }) { (addon, catalog) ->
        LaunchedEffect(catalog.key) { s.loadRow(addon, catalog) }
        CatalogShelf(s, catalog.name + " · " + typeLabel(catalog.type), addon.name, s.rows[catalog.key], catalog.key)
    }
}

private fun LazyListScope.searchRows(s: StremioModel) {
    val results = s.searchCatalogs.mapNotNull { (a, c) -> s.search[c.key]?.let { Triple(a, c, it) } }
    items(results, key = { "s:" + it.second.key }) { (addon, catalog, state) ->
        CatalogShelf(s, catalog.name + " · " + typeLabel(catalog.type), addon.name, state, "s:" + catalog.key)
    }
    if (results.isNotEmpty() && results.all { it.third == StremioModel.RowState.Failed }) item {
        Text("Nothing found for “${s.searchQuery.trim()}”.", color = Palette.muted, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun CatalogShelf(s: StremioModel, title: String, source: String, state: StremioModel.RowState?, key: String) {
    when (state) {
        is StremioModel.RowState.Loaded -> Shelf(title, source) {
            items(state.items, key = { "$key:" + it.id }) { m -> MetaTile(s, m) }
        }
        StremioModel.RowState.Failed -> Spacer(Modifier.height(0.dp))
        else -> Shelf(title, source) {
            items(4) { Box(Modifier.width(112.dp).height(168.dp).clip(RoundedCornerShape(10.dp)).background(Palette.surface2)) }
        }
    }
}

private fun typeLabel(type: String) = when (type) { "movie" -> "Movies"; "series" -> "Series"; "anime" -> "Anime"; else -> type.replaceFirstChar { it.uppercase() } }

@Composable
private fun Shelf(title: String, source: String?, content: LazyListScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            source?.let { Text(it, fontSize = 11.5.sp, color = Palette.faint, maxLines = 1) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun MetaTile(s: StremioModel, m: MetaPreview) =
    PosterTile(m.poster, m.name, m.releaseInfo, null, wide = m.posterShape == "landscape") { s.openDetail(m) }

@Composable
private fun LibraryTile(s: StremioModel, item: LibraryItem, progress: Boolean) =
    PosterTile(item.poster, item.name, if (progress) item.episodeLabel ?: remaining(item) else null, if (progress) item.progress else null,
        wide = item.posterShape == "landscape") { s.openDetail(item) }

private fun remaining(item: LibraryItem): String? {
    if (item.duration <= 0) return null
    return "${((item.duration - item.timeOffset) / 60_000).coerceAtLeast(1)} min left"
}

// ---- A title's page

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitlePage(vm: AppViewModel, d: StremioModel.Detail) {
    val s = vm.stremio
    val meta = d.meta?.preview ?: d.preview
    val item = s.libraryItem(d.preview.id)
    var choosing by remember { mutableStateOf<Pair<Video?, Stream>?>(null) }
    BackHandler { if (d.isSeries && d.sourcesFor != null) s.closeSources(d) else s.closeDetail() }

    // Every source asks where to play when a TV is around; otherwise it plays here.
    val pick: (Video?, Stream) -> Unit = { video, stream ->
        if (vm.phone.tv.devices.isEmpty()) s.play(d, video, stream) else choosing = video to stream
    }
    LaunchedEffect(Unit) { vm.phone.tv.startBrowsing() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "hero") {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 11f)) {
                (meta.background ?: meta.poster)?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66080A0E), Color.Transparent, Palette.bg))))
                IconButton(onClick = { s.closeDetail() }, modifier = Modifier.statusBarsPadding().padding(4.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.4f))) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp)) {
                    if (meta.logo != null) {
                        AsyncImage(model = meta.logo, contentDescription = meta.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                            modifier = Modifier.height(72.dp).fillMaxWidth(0.75f))
                    } else Text(meta.name, fontSize = 28.sp, fontWeight = FontWeight.Black, maxLines = 3)
                }
            }
        }
        item(key = "info") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val facts = listOfNotNull(meta.releaseInfo, meta.runtime, meta.imdbRating?.let { "★ $it" }, meta.genres.take(3).joinToString(" · ").ifEmpty { null })
                if (facts.isNotEmpty()) Text(facts.joinToString("   "), fontSize = 13.sp, color = Palette.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item != null && item.inContinueWatching) {
                        val label = buildString {
                            append("Resume"); item.episodeLabel?.let { append(" ${it.replace(" · ", "")}") }
                            if (item.duration > 0) append(" · ${((item.duration - item.timeOffset) / 60_000).coerceAtLeast(1)} min left")
                        }
                        Button(onClick = { s.resume(d) }, modifier = Modifier.weight(1f, fill = false)) {
                            Icon(AIcons.Play, null, modifier = Modifier.size(18.dp)); Text(label, modifier = Modifier.padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    OutlinedButton(onClick = { s.toggleLibrary(d) }) {
                        Icon(if (item?.inLibrary == true) Icons.Default.Check else Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Text(if (item?.inLibrary == true) "In library" else "Library", modifier = Modifier.padding(start = 6.dp), maxLines = 1, softWrap = false)
                    }
                }
                meta.description?.let { ExpandableText(it) }
                if (d.meta == null && !d.metaFailed) CircularProgressIndicator(color = Palette.accent, modifier = Modifier.size(24.dp))
            }
        }
        if (!d.isSeries) {
            sourceItems(s, d, null, pick)
        } else d.meta?.let { m ->
            val seasons = m.seasons
            val season = d.season ?: seasons.firstOrNull()
            if (seasons.size > 1) item(key = "seasons") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons) { n -> Pill(if (n == 0) "Specials" else "Season $n", n == season) { d.season = n } }
                }
            }
            val current = item?.videoId
            items(if (season != null) m.episodes(season) else m.videos, key = { "e:" + it.id }) { v ->
                EpisodeRow(s, d, v, v.id == current) { s.loadSources(d, v) }
            }
        }
        if (d.isSeries && d.metaFailed) item { Text("Couldn't load the episodes for this series.", color = Palette.muted, modifier = Modifier.padding(16.dp)) }
    }

    // A series' episode: its sources in a sheet.
    val episode = d.sourcesFor
    if (d.isSeries && episode != null) {
        ModalBottomSheet(onDismissRequest = { s.closeSources(d) }, sheetState = rememberModalBottomSheetState(), containerColor = Palette.surface) {
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { Text("${episode.label} · ${episode.title}", fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                sourceItems(s, d, episode, pick, rowColor = Palette.surface2)
            }
        }
    }

    choosing?.let { (video, stream) ->
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text("Where do you want to watch?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { choosing = null; s.play(d, video, stream) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(AIcons.Play, null, modifier = Modifier.size(18.dp)); Text("This phone", modifier = Modifier.padding(start = 8.dp))
                    }
                    vm.phone.tv.devices.forEach { tv ->
                        Button(onClick = { choosing = null; if (d.isSeries) s.closeSources(d); vm.phone.castStremio(d, video, stream, tv) }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.surface3, contentColor = Palette.fg)) {
                            Icon(AIcons.Cast, null, modifier = Modifier.size(18.dp)); Text(tv.displayName, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { choosing = null }) { Text("Cancel") } },
            containerColor = Palette.surface,
        )
    }
}

@Composable
private fun ExpandableText(text: String) {
    var open by remember { mutableStateOf(false) }
    Text(text, fontSize = 14.sp, color = Color(0xFFC3CAD6), maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable { open = !open })
}

@Composable
private fun EpisodeRow(s: StremioModel, d: StremioModel.Detail, v: Video, isCurrent: Boolean, onClick: () -> Unit) {
    val upcoming = v.released != null && v.released > System.currentTimeMillis()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Palette.surface2)) {
            v.thumbnail?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (isCurrent) s.libraryItem(d.preview.id)?.let {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(it.progress).height(3.dp).background(Palette.accent))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text((v.episode?.let { "$it. " } ?: "") + v.title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val info = listOfNotNull(
                if (upcoming) "Coming " + android.text.format.DateFormat.format("d MMM yyyy", v.released!!) else null,
                if (isCurrent) "Watching" else null,
            ).joinToString(" · ")
            if (info.isNotEmpty()) Text(info, fontSize = 12.sp, color = Palette.accent)
            v.overview?.let { Text(it, fontSize = 12.sp, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** The sources every add-on found, grouped by add-on, as they arrive. */
private fun LazyListScope.sourceItems(s: StremioModel, d: StremioModel.Detail, video: Video?, pick: (Video?, Stream) -> Unit, rowColor: Color = Palette.surface) {
    val entries = d.sources.toList()
    val loaded = entries.mapNotNull { (url, st) -> (st as? StremioModel.SourcesState.Loaded)?.let { url to it.streams } }
    val pending = d.sourcesPending
    item(key = "sources") {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text("Sources", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(when {
                d.sourcesVideoId == null -> "Looking…"
                pending > 0 -> "$pending add-on${if (pending == 1) "" else "s"} still looking…"
                loaded.isEmpty() -> ""
                else -> "${loaded.sumOf { it.second.size }} found"
            }, fontSize = 12.5.sp, color = Palette.faint)
        }
    }
    if (entries.isEmpty() && d.sourcesVideoId != null) item { Hint("None of your add-ons provide sources for this.") }
    else if (pending == 0 && loaded.isEmpty() && entries.isNotEmpty()) item { Hint("Your add-ons didn't find any sources for this one.") }
    loaded.forEach { (url, streams) ->
        item(key = "h:$url") {
            Text(s.addonName(url).uppercase(), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Palette.faint, letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp))
        }
        items(streams.size, key = { "$url#$it" }) { i -> SourceRow(streams[i], rowColor) { pick(video, streams[i]) } }
    }
    if (pending > 0) item(key = "pending") {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = Palette.accent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Asking your add-ons…", fontSize = 13.sp, color = Palette.muted)
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

@Composable
private fun SourceRow(stream: Stream, color: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(color)
        .clickable(onClick = onClick).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(96.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text((stream.name ?: stream.addonName).trim(), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val kind = when {
                stream.isTorrent -> "Torrent"
                stream.url != null -> "Direct link"
                stream.ytId != null -> "YouTube · not supported"
                else -> "Website only"
            }
            Text(kind, fontSize = 10.5.sp, color = if (stream.playable) Palette.faint else Color(0xFFFF8A80))
        }
        Text(stream.description ?: stream.filename ?: "", fontSize = 12.sp, color = Color(0xFFC3CAD6), maxLines = 5, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
    }
}

