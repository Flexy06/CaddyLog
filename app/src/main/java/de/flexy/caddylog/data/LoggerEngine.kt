package de.flexy.caddylog.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import de.flexy.caddylog.ble.BleElmLink
import de.flexy.caddylog.core.Elm327
import de.flexy.caddylog.core.LivePoller
import de.flexy.caddylog.core.Obd
import de.flexy.caddylog.core.RawLog
import de.flexy.caddylog.core.Reading
import de.flexy.caddylog.core.SessionWriter
import de.flexy.caddylog.core.Vw
import de.flexy.caddylog.service.LoggingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** App-wide singleton holding the adapter connection, the running task and the current logging session. */
@SuppressLint("MissingPermission")
object LoggerEngine {

    sealed interface Conn {
        data object Off : Conn
        data class Connecting(val name: String) : Conn
        data class Ready(val name: String, val address: String, val info: Map<String, String>) : Conn
        data class Failed(val message: String) : Conn
    }

    data class FoundDevice(val name: String, val address: String, val rssi: Int, val likelyObd: Boolean)
    data class LiveStats(val running: Boolean = false, val cycles: Long = 0, val lastCycleMs: Long = 0, val readings: Long = 0)
    data class SessionInfo(val dir: File, val sizeBytes: Long, val files: Int)

    val conn = MutableStateFlow<Conn>(Conn.Off)
    val devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val bleScanning = MutableStateFlow(false)
    val activity = MutableStateFlow<String?>(null)
    val progress = MutableStateFlow<List<String>>(emptyList())
    val latest = MutableStateFlow<Map<String, Reading>>(sortedMapOf())
    val live = MutableStateFlow(LiveStats())
    val sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val currentSession = MutableStateFlow<String?>(null)
    val console = MutableStateFlow<List<String>>(emptyList())
    val supported = MutableStateFlow<Map<Int, Set<Int>>>(emptyMap())

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var link: BleElmLink? = null
    private var elm: Elm327? = null
    @Volatile private var session: SessionWriter? = null
    private var job: Job? = null

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val rawLog = RawLog { dir, text ->
        session?.log(dir, text)
        console.update { (it + "$dir $text").takeLast(400) }
    }

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
        refreshSessions()
    }

    private val sessionsRoot get() = File(app.filesDir, "sessions")

    fun addProgress(text: String) {
        val line = "${LocalDateTime.now().format(timeFmt)}  $text"
        progress.update { (it + line).takeLast(300) }
        session?.note(text)
    }

    private fun onReading(r: Reading) {
        session?.write(r)
        latest.update { old -> java.util.TreeMap(old).apply { put(r.channel, r) } }
    }

    private fun onReadings(rs: List<Reading>) {
        rs.forEach { session?.write(it) }
        latest.update { old -> java.util.TreeMap(old).apply { rs.forEach { put(it.channel, it) } } }
    }

    // ------------------------------------------------------------------ BLE scan / connect

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val likely = Regex("(?i)vlink|v-link|icar|vgate|obd|elm|ios-v|android-v").containsMatchIn(name)
            val d = FoundDevice(name, result.device.address, result.rssi, likely)
            devices.update { list ->
                (list.filter { it.address != d.address } + d)
                    .sortedWith(compareByDescending<FoundDevice> { it.likelyObd }.thenByDescending { it.rssi })
            }
        }

        override fun onScanFailed(errorCode: Int) {
            bleScanning.value = false
            addProgress("BLE-Suche fehlgeschlagen (Code $errorCode)")
        }
    }

    private fun bluetooth() = app.getSystemService(BluetoothManager::class.java)?.adapter

    fun startBleScan() {
        val scanner = bluetooth()?.bluetoothLeScanner
        if (scanner == null) { conn.value = Conn.Failed("Bluetooth ist ausgeschaltet"); return }
        devices.value = emptyList()
        bleScanning.value = true
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        scope.launch { delay(12_000); stopBleScan() }
    }

    fun stopBleScan() {
        if (!bleScanning.value) return
        bleScanning.value = false
        runCatching { bluetooth()?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    fun connect(device: FoundDevice) {
        if (job?.isActive == true) return
        stopBleScan()
        job = scope.launch {
            conn.value = Conn.Connecting(device.name)
            activity.value = "Verbinde mit ${device.name} …"
            try {
                val bt = bluetooth() ?: error("Bluetooth nicht verfügbar")
                val l = BleElmLink(app, bt.getRemoteDevice(device.address)) { scope.launch { onLinkLost() } }
                l.connect()
                link = l
                newSessionInternal("fahrt")
                val e = Elm327(l, rawLog)
                elm = e
                addProgress("BLE verbunden (${l.description}), initialisiere ELM327 …")
                val info = e.initialize()
                val full = linkedMapOf("Gerät" to "${device.name} (${device.address})", "BLE" to l.description) + info
                session?.writeJson("meta.json", mapOf(
                    "app" to "CaddyLog 0.1", "fahrzeug" to "VW Caddy Maxi 2.0 EcoFuel (CNG), 2010",
                    "android" to "${Build.MANUFACTURER} ${Build.MODEL} / SDK ${Build.VERSION.SDK_INT}",
                    "adapter" to full, "start" to java.time.Instant.now().toString()
                ))
                conn.value = Conn.Ready(device.name, device.address, full)
                addProgress("Bereit. Protokoll: ${info["Protokoll"]}")
                LoggingService.start(app)
            } catch (e: CancellationException) {
                link?.close(); conn.value = Conn.Off; throw e
            } catch (e: Exception) {
                link?.close(); link = null; elm = null
                conn.value = Conn.Failed(e.message ?: e.toString())
                addProgress("Verbindung fehlgeschlagen: ${e.message}")
            } finally {
                activity.value = null
            }
        }
    }

    private fun onLinkLost() {
        if (conn.value !is Conn.Ready) return
        addProgress("Verbindung zum Adapter verloren")
        job?.cancel()
        conn.value = Conn.Failed("Verbindung zum Adapter verloren (Zündung aus?)")
        elm = null; link = null
        session?.flush()
        live.update { it.copy(running = false) }
        LoggingService.stop(app)
    }

    fun disconnect() {
        job?.cancel()
        scope.launch {
            job?.join()
            link?.close(); link = null; elm = null
            session?.close(); session = null; currentSession.value = null
            conn.value = Conn.Off
            live.update { it.copy(running = false) }
            LoggingService.stop(app)
            refreshSessions()
        }
    }

    // ------------------------------------------------------------------ sessions

    private fun newSessionInternal(label: String) {
        session?.close()
        val s = SessionWriter(SessionWriter.newDir(sessionsRoot, label))
        session = s
        currentSession.value = s.dir.name
        refreshSessions()
    }

    fun newSession() {
        if (job?.isActive == true) return
        newSessionInternal("fahrt")
        (conn.value as? Conn.Ready)?.let { session?.writeJson("meta.json", mapOf("adapter" to it.info, "start" to java.time.Instant.now().toString())) }
        latest.value = sortedMapOf()
        addProgress("Neue Session: ${currentSession.value}")
    }

    fun refreshSessions() {
        val dirs = sessionsRoot.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
        sessions.value = dirs.map { d ->
            val files = d.listFiles()?.toList() ?: emptyList()
            SessionInfo(d, files.sumOf { it.length() }, files.size)
        }
    }

    /** Creates a zip of the session in the cache and returns a content:// uri for sharing. */
    suspend fun exportZip(dir: File): Uri = withContext(Dispatchers.IO) {
        if (dir.name == currentSession.value) session?.flush()
        val out = File(app.cacheDir, "exports").apply { mkdirs() }
        val zip = File(out, "CaddyLog_${dir.name}.zip")
        SessionWriter.zip(dir, zip)
        FileProvider.getUriForFile(app, "${app.packageName}.files", zip)
    }

    suspend fun saveZipTo(dir: File, target: Uri) = withContext(Dispatchers.IO) {
        if (dir.name == currentSession.value) session?.flush()
        app.contentResolver.openOutputStream(target)?.use { SessionWriter.zip(dir, it) }
    }

    fun deleteSession(dir: File) {
        if (dir.name == currentSession.value) return
        dir.deleteRecursively()
        refreshSessions()
    }

    // ------------------------------------------------------------------ tasks

    private fun launchTask(name: String, block: suspend CoroutineScope.(Elm327) -> Unit) {
        val e = elm ?: run { addProgress("Nicht verbunden"); return }
        if (job?.isActive == true) { addProgress("Es läuft bereits: ${activity.value}"); return }
        job = scope.launch {
            activity.value = name
            addProgress("▶ $name")
            try {
                block(e)
                addProgress("✔ $name fertig")
            } catch (c: CancellationException) {
                addProgress("■ $name abgebrochen")
                withContext(NonCancellable) {
                    // re-sync the adapter after an interrupted command
                    delay(400); e.resetState()
                    if (link?.isConnected == true) runCatching { e.cmd("ATI") }
                }
                throw c
            } catch (ex: Exception) {
                addProgress("✖ Fehler: ${ex.message}")
            } finally {
                activity.value = null
                session?.flush()
                refreshSessions()
            }
        }
    }

    fun cancelTask() { job?.cancel() }

    fun runObdScan() = launchTask("OBD2-Komplettscan") { e ->
        val obd = Obd(e)
        val report = obd.fullScan(::addProgress, ::onReading)
        session?.writeJson("scan_obd_${stamp()}.json", report)
        supported.value = obd.supported(0x01)
    }

    fun runVwScan(allAddresses: Boolean, groups: IntRange) = launchTask("VW-Steuergeräte-Scan") { e ->
        val modules = if (allAddresses) (0x01..0x7F).toList() else Vw.MODULE_NAMES.keys.sorted()
        val report = Vw(e, ::addProgress).scanAll(modules, groups, ::addProgress, ::onReading)
        session?.writeJson("scan_vw_${stamp()}.json", report)
        @Suppress("UNCHECKED_CAST")
        val found = (report["modules"] as List<Map<String, Any?>>).joinToString { "${it["address"]} ${it["name"]}" }
        addProgress("Gefundene Steuergeräte: ${found.ifEmpty { "keine" }}")
    }

    fun startLive(allObd: Boolean, vwSpec: String, pauseMs: Long) = launchTask("Live-Logging") { e ->
        if (allObd && supported.value.isEmpty()) supported.value = Obd(e).supported(0x01)
        val pids = if (allObd) supported.value.values.flatten().distinct().sorted() else listOf(0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11)
        val groups = LivePoller.parseGroupSpec(vwSpec)
        val cfg = LivePoller.Config(pids, groups)
        session?.writeJson("live_config_${stamp()}.json", mapOf(
            "obd_pids" to pids.map { "%02X".format(it) },
            "vw_groups" to groups.map { "%02X:%d".format(it.first, it.second) }, "pause_ms" to pauseMs
        ))
        addProgress("Live: ${pids.size} OBD-PIDs, ${groups.size} VW-Messwertblöcke")
        val poller = LivePoller(e, ::addProgress)
        live.value = LiveStats(running = true)
        try {
            var errors = 0
            while (isActive) {
                val t0 = System.currentTimeMillis()
                try {
                    val rs = poller.cycle(cfg)
                    onReadings(rs)
                    errors = 0
                    live.update { it.copy(cycles = it.cycles + 1, lastCycleMs = System.currentTimeMillis() - t0, readings = it.readings + rs.size) }
                } catch (c: CancellationException) {
                    throw c
                } catch (ex: Exception) {
                    addProgress("Zyklusfehler: ${ex.message}")
                    if (link?.isConnected != true || ++errors > 10) throw ex
                    delay(500); e.resetState()
                }
                if (pauseMs > 0) delay(pauseMs)
            }
        } finally {
            live.update { it.copy(running = false) }
            withContext(NonCancellable) { runCatching { poller.stop() } }
        }
    }

    fun consoleSend(command: String) = launchTask("Konsole: $command") { e ->
        e.cmd(command.trim(), 8000)
        e.resetState() // manual commands may change adapter config → re-apply before next automatic use
    }

    fun clearProgress() { progress.value = emptyList() }
    fun clearConsole() { console.value = emptyList() }

    private fun stamp() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))
}
