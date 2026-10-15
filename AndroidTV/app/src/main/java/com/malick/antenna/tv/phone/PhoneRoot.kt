package com.malick.antenna.tv.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.Phase
import androidx.lifecycle.viewModelScope
import com.malick.antenna.tv.ui.Palette
import kotlinx.coroutines.launch

@Composable
fun PhoneRoot(vm: AppViewModel) {
    val ph = vm.phone
    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        when (vm.phase) {
            Phase.Launching -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Palette.accent)
            // The form stays up while it connects, so a typo can be fixed without typing everything again.
            Phase.SignedOut, Phase.Connecting -> PhoneLogin(vm)
            Phase.Ready -> PhoneMain(vm)
        }
        // Players cover everything, tabs included; in picture in picture they're all that shows.
        if (vm.playing != null) PhoneLivePlayer(vm)
        vm.stremio.playback?.let { PhoneMoviePlayer(vm, it) }

        if (!ph.pip) AnimatedVisibility(vm.toast != null, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp, start = 24.dp, end = 24.dp)) {
            Text(vm.toast.orEmpty(), color = Palette.surface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFF1F3F7)).padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

// ---- Sign-in: the IPTV subscription's details, typed on the phone

@Composable
private fun PhoneLogin(vm: AppViewModel) {
    var name by rememberSaveable { mutableStateOf(vm.account?.name ?: "") }
    var server by rememberSaveable { mutableStateOf(vm.account?.server ?: "") }
    var user by rememberSaveable { mutableStateOf(vm.account?.username ?: "") }
    var pass by rememberSaveable { mutableStateOf("") }
    val busy = vm.phase == Phase.Connecting
    // In the view model's scope: this screen may leave while it connects.
    fun submit() { if (!busy) vm.viewModelScope.launch { vm.login(name, server, user, pass) } }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(AIcons.LiveTv, null, tint = Palette.accent, modifier = Modifier.size(30.dp))
            Text("Antenna", fontSize = 30.sp, fontWeight = FontWeight.Black)
        }
        Text("Sign in with the details your IPTV provider gave you (Xtream Codes). They stay on this phone, in encrypted storage.",
            color = Palette.muted, fontSize = 14.5.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(server, { server = it }, label = { Text("Server URL") }, placeholder = { Text("http://example.com:8080") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false))
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false))
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next))
        OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, placeholder = { Text("My IPTV") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { submit() }))
        if (!busy) vm.loginError?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp) }
        Button(onClick = { submit() }, modifier = Modifier.fillMaxWidth().height(50.dp),
            enabled = !busy && server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
            if (busy) {
                CircularProgressIndicator(color = Palette.fg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(vm.connectStep ?: "Connecting", modifier = Modifier.padding(start = 10.dp))
            } else Text("Sign in", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---- Tabs

@Composable
private fun PhoneMain(vm: AppViewModel) {
    val ph = vm.phone
    Scaffold(
        containerColor = Palette.bg,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            Column {
                CastingBar(vm)
                NavigationBar(containerColor = Palette.surface, tonalElevation = 0.dp) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Palette.accentInk, indicatorColor = Palette.accent, selectedTextColor = Palette.fg,
                        unselectedIconColor = Palette.muted, unselectedTextColor = Palette.muted,
                    )
                    listOf(
                        Triple(PhoneTab.Home, "Home", Icons.Default.Home),
                        Triple(PhoneTab.Live, "Live TV", AIcons.LiveTv),
                        Triple(PhoneTab.Movies, "Movies", AIcons.Movie),
                        Triple(PhoneTab.Remote, "Remote", AIcons.Remote),
                        Triple(PhoneTab.Settings, "Settings", Icons.Default.Settings),
                    ).forEach { (tab, label, icon) ->
                        NavigationBarItem(
                            selected = ph.tab == tab, onClick = { if (ph.tab == tab && tab == PhoneTab.Movies) vm.stremio.closeDetail(); ph.tab = tab },
                            icon = { Icon(icon, contentDescription = label) }, label = { Text(label, fontSize = 11.sp, maxLines = 1) }, colors = colors,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (ph.tab) {
                PhoneTab.Home -> PhoneHome(vm)
                PhoneTab.Live -> PhoneLive(vm)
                PhoneTab.Movies -> PhoneMovies(vm)
                PhoneTab.Remote -> PhoneRemote(vm)
                PhoneTab.Settings -> PhoneSettings(vm)
            }
        }
    }
}

/** What the TV is playing when this phone sent it something; tap for the remote. */
@Composable
private fun CastingBar(vm: AppViewModel) {
    val tv = vm.phone.tv
    // The Remote tab has its own, bigger now-playing card.
    if (vm.phone.tab == PhoneTab.Remote) return
    val device = tv.active ?: return
    val st = tv.status ?: return
    if (!st.isPlaying) return
    val vod = st.vod
    val title = vod?.title ?: st.playing?.name ?: return
    val sub = vod?.subtitle ?: st.playing?.title
    val paused = (vod?.state ?: st.state) == "paused"
    Row(
        Modifier.fillMaxWidth().background(Palette.surface2).clickable { vm.phone.tab = PhoneTab.Remote }.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AIcons.Cast, null, tint = Palette.accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(sub, "on ${device.displayName}").joinToString(" · "), fontSize = 12.sp, color = Palette.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundIcon(if (paused) AIcons.Play else AIcons.Pause, "Pause", size = 40.dp, background = Color.Transparent) { tv.command("pause") }
        RoundIcon(AIcons.Stop, "Stop", size = 40.dp, background = Color.Transparent) { tv.disconnect(stopTv = true) }
    }
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = Palette.faint, modifier = Modifier.size(44.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(text, fontSize = 14.sp, color = Palette.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(300.dp))
        if (action != null) TextButton(onClick = onAction) { Text(action, color = Palette.accent) }
    }
}
