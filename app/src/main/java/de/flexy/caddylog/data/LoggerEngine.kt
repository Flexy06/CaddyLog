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
import de.flexy.caddylog.core.Bus
import de.flexy.caddylog.core.Elm327
import de.flexy.caddylog.core.Obd
import de.flexy.caddylog.core.RawLog
import de.flexy.caddylog.core.Reading
import de.flexy.caddylog.core.SessionWriter
import de.flexy.caddylog.core.TripWriter
import de.flexy.caddylog.core.Vw
import de.flexy.caddylog.monitor.MonitorEngine
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * App-wide singleton: adapter connection (BLE → ELM327), exclusive access to the adapter (mutex),
 * logging sessions (raw.log / live.csv / scans) and the diagnostic tasks (scans, console).
 * Live monitoring itself lives in [MonitorEngine].
 */
@SuppressLint("MissingPermission")
object LoggerEngine {

    sealed interface Conn {
        data object Off : Conn
        data class Connecting(val name: String) : Conn
        data class Ready(val name: String, val address: String, val info: Map<String, String>, val bus: Bus) : Conn
        data class Reconnecting(val name: String, val attempt: Int, val reason: String) : Conn
        data class Failed(val message: String) : Conn
    }

    data class FoundDevice(val name: String, val address: String, val rssi: Int, val likelyObd: Boolean)
    data class SessionInfo(val dir: File, val sizeBytes: Long, val files: Int, val isTrip: Boolean, val summary: List<String>)

    val conn = MutableStateFlow<Conn>(Conn.Off)
    val devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val bleScanning = MutableStateFlow(false)
    /** name of the running diagnostic task (scan / console) – while set, the adapter is busy */
    val activity = MutableStateFlow<String?>(null)
    val progress = MutableStateFlow<List<String>>(emptyList())
    val latest = MutableStateFlow<Map<String, Reading>>(sortedMapOf())
    val sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val currentSession = MutableStateFlow<String?>(null)
    val console = MutableStateFlow<List<String>>(emptyList())
    val supported = MutableStateFlow<Map<Int, Set<Int>>>(emptyMap())

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val elmMutex = Mutex()
    private var link: BleElmLink? = null
    private var elm: Elm327? = null
    @Volatile private var session: SessionWriter? = null
    private var job: Job? = null
    private var reconnectJob: Job? = null
    private var lastDevice: FoundDevice? = null
    @Volatile private var userDisconnect = false

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val rawLog = RawLog { dir, text ->
        session?.log(dir, text)
        console.update { (it + "$dir $text").takeLast(400) }
    }

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
        Settings.init(app)
        refreshSessions()
    }

    private val sessionsRoot get() = File(app.filesDir, "sessions")

    fun isReady() = conn.value is Conn.Ready && link?.isConnected == true

    /** Exclusive adapter access. Returns null if not connected. */
    suspend fun <T> withElm(block: suspend (Elm327) -> T): T? {
        val e = elm ?: return null
        if (link?.isConnected != true) return null
        return elmMutex.withLock { block(e) }
    }

    fun addProgress(text: String) {
        val line = "${LocalDateTime.now().format(timeFmt)}  $text"
        progress.update { (it + line).takeLast(300) }
        session?.note(text)
    }

    fun note(text: String) { session?.note(text) }

    /** Writes readings into live.csv of the current session and updates the "all values" view. */
    fun logReadings(rs: List<Reading>) {
        if (rs.isEmpty()) return
        rs.forEach { session?.write(it) }
        latest.update { old -> java.util.TreeMap(old).apply { rs.forEach { put(it.channel, it) } } }
    }

    private fun onReading(r: Reading) = logReadings(listOf(r))

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
        userDisconnect = false
        lastDevice = device
        job = scope.launch {
            conn.value = Conn.Connecting(device.name)
            activity.value = "Verbinde mit ${device.name} …"
            try {
                connectInternal(device, newSession = true)
                LoggingService.start(app)
                MonitorEngine.start()
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

    /** BLE connect + ELM init. Throws on failure. */
    private suspend fun connectInternal(device: FoundDevice, newSession: Boolean) {
        val bt = bluetooth() ?: error("Bluetooth nicht verfügbar")
        val l = BleElmLink(app, bt.getRemoteDevice(device.address)) { scope.launch { onLinkLost() } }
        l.connect()
        link = l
        if (newSession || session == null) newSessionInternal(if (Settings.current.autoTrip) "fahrt" else "verbindung")
        val e = Elm327(l, rawLog)
        addProgress("BLE verbunden (${l.description}), initialisiere ELM327 …")
        val info = elmMutex.withLock { e.initialize(Settings.current.preferredProtocol) }
        elm = e
        e.protocolNumber?.let { p -> if (p != Settings.current.preferredProtocol) Settings.update { it.copy(preferredProtocol = p) } }
        val full = linkedMapOf("Gerät" to "${device.name} (${device.address})", "BLE" to l.description) + info
        session?.writeJson("meta.json", mapOf(
            "app" to "CaddyLog 0.2", "fahrzeug" to "VW Caddy Maxi 2.0 EcoFuel (CNG), 2010",
            "android" to "${Build.MANUFACTURER} ${Build.MODEL} / SDK ${Build.VERSION.SDK_INT}",
            "adapter" to full, "start" to java.time.Instant.now().toString()
        ))
        conn.value = Conn.Ready(device.name, device.address, full, e.bus)
        addProgress("Bereit. Protokoll: ${info["Protokoll"]} (${e.bus.label})")
    }

    private fun onLinkLost() {
        val c = conn.value
        if (c !is Conn.Ready || userDisconnect) return
        addProgress("Verbindung zum Adapter verloren")
        job?.cancel()
        elm = null; link = null
        session?.flush()
        MonitorEngine.onConnectionLost()
        val dev = lastDevice
        if (dev != null && Settings.current.autoReconnect) {
            startReconnect(dev, "Verbindung verloren")
        } else {
            conn.value = Conn.Failed("Verbindung zum Adapter verloren (Zündung aus?)")
            MonitorEngine.stop()
            LoggingService.stop(app)
        }
    }

    /** Tries to re-establish the connection for ~5 minutes; the trip continues afterwards. */
    private fun startReconnect(dev: FoundDevice, reason: String) {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            for (attempt in 1..60) {
                if (userDisconnect) return@launch
                conn.value = Conn.Reconnecting(dev.name, attempt, reason)
                delay(if (attempt == 1) 1000 else 5000)
                try {
                    connectInternal(dev, newSession = false)
                    addProgress("Verbindung wiederhergestellt (Versuch $attempt)")
                    MonitorEngine.onReconnected()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    link?.close(); link = null; elm = null
                    addProgress("Wiederverbinden fehlgeschlagen ($attempt): ${e.message}")
                }
            }
            conn.value = Conn.Failed("Adapter nicht mehr erreichbar")
            MonitorEngine.stop()
            LoggingService.stop(app)
        }
    }

    fun disconnect() {
        userDisconnect = true
        reconnectJob?.cancel()
        job?.cancel()
        MonitorEngine.stop()
        scope.launch {
            job?.join()
            delay(300) // let the monitor finish the trip summary
            link?.close(); link = null; elm = null
            session?.close(); session = null; currentSession.value = null
            conn.value = Conn.Off
            LoggingService.stop(app)
            refreshSessions()
        }
    }

    // ------------------------------------------------------------------ sessions

    private fun newSessionInternal(label: String): File {
        session?.close()
        val s = SessionWriter(SessionWriter.newDir(sessionsRoot, label))
        session = s
        currentSession.value = s.dir.name
        refreshSessions()
        return s.dir
    }

    /**
     * Starts a new session folder (used by the monitor when a trip starts). If the current session is still
     * empty apart from the connection log, it is reused.
     */
    fun startNewSession(label: String): File? {
        if (!::app.isInitialized) return null
        val cur = session
        if (cur != null && cur.readingCount == 0L && cur.dir.name.endsWith(label) && !TripWriter.isTrip(cur.dir)) return cur.dir
        val dir = newSessionInternal(label)
        (conn.value as? Conn.Ready)?.let { session?.writeJson("meta.json", mapOf("adapter" to it.info, "start" to java.time.Instant.now().toString())) }
        return dir
    }

    fun refreshSessions() {
        if (!::app.isInitialized) return
        val dirs = sessionsRoot.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
        sessions.value = dirs.map { d ->
            val files = d.listFiles()?.toList() ?: emptyList()
            val summary = File(d, "trip_summary.txt").takeIf { it.exists() }?.readLines()?.take(5) ?: emptyList()
            SessionInfo(d, files.sumOf { it.length() }, files.size, TripWriter.isTrip(d), summary)
        }
    }

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

    // ------------------------------------------------------------------ diagnostic tasks (hold the adapter)

    private fun launchTask(name: String, block: suspend CoroutineScope.(Elm327) -> Unit) {
        if (elm == null) { addProgress("Nicht verbunden"); return }
        if (job?.isActive == true) { addProgress("Es läuft bereits: ${activity.value}"); return }
        job = scope.launch {
            activity.value = name
            addProgress("▶ $name")
            try {
                elmMutex.withLock {
                    val e = elm ?: error("Nicht verbunden")
                    try {
                        block(e)
                    } catch (c: CancellationException) {
                        withContext(NonCancellable) {
                            // re-sync the adapter after an interrupted command
                            delay(400); e.resetState()
                            if (link?.isConnected == true) runCatching { e.cmd("ATI") }
                        }
                        throw c
                    }
                }
                addProgress("✔ $name fertig")
            } catch (c: CancellationException) {
                addProgress("■ $name abgebrochen")
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

    fun cancelTask() { if (activity.value != null) job?.cancel() }

    fun runObdScan() = launchTask("OBD2-Komplettscan") { e ->
        val obd = Obd(e)
        val report = obd.fullScan(::addProgress, ::onReading)
        session?.writeJson("scan_obd_${stamp()}.json", report)
        supported.value = obd.supported(0x01)
    }

    fun runVwScan(allAddresses: Boolean, groups: IntRange) {
        if (elm?.bus == Bus.KLINE) {
            addProgress("VW-TP2.0-Scan übersprungen: Das Fahrzeug kommuniziert über K-Line (ISO 14230-4). TP2.0 ist nur über CAN möglich.")
            return
        }
        launchTask("VW-Steuergeräte-Scan") { e ->
            val modules = if (allAddresses) (0x01..0x7F).toList() else Vw.MODULE_NAMES.keys.sorted()
            val report = Vw(e, ::addProgress).scanAll(modules, groups, ::addProgress, ::onReading)
            session?.writeJson("scan_vw_${stamp()}.json", report)
            @Suppress("UNCHECKED_CAST")
            val found = (report["modules"] as List<Map<String, Any?>>).joinToString { "${it["address"]} ${it["name"]}" }
            addProgress("Gefundene Steuergeräte: ${found.ifEmpty { "keine" }}")
        }
    }

    fun readDtcs() = launchTask("Fehlerspeicher lesen") { e ->
        val obd = Obd(e)
        for ((mode, name) in listOf(0x03 to "gespeichert", 0x07 to "temporär", 0x0A to "permanent")) {
            val d = obd.readDtcs(mode)
            addProgress("Fehler ($name): " + if (d.isEmpty()) "keine Antwort" else d.entries.joinToString { (ecu, l) -> "%03X: %s".format(ecu, l.ifEmpty { listOf("keine") }.joinToString()) })
        }
    }

    fun consoleSend(command: String) {
        de.flexy.caddylog.core.ReadOnlyGuard.check(command)?.let { reason ->
            console.update { (it + "! $command abgelehnt: $reason").takeLast(400) }
            return
        }
        consoleSendChecked(command)
    }

    private fun consoleSendChecked(command: String) = launchTask("Konsole: $command") { e ->
        e.cmd(command.trim(), 8000)
        e.resetState() // manual commands may change adapter config → re-apply before next automatic use
    }

    fun clearProgress() { progress.value = emptyList() }
    fun clearConsole() { console.value = emptyList() }

    private fun stamp() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))
}
