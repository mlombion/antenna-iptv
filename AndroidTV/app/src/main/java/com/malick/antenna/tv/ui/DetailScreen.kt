package com.malick.antenna.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.StremioModel
import com.malick.antenna.tv.data.stremio.Stream
import com.malick.antenna.tv.data.stremio.Video
import kotlinx.coroutines.delay

/** A movie's or series' page: details, then episodes and the sources your add-ons found. */
@Composable
fun DetailScreen(vm: AppViewModel, d: StremioModel.Detail) {
    val s = vm.stremio
    val meta = d.meta?.preview ?: d.preview
    val item = s.libraryItem(d.preview.id)
    val showingSources = !d.isSeries || d.sourcesFor != null

    BackHandler {
        if (d.isSeries && d.sourcesFor != null) s.closeSources(d) else s.closeDetail()
    }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        (meta.background ?: meta.poster)?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.45f)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF5080A0E), Color(0xD9080A0E), Color(0x99080A0E)))))
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            // Left: the title and what it's about.
            Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Spacer(Modifier.weight(1f))
                if (meta.logo != null) {
                    AsyncImage(model = meta.logo, contentDescription = meta.name, contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart, modifier = Modifier.heightIn(max = 110.dp).fillMaxWidth())
                } else {
                    Text(meta.name, fontSize = 38.sp, fontWeight = FontWeight.Black, maxLines = 3)
                }
                val facts = listOfNotNull(meta.releaseInfo, meta.runtime, meta.imdbRating?.let { "★ $it" }, meta.genres.take(3).joinToString(" · ").ifEmpty { null })
                if (facts.isNotEmpty()) Text(facts.joinToString("   "), fontSize = 14.sp, color = Color(0xFFC3CAD6))
                meta.description?.let { Text(it, fontSize = 15.sp, color = Color(0xFFC3CAD6), maxLines = 6, overflow = TextOverflow.Ellipsis) }
                if (d.meta == null && !d.metaFailed) CircularProgressIndicator(color = Palette.accent, modifier = Modifier.size(28.dp))
                val resumable = item != null && item.inContinueWatching
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (resumable) {
                        val label = buildString {
                            append("Resume")
                            item.episodeLabel?.let { append(" $it") }
                            if (item.duration > 0) append(" · ${((item.duration - item.timeOffset) / 60_000).coerceAtLeast(1)} min left")
                        }
                        PillButton(label, primary = true, requestFocus = true) { s.resume(d) }
                    }
                    PillButton(if (item?.inLibrary == true) "✓ In your library" else "+ Add to library", primary = false,
                        requestFocus = !resumable) { s.toggleLibrary(d) }
                }
                Spacer(Modifier.weight(1f))
            }
            // Right: episodes, or the sources for the movie / chosen episode.
            Box(Modifier.weight(1.1f).fillMaxHeight()) {
                when {
                    showingSources -> SourcesPanel(s, d)
                    d.meta != null -> EpisodesPanel(s, d)
                    d.metaFailed -> Text("Couldn't load the episodes for this series.", color = Palette.muted, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
private fun PillButton(label: String, primary: Boolean, requestFocus: Boolean = false, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    Surface(
        onClick = onClick,
        modifier = Modifier.focusRequester(focus),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Palette.accent else Color.White.copy(alpha = 0.12f),
            contentColor = if (primary) Palette.accentInk else Palette.fg,
            focusedContainerColor = if (primary) Color(0xFFFFC75E) else Color.White, focusedContentColor = Palette.accentInk,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 11.dp))
    }
    if (requestFocus) LaunchedEffect(Unit) { delay(80); runCatching { focus.requestFocus() } }
}

@Composable
private fun EpisodesPanel(s: StremioModel, d: StremioModel.Detail) {
    val meta = d.meta ?: return
    val seasons = meta.seasons
    val season = d.season ?: seasons.firstOrNull()
    val episodes = if (season != null) meta.episodes(season) else meta.videos
    val current = s.libraryItem(d.preview.id)?.videoId
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { n ->
                    val on = n == season
                    Surface(
                        onClick = { d.season = n },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (on) Palette.accent else Palette.surface2, contentColor = if (on) Palette.accentInk else Palette.muted,
                            focusedContainerColor = Color.White, focusedContentColor = Palette.accentInk,
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                    ) {
                        Text(if (n == 0) "Specials" else "Season $n", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(episodes, key = { it.id }) { v -> EpisodeRow(s, d, v, isCurrent = v.id == current) }
        }
    }
}

@Composable
private fun EpisodeRow(s: StremioModel, d: StremioModel.Detail, v: Video, isCurrent: Boolean) {
    val now = System.currentTimeMillis()
    val upcoming = v.released != null && v.released > now
    Surface(
        onClick = { s.loadSources(d, v) },
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Black.copy(alpha = 0.25f), contentColor = Palette.fg,
            focusedContainerColor = Palette.surface3, focusedContentColor = Palette.fg),
        border = tileBorder(10.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Palette.surface2)) {
                v.thumbnail?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (isCurrent) {
                    val item = s.libraryItem(d.preview.id)
                    item?.let { ProgressLine(it.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp)) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text((v.episode?.let { "$it. " } ?: "") + v.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val info = listOfNotNull(
                    if (upcoming) "Coming " + android.text.format.DateFormat.format("d MMM yyyy", v.released!!) else null,
                    if (isCurrent) "Watching" else null,
                ).joinToString(" · ")
                if (info.isNotEmpty()) Text(info, fontSize = 12.sp, color = Palette.accent)
                v.overview?.let { Text(it, fontSize = 12.sp, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun SourcesPanel(s: StremioModel, d: StremioModel.Detail) {
    val video = d.sourcesFor
    val entries = d.sources.toList()
    val loaded = entries.mapNotNull { (url, st) -> (st as? StremioModel.SourcesState.Loaded)?.let { url to it.streams } }
    val pending = d.sourcesPending
    val first = remember(d.sourcesVideoId) { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (video != null && d.isSeries) "${video.label} · ${video.title}" else "Sources", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(when {
                d.sourcesVideoId == null -> "Looking…"
                pending > 0 -> "$pending add-on${if (pending == 1) "" else "s"} still looking…"
                loaded.isEmpty() && entries.isNotEmpty() -> ""
                else -> "${loaded.sumOf { it.second.size }} found"
            }, fontSize = 13.sp, color = Palette.faint)
        }
        if (entries.isEmpty() && d.sourcesVideoId != null) {
            Text("None of your add-ons provide sources for this.", color = Palette.muted)
        } else if (pending == 0 && loaded.isEmpty() && entries.isNotEmpty()) {
            Text("Your add-ons didn't find any sources for this one.", color = Palette.muted)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            val firstUrl = loaded.firstOrNull()?.first
            loaded.forEach { (url, streams) ->
                item(key = "h:$url") { SectionLabel(s.addonName(url), Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)) }
                items(streams.size, key = { "$url#$it" }) { i ->
                    SourceRow(streams[i], if (url == firstUrl && i == 0) first else null) { s.play(d, video, streams[i]) }
                }
            }
            if (pending > 0) item(key = "pending") {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Palette.accent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Asking your add-ons…", fontSize = 13.sp, color = Palette.muted)
                }
            }
        }
    }
    // Land on the first source once one shows up (unless Resume has focus).
    val hasAny = loaded.isNotEmpty()
    val resumable = s.libraryItem(d.preview.id)?.inContinueWatching == true
    LaunchedEffect(hasAny, d.sourcesVideoId) { if (hasAny && (d.isSeries || !resumable)) { delay(60); runCatching { first.requestFocus() } } }
}

@Composable
private fun SourceRow(stream: Stream, focus: FocusRequester?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Black.copy(alpha = 0.3f), contentColor = Palette.fg,
            focusedContainerColor = Palette.surface3, focusedContentColor = Palette.fg),
        border = tileBorder(10.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.width(130.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text((stream.name ?: stream.addonName).trim(), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val kind = when {
                    stream.isTorrent -> "Torrent"
                    stream.url != null -> "Direct link"
                    stream.ytId != null -> "YouTube · not supported"
                    else -> "Website only"
                }
                Text(kind, fontSize = 11.sp, color = if (stream.playable) Palette.faint else Color(0xFFFF8A80))
            }
            Text(stream.description ?: stream.filename ?: "", fontSize = 12.sp, color = Color(0xFFC3CAD6), maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
        }
    }
}
