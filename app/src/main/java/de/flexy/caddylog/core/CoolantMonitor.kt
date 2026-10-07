package de.flexy.caddylog.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Diagnostic warning thresholds (configurable). Exceeding them is reported as an observation –
 * it does NOT mean an engine fault has been diagnosed.
 */
data class CoolantThresholds(
    val elevated: Double = 105.0,
    val warning: Double = 110.0,
    val strong: Double = 115.0,
    val critical: Double = 120.0,
    val extreme: Double = 125.0,
    /** a level is only left when the temperature is this far below its threshold */
    val hysteresis: Double = 5.0,
    /** ... and has stayed there for this long (prevents flapping) */
    val deEscalateDwellMs: Long = 10_000,
    /** rapid rise: slope over the last 30 s ≥ this (°C/min) ... */
    val riseRatePerMin: Double = 6.0,
    /** ... or ≥ riseDelta °C above the minimum of the last riseWindowMs */
    val riseDelta: Double = 10.0,
    val riseWindowMs: Long = 60_000,
    /** rapid rise is only reported above this temperature (cold engine warms up fast, that's normal) */
    val riseMinTemp: Double = 95.0,
    /** a level ≥ warning lasting this long is reported as "sustained" */
    val sustainedMs: Long = 120_000,
    /** without a new valid coolant reading for this long the value is shown as "no current measurement" */
    val staleAfterMs: Long = 5_000,
) {
    fun isValid() = elevated < warning && warning < strong && strong < critical && critical < extreme &&
        hysteresis in 0.0..20.0 && riseRatePerMin > 0 && riseDelta > 0

    fun thresholdOf(level: CoolantLevel): Double? = when (level) {
        CoolantLevel.ELEVATED -> elevated
        CoolantLevel.WARNING -> warning
        CoolantLevel.STRONG_WARNING -> strong
        CoolantLevel.CRITICAL -> critical
        CoolantLevel.EXTREME -> extreme
        else -> null
    }
}

enum class CoolantLevel(val label: String) {
    UNKNOWN("Keine Messung"),
    NORMAL("Normal"),
    ELEVATED("Erhöht"),
    WARNING("Warnung"),
    STRONG_WARNING("Starke Warnung"),
    CRITICAL("Kritisch"),
    EXTREME("Extreme Überhitzung");

    fun atLeast(other: CoolantLevel) = ordinal >= other.ordinal
}

enum class EventKind(val label: String) {
    LEVEL("Status"), RAPID_RISE("Schneller Anstieg"), SUSTAINED("Anhaltend hoch"), OBSERVATION("Beobachtung"),
    DATA_QUALITY("Messung"), CONNECTION("Verbindung"), TRIP("Fahrt"), INFO("Info"),
}

data class DriveContext(val rpm: Double? = null, val speed: Double? = null, val load: Double? = null, val iat: Double? = null)

data class MonitorEvent(
    val timeMs: Long,
    val kind: EventKind,
    val level: CoolantLevel,
    val text: String,
    val coolant: Double? = null,
    val context: DriveContext = DriveContext(),
)

data class CoolantState(
    val level: CoolantLevel = CoolantLevel.UNKNOWN,
    /** last accepted value (may be old – check [fresh]) */
    val tempC: Double? = null,
    val lastValidMs: Long? = null,
    val fresh: Boolean = false,
    /** °C per minute over the last 30 s, null if not enough data */
    val ratePerMin: Double? = null,
    val rapidRise: Boolean = false,
    val tripMax: Double? = null,
    val tripMaxTimeMs: Long? = null,
    val tripMaxContext: DriveContext? = null,
    val levelSinceMs: Long? = null,
    /** since when the level is ≥ WARNING (null = currently below) */
    val aboveWarningSinceMs: Long? = null,
)

/**
 * Evaluates the coolant temperature: absolute thresholds with hysteresis + dwell, rate of rise, duration and
 * correlations with speed/load. Pure logic (no Android) – driven by timestamps so it is fully unit-testable.
 * All texts are worded as observations, never as diagnoses.
 */
class CoolantMonitor(thresholds: CoolantThresholds = CoolantThresholds()) {

    var thresholds: CoolantThresholds = thresholds
        set(value) { require(value.isValid()) { "Ungültige Schwellen" }; field = value }

    var state = CoolantState()
        private set

    private val history = ArrayDeque<Pair<Long, Double>>()
    private var pendingDownSince: Long? = null
    private var pendingSpike: Pair<Long, Double>? = null
    private var riseClearSince: Long? = null
    private var sustainedFired = false
    private var staleReported = false

    private class Condition(val holdMs: Long, val rearmMs: Long = 60_000) {
        private var since: Long? = null
        private var fired = false
        private var falseSince: Long? = null

        /** true exactly once when the condition has held for holdMs; re-armed after it was false for rearmMs */
        fun update(t: Long, cond: Boolean): Boolean {
            if (cond) {
                falseSince = null
                val s = since ?: t.also { since = it }
                if (!fired && t - s >= holdMs) { fired = true; return true }
            } else {
                since = null
                if (fired) {
                    val f = falseSince ?: t.also { falseSince = it }
                    if (t - f >= rearmMs) { fired = false; falseSince = null }
                }
            }
            return false
        }
    }

    private val obsStandstill = Condition(30_000)
    private val obsRisingAtSpeed = Condition(30_000)
    private val obsNoCooling = Condition(60_000)
    private val obsLowLoad = Condition(30_000)
    private val obsContinuousRise = Condition(0, rearmMs = 300_000)

    fun resetTrip() {
        state = state.copy(tripMax = null, tripMaxTimeMs = null, tripMaxContext = null)
    }

    /** Level for a temperature, taking hysteresis into account for levels at or below the current one. */
    fun levelFor(temp: Double, current: CoolantLevel): CoolantLevel {
        val t = thresholds
        for (l in listOf(CoolantLevel.EXTREME, CoolantLevel.CRITICAL, CoolantLevel.STRONG_WARNING, CoolantLevel.WARNING, CoolantLevel.ELEVATED)) {
            val thr = t.thresholdOf(l)!! - if (current.atLeast(l)) t.hysteresis else 0.0
            if (temp >= thr) return l
        }
        return CoolantLevel.NORMAL
    }

    /** A valid coolant reading arrived. */
    fun onSample(t: Long, temp: Double, ctx: DriveContext = DriveContext()): List<MonitorEvent> {
        val ev = mutableListOf<MonitorEvent>()

        // --- plausibility -------------------------------------------------------------------------------
        if (temp < -40.0 || temp > 160.0) {
            ev += MonitorEvent(t, EventKind.DATA_QUALITY, state.level, "Unplausibler Kühlmittelwert verworfen (${fmt(temp)} °C)", temp, ctx)
            return ev
        }
        val last = history.lastOrNull()
        if (last != null && t - last.first < 3000 && abs(temp - last.second) > 15.0) {
            val spike = pendingSpike
            if (spike == null || abs(temp - spike.second) > 5.0) {
                pendingSpike = t to temp
                ev += MonitorEvent(t, EventKind.DATA_QUALITY, state.level,
                    "Sprung ${fmt(last.second)} → ${fmt(temp)} °C – wird erst nach Bestätigung übernommen", temp, ctx)
                return ev
            }
        }
        pendingSpike = null

        history.addLast(t to temp)
        while (history.isNotEmpty() && t - history.first().first > 600_000) history.removeFirst()
        staleReported = false

        val rate = slopePerMin(t, 30_000)
        var s = state.copy(tempC = temp, lastValidMs = t, fresh = true, ratePerMin = rate)

        // --- trip maximum ---------------------------------------------------------------------------------
        if (s.tripMax == null || temp > s.tripMax!!) s = s.copy(tripMax = temp, tripMaxTimeMs = t, tripMaxContext = ctx)

        // --- level with hysteresis + dwell ----------------------------------------------------------------
        val current = s.level
        val target = levelFor(temp, if (current == CoolantLevel.UNKNOWN) CoolantLevel.NORMAL else current)
        val newLevel: CoolantLevel = when {
            current == CoolantLevel.UNKNOWN -> { pendingDownSince = null; target }
            target.ordinal > current.ordinal -> { pendingDownSince = null; target }
            target.ordinal < current.ordinal -> {
                val since = pendingDownSince ?: t.also { pendingDownSince = it }
                if (t - since >= thresholds.deEscalateDwellMs) { pendingDownSince = null; target } else current
            }
            else -> { pendingDownSince = null; current }
        }
        if (newLevel != current) {
            s = s.copy(level = newLevel, levelSinceMs = t)
            val dir = if (newLevel.ordinal > current.ordinal) "" else " (sinkend)"
            ev += MonitorEvent(t, EventKind.LEVEL, newLevel, "Kühlmittel ${fmt(temp)} °C – ${newLevel.label}$dir", temp, ctx)
        }

        // --- duration above warning -----------------------------------------------------------------------
        if (newLevel.atLeast(CoolantLevel.WARNING)) {
            val since = s.aboveWarningSinceMs ?: t
            s = s.copy(aboveWarningSinceMs = since)
            if (!sustainedFired && t - since >= thresholds.sustainedMs) {
                sustainedFired = true
                ev += MonitorEvent(t, EventKind.SUSTAINED, newLevel,
                    "Kühlmitteltemperatur seit ${duration(t - since)} im Warnbereich (aktuell ${fmt(temp)} °C, max. ${fmt(s.tripMax ?: temp)} °C)", temp, ctx)
            }
        } else {
            s = s.copy(aboveWarningSinceMs = null)
            sustainedFired = false
        }

        // --- rapid rise -----------------------------------------------------------------------------------
        val window = history.filter { t - it.first <= thresholds.riseWindowMs }
        val minPoint = window.minByOrNull { it.second }
        val delta = if (minPoint != null) temp - minPoint.second else 0.0
        val span = if (window.size >= 2) t - window.first().first else 0L
        val rapidNow = temp >= thresholds.riseMinTemp &&
            ((minPoint != null && delta >= thresholds.riseDelta) || (rate != null && span >= 20_000 && rate >= thresholds.riseRatePerMin))
        if (rapidNow) {
            riseClearSince = null
            if (!s.rapidRise) {
                s = s.copy(rapidRise = true)
                val detail = if (minPoint != null && delta >= 3.0)
                    "+${fmt(delta)} °C innerhalb von ${((t - minPoint.first) / 1000.0).roundToInt()} s (${fmt(minPoint.second)} → ${fmt(temp)} °C)"
                else "${fmt(rate ?: 0.0)} °C/min"
                ev += MonitorEvent(t, EventKind.RAPID_RISE, newLevel, "Kühlmitteltemperatur steigt ungewöhnlich schnell: $detail", temp, ctx)
            }
        } else if (s.rapidRise) {
            if (rate == null || rate < thresholds.riseRatePerMin / 2) {
                val since = riseClearSince ?: t.also { riseClearSince = it }
                if (t - since >= 30_000) { s = s.copy(rapidRise = false); riseClearSince = null }
            } else riseClearSince = null
        }

        // --- correlations (observations only) -------------------------------------------------------------
        val tr = thresholds
        if (obsStandstill.update(t, newLevel.atLeast(CoolantLevel.ELEVATED) && ctx.speed != null && ctx.speed <= 3.0)) {
            ev += MonitorEvent(t, EventKind.OBSERVATION, newLevel,
                "Hohe Kühlmitteltemperatur bei Fahrzeugstillstand (${fmt(temp)} °C, ${fmt(ctx.speed ?: 0.0)} km/h${ctx.rpm?.let { ", ${it.roundToInt()} 1/min" } ?: ""})", temp, ctx)
        }
        if (obsRisingAtSpeed.update(t, ctx.speed != null && ctx.speed >= 60.0 && rate != null && rate >= 3.0 && temp >= tr.elevated - 10)) {
            ev += MonitorEvent(t, EventKind.OBSERVATION, newLevel,
                "Kühlmitteltemperatur steigt (${fmtSigned(rate ?: 0.0)} °C/min), obwohl das Fahrzeug mit ${ctx.speed?.roundToInt()} km/h fährt", temp, ctx)
        }
        if (obsNoCooling.update(t, newLevel.atLeast(CoolantLevel.WARNING) && rate != null && rate > -0.5)) {
            ev += MonitorEvent(t, EventKind.OBSERVATION, newLevel,
                "Kühlmitteltemperatur ${fmt(temp)} °C – keine erkennbare Abkühlung seit 60 s", temp, ctx)
        }
        if (obsLowLoad.update(t, newLevel.atLeast(CoolantLevel.WARNING) && ctx.load != null && ctx.load <= 30.0)) {
            ev += MonitorEvent(t, EventKind.OBSERVATION, newLevel,
                "Hohe Kühlmitteltemperatur bei geringer Motorlast (${ctx.load?.roundToInt()} %)", temp, ctx)
        }
        val fiveMinAgo = history.firstOrNull { t - it.first <= 300_000 }
        val longRise = fiveMinAgo != null && t - fiveMinAgo.first >= 270_000 && temp >= 100.0 &&
            temp - fiveMinAgo.second >= 8.0 && (rate ?: 0.0) >= 0.0 && (slopePerMin(t, 300_000) ?: 0.0) >= 1.5
        if (obsContinuousRise.update(t, longRise)) {
            ev += MonitorEvent(t, EventKind.OBSERVATION, newLevel,
                "Kühlmitteltemperatur steigt seit ${duration(t - fiveMinAgo!!.first)} kontinuierlich an (${fmt(fiveMinAgo.second)} → ${fmt(temp)} °C)", temp, ctx)
        }

        state = s
        return ev
    }

    /** Called every sampler tick (also when the coolant request failed) to detect stale data. */
    fun onTick(t: Long, lastStatus: ReadStatus?): List<MonitorEvent> {
        val lastValid = state.lastValidMs
        val stale = lastValid == null || t - lastValid > thresholds.staleAfterMs
        if (stale && state.fresh) state = state.copy(fresh = false)
        if (stale && lastValid != null && !staleReported) {
            staleReported = true
            val why = lastStatus?.takeIf { it != ReadStatus.OK }?.label?.let { " ($it)" } ?: ""
            return listOf(MonitorEvent(t, EventKind.DATA_QUALITY, state.level,
                "Keine aktuelle Kühlmittelmessung seit ${((t - lastValid) / 1000.0).roundToInt()} s$why", state.tempC))
        }
        return emptyList()
    }

    /** Linear regression slope in °C/min over the last windowMs (null if < 10 s of data). */
    fun slopePerMin(now: Long, windowMs: Long): Double? {
        val pts = history.filter { now - it.first <= windowMs }
        if (pts.size < 3 || pts.last().first - pts.first().first < 10_000) return null
        val t0 = pts.first().first
        val xs = pts.map { (it.first - t0) / 60_000.0 }
        val ys = pts.map { it.second }
        val mx = xs.average(); val my = ys.average()
        var num = 0.0; var den = 0.0
        for (i in xs.indices) { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) * (xs[i] - mx) }
        return if (den == 0.0) null else num / den
    }

    companion object {
        fun fmt(v: Double): String = if (abs(v - Math.round(v)) < 0.05) Math.round(v).toString() else String.format(java.util.Locale.GERMANY, "%.1f", v)
        fun fmtSigned(v: Double) = (if (v >= 0) "+" else "") + fmt(v)
        fun duration(ms: Long): String {
            val s = (ms / 1000).toInt()
            return when {
                s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
                s >= 60 -> "${s / 60} min ${s % 60} s"
                else -> "$s s"
            }
        }
    }
}
