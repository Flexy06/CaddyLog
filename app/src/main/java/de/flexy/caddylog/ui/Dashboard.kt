package de.flexy.caddylog.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.flexy.caddylog.core.CoolantLevel
import de.flexy.caddylog.core.CoolantMonitor
import de.flexy.caddylog.core.CoolantThresholds
import de.flexy.caddylog.core.EventKind
import de.flexy.caddylog.core.MonitorEvent
import de.flexy.caddylog.core.ReadStatus
import de.flexy.caddylog.core.Signal
import de.flexy.caddylog.data.LoggerEngine
import de.flexy.caddylog.data.LoggerEngine.Conn
import de.flexy.caddylog.data.Settings
import de.flexy.caddylog.monitor.MonitorEngine
import kotlinx.coroutines.delay
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Background / content colour of the coolant display per level. */
fun levelColors(level: CoolantLevel, fresh: Boolean, surface: Color, onSurface: Color): Pair<Color, Color> = when {
    !fresh -> surface to onSurface
    level == CoolantLevel.ELEVATED -> Color(0xFFFFD54F) to Color(0xFF2B1B00)
    level == CoolantLevel.WARNING -> Color(0xFFFF9800) to Color(0xFF1F1200)
    level == CoolantLevel.STRONG_WARNING -> Color(0xFFF4511E) to Color.White
    level == CoolantLevel.CRITICAL -> Color(0xFFD32F2F) to Color.White
    level == CoolantLevel.EXTREME -> Color(0xFF8E0000) to Color.White
    else -> surface to onSurface
}

private val DATE_TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm")
fun clockTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
fun dateTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DATE_TIME)

@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    return now
}

@Composable
fun DashboardScreen(onOpenTrip: (File) -> Unit) {
    val conn by LoggerEngine.conn.collectAsStateWithLifecycle()
    val st by MonitorEngine.ui.collectAsStateWithLifecycle()
    val events by MonitorEngine.events.collectAsStateWithLifecycle()
    val lastTrip by MonitorEngine.lastTrip.collectAsStateWithLifecycle()
    val settings by Settings.flow.collectAsStateWithLifecycle()
    val version by MonitorEngine.graphVersion.collectAsStateWithLifecycle()
    val now = rememberNow()

    val connected = conn is Conn.Ready || conn is Conn.Reconnecting
    if (!connected) {
        Column(Modifier.fillMaxSize()) {
            Text("CADDY LIVE", Modifier.padding(start = 16.dp, top = 12.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            lastTrip?.let { (dir, s) -> TripSummaryCard(s.lines, onDetails = { onOpenTrip(dir) }, onClose = { MonitorEngine.lastTrip.value = null }) }
            Box(Modifier.weight(1f)) { ConnectScreen() }
        }
        return
    }

    val maxAge = maxOf(5_000L, (3000 / st.effectiveHz).toLong())
    val points = remember(version) { MonitorEngine.liveBuffer.points() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("CADDY LIVE", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Text(hzText(st.effectiveHz) + if (st.effectiveHz < st.targetHz) " (Ziel ${hzText(st.targetHz)})" else "",
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        (conn as? Conn.Reconnecting)?.let { c ->
            item { Banner("Verbindung verloren – verbinde neu (Versuch ${c.attempt}). Keine aktuellen Messwerte!", Color(0xFFD32F2F), Color.White) }
        }
        if (st.paused) item { Banner("Diagnose-Scan läuft – Live-Überwachung pausiert", Color(0xFFFF9800), Color.Black) }
        item { CoolantCard(st, settings.thresholds(), now) }
        item { TripCard(st.trip, now) }
        lastTrip?.let { (dir, s) ->
            item { TripSummaryCard(s.lines, onDetails = { onOpenTrip(dir) }, onClose = { MonitorEngine.lastTrip.value = null }) }
        }
        item {
            DashCard("Temperaturverlauf (10 min)") {
                TimeChart(points, setOf(Signal.COOLANT), 10 * 60_000L, settings.thresholds(), MonitorEngine.liveBuffer.events(), height = 150.dp)
            }
        }
        item { SignalsCard(st, now, maxAge) }
        item {
            DashCard("Kühlerlüfter") {
                Text("Der Lüfterzustand ist über Standard-OBD2 nicht auslesbar – die App zeigt deshalb keinen Lüfterstatus an. " +
                        "Stattdessen wird beobachtet, ob die Temperatur bei hohen Werten weiter steigt oder nicht sinkt " +
                        "(z. B. „keine erkennbare Abkühlung“).", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { StatusCard(conn, st) }
        item {
            DashCard("Letzte Ereignisse") {
                val recent = events.takeLast(8).reversed()
                if (recent.isEmpty()) Text("Noch keine Ereignisse.", style = MaterialTheme.typography.bodySmall)
                recent.forEach { EventRow(it) }
            }
        }
        item {
            Text("Diagnosehilfe: Warnungen beruhen auf konfigurierbaren Schwellen und gemessenen Daten. " +
                    "Sie stellen keine Fehlerdiagnose dar. Die App liest nur und steuert nichts am Fahrzeug.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun hzText(hz: Double) = if (hz >= 1) "${hz.roundToInt()} Hz" else "${hz} Hz"

@Composable
private fun Banner(text: String, bg: Color, fg: Color) {
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text, Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun DashCard(title: String?, containerColor: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = if (containerColor != null) CardDefaults.cardColors(containerColor = containerColor) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
private fun CoolantCard(st: MonitorEngine.UiState, thr: CoolantThresholds, now: Long) {
    val c = st.coolant
    val (bg0, fg) = levelColors(c.level, c.fresh, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface)
    val bg by animateColorAsState(bg0, label = "coolantBg")
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Kühlmittel", style = MaterialTheme.typography.titleMedium)
            val temp = c.tempC
            if (c.fresh && temp != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(temp.roundToInt().toString(), fontSize = 96.sp, lineHeight = 96.sp, fontWeight = FontWeight.Black)
                    Text(" °C", fontSize = 32.sp, modifier = Modifier.padding(bottom = 14.dp))
                }
                Text(c.level.label.uppercase(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            } else {
                Text("– –", fontSize = 72.sp, lineHeight = 80.sp, fontWeight = FontWeight.Black)
                Text("KEINE AKTUELLE MESSUNG", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val why = st.lastCoolantStatus?.takeIf { it != ReadStatus.OK }?.label
                val last = if (temp != null && c.lastValidMs != null)
                    "Letzter Wert ${CoolantMonitor.fmt(temp)} °C vor ${CoolantMonitor.duration(now - c.lastValidMs)}" else "Noch kein Messwert empfangen"
                Text(listOfNotNull(last, why).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            TempGauge(if (c.fresh) temp else null, thr, fg)
            Spacer(Modifier.height(12.dp))
            val rate = c.ratePerMin
            Row(Modifier.fillMaxWidth()) {
                Stat("Änderung", rate?.let { CoolantMonitor.fmtSigned(Math.round(it * 10) / 10.0) + " °C/min" } ?: "–", Modifier.weight(1f))
                Stat("Max. Fahrt", c.tripMax?.let { "${CoolantMonitor.fmt(it)} °C" } ?: "–",
                    Modifier.weight(1f), c.tripMaxTimeMs?.let { clockTime(it) })
                Stat("Max. bisher", st.allTimeMax?.let { "${CoolantMonitor.fmt(it)} °C" } ?: "–",
                    Modifier.weight(1f), st.allTimeMaxMs?.let { dateTime(it) })
            }
            if (c.rapidRise) {
                Spacer(Modifier.height(10.dp))
                Text("⚠ Temperatur steigt ungewöhnlich schnell", fontWeight = FontWeight.Bold)
            }
            c.aboveWarningSinceMs?.let { since ->
                Text("Seit ${CoolantMonitor.duration(now - since)} im Warnbereich (≥ ${CoolantMonitor.fmt(thr.warning)} °C)",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, sub: String? = null) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall)
    }
}

/** Horizontal bar 40–130 °C with coloured zones and threshold ticks. */
@Composable
fun TempGauge(temp: Double?, thr: CoolantThresholds, markerColor: Color) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val lo = 40.0; val hi = 130.0
        fun x(v: Double) = ((v.coerceIn(lo, hi) - lo) / (hi - lo) * size.width).toFloat()
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, size = size, cornerRadius = r)
        val zones = listOf(
            thr.elevated to thr.warning to Color(0xFFFFD54F),
            thr.warning to thr.critical to Color(0xFFFF9800),
            thr.critical to hi to Color(0xFFD32F2F),
        )
        for ((range, col) in zones) {
            val (a, b) = range
            drawRect(col.copy(alpha = 0.35f), topLeft = Offset(x(a), 0f), size = Size(x(b) - x(a), size.height))
        }
        if (temp != null) {
            drawRoundRect(markerColor.copy(alpha = 0.85f), size = Size(x(temp).coerceAtLeast(size.height), size.height), cornerRadius = r)
        }
        for (t in listOf(thr.elevated, thr.warning, thr.strong, thr.critical, thr.extreme)) {
            drawLine(markerColor.copy(alpha = 0.6f), Offset(x(t), 0f), Offset(x(t), size.height), strokeWidth = 2f)
        }
    }
}

@Composable
private fun TripCard(trip: MonitorEngine.TripInfo?, now: Long) {
    DashCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (trip != null) {
                    Text("● Fahrt wird aufgezeichnet", fontWeight = FontWeight.SemiBold, color = Color(0xFFD32F2F))
                    Text("seit ${clockTime(trip.startMs)} · ${CoolantMonitor.duration(now - trip.startMs)}", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Keine Fahrtaufzeichnung", fontWeight = FontWeight.SemiBold)
                    Text("Warnungen sind trotzdem aktiv.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (trip != null) OutlinedButton(onClick = MonitorEngine::stopTrip) { Text("Fahrt beenden") }
            else Button(onClick = MonitorEngine::startTrip) { Text("Fahrt starten") }
        }
    }
}

@Composable
fun TripSummaryCard(lines: List<String>, onDetails: () -> Unit, onClose: () -> Unit) {
    DashCard("Fahrtanalyse", MaterialTheme.colorScheme.secondaryContainer) {
        lines.take(9).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDetails) { Text("Details & Graph") }
            TextButton(onClick = onClose) { Text("Schließen") }
        }
    }
}

@Composable
private fun SignalsCard(st: MonitorEngine.UiState, now: Long, maxAge: Long) {
    DashCard("Motorwerte") {
        val list = listOf(Signal.RPM, Signal.SPEED, Signal.LOAD, Signal.IAT, Signal.MAP, Signal.THROTTLE, Signal.OIL_TEMP, Signal.EXHAUST_TEMP, Signal.ECU_VOLTAGE, Signal.BATTERY)
        for (sig in list) {
            val s = st.signals[sig]
            val fresh = s?.isFresh(now, maxAge) == true
            val (text, dim) = when {
                s?.supported == false -> "nicht verfügbar" to true
                fresh && s?.last != null -> "${formatSignal(sig, s.last.value)} ${sig.unit}" to false
                s?.last != null -> "keine aktuelle Messung" to true
                else -> "–" to true
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(
                    if (fresh) Color(0xFF22A06B) else if (s?.supported == false) Color.Gray else Color(0xFFFF9800)))
                Spacer(Modifier.width(8.dp))
                Text(sig.label, Modifier.weight(1f))
                Text(text, fontWeight = if (dim) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun StatusCard(conn: Conn, st: MonitorEngine.UiState) {
    DashCard("Verbindung") {
        val ready = conn as? Conn.Ready
        Dot(if (ready != null) Color(0xFF22A06B) else Color(0xFFD32F2F), if (ready != null) "Verbunden (${ready.name})" else "Nicht verbunden")
        Dot(if (ready != null) Color(0xFF22A06B) else Color.Gray, "Bus: " + (ready?.let { "${it.bus.label} · ${it.info["Protokoll"] ?: ""}" } ?: "–"))
        Dot(if (st.ecuResponding) Color(0xFF22A06B) else Color(0xFFD32F2F),
            if (st.ecuResponding) "Motorsteuergerät antwortet" else "Steuergerät: ${st.lastCoolantStatus?.label ?: "keine Antwort"}")
        Spacer(Modifier.height(6.dp))
        Text("Timeouts ${st.timeouts} · keine Antwort ${st.noData} · Prüfsummenfehler ${st.checksumErrors} · " +
                "ungültig ${st.invalid} · K-Line-Reinit ${st.kLineReinits}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Dot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EventRow(e: MonitorEvent, showDate: Boolean = false) {
    val col = when {
        e.kind == EventKind.LEVEL && e.level.atLeast(CoolantLevel.CRITICAL) -> Color(0xFFD32F2F)
        e.kind == EventKind.LEVEL && e.level.atLeast(CoolantLevel.WARNING) -> Color(0xFFFF9800)
        e.kind == EventKind.RAPID_RISE || e.kind == EventKind.SUSTAINED -> Color(0xFFF4511E)
        e.kind == EventKind.OBSERVATION -> Color(0xFF8B5CF6)
        e.kind == EventKind.DATA_QUALITY || e.kind == EventKind.CONNECTION -> Color(0xFF64748B)
        else -> MaterialTheme.colorScheme.primary
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(col))
        Spacer(Modifier.width(8.dp))
        Column {
            Text((if (showDate) dateTime(e.timeMs) else clockTime(e.timeMs)) + " · " + e.kind.label,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(e.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun EventList(events: List<MonitorEvent>) {
    LazyColumn { items(events) { EventRow(it) } }
}
