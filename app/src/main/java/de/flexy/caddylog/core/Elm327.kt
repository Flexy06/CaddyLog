package de.flexy.caddylog.core

/**
 * Transport to the ELM327 (BLE, Bluetooth classic, simulator ...).
 * [exchange] sends one command line (without CR) and returns the raw text up to the '>' prompt.
 * Must throw on timeout or disconnect.
 */
interface ElmLink {
    suspend fun exchange(command: String, timeoutMs: Long): String

    /** false once the transport (e.g. BLE) is gone – used to tell "link lost" from "ECU silent". */
    val isConnected: Boolean get() = true
}

/** Physical bus the vehicle answered on (decided from ATDPN after the first successful request). */
enum class Bus(val label: String) {
    CAN("CAN"), KLINE("K-Line"), OTHER("J1850"), UNKNOWN("unbekannt");

    companion object {
        /** ATDPN answers e.g. "A5" (auto, ISO 14230-4 KWP fast init) or "6" (ISO 15765-4 CAN 11/500). */
        fun fromDpn(dpn: String): Bus {
            val d = dpn.trim().uppercase().lines().lastOrNull()?.trim()?.let { if (it.length == 2 && it.startsWith("A")) it.substring(1) else it } ?: return UNKNOWN
            return when (d) {
                "3", "4", "5" -> KLINE
                "6", "7", "8", "9", "A", "B", "C" -> CAN
                "1", "2" -> OTHER
                else -> UNKNOWN
            }
        }
    }
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

    /** Bus of the vehicle; K-Line means: no CAN-only features (TP2.0, multi-PID requests). */
    var bus = Bus.UNKNOWN
        private set

    /** ATDPN result after init, e.g. "5" – remembered by the app to speed up the next connection. */
    var protocolNumber: String? = null
        private set

    val linkConnected: Boolean get() = link.isConnected

    /**
     * ELM327 ≥ v1.3 accepts a response count after an OBD request ("0105 1" → return after the first answer instead of
     * waiting for the timeout). Speeds up K-Line polling a lot. null = not tried yet, false = adapter rejected it.
     */
    var responseCountSupported: Boolean? = null

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

    /**
     * Resets and configures the adapter and finds the vehicle protocol.
     * @param preferredProtocol protocol number that worked last time (e.g. "5" = ISO 14230-4 KWP fast init);
     *        tried first, then automatic search (ATSP0) as fallback.
     */
    suspend fun initialize(preferredProtocol: String? = null): Map<String, String> {
        info.clear()
        cmd("ATZ", 5000)
        cmd("ATE0"); cmd("ATL0"); cmd("ATS1"); cmd("ATH1"); cmd("ATAT1")
        info["Adapter (ATI)"] = cmd("ATI")
        info["Beschreibung (AT@1)"] = cmd("AT@1")
        info["Batteriespannung (ATRV)"] = cmd("ATRV")
        val attempts = listOfNotNull(preferredProtocol?.takeIf { it.isNotBlank() && it != "0" }, "0").distinct()
        var r = ""
        for (p in attempts) {
            cmd("ATSP$p")
            // K-Line init (5 baud / fast init) can take several seconds
            r = runCatching { cmd("0100", 15000) }.getOrElse { "" }
            if (!isError(r) && r.contains("41")) break
        }
        info["Protokoll"] = cmd("ATDP")
        val dpn = cmd("ATDPN")
        info["Protokoll-Nr. (ATDPN)"] = dpn
        bus = Bus.fromDpn(dpn)
        protocolNumber = dpn.trim().removePrefix("A").takeIf { bus != Bus.UNKNOWN }
        info["Bus"] = bus.label
        info["Antwort 0100"] = r
        mode = Mode.OBD
        lastHeader = 0x7DF; lastReceiveFilter = -1
        return info
    }

    /** Standard OBD2 mode: auto formatting, functional address 7DF, all responses. */
    suspend fun ensureObd() {
        if (mode == Mode.OBD) return
        if (bus == Bus.KLINE) {
            // K-Line: keep the adapter's default KWP header (ATSH with an 11-bit CAN id would break it)
            cmd("ATH1"); cmd("ATS1"); cmd("ATAT1")
        } else {
            cmd("ATH1"); cmd("ATS1"); cmd("ATCAF1"); cmd("ATAR"); cmd("ATSH7DF"); cmd("ATAT1"); cmd("ATST32")
            lastHeader = 0x7DF; lastReceiveFilter = -1
        }
        mode = Mode.OBD
    }

    /** Forces a new K-Line/CAN initialisation on the next request (ATPC = protocol close). */
    suspend fun reinitBus() {
        cmd("ATPC")
        mode = Mode.UNKNOWN
    }

    /** Raw CAN mode: no auto formatting (PCI bytes are ours), fixed short timeout. Used for VW TP2.0. */
    suspend fun ensureRawCan() {
        check(bus != Bus.KLINE) { "VW TP2.0 benötigt CAN – das Fahrzeug kommuniziert über K-Line" }
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
            "NO DATA", "CAN ERROR", "UNABLE TO CONNECT", "BUS ERROR", "BUFFER FULL",
            "STOPPED", "DATA ERROR", "FB ERROR", "LV RESET", "ERR", "ACT ALERT"
        )

        /** Note: "BUS INIT: ...OK" (K-Line init succeeded) is NOT an error, "BUS INIT: ...ERROR" is. */
        fun isError(text: String): Boolean =
            text.isBlank() || text.lines().any { l ->
                val t = l.trim()
                t == "?" || (if (t.startsWith("BUS INIT")) t.contains("ERROR") else ERRORS.any { t.contains(it) })
            }

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
