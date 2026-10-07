package de.flexy.caddylog.core

/** Polls a configured set of OBD PIDs and VW measuring blocks in a loop (one call = one cycle). */
class LivePoller(private val elm: Elm327, private val log: (String) -> Unit = {}) {

    data class Config(
        val obdPids: List<Int>,
        /** (module address, group number) */
        val vwGroups: List<Pair<Int, Int>>,
        val includeVoltage: Boolean = true,
    )

    private val obd = Obd(elm)
    private var channel: Tp20? = null

    suspend fun cycle(cfg: Config): List<Reading> {
        val out = mutableListOf<Reading>()
        if (cfg.obdPids.isNotEmpty()) out += obd.readPids(cfg.obdPids)

        for ((module, groups) in cfg.vwGroups.groupBy({ it.first }, { it.second })) {
            var ch = channel
            if (ch == null || ch.module != module || !ch.isOpen) {
                ch?.close()
                ch = Tp20(elm, module, log)
                channel = ch
                if (!ch.open()) { log("SG %02X antwortet nicht (TP2.0)".format(module)); continue }
            }
            for (g in groups) {
                var r = ch.request(0x21, g, maxIdlePolls = 2)
                if (r == null && ch.open()) r = ch.request(0x21, g, maxIdlePolls = 2)
                if (r != null && r.isNotEmpty() && r[0] == 0x61) out += VwFormula.decodeGroup(module, g, r)
            }
        }

        if (cfg.includeVoltage) {
            val v = elm.voltage()
            val num = Regex("""(\d+(\.\d+)?)""").find(v)?.value?.toDoubleOrNull()
            out += Reading("ADAPTER.VBAT", "Bordnetzspannung (OBD-Buchse)", num, "V", null, v)
        }
        return out
    }

    suspend fun stop() {
        channel?.close(); channel = null
        runCatching { elm.ensureObd() }
    }

    companion object {
        /**
         * Parses e.g. "01:1-10, 01:115, 17:1,2,3" → module (hex) : groups (decimal, ranges allowed).
         * A bare number after a comma continues the previous module.
         */
        fun parseGroupSpec(spec: String): List<Pair<Int, Int>> {
            val out = mutableListOf<Pair<Int, Int>>()
            var module = -1
            for (part0 in spec.split(',', ';', '\n')) {
                var part = part0.trim()
                if (part.isEmpty()) continue
                if (':' in part) {
                    module = part.substringBefore(':').trim().toIntOrNull(16) ?: continue
                    part = part.substringAfter(':').trim()
                }
                if (module < 0) continue
                if ('-' in part) {
                    val a = part.substringBefore('-').trim().toIntOrNull() ?: continue
                    val b = part.substringAfter('-').trim().toIntOrNull() ?: continue
                    for (g in a..b) if (g in 0..255) out += module to g
                } else part.toIntOrNull()?.let { if (it in 0..255) out += module to it }
            }
            return out.distinct()
        }
    }
}
