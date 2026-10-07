package de.flexy.caddylog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.flexy.caddylog.core.CoolantLevel
import de.flexy.caddylog.core.CoolantMonitor
import de.flexy.caddylog.core.CoolantThresholds
import de.flexy.caddylog.core.EventKind
import de.flexy.caddylog.core.MonitorEvent
import de.flexy.caddylog.core.Signal
import de.flexy.caddylog.core.TripPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

val SIGNAL_COLORS: Map<Signal, Color> = mapOf(
    Signal.COOLANT to Color(0xFFE5484D),
    Signal.RPM to Color(0xFF3B82F6),
    Signal.SPEED to Color(0xFF22A06B),
    Signal.LOAD to Color(0xFF8B5CF6),
    Signal.IAT to Color(0xFFF59E0B),
    Signal.MAP to Color(0xFF14B8A6),
    Signal.THROTTLE to Color(0xFFEC4899),
    Signal.ECU_VOLTAGE to Color(0xFF64748B),
    Signal.BATTERY to Color(0xFF94A3B8),
)

fun formatSignal(sig: Signal, v: Double): String = when (sig) {
    Signal.ECU_VOLTAGE, Signal.BATTERY -> String.format(java.util.Locale.GERMANY, "%.1f", v)
    Signal.COOLANT -> CoolantMonitor.fmt(v)
    else -> v.roundToInt().toString()
}

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
private val HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss")
private fun clock(t: Long, seconds: Boolean) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(if (seconds) HHMMSS else HHMM)

/** Toggle chips for the graph lines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignalToggles(visible: Set<Signal>, onToggle: (Signal) -> Unit, signals: List<Signal> = Signal.entries) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in signals) {
            FilterChip(
                selected = s in visible,
                onClick = { onToggle(s) },
                label = { Text(s.label) },
                leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(SIGNAL_COLORS[s] ?: Color.Gray)) },
            )
        }
    }
}

/**
 * Time series chart. Coolant uses the left °C axis (with warning/critical lines); every other line is scaled
 * to its own range (values are shown in the cursor row). Tap or drag to inspect a moment.
 */
@Composable
fun TimeChart(
    points: List<TripPoint>,
    visible: Set<Signal>,
    windowMs: Long?,
    thresholds: CoolantThresholds,
    events: List<MonitorEvent> = emptyList(),
    height: Dp = 300.dp,
    showAxes: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var cursorX by remember { mutableStateOf<Float?>(null) }
    var cursorPoint by remember { mutableStateOf<TripPoint?>(null) }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .pointerInput(Unit) { detectTapGestures { cursorX = it.x } }
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { cursorX = it.x }, onDrag = { change, _ -> cursorX = change.position.x })
                }
        ) {
            if (points.isEmpty()) {
                drawText(measurer, "Noch keine Messwerte", Offset(size.width / 2 - 60.dp.toPx(), size.height / 2), labelStyle)
                return@Canvas
            }
            val tEnd = points.last().t
            val tStart = windowMs?.let { max(points.first().t, tEnd - it) } ?: points.first().t
            val span = (tEnd - tStart).coerceAtLeast(1000L).toFloat()
            val left = if (showAxes) 34.dp.toPx() else 0f
            val bottom = if (showAxes) 16.dp.toPx() else 0f
            val w = size.width - left
            val h = size.height - bottom
            val firstIdx = points.indexOfFirst { it.t >= tStart }.coerceAtLeast(0)
            val vis = points.subList(firstIdx, points.size)
            val step = max(1, vis.size / max(1, w.toInt()))
            val sampled = vis.filterIndexed { i, _ -> i % step == 0 || i == vis.size - 1 }
            fun x(t: Long) = left + (t - tStart) / span * w

            // coolant axis
            val coolVals = sampled.mapNotNull { it.valueOrNull(Signal.COOLANT) }
            val cMin = minOf(40.0, coolVals.minOrNull() ?: 40.0)
            val cMax = maxOf(130.0, coolVals.maxOrNull() ?: 130.0)
            fun yC(v: Double) = (h - (v - cMin) / (cMax - cMin) * h).toFloat()

            if (showAxes) {
                var g = ceil(cMin / 10) * 10
                while (g <= cMax) {
                    val y = yC(g)
                    drawLine(gridColor, Offset(left, y), Offset(size.width, y), strokeWidth = 1f)
                    drawText(measurer, "${g.toInt()}°", Offset(0f, y - 6.dp.toPx()), labelStyle)
                    g += 20
                }
                val secs = span < 10 * 60_000
                for (k in 0..3) {
                    val t = tStart + (span * k / 3).toLong()
                    val lx = (x(t) - if (k == 3) 36.dp.toPx() else if (k == 0) 0f else 18.dp.toPx()).coerceAtLeast(left)
                    drawText(measurer, clock(t, secs), Offset(lx, h + 2.dp.toPx()), labelStyle)
                }
            }

            if (Signal.COOLANT in visible) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                for ((thr, col) in listOf(thresholds.warning to Color(0xFFFF9800), thresholds.critical to Color(0xFFD32F2F))) {
                    val y = yC(thr)
                    drawLine(col.copy(alpha = 0.8f), Offset(left, y), Offset(size.width, y), strokeWidth = 2f, pathEffect = dash)
                }
            }

            // event markers (warning / critical / rapid rise)
            for (e in events) {
                if (e.timeMs < tStart || e.timeMs > tEnd) continue
                val col = when {
                    e.kind == EventKind.LEVEL && e.level.atLeast(CoolantLevel.CRITICAL) -> Color(0xFFD32F2F)
                    e.kind == EventKind.LEVEL && e.level.atLeast(CoolantLevel.WARNING) -> Color(0xFFFF9800)
                    e.kind == EventKind.RAPID_RISE -> Color(0xFFF4511E)
                    else -> null
                } ?: continue
                val ex = x(e.timeMs)
                drawLine(col.copy(alpha = 0.5f), Offset(ex, 0f), Offset(ex, h), strokeWidth = 2f)
            }

            // lines
            for (sig in Signal.entries.reversed()) {
                if (sig !in visible) continue
                val vals = sampled.mapNotNull { it.valueOrNull(sig) }
                if (vals.isEmpty()) continue
                val lo: Double; val hi: Double
                if (sig == Signal.COOLANT) { lo = cMin; hi = cMax } else {
                    lo = minOf(sig.defaultMin, vals.min()); hi = maxOf(sig.defaultMax, vals.max())
                }
                val path = Path()
                var drawing = false
                for (p in sampled) {
                    val v = p.valueOrNull(sig)
                    if (v == null) { drawing = false; continue }
                    val px = x(p.t)
                    val py = (h - (v - lo) / (hi - lo) * h).toFloat()
                    if (!drawing) { path.moveTo(px, py); drawing = true } else path.lineTo(px, py)
                }
                drawPath(path, SIGNAL_COLORS[sig] ?: Color.Gray,
                    style = Stroke(width = if (sig == Signal.COOLANT) 3.dp.toPx() else 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            // cursor
            cursorX?.let { cx ->
                val clamped = cx.coerceIn(left, size.width)
                val t = tStart + ((clamped - left) / w * span).toLong()
                val nearest = vis.minByOrNull { kotlin.math.abs(it.t - t) }
                cursorPoint = nearest
                if (nearest != null) {
                    val nx = x(nearest.t)
                    drawLine(labelColor, Offset(nx, 0f), Offset(nx, h), strokeWidth = 1.5f)
                    nearest.valueOrNull(Signal.COOLANT)?.let { drawCircle(SIGNAL_COLORS[Signal.COOLANT]!!, 5.dp.toPx(), Offset(nx, yC(it))) }
                }
            }
        }
        val cp = cursorPoint
        if (cp != null && cursorX != null) {
            val parts = visible.sortedBy { it.ordinal }.map { s -> "${s.label} " + (cp.valueOrNull(s)?.let { formatSignal(s, it) + " " + s.unit } ?: "–") }
            Text("${clock(cp.t, true)} · " + parts.joinToString(" · "), Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Shared helper: last value of every signal for the legend */
fun legendValues(points: List<TripPoint>): Map<Signal, Double?> {
    val last = points.lastOrNull() ?: return emptyMap()
    return Signal.entries.associateWith { last.valueOrNull(it) }
}

@Suppress("unused")
private fun floorTo(v: Double, step: Double) = floor(v / step) * step
