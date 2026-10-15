package com.malick.antenna.tv.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.BuildConfig
import com.malick.antenna.tv.ui.Palette
import kotlinx.coroutines.launch

@Composable
fun PhoneSettings(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val info = vm.accountInfo
    val st = vm.stremio
    val tv = vm.phone.tv
    // Storage values aren't observable; bump this to redraw after changing one.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { tv.startBrowsing() }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Black) }
        item {
            Card("IPTV subscription") {
                Text(vm.account?.name.orEmpty(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${vm.account?.username} · ${vm.account?.server}", color = Palette.muted, fontSize = 13.sp)
                Text("${vm.channels.size} channels · ${info?.status ?: "—"}" +
                    (info?.expiry?.let { " · until " + android.text.format.DateFormat.format("d MMM yyyy", it * 1000) } ?: "") +
                    (info?.maxConnections?.let { " · $it screen" + if (it > 1) "s" else "" } ?: ""), color = Palette.muted, fontSize = 13.sp)
                Divider()
                Action("Refresh channels") { scope.launch { vm.refresh(silent = false) } }
                Divider()
                key(tick) {
                    Toggle("Hide adult categories", vm.hideAdult) { vm.setHideAdultCategories(it) }
                    Divider()
                    Choice("Stream format", if (vm.storage.format == "ts") "MPEG-TS" else "HLS") {
                        vm.storage.format = if (vm.storage.format == "ts") "m3u8" else "ts"; tick++
                    }
                    Divider()
                    Choice("Rewind live TV", if (vm.storage.rewindMinutes == 0) "Off" else "${vm.storage.rewindMinutes} min") {
                        vm.storage.rewindMinutes = when (vm.storage.rewindMinutes) { 0 -> 15; 15 -> 30; else -> 0 }; tick++
                    }
                }
                Divider()
                Action("Sign out", destructive = true) { vm.signOut() }
            }
        }
        item {
            Card("Movies & Series · Stremio") {
                Text(if (st.signedIn) "Signed in as ${st.email ?: "your Stremio account"} · ${st.addons.size} add-ons · ${st.libraryItems.size} in your library"
                    else "Not signed in. Open Movies to sign in with your Stremio account.", color = Palette.muted, fontSize = 13.sp)
                if (st.signedIn) {
                    Divider()
                    Action("Refresh library & add-ons") { scope.launch { st.refresh(); vm.showToast("Stremio library and add-ons refreshed") } }
                    Divider()
                    Action("Sign out of Stremio", destructive = true) { st.signOut() }
                }
            }
        }
        item {
            Card("TVs on this Wi-Fi") {
                if (tv.devices.isEmpty()) Text("None found. Install Antenna on your Android TV or projector and keep it on the same Wi-Fi.",
                    color = Palette.muted, fontSize = 13.sp)
                tv.devices.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AIcons.Tv, null, tint = Palette.accent, modifier = Modifier.size(20.dp))
                        Text(d.displayName, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
                        Text(d.host, fontSize = 12.sp, color = Palette.faint)
                    }
                }
                Text("To use the phone as a remote for the whole TV, turn on \"Antenna phone remote\" in the TV's accessibility settings " +
                    "(Antenna's Settings on the TV has a shortcut).", color = Palette.faint, fontSize = 12.5.sp)
            }
        }
        item {
            Text("Antenna ${BuildConfig.VERSION_NAME} · Open source (MIT)", color = Palette.faint, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Palette.faint, letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = Palette.line)

@Composable
private fun Action(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (destructive) Color(0xFFFF8A80) else Palette.accent,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp))
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.accentInk))
    }
}

@Composable
private fun Choice(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, color = Palette.accent, fontWeight = FontWeight.Medium)
    }
}
