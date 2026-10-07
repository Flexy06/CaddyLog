package de.flexy.caddylog.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.flexy.caddylog.core.MonitorEvent
import de.flexy.caddylog.core.Signal
import de.flexy.caddylog.core.TripAnalysis
import de.flexy.caddylog.core.TripPoint
import de.flexy.caddylog.core.TripSummary
import de.flexy.caddylog.core.TripWriter
import de.flexy.caddylog.data.LoggerEngine
import de.flexy.caddylog.data.Settings
import de.flexy.caddylog.monitor.MonitorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val WINDOWS = listOf("5 min" to 5 * 60_000L, "15 min" to 15 * 60_000L, "1 h" to 60 * 60_000L, "Alles" to null)

// ---------------------------------------------------------------------------------------------- Live graph

@Composable
fun GraphScreen() {
    val version by MonitorEngine.graphVersion.collectAsStateWithLifecycle()
    val settings by Settings.flow.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(setOf(Signal.COOLANT, Signal.RPM, Signal.SPEED)) }
    var window by remember { mutableStateOf<Long?>(15 * 60_000L) }
    val points = remember(version) { MonitorEngine.liveBuffer.points() }
    val events = remember(version) { MonitorEngine.liveBuffer.events() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Live-Graph", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        WindowChips(window) { window = it }
        Spacer(Modifier.height(8.dp))
        TimeChart(points, visible, window, settings.thresholds(), events, height = 320.dp, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(8.dp))
        SignalToggles(visible, { s -> visible = if (s in visible) visible - s else visible + s })
        Legend(points.lastOrNull(), visible)
        Text("Kühlmittel nutzt die °C-Achse links (gestrichelt: Warn- und kritische Schwelle). Alle anderen Linien sind jeweils " +
                "auf ihren eigenen Bereich skaliert – die echten Werte stehen in der Zeile unter dem Graphen, wenn du hineintippst. " +
                "Lücken = keine aktuelle Messung.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WindowChips(window: Long?, onSelect: (Long?) -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, w) in WINDOWS) FilterChip(selected = window == w, onClick = { onSelect(w) }, label = { Text(label) })
    }
}

@Composable
private fun Legend(last: TripPoint?, visible: Set<Signal>) {
    if (last == null) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        for (s in visible.sortedBy { it.ordinal }) {
            Row {
                Text(s.label, Modifier.weight(1f), color = SIGNAL_COLORS[s] ?: MaterialTheme.colorScheme.onSurface)
                Text(last.valueOrNull(s)?.let { formatSignal(s, it) + " " + s.unit } ?: "keine aktuelle Messung")
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- Trips list

@Composable
fun TripsScreen(openDir: File?, onOpen: (File?) -> Unit) {
    if (openDir != null) { TripDetail(openDir) { onOpen(null) }; return }
    val sessions by LoggerEngine.sessions.collectAsStateWithLifecycle()
    val current by LoggerEngine.currentSession.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { LoggerEngine.refreshSessions() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("Fahrten & Sessions") {
                Text("Jede Fahrt enthält trip_series.csv (Messwerte je Abfrage), trip_events.csv (Ereignisprotokoll), " +
                        "trip_summary.json (Analyse), live.csv (alle Kanäle), raw.log (jede Anfrage/Antwort) und meta.json. " +
                        "„Teilen“ erzeugt eine ZIP-Datei.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (sessions.isEmpty()) item { Text("Noch keine Aufzeichnungen.", Modifier.padding(16.dp)) }
        items(sessions, key = { it.dir.name }) { s ->
            val active = s.dir.name == current
            ListItem(
                modifier = Modifier.clickable { onOpen(s.dir) },
                headlineContent = {
                    Text((if (s.isTrip) "Fahrt " else "Diagnose ") + prettyName(s.dir.name) + if (active) "  (aktiv)" else "",
                        fontWeight = FontWeight.SemiBold)
                },
                supportingContent = {
                    Text((s.summary.take(2).joinToString(" · ").ifEmpty { if (s.isTrip && active) "läuft gerade" else "${s.files} Dateien" }) +
                            " · " + formatSize(s.sizeBytes))
                },
            )
            HorizontalDivider()
        }
    }
}

/** "20261007_174210_fahrt" → "07.10.2026 17:42" */
fun prettyName(name: String): String {
    val m = Regex("""(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})""").find(name) ?: return name
    val (y, mo, d, h, mi) = m.destructured
    return "$d.$mo.$y $h:$mi"
}

// ---------------------------------------------------------------------------------------------- Trip detail

private class TripData(val points: List<TripPoint>, val events: List<MonitorEvent>, val summary: TripSummary?)

@Composable
private fun TripDetail(dir: File, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by LoggerEngine.currentSession.collectAsStateWithLifecycle()
    val settings by Settings.flow.collectAsStateWithLifecycle()
    var data by remember(dir) { mutableStateOf<TripData?>(null) }
    var visible by remember { mutableStateOf(setOf(Signal.COOLANT, Signal.RPM, Signal.SPEED)) }
    var confirmDelete by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch { LoggerEngine.saveZipTo(dir, uri) }
    }
    LaunchedEffect(dir) {
        data = withContext(Dispatchers.IO) {
            if (!TripWriter.isTrip(dir)) TripData(emptyList(), emptyList(), null) else {
                val (p, e) = TripWriter.load(dir)
                val start = p.firstOrNull()?.t ?: 0L
                val end = p.lastOrNull()?.t ?: start
                TripData(p, e, if (p.isEmpty()) null else TripAnalysis.summarize(start, end, p, e, settings.thresholds()))
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Text(prettyName(dir.name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        val d = data
        if (d == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        } else {
            d.summary?.let { s ->
                DashCard("Fahrtanalyse") {
                    s.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(6.dp))
                    Text("Nur aus gemessenen Daten abgeleitet – keine Fehlerdiagnose.", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (d.points.isNotEmpty()) {
                DashCard("Graph der gesamten Fahrt") {
                    TimeChart(d.points, visible, null, settings.thresholds(), d.events, height = 300.dp)
                }
                SignalToggles(visible, { s -> visible = if (s in visible) visible - s else visible + s })
            } else if (d.summary == null) {
                Section("Keine Fahrtdaten") { Text("Diese Session enthält Diagnose-/Scan-Daten (raw.log, scan_*.json, live.csv).") }
            }
            if (d.events.isNotEmpty()) {
                DashCard("Ereignisprotokoll (${d.events.size})") { d.events.forEach { EventRow(it) } }
            }
        }
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { ctx.startActivity(shareIntent(LoggerEngine.exportZip(dir))) } }) { Text("Teilen (ZIP)") }
            OutlinedButton(onClick = { saveLauncher.launch("CaddyLog_${dir.name}.zip") }) { Text("Speichern") }
            if (dir.name != current) TextButton(onClick = { confirmDelete = true }) { Text("Löschen") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Aufzeichnung löschen?") },
            text = { Text(prettyName(dir.name)) },
            confirmButton = { TextButton(onClick = { LoggerEngine.deleteSession(dir); confirmDelete = false; onBack() }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}
