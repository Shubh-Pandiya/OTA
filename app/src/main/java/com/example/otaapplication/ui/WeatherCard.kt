package com.example.otaapplication.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.otaapplication.weather.Weather
import com.example.otaapplication.weather.WeatherKind
import com.example.otaapplication.weather.WeatherNow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Sky colours for the card behind the weather: saffron sun by day, indigo at night, greys for cloud and rain. */
fun weatherSky(weather: WeatherNow?): List<Color> {
    if (weather == null) return listOf(Color(0xFFFF6F00), Color(0xFFFF8F00), Color(0xFFFFA726))
    val night = !weather.isDay
    return when (weather.kind) {
        WeatherKind.CLEAR, WeatherKind.PARTLY_CLOUDY ->
            if (night) listOf(Color(0xFF1A237E), Color(0xFF3949AB), Color(0xFF5C6BC0)) else listOf(Color(0xFFFF6F00), Color(0xFFFF8F00), Color(0xFFFFB74D))
        WeatherKind.CLOUDY, WeatherKind.FOG ->
            if (night) listOf(Color(0xFF263238), Color(0xFF455A64), Color(0xFF607D8B)) else listOf(Color(0xFF546E7A), Color(0xFF78909C), Color(0xFF90A4AE))
        WeatherKind.DRIZZLE, WeatherKind.RAIN -> listOf(Color(0xFF1E4F7A), Color(0xFF2F6A9E), Color(0xFF5B8DBE))
        WeatherKind.STORM -> listOf(Color(0xFF263238), Color(0xFF37474F), Color(0xFF4E5BA6))
        WeatherKind.SNOW -> listOf(Color(0xFF5D7FA3), Color(0xFF81A4C8), Color(0xFFA9C3DE))
    }
}

/** "just now", "12 min ago", "3 h ago". */
fun updatedAgo(at: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - at) / 60_000
    return when {
        minutes < 2 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

/**
 * Greeting, date and the local weather on a sky-coloured card. Without [weather] it shows [note]
 * instead (e.g. how to set a city), so a missing forecast never leaves an error on Home.
 */
@Composable
fun WeatherCard(
    weather: WeatherNow?,
    note: String?,
    greeting: String,
    date: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    iconSize: Dp = 64.dp,
    trailing: @Composable () -> Unit = {}
) {
    val faint = Color.White.copy(alpha = 0.85f)
    Column(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(weatherSky(weather)))
            .clickable(onClick = onClick)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(greeting, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(date, style = MaterialTheme.typography.bodyMedium, color = faint, maxLines = 1)
            }
            trailing()
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(weather?.kind ?: WeatherKind.PARTLY_CLOUDY, weather?.isDay ?: true, Modifier.size(iconSize), dim = weather == null)
            Spacer(Modifier.width(14.dp))
            if (weather != null) {
                Column(Modifier.weight(1f)) {
                    Text(Weather.degrees(weather.temperature), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
                    Text(weather.label, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(weather.place, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 1.dp))
                    }
                    if (weather.high != null && weather.low != null) {
                        Text("H ${Weather.degrees(weather.high)}  ·  L ${Weather.degrees(weather.low)}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                    Text(note ?: "Updated ${updatedAgo(weather.fetchedAt)}", style = MaterialTheme.typography.labelSmall, color = faint, maxLines = 1, textAlign = TextAlign.End)
                }
            } else {
                Text(
                    note ?: "Getting the weather…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** A small drawn weather symbol (no image assets), meant for a coloured background. */
@Composable
fun WeatherIcon(kind: WeatherKind, isDay: Boolean, modifier: Modifier = Modifier, dim: Boolean = false) {
    Canvas(modifier) {
        val k = size.minDimension / 48f
        scale(k, k, pivot = Offset.Zero) {
            val alpha = if (dim) 0.55f else 1f
            when (kind) {
                WeatherKind.CLEAR -> if (isDay) sun(Offset(24f, 24f), 9f, alpha) else moon(Offset(24f, 24f), 11f, alpha)
                WeatherKind.PARTLY_CLOUDY -> {
                    if (isDay) sun(Offset(18f, 17f), 7f, alpha) else moon(Offset(18f, 17f), 8.5f, alpha)
                    cloud(Offset(4f, 6f), Color.White.copy(alpha = 0.96f * alpha))
                }
                WeatherKind.CLOUDY -> {
                    cloud(Offset(-3f, -3f), Color.White.copy(alpha = 0.6f * alpha))
                    cloud(Offset(2f, 3f), Color.White.copy(alpha = alpha))
                }
                WeatherKind.FOG -> {
                    cloud(Offset(0f, -5f), Color.White.copy(alpha = 0.9f * alpha))
                    for ((i, y) in floatArrayOf(34f, 39f, 44f).withIndex()) {
                        drawLine(Color.White.copy(alpha = (0.85f - i * 0.2f) * alpha), Offset(9f + i * 3, y), Offset(39f - i * 2, y), strokeWidth = 2.4f, cap = StrokeCap.Round)
                    }
                }
                WeatherKind.DRIZZLE, WeatherKind.RAIN -> {
                    cloud(Offset(0f, -4f), Color.White.copy(alpha = alpha))
                    val drops = if (kind == WeatherKind.RAIN) floatArrayOf(15f, 22f, 29f, 36f) else floatArrayOf(18f, 30f)
                    for ((i, x) in drops.withIndex()) {
                        val y = 34f + (i % 2) * 4f
                        drawLine(Color(0xFFBBDEFB).copy(alpha = alpha), Offset(x, y), Offset(x - 2f, y + 5f), strokeWidth = 2.4f, cap = StrokeCap.Round)
                    }
                }
                WeatherKind.STORM -> {
                    cloud(Offset(0f, -4f), Color.White.copy(alpha = alpha))
                    val bolt = Path().apply { moveTo(25f, 30f); lineTo(19f, 39f); lineTo(24f, 39f); lineTo(21f, 47f); lineTo(30f, 36f); lineTo(25f, 36f); lineTo(28f, 30f); close() }
                    drawPath(bolt, Color(0xFFFFEB3B).copy(alpha = alpha))
                }
                WeatherKind.SNOW -> {
                    cloud(Offset(0f, -4f), Color.White.copy(alpha = alpha))
                    for ((i, x) in floatArrayOf(16f, 24f, 32f).withIndex()) flake(Offset(x, 37f + (i % 2) * 5f), 3f, alpha)
                }
            }
        }
    }
}

private fun DrawScope.sun(c: Offset, r: Float, alpha: Float) {
    drawCircle(Color(0xFFFFF59D).copy(alpha = 0.35f * alpha), radius = r + 5f, center = c)
    for (i in 0 until 8) {
        val a = i * PI.toFloat() / 4
        drawLine(
            Color(0xFFFFF176).copy(alpha = alpha),
            Offset(c.x + cos(a) * (r + 3f), c.y + sin(a) * (r + 3f)),
            Offset(c.x + cos(a) * (r + 7f), c.y + sin(a) * (r + 7f)),
            strokeWidth = 2.4f, cap = StrokeCap.Round
        )
    }
    drawCircle(Color(0xFFFFEB3B).copy(alpha = alpha), radius = r, center = c)
}

private fun DrawScope.moon(c: Offset, r: Float, alpha: Float) {
    val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + r * 0.55f, c.y - r * 0.45f), r * 0.9f)) }
    drawPath(Path.combine(PathOperation.Difference, disc, bite), Color(0xFFFFF9C4).copy(alpha = alpha))
}

private fun DrawScope.cloud(shift: Offset, color: Color) {
    val x = shift.x
    val y = shift.y
    drawCircle(color, radius = 7.5f, center = Offset(x + 15f, y + 27f))
    drawCircle(color, radius = 10f, center = Offset(x + 24f, y + 22f))
    drawCircle(color, radius = 7f, center = Offset(x + 33f, y + 27f))
    drawRoundRect(color, topLeft = Offset(x + 9f, y + 26f), size = Size(30f, 8.5f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.25f))
}

private fun DrawScope.flake(c: Offset, r: Float, alpha: Float) {
    for (i in 0 until 3) {
        val a = i * PI.toFloat() / 3
        drawLine(Color.White.copy(alpha = alpha), Offset(c.x - cos(a) * r, c.y - sin(a) * r), Offset(c.x + cos(a) * r, c.y + sin(a) * r), strokeWidth = 1.6f, cap = StrokeCap.Round)
    }
}
