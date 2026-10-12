package com.malick.antenna.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.Phase
import kotlinx.coroutines.launch

/** Sign in from a phone (scan the QR code) or, if needed, type the details with the remote. */
@Composable
fun LoginScreen(vm: AppViewModel) {
    var manual by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .background(Brush.radialGradient(listOf(Palette.accent.copy(alpha = 0.35f), Color.Transparent), radius = 900f))
                .padding(56.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandMark(34)
                Text("Live TV from your IPTV subscription, on a player that opens fast and stays open.",
                    color = Color(0xFFC3CAD6), fontSize = 16.sp, modifier = Modifier.width(420.dp))
            }
        }
        Column(Modifier.weight(1.3f).fillMaxHeight().padding(48.dp), verticalArrangement = Arrangement.Center) {
            when {
                vm.phase == Phase.Connecting -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(color = Palette.accent)
                    Text(vm.connectStep ?: "Signing in…", fontSize = 20.sp)
                }
                manual -> ManualForm(vm) { manual = false }
                else -> PairingPanel(vm) { manual = true }
            }
        }
    }
}

@Composable
private fun PairingPanel(vm: AppViewModel, onManual: () -> Unit) {
    val target = vm.pairing.qrTarget
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Sign in with your phone", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        vm.loginError?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 15.sp) }
        if (target != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                val qr = remember(target) { qrBitmap(target, 360) }
                Image(qr.asImageBitmap(), contentDescription = "QR code to sign in", modifier = Modifier.size(200.dp).clip(RoundedCornerShape(12.dp)))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1. Scan this with your phone's camera", fontSize = 16.sp, color = Palette.muted)
                    Text("   or open:", fontSize = 16.sp, color = Palette.muted)
                    Text(vm.pairing.address ?: "", fontSize = 20.sp, fontFamily = FontFamily.Monospace, color = Palette.fg)
                    Text("2. Enter this code and your provider details", fontSize = 16.sp, color = Palette.muted)
                    Text(vm.pairing.code, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        color = Palette.accent, letterSpacing = 8.sp)
                }
            }
            Text("Your phone needs to be on the same Wi-Fi as this TV.", fontSize = 13.sp, color = Palette.faint)
        } else {
            Text("This TV isn't connected to Wi-Fi or Ethernet yet.", color = Palette.muted)
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onManual) { Text("Type the details with the remote instead") }
    }
}

@Composable
private fun ManualForm(vm: AppViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("My IPTV") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Palette.fg, unfocusedTextColor = Palette.fg, focusedBorderColor = Palette.accent,
        unfocusedBorderColor = Palette.lineStrong, focusedLabelColor = Palette.accent, unfocusedLabelColor = Palette.muted, cursorColor = Palette.accent,
    )
    Column(Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Add your subscription", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        vm.loginError?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp) }
        OutlinedTextField(name, { name = it }, label = { androidx.compose.material3.Text("Name") }, singleLine = true, colors = colors)
        OutlinedTextField(server, { server = it }, label = { androidx.compose.material3.Text("Server URL") }, singleLine = true, colors = colors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(user, { user = it }, label = { androidx.compose.material3.Text("Username") }, singleLine = true, colors = colors)
        OutlinedTextField(pass, { pass = it }, label = { androidx.compose.material3.Text("Password") }, singleLine = true, colors = colors,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { scope.launch { vm.login(name, server, user, pass) } }) { Text("Connect") }
            Button(onClick = onBack) { Text("Use my phone instead") }
        }
    }
}

internal fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) 0xFF0B0E13.toInt() else 0xFFFFFFFF.toInt())
    return bmp
}
