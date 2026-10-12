package com.malick.antenna.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.StremioModel
import com.malick.antenna.tv.data.stremio.LibraryItem
import com.malick.antenna.tv.data.stremio.MetaPreview

/** Scroll positions and the last card you opened, kept while a title's page or the player is on screen. */
class BoardUi {
    val column = LazyListState()
    val rows = HashMap<String, LazyListState>()
    var focusKey by mutableStateOf<String?>(null)
    var searching by mutableStateOf(false)
    fun row(key: String) = rows.getOrPut(key) { LazyListState() }
}

@Composable
fun MoviesScreen(vm: AppViewModel, ui: BoardUi) {
    val s = vm.stremio
    when {
        !s.signedIn -> StremioSignIn(s)
        ui.searching -> SearchBoard(s, ui)
        else -> Board(s, ui)
    }
}

// ---- Sign-in

@Composable
private fun StremioSignIn(s: StremioModel) {
    LaunchedEffect(Unit) { s.startSignIn() }
    Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Movies & Series", fontSize = 32.sp, fontWeight = FontWeight.Black)
            Text("Sign in with your Stremio account to bring your library, Continue Watching and add-ons here. " +
                "What you watch is saved back to your account, so the Stremio apps stay in sync.",
                color = Color(0xFFC3CAD6), fontSize = 15.sp, modifier = Modifier.width(620.dp))
        }
        s.signInError?.let {
            Text(it, color = Color(0xFFFF8A80), fontSize = 15.sp)
            Button(onClick = { s.startSignIn() }) { Text("Try again") }
        }
        val code = s.linkCode
        if (code == null) {
            if (s.signInError == null) CircularProgressIndicator(color = Palette.accent)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                val qr = remember(code.link) { qrBitmap(code.link, 360) }
                Image(qr.asImageBitmap(), contentDescription = "QR code to sign in to Stremio", modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Scan with your phone, or open", fontSize = 15.sp, color = Palette.muted)
                    Text(code.link.removePrefix("https://"), fontSize = 19.sp, fontFamily = FontFamily.Monospace, color = Palette.fg)
                    Text("2. Sign in to Stremio as usual", fontSize = 15.sp, color = Palette.muted)
                    Text("Facebook, Apple or email all work", fontSize = 13.sp, color = Palette.faint)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Text(code.code, fontSize = 38.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            color = Palette.accent, letterSpacing = 6.sp)
                        Text("updates by itself\nonce you're in", fontSize = 12.sp, color = Palette.faint, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
            }
        }
    }
}

// ---- The board: Continue Watching, your library, then every add-on catalog

@Composable
private fun Board(s: StremioModel, ui: BoardUi) {
    val catalogs = s.boardCatalogs
    LazyColumn(state = ui.column, contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Movies & Series", fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text(when {
                        s.loadError != null -> s.loadError!!
                        s.isRefreshing && s.addons.isEmpty() -> "Loading your Stremio library and add-ons…"
                        else -> "${s.addons.size} add-ons · ${s.libraryItems.size} in your library"
                    }, fontSize = 13.sp, color = if (s.loadError != null) Color(0xFFFF8A80) else Palette.faint)
                }
                Surface(
                    onClick = { ui.searching = true },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Palette.surface2, contentColor = Palette.fg,
                        focusedContainerColor = Palette.accent, focusedContentColor = Palette.accentInk),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = androidx.tv.material3.LocalContentColor.current)
                        Text("Search movies & series", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        val cw = s.continueWatching
        if (cw.isNotEmpty()) item(key = "cw") {
            Shelf("Continue watching", null, ui.row("cw")) {
                items(cw, key = { "cw:" + it.id }) { item -> LibraryCard(s, ui, item, "cw:" + item.id, showProgress = true) }
            }
        }
        val lib = s.libraryItems
        if (lib.isNotEmpty()) item(key = "library") {
            Shelf("Your library", null, ui.row("library")) {
                items(lib.take(60), key = { "lib:" + it.id }) { item -> LibraryCard(s, ui, item, "lib:" + item.id, showProgress = false) }
            }
        }
        items(catalogs, key = { it.second.key }) { (addon, catalog) ->
            LaunchedEffect(catalog.key) { s.loadRow(addon, catalog) }
            when (val state = s.rows[catalog.key]) {
                is StremioModel.RowState.Loaded -> Shelf(catalog.name + " · " + typeLabel(catalog.type), addon.name, ui.row(catalog.key)) {
                    items(state.items, key = { catalog.key + ":" + it.id }) { m -> MetaCard(s, ui, m, catalog.key + ":" + m.id) }
                }
                StremioModel.RowState.Failed -> Spacer(Modifier.height(0.dp))
                else -> LoadingShelf(catalog.name + " · " + typeLabel(catalog.type), addon.name)
            }
        }
        if (s.addons.isNotEmpty() && catalogs.isEmpty()) item {
            Text("None of your add-ons have catalogs to browse. Use Search to find something.", color = Palette.muted,
                modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}

@Composable
private fun SearchBoard(s: StremioModel, ui: BoardUi) {
    androidx.activity.compose.BackHandler { ui.searching = false }
    val field = remember { FocusRequester() }
    var text by remember { mutableStateOf(s.searchQuery) }
    var fieldFocused by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(key = "field") {
            Column(Modifier.padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Search", fontSize = 28.sp, fontWeight = FontWeight.Black)
                Box(
                    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(12.dp)).background(Palette.surface2)
                        .border(2.dp, if (fieldFocused) Palette.accent else Palette.lineStrong, RoundedCornerShape(12.dp))
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it; s.search(it) },
                        singleLine = true,
                        textStyle = TextStyle(color = Palette.fg, fontSize = 20.sp),
                        cursorBrush = SolidColor(Palette.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { s.search(text) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { fieldFocused = it.isFocused },
                    )
                    if (text.isEmpty()) Text("Type a movie or series, like “The Lantern”", color = Palette.faint, fontSize = 20.sp)
                }
                Text("Press OK to type, or use the microphone button on your remote.", fontSize = 12.sp, color = Palette.faint)
            }
        }
        val results = s.searchCatalogs.mapNotNull { (a, c) -> s.search[c.key]?.let { Triple(a, c, it) } }
        items(results, key = { "s:" + it.second.key }) { (addon, catalog, state) ->
            when (state) {
                is StremioModel.RowState.Loaded -> Shelf(catalog.name + " · " + typeLabel(catalog.type), addon.name, ui.row("s:" + catalog.key)) {
                    items(state.items, key = { "s:" + catalog.key + ":" + it.id }) { m -> MetaCard(s, ui, m, "s:" + catalog.key + ":" + m.id) }
                }
                StremioModel.RowState.Failed -> Spacer(Modifier.height(0.dp))
                else -> LoadingShelf(catalog.name + " · " + typeLabel(catalog.type), addon.name)
            }
        }
        if (text.trim().length >= 2 && results.isNotEmpty() && results.all { it.third == StremioModel.RowState.Failed }) item {
            Text("Nothing found for “${text.trim()}”.", color = Palette.muted, modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
    LaunchedEffect(Unit) { if (ui.focusKey == null) runCatching { field.requestFocus() } }
}

private fun typeLabel(type: String) = when (type) { "movie" -> "Movies"; "series" -> "Series"; "anime" -> "Anime"; else -> type.replaceFirstChar { it.uppercase() } }

@Composable
private fun Shelf(title: String, source: String?, state: LazyListState, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(horizontal = 32.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            source?.let { Text(it, fontSize = 12.sp, color = Palette.faint) }
        }
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
private fun LoadingShelf(title: String, source: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(horizontal = 32.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.muted)
            Text(source, fontSize = 12.sp, color = Palette.faint)
        }
        Row(Modifier.padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(7) { Box(Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Palette.surface2)) }
        }
    }
}

@Composable
private fun MetaCard(s: StremioModel, ui: BoardUi, m: MetaPreview, key: String) {
    PosterCard(ui, key, m.name, m.poster, m.posterShape, subtitle = m.releaseInfo, progress = null) { s.openDetail(m) }
}

@Composable
private fun LibraryCard(s: StremioModel, ui: BoardUi, item: LibraryItem, key: String, showProgress: Boolean) {
    PosterCard(ui, key, item.name, item.poster, item.posterShape,
        subtitle = if (showProgress) item.episodeLabel ?: remaining(item) else null,
        progress = if (showProgress) item.progress else null) { s.openDetail(item) }
}

private fun remaining(item: LibraryItem): String? {
    if (item.duration <= 0) return null
    val left = ((item.duration - item.timeOffset) / 60_000).coerceAtLeast(1)
    return "$left min left"
}

@Composable
fun PosterCard(
    ui: BoardUi?, key: String, title: String, poster: String?, shape: String,
    subtitle: String?, progress: Float?, width: Dp = 150.dp, onClick: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val ratio = when (shape) { "landscape" -> 16f / 9f; "square" -> 1f; else -> 2f / 3f }
    val cardWidth = if (shape == "landscape") width * 1.8f else width
    Column(Modifier.width(cardWidth), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = { ui?.focusKey = key; onClick() },
            modifier = Modifier.fillMaxWidth().aspectRatio(ratio).focusRequester(focus),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Palette.surface2, focusedContainerColor = Palette.surface2),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = tileBorder(10.dp),
        ) {
            Box(Modifier.fillMaxSize()) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.muted,
                    modifier = Modifier.align(Alignment.Center).padding(10.dp), maxLines = 3)
                if (poster != null) AsyncImage(model = poster, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (progress != null) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
                    ProgressLine(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), height = 4.dp)
                }
            }
        }
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Palette.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    // Coming back from a title's page lands on the card you opened.
    LaunchedEffect(Unit) { if (ui?.focusKey == key) { kotlinx.coroutines.delay(80); runCatching { focus.requestFocus() } } }
}
