package de.flexy.caddylog.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.flexy.caddylog.core.Reading
import de.flexy.caddylog.data.LoggerEngine
import de.flexy.caddylog.data.LoggerEngine.Conn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

// ---------------------------------------------------------------------------------------------- helpers

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun NotConnectedHint() {
    Section("Nicht verbunden") { Text("Verbinde dich zuerst im Tab „Verbindung“ mit dem iCar Pro.") }
}

@Composable
private fun RunningBar() {
    val activity by LoggerEngine.activity.collectAsStateWithLifecycle()
    activity?.let { a ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(a, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = LoggerEngine::cancelTask) { Text("Abbrechen") }
        }
    }
}

@Composable
private fun ProgressLog(modifier: Modifier = Modifier) {
    val lines by LoggerEngine.progress.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.animateScrollToItem(lines.size - 1) }
    LazyColumn(modifier.padding(horizontal = 16.dp), state = state) {
        items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}

fun formatValue(r: Reading): String {
    val v = r.value?.let {
        if (it == Math.floor(it) && kotlin.math.abs(it) < 1e9) it.toLong().toString() else String.format(Locale.GERMANY, "%.3f", it).trimEnd('0').trimEnd(',')
    }
    return listOfNotNull(v?.let { "$it ${r.unit}".trim() }, r.text?.takeIf { it.isNotBlank() }).joinToString("  ·  ").ifEmpty { "—" }
}

fun formatSize(b: Long) = when {
    b > 1_000_000 -> String.format(Locale.GERMANY, "%.1f MB", b / 1e6)
    b > 1_000 -> String.format(Locale.GERMANY, "%.0f kB", b / 1e3)
    else -> "$b B"
}

// ---------------------------------------------------------------------------------------------- Verbindung

@Composable
fun ConnectScreen() {
    val ctx = LocalContext.current
    val conn by LoggerEngine.conn.collectAsStateWithLifecycle()
    val devices by LoggerEngine.devices.collectAsStateWithLifecycle()
    val scanning by LoggerEngine.bleScanning.collectAsStateWithLifecycle()
    val session by LoggerEngine.currentSession.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(ctx.hasBlePermissions()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = ctx.hasBlePermissions()
        if (granted) LoggerEngine.startBleScan()
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            when (val c = conn) {
                is Conn.Ready -> Section("Verbunden mit ${c.name}") {
                    c.info.forEach { (k, v) ->
                        Text(k, style = MaterialTheme.typography.labelMedium)
                        Text(v.ifBlank { "—" }, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text("Aktuelle Session: ${session ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = LoggerEngine::disconnect) { Text("Trennen") }
                        OutlinedButton(onClick = LoggerEngine::newSession) { Text("Neue Session") }
                    }
                }
                is Conn.Connecting -> Section("Verbinde mit ${c.name} …") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else -> Section("iCar Pro verbinden") {
                    Text("Zündung einschalten (oder Motor starten), Adapter in die OBD-Buchse stecken, dann suchen. " +
                            "Der Adapter wird NICHT in den Android-Bluetooth-Einstellungen gekoppelt – nur hier in der App auswählen.")
                    if (c is Conn.Failed) {
                        Spacer(Modifier.height(6.dp))
                        Text(c.message, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(enabled = !scanning, onClick = {
                        if (granted) LoggerEngine.startBleScan() else permLauncher.launch(REQUIRED_PERMISSIONS)
                    }) { Text(if (scanning) "Suche läuft …" else "Adapter suchen") }
                }
            }
        }
        if (conn !is Conn.Ready && conn !is Conn.Connecting) {
            items(devices, key = { it.address }) { d ->
                ListItem(
                    modifier = Modifier.clickable { LoggerEngine.connect(d) },
                    headlineContent = { Text(d.name, fontWeight = if (d.likelyObd) FontWeight.Bold else FontWeight.Normal) },
                    supportingContent = { Text("${d.address} · ${d.rssi} dBm" + if (d.likelyObd) " · vermutlich OBD-Adapter" else "") },
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)); Text("Protokoll", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall) }
        item { ProgressLog(Modifier.heightIn(max = 400.dp)) }
    }
}

// ---------------------------------------------------------------------------------------------- Scan

@Composable
fun ScanScreen() {
    val conn by LoggerEngine.conn.collectAsStateWithLifecycle()
    val activity by LoggerEngine.activity.collectAsStateWithLifecycle()
    var allAddresses by rememberSaveable { mutableStateOf(false) }
    var allGroups by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        if (conn !is Conn.Ready) { NotConnectedHint(); return@Column }
        Section("OBD2-Komplettscan") {
            Text("Alle unterstützten Standard-PIDs, Fehlerspeicher (gespeichert/temporär/permanent), Freeze Frame, " +
                    "Fahrzeuginfo (VIN, Kalibrierung) und On-Board-Tests (Mode 06). Dauer ca. 1 min.")
            Spacer(Modifier.height(8.dp))
            Button(enabled = activity == null, onClick = LoggerEngine::runObdScan) { Text("OBD2-Scan starten") }
        }
        Section("VW-Steuergeräte (TP2.0 / KWP2000)") {
            Text("Sucht alle Steuergeräte und liest Identifikation, Fehlerspeicher und Messwertblöcke. " +
                    "Nur lesend. Am besten im Stand mit laufendem Motor.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(allAddresses, { allAddresses = it }); Text("Alle Adressen 01–7F (sonst ~45 bekannte)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(allGroups, { allGroups = it }); Text("Messwertblöcke 1–255 (sonst 1–50)")
            }
            Text(if (allGroups) "Dauer: kann pro Steuergerät mehrere Minuten dauern." else "Dauer: ca. 10–30 s pro gefundenem Steuergerät.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(enabled = activity == null, onClick = {
                LoggerEngine.runVwScan(allAddresses, if (allGroups) 1..255 else 1..50)
            }) { Text("VW-Scan starten") }
        }
        RunningBar()
        ProgressLog(Modifier.weight(1f).fillMaxWidth())
    }
}

// ---------------------------------------------------------------------------------------------- Live

@Composable
fun LiveScreen() {
    val conn by LoggerEngine.conn.collectAsStateWithLifecycle()
    val activity by LoggerEngine.activity.collectAsStateWithLifecycle()
    val stats by LoggerEngine.live.collectAsStateWithLifecycle()
    val latest by LoggerEngine.latest.collectAsStateWithLifecycle()
    var allObd by rememberSaveable { mutableStateOf(true) }
    var vwSpec by rememberSaveable { mutableStateOf("01:1-10") }
    var pause by rememberSaveable { mutableStateOf("0") }
    var filter by rememberSaveable { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            if (conn !is Conn.Ready) NotConnectedHint()
            else Section("Live-Logging") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(allObd, { allObd = it }, enabled = !stats.running)
                    Spacer(Modifier.width(8.dp))
                    Text(if (allObd) "Alle unterstützten OBD-PIDs" else "Nur Basis-PIDs (Last, Temp., Drehzahl, Speed, MAF …)")
                }
                OutlinedTextField(vwSpec, { vwSpec = it }, Modifier.fillMaxWidth(), enabled = !stats.running,
                    label = { Text("VW-Messwertblöcke (SG:Gruppen)") },
                    supportingText = { Text("z. B. 01:1-10, 01:115, 17:1 · leer = keine. SG hex, Gruppen dezimal.") })
                OutlinedTextField(pause, { pause = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), enabled = !stats.running,
                    label = { Text("Pause zwischen Zyklen (ms)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (stats.running) Button(onClick = LoggerEngine::cancelTask) { Text("Stopp") }
                    else Button(enabled = activity == null, onClick = {
                        LoggerEngine.startLive(allObd, vwSpec, pause.toLongOrNull() ?: 0)
                    }) { Text("Start") }
                    Spacer(Modifier.width(12.dp))
                    Text("${stats.cycles} Zyklen · ${stats.lastCycleMs} ms/Zyklus · ${stats.readings} Werte",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("Logging läuft mit ausgeschaltetem Bildschirm weiter. Bitte nicht während der Fahrt bedienen.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                label = { Text("Filter (${latest.size} Kanäle)") }, singleLine = true)
        }
        val shown = latest.values.filter {
            filter.isBlank() || it.name.contains(filter, true) || it.channel.contains(filter, true)
        }
        items(shown, key = { it.channel }) { r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.bodyMedium)
                    Text("${r.channel} · roh ${r.raw}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Text(formatValue(r), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
        }
    }
}

// ---------------------------------------------------------------------------------------------- Daten

@Composable
fun DataScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by LoggerEngine.sessions.collectAsStateWithLifecycle()
    val current by LoggerEngine.currentSession.collectAsStateWithLifecycle()
    var toSave by remember { mutableStateOf<File?>(null) }
    var toDelete by remember { mutableStateOf<File?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val dir = toSave
        if (uri != null && dir != null) scope.launch { LoggerEngine.saveZipTo(dir, uri) }
        toSave = null
    }
    LaunchedEffect(Unit) { LoggerEngine.refreshSessions() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("Sessions") {
                Text("Jede Verbindung erzeugt eine Session mit live.csv (alle Messwerte), scan_*.json (Scan-Ergebnisse), " +
                        "raw.log (jede Anfrage/Antwort) und meta.json. „Teilen“ erzeugt eine ZIP-Datei – die kannst du mir direkt schicken.")
            }
        }
        if (sessions.isEmpty()) item { Text("Noch keine Sessions.", Modifier.padding(16.dp)) }
        items(sessions, key = { it.dir.name }) { s ->
            ListItem(
                headlineContent = { Text(s.dir.name + if (s.dir.name == current) "  (aktiv)" else "") },
                supportingContent = { Text("${s.files} Dateien · ${formatSize(s.sizeBytes)}") },
                trailingContent = {
                    Row {
                        TextButton(onClick = {
                            scope.launch { ctx.startActivity(shareIntent(LoggerEngine.exportZip(s.dir))) }
                        }) { Text("Teilen") }
                        TextButton(onClick = { toSave = s.dir; saveLauncher.launch("CaddyLog_${s.dir.name}.zip") }) { Text("Speichern") }
                        if (s.dir.name != current) TextButton(onClick = { toDelete = s.dir }) { Text("Löschen") }
                    }
                }
            )
            HorizontalDivider()
        }
    }

    toDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Session löschen?") },
            text = { Text(d.name) },
            confirmButton = { TextButton(onClick = { LoggerEngine.deleteSession(d); toDelete = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Abbrechen") } }
        )
    }
}

// ---------------------------------------------------------------------------------------------- Konsole

@Composable
fun ConsoleScreen() {
    val conn by LoggerEngine.conn.collectAsStateWithLifecycle()
    val activity by LoggerEngine.activity.collectAsStateWithLifecycle()
    val lines by LoggerEngine.console.collectAsStateWithLifecycle()
    var cmd by rememberSaveable { mutableStateOf("") }
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }

    Column(Modifier.fillMaxSize()) {
        if (conn !is Conn.Ready) NotConnectedHint()
        Text("Alle Befehle/Antworten des Adapters (> gesendet, < empfangen). Eigene Befehle landen auch im raw.log.",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = state) {
            items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (q in listOf("ATRV", "ATDP", "0100", "03")) {
                AssistChip(onClick = { LoggerEngine.consoleSend(q) }, label = { Text(q) }, enabled = activity == null && conn is Conn.Ready)
            }
            AssistChip(onClick = LoggerEngine::clearConsole, label = { Text("Leeren") })
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(cmd, { cmd = it.uppercase() }, Modifier.weight(1f), singleLine = true,
                label = { Text("Befehl (z. B. ATI, 010C)") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send))
            Spacer(Modifier.width(8.dp))
            Button(enabled = cmd.isNotBlank() && activity == null && conn is Conn.Ready,
                onClick = { LoggerEngine.consoleSend(cmd); cmd = "" }) { Text("Senden") }
        }
    }
}
