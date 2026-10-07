package de.flexy.caddylog.core

/**
 * Schedules the live requests. Every tick:
 *   1. coolant temperature (always – most important signal)
 *   2. as many further PIDs as fit into the tick period (at least one), priority PIDs 3× as often as the rest
 *   3. adapter voltage (ATRV) every few seconds
 *
 * If the vehicle/adapter cannot keep up with the target rate (K-Line: one PID per request, ~50–150 ms each),
 * the rate is lowered step by step (5 → 2 → 1 → 0.5 Hz) and an event is emitted.
 * Clock and sleep are injected so the logic can be tested without real time.
 */
class LiveSampler(
    private val readPid: suspend (Int) -> PidResult,
    private val readVoltage: (suspend () -> Double?)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit,
) {
    data class Config(
        val targetHz: Double = 1.0,
        val priorityPids: List<Int> = DEFAULT_PRIORITY,
        val otherPids: List<Int> = emptyList(),
        val voltageEveryMs: Long = 5_000,
        /** consecutive overrunning ticks before the rate is lowered */
        val overrunTicks: Int = 5,
    )

    data class Tick(
        val startMs: Long,
        val coolant: PidResult,
        val results: List<PidResult>,
        val voltage: Double?,
        val durationMs: Long,
        val effectiveHz: Double,
        val events: List<String>,
    )

    var effectiveHz: Double = 1.0
        private set
    private var configuredHz = -1.0
    private var priIdx = 0
    private var otherIdx = 0
    private var slot = 0
    private var overruns = 0
    private var lastVoltageMs = 0L
    private var avgRequestMs = 100.0
    private var coolantFailures = 0

    companion object {
        val DEFAULT_PRIORITY = listOf(0x0C, 0x0D, 0x04, 0x0F, 0x0B, 0x11)
        val RATE_STEPS = listOf(5.0, 2.0, 1.0, 0.5)

        fun nextLower(hz: Double): Double? = RATE_STEPS.firstOrNull { it < hz - 1e-9 }
    }

    private suspend fun timed(pid: Int): PidResult {
        val t0 = clock()
        val r = readPid(pid).copy(timeMs = clock())
        val dt = (clock() - t0).toDouble()
        avgRequestMs = avgRequestMs * 0.8 + dt * 0.2
        return r
    }

    suspend fun tick(cfg: Config): Tick {
        if (cfg.targetHz != configuredHz) { configuredHz = cfg.targetHz; effectiveHz = cfg.targetHz; overruns = 0 }
        val events = mutableListOf<String>()
        val start = clock()
        val period = (1000.0 / effectiveHz).toLong()

        val coolant = timed(0x05)
        coolantFailures = if (coolant.status == ReadStatus.OK) 0 else coolantFailures + 1
        val results = mutableListOf(coolant)

        // secondary PIDs: always at least one, then as many as fit into the period
        var first = true
        val hasAny = cfg.priorityPids.isNotEmpty() || cfg.otherPids.isNotEmpty()
        while (hasAny && coolant.status != ReadStatus.LINK_LOST) {
            val elapsed = clock() - start
            if (!first && elapsed + avgRequestMs > period * 0.9) break
            val pid = nextSecondary(cfg) ?: break
            val r = timed(pid)
            results += r
            first = false
            if (r.status == ReadStatus.LINK_LOST) break
            if (results.size > 1 + cfg.priorityPids.size + cfg.otherPids.size) break
        }

        var voltage: Double? = null
        if (readVoltage != null && clock() - lastVoltageMs >= cfg.voltageEveryMs && coolant.status != ReadStatus.LINK_LOST) {
            lastVoltageMs = clock()
            val rv = readVoltage
            voltage = runCatching { rv() }.getOrNull()
        }

        // rate control: coolant + one secondary must fit into the period
        val minimalWork = clock() - start - (results.size - 2).coerceAtLeast(0) * avgRequestMs.toLong()
        if (minimalWork > period * 1.2) {
            overruns++
            if (overruns >= cfg.overrunTicks) {
                val lower = nextLower(effectiveHz)
                if (lower != null) {
                    events += "Abtastrate von ${fmtHz(effectiveHz)} auf ${fmtHz(lower)} reduziert – Fahrzeug/Adapter antwortet zu langsam (${minimalWork} ms pro Zyklus)"
                    effectiveHz = lower
                }
                overruns = 0
            }
        } else overruns = 0

        val duration = clock() - start
        val rest = (1000.0 / effectiveHz).toLong() - duration
        if (rest > 0) sleep(rest)
        return Tick(start, coolant, results, voltage, duration, effectiveHz, events)
    }

    /** consecutive failed coolant requests (used to trigger re-initialisation) */
    val coolantFailureCount get() = coolantFailures

    private fun nextSecondary(cfg: Config): Int? {
        val usePriority = cfg.otherPids.isEmpty() || (cfg.priorityPids.isNotEmpty() && slot % 4 != 3)
        slot++
        return if (usePriority && cfg.priorityPids.isNotEmpty()) {
            cfg.priorityPids[priIdx++ % cfg.priorityPids.size]
        } else if (cfg.otherPids.isNotEmpty()) {
            cfg.otherPids[otherIdx++ % cfg.otherPids.size]
        } else null
    }

    private fun fmtHz(hz: Double) = if (hz >= 1) "${hz.toInt()} Hz" else "$hz Hz"
}
