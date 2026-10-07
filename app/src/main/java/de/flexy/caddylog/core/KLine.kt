package de.flexy.caddylog.core

/**
 * K-Line (ISO 14230-4 KWP2000 and ISO 9141-2) response frames as printed by the ELM327 with headers on (ATH1).
 *
 * KWP2000 example: `83 F1 11 41 05 7E 49`
 *   83 = format byte (0x80 = physical addressing, low 6 bits = data length 3)
 *   F1 = target (tester), 11 = source (engine ECU)
 *   41 05 7E = data (mode 01 response, PID 05, A=0x7E → 126-40 = 86 °C)
 *   49 = checksum (sum of all previous bytes modulo 256)
 *
 * ISO 9141-2 example: `48 6B 11 41 05 7E xx` (fixed 3-byte header, checksum at the end).
 */
data class KLineFrame(
    val header: IntArray,
    val target: Int,
    val source: Int,
    val data: IntArray,
    /** checksum byte as received, null if the line had none */
    val checksum: Int?,
    /** true = checksum correct, false = wrong, null = no checksum present */
    val checksumOk: Boolean?,
) {
    override fun toString() = "KLine src=%02X tgt=%02X data=%s cs=%s".format(source, target, data.toHex(), checksumOk)
}

object KLine {

    /** KWP2000 / ISO 9141 checksum: 8-bit sum of all bytes. */
    fun checksum(bytes: List<Int>): Int = bytes.sum() and 0xFF

    fun checksum(bytes: IntArray): Int = checksum(bytes.toList())

    data class ParseResult(val frames: List<KLineFrame>, val invalidLines: List<String>) {
        val checksumErrors get() = frames.count { it.checksumOk == false }
    }

    /** Parses all hex lines of an ELM327 response. Non-hex lines (e.g. "BUS INIT: ...OK") are ignored. */
    fun parse(text: String): ParseResult {
        val frames = mutableListOf<KLineFrame>()
        val invalid = mutableListOf<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val bytes = hexBytes(line) ?: continue
            val f = parseFrame(bytes)
            if (f == null) invalid += line else frames += f
        }
        return ParseResult(frames, invalid)
    }

    /** "83 F1 11 41 05 7E 49" or "83F111..." → bytes; null if the line is not pure hex. */
    fun hexBytes(line: String): List<Int>? {
        val compact = line.replace(" ", "")
        if (compact.length < 2 || compact.length % 2 != 0) return null
        if (!compact.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
        return compact.chunked(2).map { it.toInt(16) }
    }

    fun parseFrame(b: List<Int>): KLineFrame? {
        if (b.size < 4) return null
        val b0 = b[0]
        // ISO 9141-2 response header 48 6B xx
        if (b0 == 0x48 && b[1] == 0x6B) {
            val data = b.subList(3, b.size - 1)
            val cs = b.last()
            return KLineFrame(b.subList(0, 3).toIntArray(), b[1], b[2], data.toIntArray(), cs, checksum(b.subList(0, b.size - 1)) == cs)
        }
        // KWP2000: format byte with address mode bits 10 (physical) or 11 (functional)
        if (b0 and 0x80 == 0) return null
        var len = b0 and 0x3F
        var hdr = 3
        if (len == 0) { // length in separate byte
            if (b.size < 5) return null
            len = b[3]; hdr = 4
        }
        if (len == 0) return null
        return when (b.size) {
            hdr + len + 1 -> {
                val cs = b.last()
                KLineFrame(b.subList(0, hdr).toIntArray(), b[1], b[2], b.subList(hdr, hdr + len).toIntArray(), cs,
                    checksum(b.subList(0, b.size - 1)) == cs)
            }
            hdr + len -> KLineFrame(b.subList(0, hdr).toIntArray(), b[1], b[2], b.subList(hdr, hdr + len).toIntArray(), null, null)
            else -> null
        }
    }

    /**
     * Combines the frames of one response into one payload per ECU (source address),
     * so the rest of the code can treat K-Line exactly like reassembled CAN messages.
     *  - mode 01/02 and others: first frame as is
     *  - mode 03/07/0A (DTCs, up to 3 per frame): 43 + all DTC pairs
     *  - mode 09: 49 PID 01 + concatenated data (message counter bytes removed)
     */
    fun toPayloads(frames: List<KLineFrame>): List<Pair<Int, IntArray>> {
        val out = mutableListOf<Pair<Int, IntArray>>()
        for ((src, fs) in frames.groupBy { it.source }) {
            val first = fs.first().data
            if (first.isEmpty()) continue
            when (first[0]) {
                0x43, 0x47, 0x4A -> {
                    val all = mutableListOf(first[0])
                    fs.forEach { f -> if (f.data.isNotEmpty() && f.data[0] == first[0]) all.addAll(f.data.drop(1)) }
                    out += src to all.toIntArray()
                }
                0x49 -> {
                    if (first.size < 2) { out += src to first; continue }
                    val all = mutableListOf(0x49, first[1], 0x01)
                    fs.sortedBy { it.data.getOrElse(2) { 0 } }.forEach { f ->
                        if (f.data.size > 3 && f.data[0] == 0x49 && f.data[1] == first[1]) all.addAll(f.data.drop(3))
                    }
                    out += src to all.toIntArray()
                }
                else -> fs.forEach { out += src to it.data }
            }
        }
        return out
    }
}
