package de.flexy.caddylog.monitor

import de.flexy.caddylog.alarm.AlarmOutputs
import de.flexy.caddylog.core.AlarmEngine
import de.flexy.caddylog.core.Bus
import de.flexy.caddylog.core.CoolantMonitor
import de.flexy.caddylog.core.CoolantState
import de.flexy.caddylog.core.DriveContext
import de.flexy.caddylog.core.EventKind
import de.flexy.caddylog.core.LiveSampler
import de.flexy.caddylog.core.MonitorEvent
import de.flexy.caddylog.core.Obd
import de.flexy.caddylog.core.ObdPids
import de.flexy.caddylog.core.PidResult
import de.flexy.caddylog.core.ReadStatus
import de.flexy.caddylog.core.Signal
import de.flexy.caddylog.core.SignalState
import de.flexy.caddylog.core.SignalValue
import de.flexy.caddylog.core.TripPoint
import de.flexy.caddylog.core.TripRecorder
import de.flexy.caddylog.core.TripSummary
import de.flexy.caddylog.core.TripWriter
import de.flexy.caddylog.core.WarningDialog
import de.flexy.caddylog.core.toSignalValue
import de.flexy.caddylog.data.LoggerEngine
import de.flexy.caddylog.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Live monitoring pipeline (runs while connected, independent of the UI):
 *
 *   OBD/KWP (LoggerEngine.withElm) → PID decoder (Obd.readPid) → VehicleData (SignalState)
 *     → CoolantMonitor → AlarmEngine → AlarmOutputs (sound, notification) + dialog state for the UI
 *     → TripRecorder/TripWriter (trip_series.csv, trip_events.csv, trip_summary.json)
 */
object MonitorEngine {

    data class TripInfo(val startMs: Long, val dirName: String)

    data class UiState(
        val running: Boolean = false,
        /** a scan holds the adapter → no live values */
        val paused: Boolean = false,
        val nowMs: Long = 0,
        val coolant: CoolantState = CoolantState(),
        val signals: Map<Signal, SignalState> = Signal.entries.associateWith { SignalState(it) },
        val targetHz: Double = 1.0,
        val effectiveHz: Double = 1.0,
        val lastCoolantStatus: ReadStatus? = null,
        val ecuResponding: Boolean = false,
        val timeouts: Int = 0,
        val noData: Int = 0,
        val checksumErrors: Int = 0,
        val invalid: Int = 0,
        val kLineReinits: Int = 0,
        val trip: TripInfo? = null,
        val allTimeMax: Double? = null,
        val allTimeMaxMs: Long? = null,
    )

    val ui = MutableStateFlow(UiState())
    /** events of the current monitoring session (newest last) */
    val events = MutableStateFlow<List<MonitorEvent>>(emptyList())
    /** critical warning dialog – non-null while it must be shown (not acknowledged) */
    val dialog = MutableStateFlow<WarningDialog?>(null)
    /** bumps (max. 1×/s) when new graph data is available */
    val graphVersion = MutableStateFlow(0L)
    /** summary of the trip that just ended (shown once on the dashboard) */
    val lastTrip = MutableStateFlow<Pair<File, TripSummary>?>(null)

    /** in-memory series of the whole monitoring session for the live graph */
    @Volatile var liveBuffer = TripRecorder(System.currentTimeMillis()); private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var loopJob: Job? = null
    private var watchdogJob: Job? = null

    private val monitor = CoolantMonitor()
    private val alarm = AlarmEngine()
    private var sampler: LiveSampler? = null
    private var tripRecorder: TripRecorder? = null
    private var tripWriter: TripWriter? = null
    private var tripDir: File? = null
    private var lastSummaryWrite = 0L
    private var lastGraphBump = 0L
    private var lastReinitMs = 0L
    private var lastCoolantStatus: ReadStatus? = null
    private var supportedPids: Set<Int> = emptySet()

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (loopJob?.isActive == true) return
        val s = Settings.current
        monitor.thresholds = s.thresholds()
        alarm.config = s.alarmConfig()
        liveBuffer = TripRecorder(System.currentTimeMillis())
        ui.update { it.copy(running = true, allTimeMax = s.allTimeMax, allTimeMaxMs = s.allTimeMaxMs, targetHz = s.sampleHz) }
        val smp = LiveSampler(
            readPid = { pid -> LoggerEngine.withElm { Obd(it).readPid(pid) } ?: PidResult(pid, ReadStatus.LINK_LOST) },
            readVoltage = { LoggerEngine.withElm { e -> Regex("""\d+(\.\d+)?""").find(e.voltage())?.value?.toDoubleOrNull() } },
            sleep = { delay(it) },
        )
        sampler = smp
        loopJob = scope.launch {
            detectSupported()
            if (Settings.current.autoTrip && tripRecorder == null) startTrip()
            while (isActive) {
                if (!LoggerEngine.isReady()) { delay(500); continue }
                val tick = runCatching { smp.tick(samplerConfig()) }.getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    delay(500); null
                } ?: continue
                lock.withLock { process(tick) }
            }
        }
        // watchdog: detects stale data even when the sampler is blocked (scan running, adapter hanging)
        watchdogJob = scope.launch {
            while (isActive) {
                delay(1000)
                lock.withLock {
                    val now = System.currentTimeMillis()
                    val ev = monitor.onTick(now, lastCoolantStatus)
                    if (ev.isNotEmpty()) handleEvents(ev)
                    applyAlarm(alarm.update(now, monitor.state, ev))
                    ui.update { it.copy(nowMs = now, coolant = monitor.state, paused = LoggerEngine.activity.value != null) }
                }
            }
        }
    }

    /** Stops monitoring (user disconnect). Ends the trip. */
    fun stop() {
        loopJob?.cancel(); watchdogJob?.cancel()
        loopJob = null; watchdogJob = null
        scope.launch {
            lock.withLock {
                stopTripLocked()
                alarm.reset(); dialog.value = null
                AlarmOutputs.stop()
                ui.update { it.copy(running = false) }
            }
        }
    }

    /** BLE/adapter lost: keep the trip (reconnect may follow), warn the driver. */
    fun onConnectionLost() {
        scope.launch {
            lock.withLock {
                val now = System.currentTimeMillis()
                handleEvents(listOf(MonitorEvent(now, EventKind.CONNECTION, monitor.state.level, "Verbindung zum OBD-Adapter verloren", monitor.state.tempC)))
                AlarmOutputs.handle(alarm.onConnectionLost(monitor.state), Settings.current.vibrate)
                ui.update { it.copy(ecuResponding = false) }
            }
        }
    }

    fun onReconnected() {
        scope.launch {
            lock.withLock {
                handleEvents(listOf(MonitorEvent(System.currentTimeMillis(), EventKind.CONNECTION, monitor.state.level, "Verbindung wiederhergestellt", monitor.state.tempC)))
            }
        }
    }

    fun acknowledge() {
        scope.launch {
            lock.withLock {
                alarm.acknowledge(System.currentTimeMillis(), monitor.state.tempC)
                dialog.value = null
                AlarmOutputs.stop()
            }
        }
    }

    fun applySettings() {
        scope.launch {
            lock.withLock {
                val s = Settings.current
                monitor.thresholds = s.thresholds()
                alarm.config = s.alarmConfig()
                ui.update { it.copy(targetHz = s.sampleHz) }
            }
        }
    }

    // ------------------------------------------------------------------ trips

    fun startTrip() { scope.launch { lock.withLock { startTripLocked() } } }
    fun stopTrip() { scope.launch { lock.withLock { stopTripLocked() } } }

    private fun startTripLocked() {
        if (tripRecorder != null) return
        val now = System.currentTimeMillis()
        val dir = LoggerEngine.startNewSession("fahrt") ?: return
        tripDir = dir
        tripWriter = TripWriter(dir)
        tripRecorder = TripRecorder(now)
        monitor.resetTrip()
        lastSummaryWrite = now
        ui.update { it.copy(trip = TripInfo(now, dir.name)) }
        handleEvents(listOf(MonitorEvent(now, EventKind.TRIP, monitor.state.level, "Fahrt gestartet", monitor.state.tempC)))
    }

    private fun stopTripLocked() {
        val rec = tripRecorder ?: return
        val now = System.currentTimeMillis()
        handleEvents(listOf(MonitorEvent(now, EventKind.TRIP, monitor.state.level, "Fahrt beendet", monitor.state.tempC)))
        val summary = rec.summary(now, monitor.thresholds)
        tripWriter?.writeSummary(summary)
        tripWriter?.close()
        val dir = tripDir
        tripRecorder = null; tripWriter = null; tripDir = null
        ui.update { it.copy(trip = null) }
        if (dir != null && rec.pointCount() > 0) lastTrip.value = dir to summary
        LoggerEngine.refreshSessions()
    }

    fun tripPoints() = tripRecorder?.points()

    // ------------------------------------------------------------------ processing

    private suspend fun detectSupported() {
        val sup = LoggerEngine.withElm { Obd(it).supported(0x01) } ?: emptyMap()
        supportedPids = sup.values.flatten().toSet()
        if (sup.isNotEmpty()) LoggerEngine.supported.value = sup
        if (supportedPids.isNotEmpty()) {
            LoggerEngine.note("Unterstützte Mode-01-PIDs: " + supportedPids.filter { !ObdPids.isBitmapPid(it) }.sorted()
                .joinToString(" ") { "%02X".format(it) } + " – alle werden aufgezeichnet")
        }
        // values that never change while driving: read once per connection, not in every polling round
        for (pid in supportedPids.filter { it in ObdPids.STATIC_PIDS }.sorted()) {
            val r = LoggerEngine.withElm { Obd(it).readPid(pid) } ?: break
            if (r.status == ReadStatus.OK) LoggerEngine.logReadings(r.readings)
        }
        ui.update { st ->
            st.copy(signals = st.signals.mapValues { (sig, ss) ->
                if (sig.pid == null || supportedPids.isEmpty()) ss else ss.copy(supported = sig.pid in supportedPids || sig == Signal.COOLANT)
            })
        }
    }

    private fun samplerConfig(): LiveSampler.Config {
        val sup = supportedPids
        val priority = LiveSampler.DEFAULT_PRIORITY.filter { sup.isEmpty() || it in sup } +
            LiveSampler.OPTIONAL_PRIORITY.filter { it in sup }
        // everything else the ECU supports is polled round-robin – so every available sensor ends up in live.csv
        val other = sup.filter { it != 0x05 && it !in priority && !ObdPids.isBitmapPid(it) && it !in ObdPids.STATIC_PIDS }.sorted()
        return LiveSampler.Config(targetHz = Settings.current.sampleHz, priorityPids = priority, otherPids = other)
    }

    private fun process(tick: LiveSampler.Tick) {
        val now = System.currentTimeMillis()
        val s0 = ui.value
        val signals = s0.signals.toMutableMap()
        var timeouts = s0.timeouts; var noData = s0.noData; var cs = s0.checksumErrors; var inv = s0.invalid; var reinits = s0.kLineReinits
        val ev = mutableListOf<MonitorEvent>()

        for (r in tick.results) {
            when (r.status) {
                ReadStatus.TIMEOUT -> timeouts++
                ReadStatus.NO_DATA -> noData++
                ReadStatus.CHECKSUM_ERROR -> cs++
                ReadStatus.INVALID -> inv++
                else -> Unit
            }
            if (r.busInit && tick.startMs > 0) {
                reinits++
                ev += MonitorEvent(now, EventKind.DATA_QUALITY, monitor.state.level, "K-Line wurde neu initialisiert (BUS INIT)", monitor.state.tempC)
            }
            if (r.status == ReadStatus.OK) LoggerEngine.logReadings(r.readings)
            val sig = Signal.forPid(r.pid) ?: continue
            val old = signals[sig] ?: SignalState(sig)
            val v = r.toSignalValue()
            signals[sig] = old.copy(
                last = v ?: old.last, lastStatus = r.status, lastAttemptMs = r.timeMs,
                supported = when (r.status) { ReadStatus.OK -> true; ReadStatus.NOT_SUPPORTED -> false; else -> old.supported },
            )
        }
        tick.voltage?.let { signals[Signal.BATTERY] = SignalState(Signal.BATTERY, SignalValue(it, now), ReadStatus.OK, now, true) }

        // context for correlations: only fresh values
        val maxAge = maxOf(5_000L, (3000 / tick.effectiveHz).toLong())
        fun fresh(sig: Signal) = signals[sig]?.takeIf { it.isFresh(now, maxAge) }?.last?.value
        val ctx = DriveContext(rpm = fresh(Signal.RPM), speed = fresh(Signal.SPEED), load = fresh(Signal.LOAD), iat = fresh(Signal.IAT))

        // coolant
        val c = tick.coolant
        if (c.status == ReadStatus.OK) {
            c.value?.let { ev += monitor.onSample(c.timeMs, it, ctx) }
        }
        if (c.status != lastCoolantStatus) {
            if (lastCoolantStatus == ReadStatus.OK || (lastCoolantStatus == null && c.status != ReadStatus.OK)) {
                if (c.status != ReadStatus.OK) ev += MonitorEvent(now, EventKind.DATA_QUALITY, monitor.state.level, "Kühlmittel-Abfrage: ${c.status.label}", monitor.state.tempC)
            } else if (c.status == ReadStatus.OK && lastCoolantStatus != null) {
                ev += MonitorEvent(now, EventKind.DATA_QUALITY, monitor.state.level, "Kühlmittelmessung wieder verfügbar", monitor.state.tempC)
            }
            lastCoolantStatus = c.status
        }
        ev += monitor.onTick(now, c.status)
        tick.events.forEach { ev += MonitorEvent(now, EventKind.INFO, monitor.state.level, it, monitor.state.tempC) }

        // K-Line recovery: several failed coolant requests in a row → force re-initialisation
        val smp = sampler
        if (smp != null && smp.coolantFailureCount >= 5 && c.status != ReadStatus.LINK_LOST && now - lastReinitMs > 15_000) {
            lastReinitMs = now
            reinits++
            ev += MonitorEvent(now, EventKind.DATA_QUALITY, monitor.state.level, "Steuergerät antwortet nicht – Bus wird neu initialisiert", monitor.state.tempC)
            scope.launch { LoggerEngine.withElm { if (it.bus == Bus.KLINE || it.bus == Bus.UNKNOWN) it.reinitBus() } }
        }

        handleEvents(ev)
        applyAlarm(alarm.update(now, monitor.state, ev))

        // all-time maximum
        var atMax = s0.allTimeMax; var atMaxMs = s0.allTimeMaxMs
        val t = monitor.state.tempC
        if (c.status == ReadStatus.OK && t != null && (atMax == null || t > atMax)) {
            atMax = t; atMaxMs = c.timeMs
            Settings.update { it.copy(allTimeMax = t, allTimeMaxMs = c.timeMs) }
        }

        // series point
        val vals = DoubleArray(Signal.entries.size) { Double.NaN }
        for (sig in Signal.entries) {
            val st = signals[sig] ?: continue
            if (st.isFresh(now, maxAge)) st.last?.let { vals[sig.ordinal] = it.value }
        }
        if (!monitor.state.fresh) vals[Signal.COOLANT.ordinal] = Double.NaN
        val point = TripPoint(now, vals, monitor.state.fresh, monitor.state.level)
        liveBuffer.addPoint(point)
        tripRecorder?.addPoint(point)
        tripWriter?.write(point)
        if (now - lastGraphBump >= 1000) { lastGraphBump = now; graphVersion.value = graphVersion.value + 1 }
        if (tripRecorder != null && now - lastSummaryWrite > 60_000) {
            lastSummaryWrite = now
            tripRecorder?.let { tripWriter?.writeSummary(it.summary(now, monitor.thresholds)) }
        }

        ui.value = s0.copy(
            nowMs = now, coolant = monitor.state, signals = signals, effectiveHz = tick.effectiveHz,
            targetHz = Settings.current.sampleHz, lastCoolantStatus = c.status, ecuResponding = c.status == ReadStatus.OK,
            timeouts = timeouts, noData = noData, checksumErrors = cs, invalid = inv, kLineReinits = reinits,
            allTimeMax = atMax, allTimeMaxMs = atMaxMs, paused = false,
        )
    }

    private fun handleEvents(ev: List<MonitorEvent>) {
        if (ev.isEmpty()) return
        for (e in ev) {
            liveBuffer.addEvent(e)
            tripRecorder?.addEvent(e)
            tripWriter?.write(e)
            LoggerEngine.note("[${e.kind.label}] ${e.text}")
        }
        events.update { (it + ev).takeLast(500) }
    }

    private fun applyAlarm(out: de.flexy.caddylog.core.AlarmOutput) {
        if (!out.isEmpty) AlarmOutputs.handle(out, Settings.current.vibrate)
        if (out.dismissDialog) AlarmOutputs.stop()
        dialog.value = alarm.activeDialog?.takeIf { !alarm.acknowledged }
    }
}
