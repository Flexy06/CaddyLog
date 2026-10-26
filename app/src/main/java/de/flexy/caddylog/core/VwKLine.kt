package de.flexy.caddylog.core

/** A KWP2000 connection to one VW control unit – over CAN (TP2.0) or K-Line (physical ISO 14230 session). */
interface KwpChannel {
    val module: Int
    val isOpen: Boolean
    suspend fun open(): Boolean
    /** sends a READ request and returns the ECU's response payload (e.g. 61 01 …), null on timeout */
    suspend fun kwp(vararg req: Int): IntArray?
    suspend fun close()
    /** transport details for the scan report */
    fun describe(): Map<String, Any?>
}

/**
 * VW measuring blocks on older VAG cars whose ECUs are only reachable via K-Line (Caddy 2K up to ~2010,
 * T5 up to ~2009 …): physical KWP2000 session to the ECU address (01 = engine), then "21 xx" = read group xx.
 *
 * Limitations: ECUs that only speak the old KW1281 protocol (most cars before ~2002) cannot be read with an ELM327.
 * Only READ services are sent (10 89 session, 1A identification, 18 DTCs, 21 measuring blocks).
 */
class KwpKLine(private val elm: Elm327, override val module: Int, private val log: (String) -> Unit = {}) : KwpChannel {

    override var isOpen = false
        private set
    private var sessionAnswer: String? = null

    override suspend fun open(): Boolean {
        isOpen = false
        elm.ensureKwpPhysical(module)
        // first request initialises the K-Line with the ECU address – takes a few seconds
        val r = exchange(intArrayOf(0x10, 0x89), elm.timeoutFor(3000))
        if (r == null) {
            log("SG %02X antwortet nicht über K-Line (KWP2000)".format(module))
            return false
        }
        elm.busInitPending = false
        sessionAnswer = r.toHex("")
        isOpen = true
        log("K-Line-Sitzung zu SG %02X offen (Antwort auf 10 89: %s)".format(module, r.toHex()))
        return true
    }

    override suspend fun kwp(vararg req: Int): IntArray? {
        if (!isOpen) return null
        elm.ensureKwpPhysical(module)
        return exchange(req, 3000)
    }

    /**
     * One request. Frames with "response pending" (7F xx 78) are skipped – usually the real answer is in the same
     * adapter response. If only "pending" came back, the (read-only, idempotent) request is repeated.
     */
    private suspend fun exchange(req: IntArray, timeoutMs: Long): IntArray? {
        repeat(3) {
            val text = elm.cmd(req.toHex(""), timeoutMs)
            val frames = KLine.parse(text).frames.filter { it.checksumOk != false && it.source == module && it.data.isNotEmpty() }
            val answer = frames.lastOrNull { f -> !(f.data.size >= 3 && f.data[0] == 0x7F && f.data[2] == 0x78) }
            if (answer != null) return answer.data
            if (frames.isEmpty()) return null
        }
        return null
    }

    override suspend fun close() {
        isOpen = false
        // the OBD addressing is restored by Elm327.ensureObd() before the next OBD request
    }

    override fun describe(): Map<String, Any?> = mapOf(
        "transport" to "K-Line KWP2000 (physisch)", "protocol" to elm.protocolNumber, "session_10_89" to sessionAnswer,
    )
}
