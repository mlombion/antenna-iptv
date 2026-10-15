package com.malick.antenna.tv.phone

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.ui.Palette
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

private enum class RemoteMode(val label: String) { Trackpad("Trackpad"), Air("Air mouse"), Buttons("Buttons") }

private fun View.tick() = performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

/**
 * The phone as a remote for the whole TV: a trackpad, an air mouse (point the phone), or the remote's buttons.
 * Talks to the TV's "Antenna phone remote" service, so it works in every app on the TV, not just Antenna.
 */
@Composable
fun PhoneRemote(vm: AppViewModel) {
    val tv = vm.phone.tv
    val pointer = vm.phone.pointer
    val prefs = remember { vm.getApplication<android.app.Application>().getSharedPreferences("remote", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(runCatching { RemoteMode.valueOf(prefs.getString("mode", null) ?: "") }.getOrDefault(RemoteMode.Trackpad)) }
    var keyboard by remember { mutableStateOf(false) }
    val connected = pointer.state == PointerLink.State.Connected

    fun reconnect() {
        val devices = tv.devices
        val target = pointer.device?.let { d -> devices.firstOrNull { it.name == d.name } } ?: tv.active ?: devices.firstOrNull() ?: return
        pointer.disconnect()
        pointer.connect(target)
    }
    LaunchedEffect(Unit) { tv.startBrowsing(); if (!connected) reconnect() }
    LaunchedEffect(tv.devices) { if (pointer.device == null) reconnect() }
    // The now-playing card follows the TV the remote is connected to.
    LaunchedEffect(pointer.device) { pointer.device?.let { tv.watch(it) } }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Remote", fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            TvPicker(vm)
        }
        NowPlaying(vm)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            RemoteMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m, onClick = { mode = m; prefs.edit().putString("mode", m.name).apply() },
                    shape = SegmentedButtonDefaults.itemShape(i, RemoteMode.entries.size),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = Palette.accent, activeContentColor = Palette.accentInk,
                        inactiveContainerColor = Palette.surface2, inactiveContentColor = Palette.fg),
                    icon = {},
                ) { Text(m.label, fontSize = 13.sp, maxLines = 1) }
            }
        }
        if (connected && pointer.arrowMode && mode != RemoteMode.Buttons) {
            Text("This app uses the remote's arrows: move to step the highlight, tap for OK.", color = Palette.accentInk, fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Palette.accent).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val st = pointer.state) {
                PointerLink.State.Connected -> when (mode) {
                    RemoteMode.Trackpad -> Trackpad(pointer)
                    RemoteMode.Air -> AirMouse(pointer, prefs)
                    RemoteMode.Buttons -> DPad(pointer)
                }
                PointerLink.State.Connecting -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Palette.accent); Text("Connecting to the TV…", color = Palette.muted)
                }
                is PointerLink.State.Failed -> Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(AIcons.Mouse, null, tint = Palette.accent, modifier = Modifier.size(40.dp))
                    Text(st.message, color = Palette.muted, textAlign = TextAlign.Center)
                    Button(onClick = { reconnect() }) { Text("Try again") }
                }
                PointerLink.State.Idle -> Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Palette.accent)
                    Text("Looking for TVs on your Wi-Fi…", color = Palette.muted)
                    Text("Antenna needs to be installed on the TV, and the phone on the same Wi-Fi.", color = Palette.faint, fontSize = 13.sp,
                        textAlign = TextAlign.Center)
                }
            }
        }
        BottomBar(pointer, enabled = connected) { keyboard = true }
    }
    if (keyboard) KeyboardDialog(pointer) { keyboard = false }
}

@Composable
private fun TvPicker(vm: AppViewModel) {
    val tv = vm.phone.tv
    val pointer = vm.phone.pointer
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.height(36.dp).clip(CircleShape).background(Palette.surface2).clickable { open = true }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (pointer.state == PointerLink.State.Connected) Palette.ok else Palette.faint))
            Icon(AIcons.Tv, null, tint = Palette.fg, modifier = Modifier.size(18.dp))
            Text(pointer.device?.displayName ?: "Choose a TV", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.width(IntrinsicWidthCap))
            Icon(Icons.Default.ArrowDropDown, null, tint = Palette.muted)
        }
        DropdownMenu(open, { open = false }) {
            if (tv.devices.isEmpty()) DropdownMenuItem(text = { Text("No TV found on this Wi-Fi") }, onClick = {}, enabled = false)
            tv.devices.forEach { d ->
                DropdownMenuItem(
                    text = { Text(d.displayName) },
                    leadingIcon = { if (d.name == pointer.device?.name) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { open = false; pointer.connect(d) },
                )
            }
        }
    }
}

private val IntrinsicWidthCap = 130.dp

/** What the TV is playing, with the basic controls (Antenna on the TV only). */
@Composable
private fun NowPlaying(vm: AppViewModel) {
    val tv = vm.phone.tv
    val st = tv.status ?: return
    if (tv.active?.name != vm.phone.pointer.device?.name || !st.isPlaying) return
    val vod = st.vod
    val paused = (vod?.state ?: st.state) == "paused"
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(vod?.title ?: st.playing?.name.orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                (vod?.subtitle ?: st.playing?.title)?.let { Text(it, fontSize = 12.5.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            RoundIcon(AIcons.Replay, "Back 30 seconds", 38.dp, background = Palette.surface2) { tv.command("back30") }
            Spacer(Modifier.width(6.dp))
            RoundIcon(if (paused) AIcons.Play else AIcons.Pause, "Pause", 44.dp, tint = Palette.accentInk, background = Palette.accent) { tv.command("pause") }
            Spacer(Modifier.width(6.dp))
            RoundIcon(AIcons.Forward, "Forward 30 seconds", 38.dp, background = Palette.surface2) { tv.command("fwd30") }
            Spacer(Modifier.width(6.dp))
            RoundIcon(AIcons.Stop, "Stop", 38.dp, background = Palette.surface2) { tv.disconnect(stopTv = true) }
        }
        if (vod != null && vod.durationMs > 0) {
            var dragging by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragging ?: (vod.positionMs.toFloat() / vod.durationMs),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { tv.command("seekTo", org.json.JSONObject().put("position", (it * vod.durationMs).toLong())) }
                    dragging = null
                },
                colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.surface3),
                modifier = Modifier.height(24.dp),
            )
            Row {
                Text(hms(dragging?.let { (it * vod.durationMs).toLong() } ?: vod.positionMs), fontSize = 11.5.sp, color = Palette.faint)
                Spacer(Modifier.weight(1f))
                if (vod.hasNext) Text("Next episode", fontSize = 12.sp, color = Palette.accent, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { tv.command("next") }.padding(horizontal = 8.dp))
                Text(hms(vod.durationMs), fontSize = 11.5.sp, color = Palette.faint)
            }
        } else if (st.playing != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("◀ Channel", false) { tv.command("previous") }
                Pill("Channel ▶", false) { tv.command("next") }
                if (st.timeline?.live == false) Pill("Go live", true) { tv.command("live") }
            }
        }
    }
}

@Composable
private fun BottomBar(pointer: PointerLink, enabled: Boolean, onKeyboard: () -> Unit) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf<Triple<ImageVector, String, () -> Unit>>(
            Triple(Icons.AutoMirrored.Filled.ArrowBack, "Back") { pointer.key("back") },
            Triple(Icons.Default.Home, "Home") { pointer.key("home") },
            Triple(AIcons.Keyboard, "Type", onKeyboard),
            Triple(AIcons.VolumeDown, "Vol −") { pointer.volume("down") },
            Triple(AIcons.VolumeOff, "Mute") { pointer.volume("mute") },
            Triple(AIcons.VolumeUp, "Vol +") { pointer.volume("up") },
        ).forEach { (icon, label, action) ->
            Column(
                Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(12.dp)).background(Palette.surface2)
                    .clickable(enabled = enabled) { view.tick(); action() },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                val tint = if (enabled) Palette.fg else Palette.faint
                Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
                Text(label, fontSize = 10.5.sp, color = tint, maxLines = 1)
            }
        }
    }
}

// ---- Trackpad

/** One finger moves, tap clicks, hold long-presses; two fingers scroll, a two-finger tap goes back. */
@Composable
private fun Trackpad(pointer: PointerLink) {
    val view = LocalView.current
    Box(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).clip(RoundedCornerShape(22.dp)).background(Palette.surface)
            .border(1.dp, Palette.line, RoundedCornerShape(22.dp))
            .pointerInput(Unit) {
                val slop = 8.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startTime = down.uptimeMillis
                    var moved = 0f
                    var maxFingers = 1
                    var longPressed = false
                    var lastTime = startTime
                    // Wait for movement or the long-press time, whichever comes first.
                    val firstMove: PointerEvent? = withTimeoutOrNull(550) {
                        var result: PointerEvent? = null
                        while (result == null) {
                            val e = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = e.changes.filter { it.pressed }
                            maxFingers = maxOf(maxFingers, pressed.size)
                            val d = e.changes.fold(Offset.Zero) { acc, c -> acc + c.positionChange() } / e.changes.size.toFloat()
                            moved += d.getDistance()
                            if (pressed.isEmpty() || moved > slop) result = e
                        }
                        result
                    }
                    if (firstMove == null && maxFingers == 1) {
                        longPressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        pointer.longPress()
                    }
                    if (firstMove != null && firstMove.changes.none { it.pressed }) {
                        // Lifted without moving: a tap.
                        view.tick()
                        if (maxFingers >= 2) pointer.key("back") else pointer.click()
                        return@awaitEachGesture
                    }
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Main)
                        val pressed = e.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        maxFingers = maxOf(maxFingers, pressed.size)
                        val d = pressed.fold(Offset.Zero) { acc, c -> acc + c.positionChange() } / pressed.size.toFloat()
                        e.changes.forEach { it.consume() }
                        if (longPressed) continue
                        val dx = d.x / density
                        val dy = d.y / density
                        if (pressed.size >= 2) {
                            pointer.scroll(dx, dy)
                        } else if (maxFingers == 1) {
                            val now = e.changes.first().uptimeMillis
                            val speed = hypot(dx, dy) / ((now - lastTime).coerceAtLeast(1) / 1000f)
                            lastTime = now
                            // Pointer acceleration: slow movements are precise, quick flicks cross the screen.
                            val gain = 1 + min(speed / 700f, 2.2f)
                            pointer.move(dx * gain, dy * gain)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(AIcons.Mouse, null, tint = Palette.faint, modifier = Modifier.size(28.dp))
            Text("Slide to move · tap to click · hold to long-press\nTwo fingers to scroll · two-finger tap to go back",
                fontSize = 12.5.sp, color = Palette.faint, textAlign = TextAlign.Center)
        }
    }
}

// ---- Air mouse

/**
 * Turns the gyroscope into cursor movement, as on the iPhone. Turning the phone left or right moves the cursor sideways
 * (rotation around the vertical, whatever angle the phone is held at); tilting its top up or down moves it vertically.
 */
private class AirMotion(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyro = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val available get() = gyro != null && gravitySensor != null
    var sensitivity = 1f
    private var onMove: ((Float, Float) -> Unit)? = null
    private val gravity = floatArrayOf(0f, 0f, 9.81f)
    private var lastTimestamp = 0L
    private var smoothX = 0f
    private var smoothY = 0f

    fun start(onMove: (Float, Float) -> Unit) {
        if (!available || this.onMove != null) return
        this.onMove = onMove
        smoothX = 0f; smoothY = 0f; lastTimestamp = 0L
        sensors?.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
        sensors?.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST)
    }

    fun stop() {
        onMove = null
        sensors?.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_GYROSCOPE) {
            // TYPE_GRAVITY points away from the ground; smooth the accelerometer if that's all there is.
            val k = if (e.sensor.type == Sensor.TYPE_GRAVITY) 1f else 0.1f
            for (i in 0..2) gravity[i] += (e.values[i] - gravity[i]) * k
            return
        }
        val move = onMove ?: return
        val dt = if (lastTimestamp == 0L) 0.01f else ((e.timestamp - lastTimestamp) / 1e9f).coerceIn(0.002f, 0.05f)
        lastTimestamp = e.timestamp
        val g = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).coerceAtLeast(0.0001f)
        // "Up" in the phone's own axes, and the horizontal axis across its pointing direction (its top): (0,1,0) × up.
        val ux = gravity[0] / g; val uy = gravity[1] / g; val uz = gravity[2] / g
        var rx = uz; val ry = 0f; var rz = -ux
        val len = sqrt(rx * rx + rz * rz).coerceAtLeast(0.0001f)
        rx /= len; rz /= len
        val w = e.values
        var yaw = w[0] * ux + w[1] * uy + w[2] * uz         // turning left is positive
        var pitch = w[0] * rx + w[1] * ry + w[2] * rz      // tilting the top up is positive
        // Ignore hand tremor, then smooth.
        if (abs(yaw) < 0.015f) yaw = 0f
        if (abs(pitch) < 0.015f) pitch = 0f
        smoothX = smoothX * 0.45f + yaw * 0.55f
        smoothY = smoothY * 0.45f + pitch * 0.55f
        val k = 950f * sensitivity * dt // points per radian
        move(-smoothX * k, -smoothY * k)
    }
}

/** Hold the pad and point the phone at the TV: the cursor follows. A quick tap clicks; the strip scrolls. */
@Composable
private fun AirMouse(pointer: PointerLink, prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    val view = LocalView.current
    val motion = remember { AirMotion(context) }
    var sensitivity by remember { mutableFloatStateOf(prefs.getFloat("airSensitivity", 1f)) }
    var holding by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { motion.stop() } }
    motion.sensitivity = sensitivity

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!motion.available) Text("The air mouse needs a gyroscope, which this phone doesn't have.", color = Palette.muted,
            modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.weight(1f).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(26.dp))
                    .background(if (holding) Palette.accentSoft else Palette.surface)
                    .border(if (holding) 2.dp else 1.dp, if (holding) Palette.accent else Palette.line, RoundedCornerShape(26.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            holding = true
                            motion.start { dx, dy -> pointer.move(dx, dy) }
                            var upTime: Long
                            while (true) {
                                val e = awaitPointerEvent()
                                upTime = e.changes.first().uptimeMillis
                                if (e.changes.none { it.pressed }) break
                            }
                            holding = false
                            motion.stop()
                            if (upTime - down.uptimeMillis < 250) {
                                view.tick()
                                pointer.click()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(AIcons.Remote, null, tint = if (holding) Palette.accent else Palette.faint, modifier = Modifier.size(34.dp))
                    Text(if (holding) "Point at the TV and move" else "Hold here and point the phone\nQuick tap to click",
                        fontSize = 13.sp, color = Palette.muted, textAlign = TextAlign.Center)
                }
            }
            // Scroll strip: drag up and down.
            Box(
                Modifier.width(64.dp).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Palette.surface)
                    .border(1.dp, Palette.line, RoundedCornerShape(18.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull { it.pressed } ?: break
                                pointer.scroll(0f, c.positionChange().y / density)
                                c.consume()
                            }
                        }
                    },
            ) {
                Icon(Icons.Default.KeyboardArrowUp, null, tint = Palette.faint, modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
                Text("Scroll", fontSize = 11.sp, color = Palette.faint, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                Icon(Icons.Default.KeyboardArrowDown, null, tint = Palette.faint, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Slow", fontSize = 12.sp, color = Palette.faint)
            Slider(sensitivity, { sensitivity = it; prefs.edit().putFloat("airSensitivity", it).apply() }, valueRange = 0.4f..2.2f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.surface3))
            Text("Fast", fontSize = 12.sp, color = Palette.faint)
            Pill("Center", false) { pointer.center() }
        }
    }
}

// ---- Buttons

@Composable
private fun DPad(pointer: PointerLink) {
    val view = LocalView.current
    @Composable
    fun arrow(icon: ImageVector, key: String) {
        Box(Modifier.size(76.dp).clip(CircleShape).clickable { view.tick(); pointer.key(key) }, contentAlignment = Alignment.Center) {
            Icon(icon, key, tint = Palette.fg, modifier = Modifier.size(36.dp))
        }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(250.dp).clip(CircleShape).background(Palette.surface).border(1.dp, Palette.line, CircleShape), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                arrow(Icons.Default.KeyboardArrowUp, "up")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    arrow(Icons.Default.KeyboardArrowLeft, "left")
                    Box(Modifier.size(96.dp).clip(CircleShape).background(Palette.surface3).clickable { view.tick(); pointer.key("ok") },
                        contentAlignment = Alignment.Center) { Text("OK", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    arrow(Icons.Default.KeyboardArrowRight, "right")
                }
                arrow(Icons.Default.KeyboardArrowDown, "down")
            }
        }
        Text("The remote's arrows and OK, for apps that don't respond to the cursor.", fontSize = 12.5.sp, color = Palette.faint,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 30.dp))
    }
}

// ---- Keyboard

/** Types into the search box focused on the TV. */
@Composable
private fun KeyboardDialog(pointer: PointerLink, close: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    fun send() { if (text.isNotEmpty()) { pointer.type(text); text = "" } }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Type on the TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select a search box on the TV first, then type here.", fontSize = 13.sp, color = Palette.muted)
                OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
            }
        },
        confirmButton = { TextButton(onClick = { send(); pointer.enter() }) { Text("Send + Enter") } },
        dismissButton = { TextButton(onClick = { send(); close() }) { Text("Send") } },
        containerColor = Palette.surface,
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
