package com.malick.antenna.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.Phase
import com.malick.antenna.tv.Section

@Composable
fun RootScreen(vm: AppViewModel) {
    // Text defaults to the light foreground everywhere, as on the Mac and iPhone.
    androidx.compose.runtime.CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Palette.fg) { RootContent(vm) }
}

@Composable
private fun RootContent(vm: AppViewModel) {
    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        when (vm.phase) {
            Phase.Launching -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Palette.accent)
            Phase.SignedOut, Phase.Connecting -> LoginScreen(vm)
            Phase.Ready -> {
                // A title's page and the movie player replace the menu while they're open; the board remembers where you were.
                val board = androidx.compose.runtime.remember { BoardUi() }
                val playback = vm.stremio.playback
                val detail = vm.stremio.detail
                when {
                    playback != null -> VodPlayerScreen(vm, playback)
                    detail != null -> DetailScreen(vm, detail)
                    else -> MainScreen(vm, board)
                }
            }
        }
        if (vm.playing != null) PlayerScreen(vm)
        vm.toast?.let {
            Text(it, color = Color(0xFF11151C), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xFFF1F3F7)).padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun MainScreen(vm: AppViewModel, board: BoardUi) {
    val navFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    var navHasFocus by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Back from a screen goes to the side menu first; Back from the menu leaves the app.
    androidx.activity.compose.BackHandler(enabled = !navHasFocus && vm.playing == null) { runCatching { navFocus.requestFocus() } }
    Row(Modifier.fillMaxSize()) {
        SideNav(vm, navFocus) { navHasFocus = it }
        Box(Modifier.fillMaxSize()) {
            when (vm.section) {
                Section.Home -> HomeScreen(vm)
                Section.Live -> LiveScreen(vm)
                Section.Guide -> GuideScreen(vm)
                Section.Movies -> MoviesScreen(vm, board)
                Section.Favorites -> FavoritesScreen(vm)
                Section.Settings -> SettingsScreen(vm)
            }
        }
    }
}

/** Icons only until you move onto it with the remote, then it opens with labels, like Google TV's own menu. */
@Composable
private fun SideNav(vm: AppViewModel, navFocus: androidx.compose.ui.focus.FocusRequester, onFocus: (Boolean) -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val width by androidx.compose.animation.core.animateDpAsState(if (open) 210.dp else 76.dp, label = "nav")
    Column(
        Modifier.width(width).fillMaxHeight()
            .onFocusChanged { open = it.hasFocus; onFocus(it.hasFocus) }
            .background(Brush.verticalGradient(listOf(Color(0xFF121722), Color(0xFF0E1219))))
            .padding(horizontal = 14.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.padding(start = 8.dp, bottom = 22.dp).height(28.dp)) {
            if (open) BrandMark(18) else Text("((•))", color = Palette.accent, fontSize = 16.sp, fontWeight = FontWeight.Black)
        }
        NavItem(vm, Section.Home, "Home", Icons.Filled.Home, open, navFocus)
        NavItem(vm, Section.Live, "Live TV", Icons.Filled.List, open, navFocus)
        NavItem(vm, Section.Guide, "TV Guide", Icons.Filled.DateRange, open, navFocus)
        NavItem(vm, Section.Movies, "Movies & Series", Icons.Filled.PlayArrow, open, navFocus)
        NavItem(vm, Section.Favorites, "Favorites", Icons.Filled.Star, open, navFocus)
        Spacer(Modifier.weight(1f))
        NavItem(vm, Section.Settings, "Settings", Icons.Filled.Settings, open, navFocus)
        if (open) Text(vm.account?.name ?: "", color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp, top = 10.dp))
    }
}

@Composable
private fun NavItem(vm: AppViewModel, section: Section, title: String, icon: ImageVector, open: Boolean,
                    navFocus: androidx.compose.ui.focus.FocusRequester) {
    val selected = vm.section == section
    Surface(
        onClick = { vm.section = section },
        // The current section's item is where Back lands.
        modifier = Modifier.fillMaxWidth().height(44.dp).then(if (selected) Modifier.focusRequester(navFocus) else Modifier),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.surface3 else Color.Transparent,
            contentColor = if (selected) Palette.fg else Palette.muted,
            focusedContainerColor = Palette.accent, focusedContentColor = Palette.accentInk,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = androidx.tv.material3.LocalContentColor.current)
            if (open) Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
