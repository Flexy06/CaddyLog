package de.flexy.caddylog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.flexy.caddylog.alarm.AlarmOutputs
import de.flexy.caddylog.core.CoolantMonitor
import de.flexy.caddylog.core.SoundKind
import de.flexy.caddylog.data.AppSettings
import de.flexy.caddylog.data.Settings
import de.flexy.caddylog.monitor.MonitorEngine

@Composable
fun SettingsScreen() {
    val saved by Settings.flow.collectAsStateWithLifecycle()
    // editable copies as text
    // key without the values that change while driving (all-time max) – otherwise the form would reset while editing
    val key = saved.copy(allTimeMax = null, allTimeMaxMs = null, preferredProtocol = null)
    var f by remember(key) { mutableStateOf(SettingsForm.from(saved)) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("Einstellungen", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        Section("Warnschwellen Kühlmittel (°C)") {
            Text("Diagnostische Warnschwellen – ein Überschreiten bedeutet nicht automatisch einen Motorschaden.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            NumberField("Erhöhte Aufmerksamkeit", f.elevated) { f = f.copy(elevated = it) }
            NumberField("Warnung", f.warning) { f = f.copy(warning = it) }
            NumberField("Starke Warnung", f.strong) { f = f.copy(strong = it) }
            NumberField("Kritischer Alarm", f.critical) { f = f.copy(critical = it) }
            NumberField("Extreme Überhitzung", f.extreme) { f = f.copy(extreme = it) }
            NumberField("Hysterese beim Absinken (°C)", f.hysteresis) { f = f.copy(hysteresis = it) }
        }
        Section("Anstieg & Dauer") {
            NumberField("Schneller Anstieg ab (°C/min)", f.riseRate) { f = f.copy(riseRate = it) }
            NumberField("… oder Anstieg um (°C) innerhalb 60 s", f.riseDelta) { f = f.copy(riseDelta = it) }
            NumberField("„Anhaltend hoch“ nach (min) im Warnbereich", f.sustainedMin) { f = f.copy(sustainedMin = it) }
        }
        Section("Abtastrate") {
            Text("Kühlmittel wird in jedem Zyklus abgefragt, die übrigen Werte abwechselnd. K-Line schafft nur eine Abfrage " +
                    "nach der anderen – ist die Rate zu hoch, reduziert die App automatisch.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (hz in listOf(1.0, 2.0, 5.0)) {
                    FilterChip(selected = f.sampleHz == hz, onClick = { f = f.copy(sampleHz = hz) }, label = { Text(hzText(hz)) })
                }
            }
        }
        Section("Alarm") {
            SwitchRow("Alarmton (Alarm-Lautstärke)", f.sound) { f = f.copy(sound = it) }
            SwitchRow("Vibration", f.vibrate) { f = f.copy(vibrate = it) }
            NumberField("Kritischen Alarm wiederholen nach (s)", f.repeatSec) { f = f.copy(repeatSec = it) }
            NumberField("… nach Bestätigung frühestens nach (s)", f.ackRepeatSec) { f = f.copy(ackRepeatSec = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { AlarmOutputs.play(SoundKind.ALARM) }) { Text("Alarmton testen") }
                OutlinedButton(onClick = { AlarmOutputs.play(SoundKind.CHIME) }) { Text("Hinweiston testen") }
            }
        }
        Section("Aufzeichnung & Verbindung") {
            SwitchRow("Fahrt nach dem Verbinden automatisch aufzeichnen", f.autoTrip) { f = f.copy(autoTrip = it) }
            SwitchRow("Bei Verbindungsabbruch automatisch neu verbinden", f.autoReconnect) { f = f.copy(autoReconnect = it) }
            Text("Gemerktes Protokoll: " + (saved.preferredProtocol?.let { "$it (wird zuerst probiert)" } ?: "automatische Suche"),
                style = MaterialTheme.typography.bodySmall)
            Text("Bisheriges Maximum: " + (saved.allTimeMax?.let { "${CoolantMonitor.fmt(it)} °C" + (saved.allTimeMaxMs?.let { t -> " am ${dateTime(t)}" } ?: "") } ?: "–"),
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { Settings.update { it.copy(preferredProtocol = null) } }) { Text("Protokoll vergessen") }
                TextButton(onClick = { Settings.update { it.copy(allTimeMax = null, allTimeMaxMs = null) }; MonitorEngine.applySettings() }) { Text("Maximum zurücksetzen") }
            }
        }

        message?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val n = f.toSettings(saved)
                if (n == null || !Settings.update { n }) {
                    message = "Ungültige Werte: Schwellen müssen aufsteigend sein (Erhöht < Warnung < Stark < Kritisch < Extrem), " +
                            "Wiederholung ≥ 10 s, nach Bestätigung ≥ Wiederholung."
                } else {
                    message = null
                    MonitorEngine.applySettings()
                }
            }) { Text("Speichern") }
            OutlinedButton(onClick = { f = SettingsForm.from(AppSettings().copy(preferredProtocol = saved.preferredProtocol)); message = null }) { Text("Standardwerte") }
        }
    }
}

private data class SettingsForm(
    val elevated: String, val warning: String, val strong: String, val critical: String, val extreme: String,
    val hysteresis: String, val riseRate: String, val riseDelta: String, val sustainedMin: String,
    val sampleHz: Double, val sound: Boolean, val vibrate: Boolean, val repeatSec: String, val ackRepeatSec: String,
    val autoTrip: Boolean, val autoReconnect: Boolean,
) {
    fun toSettings(base: AppSettings): AppSettings? {
        fun d(s: String) = s.replace(',', '.').trim().toDoubleOrNull()
        return base.copy(
            elevated = d(elevated) ?: return null, warning = d(warning) ?: return null, strong = d(strong) ?: return null,
            critical = d(critical) ?: return null, extreme = d(extreme) ?: return null, hysteresis = d(hysteresis) ?: return null,
            riseRatePerMin = d(riseRate) ?: return null, riseDelta = d(riseDelta) ?: return null,
            sustainedMin = d(sustainedMin) ?: return null, sampleHz = sampleHz, soundEnabled = sound, vibrate = vibrate,
            repeatSec = d(repeatSec)?.toInt() ?: return null, ackRepeatSec = d(ackRepeatSec)?.toInt() ?: return null,
            autoTrip = autoTrip, autoReconnect = autoReconnect,
        )
    }

    companion object {
        private fun n(v: Double) = CoolantMonitor.fmt(v).replace(',', '.')
        fun from(s: AppSettings) = SettingsForm(
            n(s.elevated), n(s.warning), n(s.strong), n(s.critical), n(s.extreme), n(s.hysteresis),
            n(s.riseRatePerMin), n(s.riseDelta), n(s.sustainedMin), s.sampleHz, s.soundEnabled, s.vibrate,
            s.repeatSec.toString(), s.ackRepeatSec.toString(), s.autoTrip, s.autoReconnect,
        )
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
