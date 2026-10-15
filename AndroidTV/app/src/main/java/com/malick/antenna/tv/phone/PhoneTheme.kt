package com.malick.antenna.tv.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.malick.antenna.tv.data.Channel
import com.malick.antenna.tv.ui.Palette
import com.malick.antenna.tv.ui.hueColor

@Composable
fun PhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent, onPrimary = Palette.accentInk, secondary = Palette.accent, onSecondary = Palette.accentInk,
            background = Palette.bg, onBackground = Palette.fg, surface = Palette.bg, onSurface = Palette.fg,
            surfaceVariant = Palette.surface2, onSurfaceVariant = Palette.muted, surfaceContainer = Palette.surface,
            surfaceContainerHigh = Palette.surface2, surfaceContainerHighest = Palette.surface3, surfaceContainerLow = Palette.surface,
            secondaryContainer = Palette.accentSoft, onSecondaryContainer = Palette.accent, outline = Palette.lineStrong,
            outlineVariant = Palette.line, error = Color(0xFFFF8A80),
        ),
    ) {
        // Text defaults to the light foreground everywhere, as on the TV and iPhone.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Palette.fg, content = content)
    }
}

/** Material icons the core icon set doesn't include (Apache 2.0 paths from Google's Material icons). */
object AIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.White)).build()

    val Pause by lazy { icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }
    val Play by lazy { icon("Play", "M8,5v14l11,-7z") }
    val Tv by lazy { icon("Tv", "M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12z") }
    val LiveTv by lazy { icon("LiveTv", "M21,6h-7.59l3.29,-3.29L16,2l-4,4 -4,-4 -0.71,0.71L10.59,6H3c-1.1,0 -2,0.89 -2,2v12c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V8c0,-1.11 -0.9,-2 -2,-2zM21,20H3V8h18v12zM9,10v8l7,-4z") }
    val Movie by lazy { icon("Movie", "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z") }
    val Cast by lazy { icon("Cast", "M21,3L3,3c-1.1,0 -2,0.9 -2,2v3h2L3,5h18v14h-7v2h7c1.1,0 2,-0.9 2,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM1,18v3h3c0,-1.66 -1.34,-3 -3,-3zM1,14v2c2.76,0 5,2.24 5,5h2c0,-3.87 -3.13,-7 -7,-7zM1,10v2c4.97,0 9,4.03 9,9h2c0,-6.08 -4.93,-11 -11,-11z") }
    val Pip by lazy { icon("Pip", "M19,11h-8v6h8v-6zM23,19L23,4.98C23,3.88 22.1,3 21,3L3,3c-1.1,0 -2,0.88 -2,1.98L1,19c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2zM21,19.02L3,19.02L3,4.97h18v14.05z") }
    val VolumeUp by lazy { icon("VolumeUp", "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z") }
    val VolumeDown by lazy { icon("VolumeDown", "M18.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM5,9v6h4l5,5V4L9,9H5z") }
    val VolumeOff by lazy { icon("VolumeOff", "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z") }
    val SkipNext by lazy { icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }
    val SkipPrevious by lazy { icon("SkipPrevious", "M6,6h2v12H6zM9.5,12l8.5,6V6z") }
    val Keyboard by lazy { icon("Keyboard", "M20,5H4c-1.1,0 -1.99,0.9 -1.99,2L2,17c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM11,8h2v2h-2V8zM11,11h2v2h-2v-2zM8,8h2v2H8V8zM8,11h2v2H8v-2zM7,13H5v-2h2v2zM7,10H5V8h2v2zM16,17H8v-2h8v2zM16,13h-2v-2h2v2zM16,10h-2V8h2v2zM19,13h-2v-2h2v2zM19,10h-2V8h2v2z") }
    val Subtitles by lazy { icon("Subtitles", "M20,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM4,12h4v2H4v-2zM14,18H4v-2h10v2zM20,18h-4v-2h4v2zM20,14H10v-2h10v2z") }
    val Remote by lazy { icon("Remote", "M15,9H9c-0.55,0 -1,0.45 -1,1v12c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1V10c0,-0.55 -0.45,-1 -1,-1zM12,15c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM7.05,6.05l1.41,1.41C9.37,6.56 10.62,6 12,6s2.63,0.56 3.54,1.46l1.41,-1.41C15.68,4.78 13.93,4 12,4s-3.68,0.78 -4.95,2.05zM12,0C8.96,0 6.21,1.23 4.22,3.22l1.41,1.41C7.26,3.01 9.51,2 12,2s4.74,1.01 6.36,2.64l1.41,-1.41C17.79,1.23 15.04,0 12,0z") }
    val Mouse by lazy { icon("Mouse", "M13,1.07V9h7c0,-4.08 -3.05,-7.44 -7,-7.93zM4,15c0,4.42 3.58,8 8,8s8,-3.58 8,-8v-4H4v4zM11,1.07C7.05,1.56 4,4.92 4,9h7V1.07z") }
    val StarOutline by lazy { icon("StarOutline", "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24zM12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z") }
    val Replay by lazy { icon("Replay", "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z") }
    val Forward by lazy { icon("Forward", "M12,5V1l5,5 -5,5V7c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6h2c0,4.42 -3.58,8 -8,8s-8,-3.58 -8,-8 3.58,-8 8,-8z") }
    val Stop by lazy { icon("Stop", "M6,6h12v12H6z") }
    val Library by lazy { icon("Library", "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zM20,2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM20,16H8V4h12v12zM12,5.5v9l6,-4.5z") }
}

@Composable
fun ChannelBadge(channel: Channel, size: Dp = 44.dp) {
    val shape = RoundedCornerShape(size * 0.22f)
    Box(
        Modifier.size(size * 1.3f, size).clip(shape).background(hueColor(channel.hue, 0.38f, 0.22f))
            .border(1.dp, hueColor(channel.hue, 0.5f, 0.4f).copy(alpha = 0.5f), shape),
        contentAlignment = Alignment.Center,
    ) {
        val monogram = @Composable {
            Text(channel.monogram, color = hueColor(channel.hue, 0.35f, 0.95f), fontWeight = FontWeight.Black,
                fontSize = (size.value * 0.3f).sp, maxLines = 1)
        }
        if (channel.iconUrl != null) {
            SubcomposeAsyncImage(
                model = channel.iconUrl, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(size * 0.12f),
                loading = { monogram() }, error = { monogram() },
            )
        } else monogram()
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(CircleShape)
            .background(if (selected) Palette.accent else Palette.surface2)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Palette.accentInk else Palette.fg, fontSize = 13.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun RoundIcon(icon: ImageVector, label: String, size: Dp = 44.dp, tint: Color = Color.White, background: Color = Color.Black.copy(alpha = 0.45f),
              onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(background).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.fg, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

fun hms(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
