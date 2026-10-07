package de.flexy.caddylog.core

import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import kotlin.math.roundToInt

/** One row of the trip time series (one per sampler tick). NaN = no fresh value at that moment. */
class TripPoint(val t: Long, val values: DoubleArray, val coolantFresh: Boolean, val level: CoolantLevel) {
    operator fun get(s: Signal): Double = values[s.ordinal]
    fun valueOrNull(s: Signal): Double? = values[s.ordinal].takeIf { !it.isNaN() }
}

data class Rise(val fromTemp: Double, val toTemp: Double, val fromMs: Long, val toMs: Long)

data class TripSummary(
    val startMs: Long,
    val endMs: Long,
    val samples: Int,
    val coolantMax: Double?,
    val coolantMaxTimeMs: Long?,
    val rpmAtMax: Double?,
    val speedAtMax: Double?,
    val coolantAvg: Double?,
    val rpmMax: Double?,
    val speedMax: Double?,
    val warnings: Int,
    val criticals: Int,
    val observations: Int,
    /** threshold → milliseconds at or above it */
    val timeAboveMs: List<Pair<Double, Long>>,
    val fastestRise: Rise?,
    /** share of the trip with a fresh coolant reading (0..1) */
    val coolantCoverage: Double,
    val lines: List<String>,
) {
    val durationMs get() = endMs - startMs

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "start" to Instant.ofEpochMilli(startMs).toString(), "end" to Instant.ofEpochMilli(endMs).toString(),
        "duration_s" to durationMs / 1000, "samples" to samples,
        "coolant_max_c" to coolantMax, "coolant_max_time" to coolantMaxTimeMs?.let { Instant.ofEpochMilli(it).toString() },
        "rpm_at_max" to rpmAtMax, "speed_at_max_kmh" to speedAtMax, "coolant_avg_c" to coolantAvg,
        "rpm_max" to rpmMax, "speed_max_kmh" to speedMax, "warnings" to warnings, "critical_events" to criticals,
        "observations" to observations,
        "time_above_s" to timeAboveMs.associate { (thr, ms) -> CoolantMonitor.fmt(thr) to ms / 1000 },
        "fastest_rise" to fastestRise?.let { mapOf("from_c" to it.fromTemp, "to_c" to it.toTemp, "seconds" to (it.toMs - it.fromMs) / 1000) },
        "coolant_coverage" to coolantCoverage, "analysis" to lines,
    )
}

/** Collects the trip in memory (for the live graph and the analysis). Thread-safe. */
class TripRecorder(val startMs: Long) {
    private val points = ArrayList<TripPoint>()
    private val events = ArrayList<MonitorEvent>()
    var version = 0L; private set

    @Synchronized fun addPoint(p: TripPoint) { points += p; version++ }
    @Synchronized fun addEvent(e: MonitorEvent) { events += e; version++ }
    @Synchronized fun points(): List<TripPoint> = ArrayList(points)
    @Synchronized fun events(): List<MonitorEvent> = ArrayList(events)
    @Synchronized fun pointCount() = points.size

    fun summary(endMs: Long, thresholds: CoolantThresholds): TripSummary =
        TripAnalysis.summarize(startMs, endMs, points(), events(), thresholds)
}

/** Derives the trip summary ONLY from measured data. No diagnoses. */
object TripAnalysis {
    private const val MAX_GAP_MS = 5_000L

    fun summarize(startMs: Long, endMs: Long, points: List<TripPoint>, events: List<MonitorEvent>, thr: CoolantThresholds): TripSummary {
        val cool = points.filter { it.coolantFresh && !it[Signal.COOLANT].isNaN() }
        val maxP = cool.maxByOrNull { it[Signal.COOLANT] }

        // time weighted average + time above thresholds (gaps > 5 s are not counted)
        val thresholds = listOf(thr.warning, thr.strong, thr.critical, thr.extreme)
        val above = LongArray(thresholds.size)
        var weighted = 0.0; var total = 0L; var freshMs = 0L
        for (i in 0 until cool.size - 1) {
            val dt = (cool[i + 1].t - cool[i].t).coerceAtMost(MAX_GAP_MS)
            if (dt <= 0) continue
            val v = cool[i][Signal.COOLANT]
            weighted += v * dt; total += dt; freshMs += dt
            thresholds.forEachIndexed { k, th -> if (v >= th) above[k] += dt }
        }
        val avg = if (total > 0) weighted / total else cool.map { it[Signal.COOLANT] }.average().takeIf { !it.isNaN() }

        // fastest rise within 120 s
        var best: Rise? = null
        var j = 0
        for (i in cool.indices) {
            while (cool[i].t - cool[j].t > 120_000) j++
            var minK = j
            for (k in j..i) if (cool[k][Signal.COOLANT] < cool[minK][Signal.COOLANT]) minK = k
            val d = cool[i][Signal.COOLANT] - cool[minK][Signal.COOLANT]
            if (d > 0 && (best == null || d > best.toTemp - best.fromTemp))
                best = Rise(cool[minK][Signal.COOLANT], cool[i][Signal.COOLANT], cool[minK].t, cool[i].t)
        }

        // escalations into warning / critical
        var warnings = 0; var criticals = 0
        var prev = CoolantLevel.NORMAL
        for (e in events.filter { it.kind == EventKind.LEVEL }) {
            if (e.level.ordinal > prev.ordinal) {
                if (e.level.atLeast(CoolantLevel.CRITICAL) && !prev.atLeast(CoolantLevel.CRITICAL)) criticals++
                else if (e.level.atLeast(CoolantLevel.WARNING) && !prev.atLeast(CoolantLevel.WARNING)) warnings++
            }
            prev = e.level
        }
        val observations = events.count { it.kind == EventKind.OBSERVATION || it.kind == EventKind.RAPID_RISE || it.kind == EventKind.SUSTAINED }

        val rpmMax = points.mapNotNull { it.valueOrNull(Signal.RPM) }.maxOrNull()
        val speedMax = points.mapNotNull { it.valueOrNull(Signal.SPEED) }.maxOrNull()
        val coverage = if (endMs > startMs) (freshMs.toDouble() / (endMs - startMs)).coerceIn(0.0, 1.0) else 0.0

        val f = { v: Double -> CoolantMonitor.fmt(v) }
        val lines = mutableListOf<String>()
        lines += "Dauer: ${CoolantMonitor.duration(endMs - startMs)}"
        if (maxP != null) lines += "Max. Kühlmitteltemperatur: ${f(maxP[Signal.COOLANT])} °C"
        if (avg != null) lines += "Durchschnitt: ${f(avg)} °C"
        lines += "Warnungen: $warnings"
        lines += "Kritische Ereignisse: $criticals"
        thresholds.forEachIndexed { k, th ->
            if (above[k] > 0) lines += "Kühlmitteltemperatur überschritt ${f(th)} °C für ${CoolantMonitor.duration(above[k])}."
        }
        if (maxP != null) {
            val ctx = listOfNotNull(
                maxP.valueOrNull(Signal.RPM)?.let { "${it.roundToInt()} 1/min" },
                maxP.valueOrNull(Signal.SPEED)?.let { "${it.roundToInt()} km/h" },
            )
            lines += "Höchste Temperatur: ${f(maxP[Signal.COOLANT])} °C" + (if (ctx.isNotEmpty()) " bei ${ctx.joinToString(" und ")}." else ".")
        }
        if (best != null && best.toTemp - best.fromTemp >= 5.0) {
            lines += "Die Temperatur stieg innerhalb von ${((best.toMs - best.fromMs) / 1000.0).roundToInt()} Sekunden von ${f(best.fromTemp)} °C auf ${f(best.toTemp)} °C."
        }
        if (rpmMax != null) lines += "Max. Drehzahl: ${rpmMax.roundToInt()} 1/min"
        if (speedMax != null) lines += "Max. Geschwindigkeit: ${speedMax.roundToInt()} km/h"
        if (observations > 0) lines += "Hinweise/Beobachtungen: $observations (siehe Ereignisprotokoll)"
        lines += "Kühlmittel-Messabdeckung: ${(coverage * 100).roundToInt()} % der Fahrtzeit" +
                if (coverage < 0.8) " – Lücken in der Messung, Werte mit Vorsicht betrachten." else ""

        return TripSummary(
            startMs, endMs, points.size, maxP?.get(Signal.COOLANT), maxP?.t, maxP?.valueOrNull(Signal.RPM), maxP?.valueOrNull(Signal.SPEED),
            avg, rpmMax, speedMax, warnings, criticals, observations,
            thresholds.zip(above.toList()), best, coverage, lines
        )
    }
}

/**
 * Writes a trip into its session folder:
 *  trip_series.csv   one row per sampler tick (wide format, easy to plot)
 *  trip_events.csv   the event log
 *  trip_summary.json analysis (rewritten periodically and at the end)
 */
class TripWriter(private val dir: File) {
    private val series: BufferedWriter
    private val events: BufferedWriter
    private var lastFlush = 0L

    init {
        dir.mkdirs()
        val sf = File(dir, SERIES); val ef = File(dir, EVENTS)
        val newS = !sf.exists(); val newE = !ef.exists()
        series = java.io.FileWriter(sf, true).buffered()
        events = java.io.FileWriter(ef, true).buffered()
        if (newS) series.write(SERIES_HEADER + "\n")
        if (newE) events.write(EVENTS_HEADER + "\n")
    }

    @Synchronized fun write(p: TripPoint) {
        val cols = mutableListOf(p.t.toString(), Instant.ofEpochMilli(p.t).toString())
        for (s in Signal.entries) cols += p.values[s.ordinal].let { if (it.isNaN()) "" else (Math.round(it * 1000) / 1000.0).toString() }
        cols += if (p.coolantFresh) "1" else "0"
        cols += p.level.name
        series.write(cols.joinToString(",")); series.write("\n")
        maybeFlush(p.t)
    }

    @Synchronized fun write(e: MonitorEvent) {
        val c = e.context
        events.write(listOf(e.timeMs.toString(), Instant.ofEpochMilli(e.timeMs).toString(), e.kind.name, e.level.name,
            e.coolant?.toString() ?: "", c.rpm?.toString() ?: "", c.speed?.toString() ?: "", c.load?.toString() ?: "",
            Csv.field(e.text)).joinToString(","))
        events.write("\n")
        events.flush() // events are rare and important
    }

    @Synchronized fun writeSummary(s: TripSummary) {
        File(dir, SUMMARY).writeText(Json.write(s.toMap()))
        File(dir, SUMMARY_TXT).writeText(s.lines.joinToString("\n"))
    }

    private fun maybeFlush(now: Long) {
        if (now - lastFlush > 3000) { series.flush(); lastFlush = now }
    }

    @Synchronized fun close() { runCatching { series.close() }; runCatching { events.close() } }

    companion object {
        const val SERIES = "trip_series.csv"
        const val EVENTS = "trip_events.csv"
        const val SUMMARY = "trip_summary.json"
        const val SUMMARY_TXT = "trip_summary.txt"
        val SERIES_HEADER = (listOf("t_ms", "time") + Signal.entries.map { it.csv } + listOf("coolant_fresh", "level")).joinToString(",")
        const val EVENTS_HEADER = "t_ms,time,kind,level,coolant_c,rpm,speed_kmh,load_pct,text"

        fun isTrip(dir: File) = File(dir, SERIES).exists()

        /** Reads a stored trip back (for the analysis screen / replay graph). */
        fun load(dir: File): Pair<List<TripPoint>, List<MonitorEvent>> {
            val pts = mutableListOf<TripPoint>()
            val sf = File(dir, SERIES)
            if (sf.exists()) {
                val lines = sf.readLines()
                val header = lines.firstOrNull()?.split(",") ?: emptyList()
                val idx = Signal.entries.map { header.indexOf(it.csv) }
                val iFresh = header.indexOf("coolant_fresh"); val iLevel = header.indexOf("level")
                for (l in lines.drop(1)) {
                    val c = l.split(",")
                    val t = c.getOrNull(0)?.toLongOrNull() ?: continue
                    val vals = DoubleArray(Signal.entries.size) { k -> idx[k].takeIf { it >= 0 }?.let { c.getOrNull(it)?.toDoubleOrNull() } ?: Double.NaN }
                    val lvl = c.getOrNull(iLevel)?.let { n -> CoolantLevel.entries.firstOrNull { it.name == n } } ?: CoolantLevel.UNKNOWN
                    pts += TripPoint(t, vals, c.getOrNull(iFresh) == "1", lvl)
                }
            }
            val evs = mutableListOf<MonitorEvent>()
            val ef = File(dir, EVENTS)
            if (ef.exists()) {
                for (l in ef.readLines().drop(1)) {
                    val c = Csv.split(l)
                    if (c.size < 9) continue
                    val kind = EventKind.entries.firstOrNull { it.name == c[2] } ?: EventKind.INFO
                    val lvl = CoolantLevel.entries.firstOrNull { it.name == c[3] } ?: CoolantLevel.UNKNOWN
                    evs += MonitorEvent(c[0].toLongOrNull() ?: 0, kind, lvl, c[8], c[4].toDoubleOrNull(),
                        DriveContext(c[5].toDoubleOrNull(), c[6].toDoubleOrNull(), c[7].toDoubleOrNull()))
                }
            }
            return pts to evs
        }
    }
}

object Csv {
    fun field(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\"" else s

    fun split(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
