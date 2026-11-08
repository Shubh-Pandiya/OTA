package com.ota.desktop.ui

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ota.desktop.Money
import com.ota.desktop.dash.Point
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Short money for axes and tooltips: ₹1.2 L, ₹45 K, ₹2.3 Cr. */
fun shortMoney(v: Double): String {
    val a = kotlin.math.abs(v)
    val s = when {
        a >= 1e7 -> trim(a / 1e7) + " Cr"
        a >= 1e5 -> trim(a / 1e5) + " L"
        a >= 1e3 -> trim(a / 1e3) + " K"
        else -> trim(a)
    }
    return (if (v < 0) "-₹" else "₹") + s
}

fun shortNumber(v: Double): String = if (v >= 1e5) trim(v / 1e5) + " L" else if (v >= 1e3) trim(v / 1e3) + " K" else trim(v)

private fun trim(v: Double): String = if (v >= 100 || v == floor(v)) "%.0f".format(v) else "%.1f".format(v)

/** Rounded upper bound and step for a y axis with about four gridlines. */
private fun niceScale(maxValue: Double): Pair<Double, Double> {
    if (maxValue <= 0) return 1.0 to 0.25
    val raw = maxValue / 4
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    return ceil(maxValue / step) * step to step
}

@Composable
private fun rememberEntry(key: Any?): Float {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(key) {
        anim.snapTo(0f)
        anim.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
    }
    return anim.value
}

private fun Modifier.hoverX(onMove: (Offset?) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            when (event.type) {
                PointerEventType.Exit -> onMove(null)
                else -> onMove(event.changes.firstOrNull()?.position)
            }
        }
    }
}

private val axisStyle = TextStyle(fontSize = 10.sp, color = Brand.Muted)

private fun DrawScope.tooltip(measurer: TextMeasurer, at: Offset, title: String, value: String) {
    val t1 = measurer.measure(title, TextStyle(fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)))
    val t2 = measurer.measure(value, TextStyle(fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold))
    val w = max(t1.size.width, t2.size.width) + 16f
    val h = t1.size.height + t2.size.height + 10f
    val x = (at.x - w / 2).coerceIn(0f, size.width - w)
    val y = (at.y - h - 10f).coerceAtLeast(0f)
    drawRoundRect(Color(0xE62A1F18), Offset(x, y), Size(w, h), CornerRadius(6f))
    drawText(t1, topLeft = Offset(x + 8f, y + 5f))
    drawText(t2, topLeft = Offset(x + 8f, y + 5f + t1.size.height))
}

/** Grid, y labels and x labels shared by the bar and line charts; returns the plot area. */
private fun DrawScope.axes(measurer: TextMeasurer, points: List<Point>, top: Double, step: Double, format: (Double) -> String): Pair<Offset, Size> {
    val labelW = (0..4).maxOf { measurer.measure(format(step * it), axisStyle).size.width }.toFloat() + 8f
    val bottomH = 18f
    val origin = Offset(labelW, 6f)
    val plot = Size(size.width - labelW, size.height - bottomH - 6f)
    var v = 0.0
    while (v <= top + 1e-9) {
        val y = origin.y + plot.height * (1 - (v / top)).toFloat()
        drawLine(Brand.Line, Offset(origin.x, y), Offset(size.width, y), 1f, pathEffect = if (v == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
        val t = measurer.measure(format(v), axisStyle)
        drawText(t, topLeft = Offset(labelW - t.size.width - 6f, y - t.size.height / 2))
        v += step
    }
    if (points.isNotEmpty()) {
        val slot = plot.width / points.size
        val sample = measurer.measure(points.maxBy { it.label.length }.label, axisStyle).size.width + 10f
        val every = max(1, ceil(sample / slot).toInt())
        points.forEachIndexed { i, p ->
            if (i % every != 0) return@forEachIndexed
            val t = measurer.measure(p.label, axisStyle)
            val cx = origin.x + slot * (i + 0.5f)
            drawText(t, topLeft = Offset((cx - t.size.width / 2).coerceIn(origin.x, size.width - t.size.width), size.height - t.size.height))
        }
    }
    return origin to plot
}

@Composable
fun BarChart(points: List<Point>, modifier: Modifier = Modifier, color: Color = Brand.Saffron, format: (Double) -> String = ::shortNumber, tipFormat: (Double) -> String = format) {
    val measurer = rememberTextMeasurer()
    val progress = rememberEntry(points)
    var hover by remember { mutableStateOf<Offset?>(null) }
    Canvas(modifier.fillMaxWidth().hoverX { hover = it }) {
        val (top, step) = niceScale(points.maxOfOrNull { it.value } ?: 0.0)
        val (o, plot) = axes(measurer, points, top, step, format)
        if (points.isEmpty()) return@Canvas
        val slot = plot.width / points.size
        val barW = (slot * 0.62f).coerceAtMost(42f)
        var tip: Triple<Offset, String, String>? = null
        points.forEachIndexed { i, p ->
            val h = (plot.height * (p.value / top).toFloat() * progress).coerceAtLeast(if (p.value > 0) 1.5f else 0f)
            val x = o.x + slot * i + (slot - barW) / 2
            val y = o.y + plot.height - h
            val hovered = hover?.let { it.x >= o.x + slot * i && it.x < o.x + slot * (i + 1) } == true
            drawRoundRect(
                if (hovered) color else color.copy(alpha = 0.82f), Offset(x, y), Size(barW, h),
                CornerRadius(min(4f, barW / 3), min(4f, barW / 3))
            )
            if (hovered) tip = Triple(Offset(x + barW / 2, y), p.label, tipFormat(p.value))
        }
        tip?.let { (at, l, v) -> tooltip(measurer, at, l, v) }
    }
}

@Composable
fun LineChart(points: List<Point>, modifier: Modifier = Modifier, color: Color = Brand.Blue, format: (Double) -> String = ::shortMoney, tipFormat: (Double) -> String = format) {
    val measurer = rememberTextMeasurer()
    val progress = rememberEntry(points)
    var hover by remember { mutableStateOf<Offset?>(null) }
    Canvas(modifier.fillMaxWidth().hoverX { hover = it }) {
        val (top, step) = niceScale(points.maxOfOrNull { it.value } ?: 0.0)
        val (o, plot) = axes(measurer, points, top, step, format)
        if (points.isEmpty()) return@Canvas
        val slot = plot.width / points.size
        val pts = points.mapIndexed { i, p ->
            Offset(o.x + slot * (i + 0.5f), o.y + plot.height * (1 - (p.value / top).toFloat() * progress))
        }
        val line = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        val area = Path().apply {
            addPath(line)
            lineTo(pts.last().x, o.y + plot.height)
            lineTo(pts.first().x, o.y + plot.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.02f)), o.y, o.y + plot.height))
        drawPath(line, color, style = Stroke(2.2f, cap = StrokeCap.Round))
        val idx = hover?.let { ((it.x - o.x) / slot).toInt() }?.takeIf { it in pts.indices }
        if (pts.size <= 40) pts.forEach { drawCircle(Color.White, 3.2f, it); drawCircle(color, 3.2f, it, style = Stroke(1.6f)) }
        if (idx != null) {
            val p = pts[idx]
            drawLine(color.copy(alpha = 0.4f), Offset(p.x, o.y), Offset(p.x, o.y + plot.height), 1f)
            drawCircle(color, 5f, p)
            tooltip(measurer, p, points[idx].label, tipFormat(points[idx].value))
        }
    }
}

class HBar(val label: String, val value: Double, val secondary: Double? = null, val color: Color = Brand.Saffron, val note: String = "")

/** Ranked horizontal bars; [HBar.secondary] (e.g. outstanding) is drawn inside the main bar. */
@Composable
fun HBars(bars: List<HBar>, modifier: Modifier = Modifier, format: (Double) -> String = ::shortMoney, secondaryColor: Color = Brand.Red) {
    if (bars.isEmpty()) { Empty("No data for this filter", modifier); return }
    val maxV = bars.maxOf { it.value }.takeIf { it > 0 } ?: 1.0
    val progress = rememberEntry(bars.map { it.label to it.value })
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        bars.forEach { bar ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bar.label, Modifier.width(150.dp).padding(end = 10.dp), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Box(Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((bar.value / maxV).toFloat().coerceIn(0f, 1f) * progress).clip(RoundedCornerShape(4.dp)).background(bar.color))
                    bar.secondary?.takeIf { it > 0 }?.let { s ->
                        Box(Modifier.fillMaxHeight().fillMaxWidth((s / maxV).toFloat().coerceIn(0f, 1f) * progress).clip(RoundedCornerShape(4.dp)).background(secondaryColor.copy(alpha = 0.75f)))
                    }
                }
                Text(
                    format(bar.value) + bar.note, Modifier.width(96.dp).padding(start = 10.dp),
                    style = MaterialTheme.typography.bodySmall.merge(Numeric), fontWeight = FontWeight.SemiBold, maxLines = 1
                )
            }
        }
    }
}

class Slice(val label: String, val value: Double, val color: Color, val detail: String = "")

@Composable
fun Donut(slices: List<Slice>, center: String, centerSub: String, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.value }
    val progress = rememberEntry(slices.map { it.value })
    var hover by remember { mutableStateOf<Offset?>(null) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().hoverX { hover = it }) {
                val stroke = size.minDimension * 0.17f
                val d = size.minDimension - stroke
                val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
                drawArc(Brand.Line, 0f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
                if (total <= 0) return@Canvas
                val hoverAngle = hover?.let {
                    val c = Offset(size.width / 2, size.height / 2)
                    val r = hypot(it.x - c.x, it.y - c.y)
                    if (r < d / 2 - stroke || r > d / 2 + stroke) null
                    else ((atan2(it.y - c.y, it.x - c.x) * 180 / PI + 90 + 360) % 360).toFloat()
                }
                var start = -90f
                slices.forEach { s ->
                    val sweep = (s.value / total * 360).toFloat() * progress
                    val a0 = start + 90
                    val hit = hoverAngle != null && hoverAngle >= a0 && hoverAngle < a0 + sweep
                    if (sweep > 0) drawArc(s.color, start, sweep - if (slices.count { it.value > 0 } > 1) 1.2f else 0f, false, tl, Size(d, d), style = Stroke(if (hit) stroke * 1.18f else stroke))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(center, style = MaterialTheme.typography.titleMedium)
                Text(centerSub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            slices.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(s.color, 10.dp)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(s.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        if (s.detail.isNotEmpty()) Text(s.detail, style = MaterialTheme.typography.labelSmall.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Half-ring gauge for cloud storage use, turning amber at [warnAt] and red at 100 %. */
@Composable
fun Gauge(percent: Double, warnAt: Int, label: String, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(percent.toFloat().coerceIn(0f, 100f), tween(800, easing = FastOutSlowInEasing))
    val color = when {
        percent >= 100 -> Brand.Red
        percent >= warnAt -> Brand.Amber
        else -> Brand.Green
    }
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = min(min(size.width, size.height * 2) * 0.09f, 18.dp.toPx())
            val d = min(size.width - stroke, (size.height - stroke) * 2)
            val tl = Offset((size.width - d) / 2, stroke / 2)
            drawArc(Brand.Line, 180f, 180f, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, 180f, 180f * animated / 100f, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            // Warning threshold tick.
            val a = Math.toRadians(180.0 + 180.0 * warnAt / 100)
            val c = Offset(size.width / 2, tl.y + d / 2)
            val r1 = d / 2 - stroke * 0.8f
            val r2 = d / 2 + stroke * 0.8f
            drawLine(Brand.Muted, Offset(c.x + r1 * kotlin.math.cos(a).toFloat(), c.y + r1 * kotlin.math.sin(a).toFloat()), Offset(c.x + r2 * kotlin.math.cos(a).toFloat(), c.y + r2 * kotlin.math.sin(a).toFloat()), 2f)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
            Text("%.1f %%".format(percent), style = MaterialTheme.typography.titleLarge, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A KPI tile. */
@Composable
fun Kpi(label: String, value: String, sub: String, accent: Color, modifier: Modifier = Modifier) {
    Panel(modifier, padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Row {
            Box(Modifier.width(4.dp).height(84.dp).background(accent))
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                // Large rupee amounts shrink to fit rather than being cut off.
                val style = MaterialTheme.typography.titleLarge.merge(Numeric)
                BasicText(
                    value, style = style.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = style.fontSize, stepSize = 1.sp)
                )
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

fun moneyFull(v: Double): String = "₹" + Money.format(v)
