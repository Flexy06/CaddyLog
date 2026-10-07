package de.flexy.caddylog.core

/**
 * Transport to the ELM327 (BLE, Bluetooth classic, simulator ...).
 * [exchange] sends one command line (without CR) and returns the raw text up to the '>' prompt.
 * Must throw on timeout or disconnect.
 */
interface ElmLink {
    suspend fun exchange(command: String, timeoutMs: Long): String
}

/** Every command and response is reported here (direction '>' = to adapter, '<' = from adapter, '!' = error). */
fun interface RawLog {
    fun log(direction: Char, text: String)
}

data class CanFrame(val id: Int, val data: IntArray) {
    override fun toString() = "%03X %s".format(id, data.toHex(" "))
}

val Byte.u: Int get() = toInt() and 0xFF
fun IntArray.toHex(sep: String = " ") = joinToString(sep) { "%02X".format(it) }
fun List<Int>.toHex(sep: String = " ") = joinToString(sep) { "%02X".format(it) }
fun IntArray.toAscii() = map { if (it in 0x20..0x7E) it.toChar() else '.' }.joinToString("")

/**
 * ELM327 command layer. Keeps track of the adapter configuration so that switching between
 * normal OBD2 requests (auto-formatting) and raw CAN (needed for VW TP2.0) only costs the AT
 * commands that are really necessary.
 */
class Elm327(private val link: ElmLink, private val rawLog: RawLog = RawLog { _, _ -> }) {

    enum class Mode { UNKNOWN, OBD, RAW_CAN }

    var mode = Mode.UNKNOWN
        private set
    val info = linkedMapOf<String, String>()

    private var lastHeader = -1
    private var lastReceiveFilter = -1

    suspend fun cmd(command: String, timeoutMs: Long = 2500): String {
        rawLog.log('>', command)
        val raw = try {
            link.exchange(command, timeoutMs)
        } catch (e: Exception) {
            rawLog.log('!', "${e::class.simpleName}: ${e.message}")
            throw e
        }
        var lines = raw.replace('\r', '\n').split('\n')
            .map { it.trim().removeSuffix(">").trim() }
            .filter { it.isNotEmpty() }
        // drop echo (only present before ATE0 took effect)
        if (lines.isNotEmpty() && lines[0].replace(" ", "").equals(command.replace(" ", ""), ignoreCase = true)) {
            lines = lines.drop(1)
        }
        lines = lines.filter { !it.startsWith("SEARCHING") }
        val clean = lines.joinToString("\n")
        rawLog.log('<', clean.replace("\n", " | "))
        return clean
    }

    suspend fun initialize(): Map<String, String> {
        info.clear()
        cmd("ATZ", 5000)
        cmd("ATE0"); cmd("ATL0"); cmd("ATS1"); cmd("ATH1"); cmd("ATAT1")
        info["Adapter (ATI)"] = cmd("ATI")
        info["Beschreibung (AT@1)"] = cmd("AT@1")
        info["Batteriespannung (ATRV)"] = cmd("ATRV")
        // VW Caddy 2K (2010): ISO 15765-4 CAN, 11 bit, 500 kbit/s = protocol 6
        cmd("ATSP6")
        var r = cmd("0100", 6000)
        if (isError(r) || parseFrames(r).isEmpty()) {
            cmd("ATSP0")
            r = cmd("0100", 12000)
        }
        info["Protokoll"] = cmd("ATDP")
        info["Protokoll-Nr. (ATDPN)"] = cmd("ATDPN")
        info["Antwort 0100"] = r
        mode = Mode.OBD
        lastHeader = 0x7DF; lastReceiveFilter = -1
        return info
    }

    /** Standard OBD2 mode: auto formatting, functional address 7DF, all responses. */
    suspend fun ensureObd() {
        if (mode == Mode.OBD) return
        cmd("ATCAF1"); cmd("ATAR"); cmd("ATSH7DF"); cmd("ATAT1"); cmd("ATST32")
        lastHeader = 0x7DF; lastReceiveFilter = -1
        mode = Mode.OBD
    }

    /** Raw CAN mode: no auto formatting (PCI bytes are ours), fixed short timeout. Used for VW TP2.0. */
    suspend fun ensureRawCan() {
        if (mode == Mode.RAW_CAN) return
        cmd("ATCAF0"); cmd("ATAT0"); cmd("ATST25")
        lastHeader = -1; lastReceiveFilter = -1
        mode = Mode.RAW_CAN
    }

    /** Set CAN TX header and RX filter (only sends what changed). */
    suspend fun target(txId: Int, rxId: Int?) {
        if (txId != lastHeader) { cmd("ATSH%03X".format(txId)); lastHeader = txId }
        if (rxId == null) {
            if (lastReceiveFilter != -1) { cmd("ATAR"); lastReceiveFilter = -1 }
        } else if (rxId != lastReceiveFilter) {
            cmd("ATCRA%03X".format(rxId)); lastReceiveFilter = rxId
        }
    }

    suspend fun voltage(): String = cmd("ATRV")

    fun resetState() {
        mode = Mode.UNKNOWN; lastHeader = -1; lastReceiveFilter = -1
    }

    companion object {
        private val ERRORS = listOf(
            "NO DATA", "CAN ERROR", "UNABLE TO CONNECT", "BUS INIT", "BUS ERROR", "BUFFER FULL",
            "STOPPED", "DATA ERROR", "FB ERROR", "LV RESET", "ERR", "ACT ALERT"
        )

        fun isError(text: String): Boolean =
            text.isBlank() || text.lines().any { l -> l.trim() == "?" || ERRORS.any { l.contains(it) } }

        /** Parses lines like "7E8 06 41 00 BE 3F A8 13" (headers on, spaces on or off). */
        fun parseFrames(text: String): List<CanFrame> {
            val out = mutableListOf<CanFrame>()
            for (line0 in text.lines()) {
                val line = line0.trim()
                if (line.isEmpty()) continue
                val tokens = line.split(' ').filter { it.isNotEmpty() }
                val hexOnly = tokens.all { t -> t.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' } }
                if (!hexOnly) continue
                if (tokens.size >= 2 && tokens[0].length == 3 && tokens.drop(1).all { it.length == 2 }) {
                    out += CanFrame(tokens[0].toInt(16), tokens.drop(1).map { it.toInt(16) }.toIntArray())
                } else if (tokens.size == 1 && line.length >= 5 && (line.length - 3) % 2 == 0) {
                    val id = line.substring(0, 3).toInt(16)
                    val data = line.substring(3).chunked(2).map { it.toInt(16) }.toIntArray()
                    out += CanFrame(id, data)
                }
            }
            return out
        }
    }
}

/** ISO 15765-2 reassembly of frames received with headers on (ELM did the flow control for us). */
object IsoTp {
    fun reassemble(frames: List<CanFrame>): List<Pair<Int, IntArray>> {
        val result = mutableListOf<Pair<Int, IntArray>>()
        val pending = linkedMapOf<Int, Pair<Int, MutableList<Int>>>()
        for (f in frames) {
            val d = f.data
            if (d.isEmpty()) continue
            when (d[0] shr 4) {
                0 -> {
                    val len = d[0] and 0x0F
                    result += f.id to d.copyOfRange(1, minOf(d.size, 1 + len))
                }
                1 -> if (d.size >= 2) {
                    val len = ((d[0] and 0x0F) shl 8) or d[1]
                    pending[f.id] = len to d.drop(2).toMutableList()
                }
                2 -> pending[f.id]?.let { (len, buf) ->
                    buf.addAll(d.drop(1))
                    if (buf.size >= len) {
                        result += f.id to buf.take(len).toIntArray()
                        pending.remove(f.id)
                    }
                }
            }
        }
        // incomplete multi-frame messages: keep what we have
        for ((id, p) in pending) result += id to p.second.toIntArray()
        return result
    }
}
