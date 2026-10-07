package de.flexy.caddylog.core

import kotlin.math.abs

/**
 * VAG KWP2000 measuring block formulas (each field = formula byte, a, b).
 * Public community knowledge, best effort – the raw triple is ALWAYS logged as well,
 * so values can be re-decoded later if a formula here turns out to be wrong.
 */
object VwFormula {
    data class Decoded(val value: Double?, val unit: String, val text: String? = null)

    fun decode(f: Int, a: Int, b: Int): Decoded {
        val ab = a * 256 + b
        return when (f) {
            0x01 -> Decoded(a * b / 5.0, "1/min")
            0x02 -> Decoded(a * 0.002 * b, "%")
            0x03 -> Decoded(a * 0.002 * b, "°")
            0x04 -> Decoded(abs(b - 127) * 0.01 * a, "°", if (b > 127) "v. OT" else "n. OT")
            0x05 -> Decoded(a * (b - 100) * 0.1, "°C")
            0x06 -> Decoded(0.001 * a * b, "V")
            0x07 -> Decoded(0.01 * a * b, "km/h")
            0x08 -> Decoded(0.1 * a * b, "")
            0x09 -> Decoded((b - 127) * 0.02 * a, "°")
            0x0A -> Decoded(b.toDouble(), "", if (b == 0) "KALT" else "WARM")
            0x0B -> Decoded(0.0001 * a * (b - 128) + 1, "")
            0x0C -> Decoded(0.001 * a * b, "Ohm")
            0x0D -> Decoded((b - 127) * 0.001 * a, "mm")
            0x0E -> Decoded(0.005 * a * b, "bar")
            0x0F -> Decoded(0.01 * a * b, "ms")
            0x10 -> Decoded(b.toDouble(), "", (0..7).joinToString("") { i ->
                if ((a shr (7 - i)) and 1 == 0) "-" else ((b shr (7 - i)) and 1).toString()
            })
            0x11 -> Decoded(null, "", "${a.toChar()}${b.toChar()}")
            0x12 -> Decoded(0.04 * a * b, "mbar")
            0x13 -> Decoded(a * b * 0.01, "l")
            0x14 -> Decoded(a * (b - 128) / 128.0, "%")
            0x15 -> Decoded(0.001 * a * b, "V")
            0x16 -> Decoded(0.001 * a * b, "ms")
            0x17 -> Decoded(b / 256.0 * a, "%")
            0x18 -> Decoded(0.001 * a * b, "A")
            0x19 -> Decoded(b * 1.421 + a / 182.0, "g/s")
            0x1A -> Decoded((b - a).toDouble(), "°C")
            0x1B -> Decoded(abs(b - 128) * 0.01 * a, "°", if (b > 128) "v. OT" else "n. OT")
            0x1C -> Decoded((b - a).toDouble(), "")
            0x1D -> Decoded(b.toDouble(), "", if (b < a) "1. Kennfeld" else "2. Kennfeld")
            0x1E -> Decoded(b / 12.0 * a, "° KW")
            0x1F -> Decoded(b / 2560.0 * a, "°C")
            0x20 -> Decoded((if (b > 128) b - 256 else b).toDouble(), "")
            0x21 -> Decoded(if (a == 0) 100.0 * b else 100.0 * b / a, "%")
            0x22 -> Decoded((b - 128) * 0.01 * a, "kW")
            0x23 -> Decoded(0.01 * a * b, "l/h")
            0x24 -> Decoded(a * 2560.0 + b * 10, "km")
            0x25 -> Decoded(b.toDouble(), "", "Status %02X %02X".format(a, b))
            0x26 -> Decoded((b - 128) * 0.001 * a, "° KW")
            0x27 -> Decoded(b / 256.0 * a, "mg/H")
            0x28 -> Decoded(b * 0.1 + 25.5 * a - 400, "A")
            0x29 -> Decoded((b + a * 255).toDouble(), "Ah")
            0x2A -> Decoded(b * 0.1 + 25.5 * a - 400, "kW")
            0x2B -> Decoded(b * 0.1 + 25.5 * a, "V")
            0x2C -> Decoded(a * 60.0 + b, "min", "%02d:%02d".format(a, b))
            0x2D -> Decoded(0.1 * a * b / 100, "")
            0x2E -> Decoded((a * b - 3200) * 0.0027, "° KW")
            0x2F -> Decoded((b - 128.0) * a, "ms")
            0x30 -> Decoded((b + a * 255).toDouble(), "")
            0x31 -> Decoded(b / 4.0 * a * 0.1, "mg/H")
            0x32 -> Decoded(if (a == 0) null else (b - 128) / (0.01 * a), "mbar")
            0x33 -> Decoded((b - 128) / 255.0 * a, "mg/H")
            0x34 -> Decoded(b * 0.02 * a - a, "Nm")
            0x35 -> Decoded((b - 128) * 1.4222 + 0.006 * a, "g/s")
            0x36 -> Decoded(ab.toDouble(), "")
            0x37 -> Decoded(a * b / 200.0, "s")
            0x38 -> Decoded(ab.toDouble(), "WSC")
            0x39 -> Decoded(ab + 65536.0, "WSC")
            0x3A -> Decoded(if (b > 128) 1.0225 * (b - 256) else 1.0225 * b, "%/s")
            0x3B -> Decoded(ab / 32768.0, "")
            0x3C -> Decoded(ab * 0.01, "s")
            0x3D -> Decoded(if (a == 0) null else (b - 128.0) / a, "")
            0x3E -> Decoded(0.256 * a * b, "s")
            0x3F -> Decoded(null, "", "${a.toChar()}${b.toChar()}")
            0x40 -> Decoded((a + b).toDouble(), "Ohm")
            0x41 -> Decoded(0.01 * a * (b - 127), "mm")
            0x42 -> Decoded(a * b / 511.12, "V")
            0x43 -> Decoded(640.0 * a + b * 2.5, "°")
            0x44 -> Decoded(ab / 7.365, "°/s")
            0x45 -> Decoded(ab * 0.3254, "bar")
            0x46 -> Decoded(ab * 0.192, "m/s²")
            else -> Decoded(null, "", "Formel %02X unbekannt".format(f))
        }
    }

    /** Splits a KWP 0x61 response (61 GG f a b f a b ...) into fields. */
    fun decodeGroup(module: Int, group: Int, resp: IntArray): List<Reading> {
        val out = mutableListOf<Reading>()
        val body = resp.copyOfRange(minOf(2, resp.size), resp.size)
        body.toList().chunked(3).forEachIndexed { idx, t ->
            if (t.size < 3) return@forEachIndexed
            val (f, a, b) = t
            val d = runCatching { decode(f, a, b) }.getOrElse { Decoded(null, "", "Fehler") }
            out += Reading(
                channel = "VW.%02X.G%03d.%d".format(module, group, idx + 1),
                name = "SG %02X Gruppe %03d Feld %d".format(module, group, idx + 1),
                value = d.value?.let { Math.round(it * 1000) / 1000.0 },
                unit = d.unit, text = d.text,
                raw = "%02X %02X %02X".format(f, a, b)
            )
        }
        return out
    }
}
