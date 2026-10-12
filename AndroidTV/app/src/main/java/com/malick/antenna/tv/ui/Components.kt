package com.malick.antenna.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.SubcomposeAsyncImage
import com.malick.antenna.tv.AppViewModel
import com.malick.antenna.tv.data.Channel

/** Palette shared with the Mac and iPhone apps. */
object Palette {
    val bg = Color(0xFF0B0E13)
    val surface = Color(0xFF11151C)
    val surface2 = Color(0xFF181D26)
    val surface3 = Color(0xFF212835)
    val line = Color(0x1C96AACD)
    val lineStrong = Color(0x3896AACD)
    val fg = Color(0xFFE9EDF3)
    val muted = Color(0xFF8C96A8)
    val faint = Color(0xFF5F6879)
    val accent = Color(0xFFF2B544)
    val accentSoft = Color(0x24F2B544)
    val accentInk = Color(0xFF1B1406)
    val live = Color(0xFFFF5151)
    val ok = Color(0xFF46C28A)
}

fun hueColor(hue: Float, saturation: Float, value: Float) = Color.hsv(hue, saturation, value)

fun clock(ms: Long): String = android.text.format.DateFormat.format("HH:mm", ms).toString()

@Composable
fun ChannelLogo(channel: Channel, width: Dp, height: Dp) {
    val bgColor = hueColor(channel.hue, 0.38f, 0.22f)
    Box(
        Modifier.size(width, height).clip(RoundedCornerShape(height * 0.2f)).background(bgColor)
            .border(1.dp, hueColor(channel.hue, 0.5f, 0.4f).copy(alpha = 0.5f), RoundedCornerShape(height * 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        val monogram = @Composable {
            Text(channel.monogram, color = hueColor(channel.hue, 0.35f, 0.95f), fontWeight = FontWeight.Black,
                fontSize = (height.value * 0.34f).sp, maxLines = 1)
        }
        if (channel.iconUrl != null) {
            SubcomposeAsyncImage(
                model = channel.iconUrl, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(height * 0.12f),
                loading = { monogram() }, error = { monogram() },
            )
        } else monogram()
    }
}

/** Two-tone wash in the channel's hue, standing in for artwork live channels don't have. */
fun Modifier.channelBackdrop(channel: Channel) = background(hueColor(channel.hue, 0.35f, 0.1f))
    .background(Brush.radialGradient(listOf(hueColor(channel.hue, 0.65f, 0.45f).copy(alpha = 0.85f), Color.Transparent),
        center = Offset(150f, 100f), radius = 520f))

@Composable
fun LiveBadge() {
    Row(Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.live).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun QualityBadge(quality: String?) {
    if (quality == null) return
    val color = if (quality == "4K") Palette.accent else Palette.muted
    Text(quality, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
        modifier = Modifier.border(1.dp, if (quality == "4K") Palette.accent.copy(alpha = 0.45f) else Palette.lineStrong, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp))
}

@Composable
fun ProgressLine(value: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, tint: Color = Palette.accent) {
    Box(modifier.height(height).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).fillMaxSize().clip(RoundedCornerShape(50)).background(tint))
    }
}

/** Focusable tile style for the remote: grows a little and gets an amber outline when focused. */
@Composable
fun tileColors() = ClickableSurfaceDefaults.colors(
    containerColor = Color.Transparent, contentColor = Palette.fg,
    focusedContainerColor = Palette.surface3, focusedContentColor = Palette.fg,
    pressedContainerColor = Palette.surface3,
)

@Composable
fun tileBorder(radius: Dp = 10.dp) = ClickableSurfaceDefaults.border(
    focusedBorder = Border(BorderStroke(2.dp, Palette.accent), shape = RoundedCornerShape(radius)),
)

/** Live channel card for Home and Favorites rows. */
@Composable
fun ChannelCard(vm: AppViewModel, channel: Channel, list: List<Channel>, width: Dp = 240.dp) {
    val sched = vm.schedule(channel)
    LaunchedEffect(channel.id) { vm.requestEpg(channel) }
    Surface(
        onClick = { vm.play(channel, list) },
        onLongClick = { vm.toggleFavorite(channel) },
        modifier = Modifier.width(width),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = tileColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = tileBorder(12.dp),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).channelBackdrop(channel),
                contentAlignment = Alignment.Center) {
                ChannelLogo(channel, 88.dp, 64.dp)
                Row(Modifier.align(Alignment.TopStart).padding(8.dp)) { LiveBadge() }
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { QualityBadge(channel.quality) }
                sched.current?.let { ProgressLine(it.progress(vm.now), Modifier.align(Alignment.BottomCenter).fillMaxWidth()) }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.displayName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Text("${channel.num}", fontSize = 12.sp, color = Palette.faint, fontFamily = FontFamily.Monospace)
                }
                Text(sched.current?.title ?: if (sched.isLoading) " " else "Live", fontSize = 13.sp, color = Palette.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Palette.faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = modifier)
}

@Composable
fun BrandMark(size: Int = 20) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("((•))", color = Palette.accent, fontSize = (size * 0.9).sp, fontWeight = FontWeight.Black)
        Text("Antenna ", fontSize = size.sp, fontWeight = FontWeight.SemiBold)
        Text("IPTV", fontSize = size.sp, fontWeight = FontWeight.SemiBold, color = Palette.accent, modifier = Modifier.padding(start = 0.dp))
    }
}
