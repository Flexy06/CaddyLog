package de.flexy.caddylog.core

/**
 * VW TP 2.0 transport (CAN, 500 kbit) implemented on top of an ELM327 in raw CAN mode.
 * Used by VAG cars of the PQ35/PQ46 era (Caddy 2K 2010, Golf 5/6, Passat B6 …) for KWP2000 diagnostics.
 *
 * Only READ services are ever sent (session start, identification, read DTCs, read measuring blocks).
 */
class Tp20(private val elm: Elm327, override val module: Int, private val log: (String) -> Unit = {}) : KwpChannel {
    var txId = -1; private set
    var rxId = -1; private set
    override var isOpen = false; private set
    private var txSeq = 0

    override suspend fun kwp(vararg req: Int): IntArray? = request(*req)

    override fun describe(): Map<String, Any?> =
        mapOf("transport" to "CAN TP2.0", "tp20_tx" to "%03X".format(txId), "tp20_rx" to "%03X".format(rxId))

    override suspend fun open(): Boolean {
        isOpen = false
        elm.ensureRawCan()
        elm.target(0x200, 0x200 + module)
        val r = elm.cmd("%02XC00010000301".format(module), 1500)
        val f = Elm327.parseFrames(r).firstOrNull { it.data.size >= 6 && it.data[1] == 0xD0 } ?: return false
        val idA = f.data[2] or ((f.data[3] and 0x07) shl 8)
        val idB = f.data[4] or ((f.data[5] and 0x07) shl 8)
        // Normally the tester transmits on idB (e.g. 0x740) and receives on idA (e.g. 0x300). Try the other way if needed.
        for ((tx, rx) in listOf(idB to idA, idA to idB)) {
            elm.target(tx, rx)
            val p = elm.cmd("A00F8AFF32FF", 1500)
            if (Elm327.parseFrames(p).any { it.id == rx && it.data.isNotEmpty() && it.data[0] == 0xA1 }) {
                txId = tx; rxId = rx; txSeq = 0; isOpen = true
                log("TP2.0 Kanal zu SG %02X offen (TX %03X / RX %03X)".format(module, tx, rx))
                // VAG diagnostic session (KWP 10 89). Some modules answer negatively – that's fine.
                request(0x10, 0x89)
                return true
            }
        }
        return false
    }

    /**
     * Sends a KWP2000 request (max 5 bytes – all our requests are short) and returns the complete response,
     * or null on timeout / channel loss. Handles TP2.0 ACKs, multi-frame blocks and "response pending" (7F xx 78).
     */
    suspend fun request(vararg kwp: Int, maxIdlePolls: Int = 4): IntArray? {
        if (!isOpen) return null
        require(kwp.size <= 5)
        elm.ensureRawCan()
        elm.target(txId, rxId)
        val frame = intArrayOf(0x10 or txSeq, 0x00, kwp.size, *kwp)
        txSeq = (txSeq + 1) and 0x0F
        var resp = elm.cmd(frame.toHex(""), 2000)
        val buf = mutableListOf<Int>()
        var expected = -1
        var idle = 0
        var guard = 0
        while (guard++ < 200) {
            var ackSeq = -1
            for (f in Elm327.parseFrames(resp)) {
                if (f.id != rxId || f.data.isEmpty()) continue
                val b0 = f.data[0]
                val op = b0 and 0xF0
                when {
                    b0 == 0xA8 -> { isOpen = false; log("SG %02X hat Kanal geschlossen".format(module)); return null }
                    op == 0xA0 || op == 0x90 || op == 0xB0 -> Unit // params / ACKs to our frames
                    op <= 0x30 -> {
                        val payload = f.data.drop(1)
                        if (expected < 0) {
                            if (payload.size < 2) continue
                            expected = (payload[0] shl 8) or payload[1]
                            buf.addAll(payload.drop(2))
                        } else buf.addAll(payload)
                        if (op == 0x00 || op == 0x10) ackSeq = b0 and 0x0F
                    }
                }
            }
            var ackResp: String? = null
            if (ackSeq >= 0) ackResp = elm.cmd("B%X".format((ackSeq + 1) and 0x0F), 2000)

            if (expected >= 0 && buf.size >= expected) {
                val msg = buf.take(expected).toIntArray()
                if (msg.size >= 3 && msg[0] == 0x7F && msg[2] == 0x78) { // response pending
                    buf.clear(); expected = -1; idle = 0
                    resp = ackResp ?: elm.cmd("A3", 2000)
                    continue
                }
                return msg
            }
            if (ackResp != null) { resp = ackResp; idle = 0; continue }
            if (++idle > maxIdlePolls) return null
            // nothing (more) received yet: send channel test, the ECU's pending frames come with the answer
            resp = elm.cmd("A3", 2000)
        }
        return null
    }

    suspend fun keepAlive(): Boolean {
        if (!isOpen) return false
        elm.ensureRawCan(); elm.target(txId, rxId)
        val r = elm.cmd("A3", 1500)
        return Elm327.parseFrames(r).any { it.id == rxId && it.data.isNotEmpty() && it.data[0] == 0xA1 }
    }

    override suspend fun close() {
        if (!isOpen) return
        runCatching { elm.ensureRawCan(); elm.target(txId, rxId); elm.cmd("A8", 800) }
        isOpen = false
    }
}

/** VAG KWP2000 module scanner / reader. */
class Vw(private val elm: Elm327, private val log: (String) -> Unit = {}) {

    companion object {
        /** TP2.0 logical addresses (= VCDS address, except gateway 19 → 1F). */
        val MODULE_NAMES = mapOf(
            0x01 to "Motorelektronik", 0x02 to "Getriebeelektronik", 0x03 to "Bremsenelektronik (ABS/ESP)",
            0x05 to "Zugang/Startberechtigung", 0x08 to "Klima/Heizung", 0x09 to "Zentralelektrik (Bordnetz)",
            0x0F to "Digitalradio", 0x10 to "Einparkhilfe 2", 0x11 to "Motorelektronik 2", 0x13 to "Abstandsregelung",
            0x14 to "Radelektronik", 0x15 to "Airbag", 0x16 to "Lenksäulenelektronik", 0x17 to "Schalttafeleinsatz (Kombi)",
            0x18 to "Standheizung", 0x1F to "Diagnose-Interface Datenbus (Gateway, VCDS 19)", 0x22 to "Allrad",
            0x25 to "Wegfahrsperre", 0x2E to "Media Player", 0x36 to "Sitzverstellung Fahrer", 0x37 to "Navigation",
            0x42 to "Türelektronik Fahrer", 0x44 to "Lenkhilfe", 0x45 to "Innenraumüberwachung", 0x46 to "Zentralmodul Komfort",
            0x47 to "Soundsystem", 0x52 to "Türelektronik Beifahrer", 0x53 to "Feststellbremse", 0x55 to "Leuchtweitenregelung",
            0x56 to "Radio", 0x5F to "Informationselektronik", 0x61 to "Batterieregelung", 0x62 to "Tür hinten links",
            0x65 to "Reifendruckkontrolle", 0x69 to "Anhängerfunktion", 0x72 to "Tür hinten rechts", 0x75 to "Telematik",
            0x76 to "Einparkhilfe", 0x77 to "Telefon"
        )

        fun name(module: Int) = MODULE_NAMES[module] ?: "Steuergerät %02X".format(module)

        /** VAG fault codes are 16 bit numbers (VCDS shows them decimal, e.g. 16684). */
        fun dtc(hi: Int, lo: Int, status: Int): Map<String, Any?> {
            val code = hi * 256 + lo
            return mapOf("vag_code" to "%05d".format(code), "hex" to "%04X".format(code), "status" to "%02X".format(status))
        }
    }

    /** CAN → TP2.0, K-Line → physical KWP2000 session */
    private fun channel(module: Int): KwpChannel =
        if (elm.bus == Bus.KLINE) KwpKLine(elm, module, log) else Tp20(elm, module, log)

    /** Scans one module. groups = measuring block numbers to read (KWP 21 xx). */
    suspend fun scanModule(module: Int, groups: IntRange, progress: (String) -> Unit, onReading: (Reading) -> Unit): Map<String, Any?>? {
        val ch = channel(module)
        if (!ch.open()) return null
        val rep = linkedMapOf<String, Any?>("address" to "%02X".format(module), "name" to name(module))
        rep.putAll(ch.describe())
        try {
            progress("SG %02X %s: Identifikation".format(module, name(module)))
            val ident = linkedMapOf<String, Any?>()
            for (opt in listOf(0x9B, 0x9A, 0x91, 0x86, 0x8A)) {
                val r = ch.kwp(0x1A, opt) ?: continue
                ident["1A%02X".format(opt)] = mapOf("raw" to r.toHex(""), "ascii" to r.copyOfRange(minOf(2, r.size), r.size).toAscii(), "positive" to (r[0] == 0x5A))
                if (opt == 0x9B && r[0] == 0x5A) onReading(Reading("VW.%02X.IDENT".format(module), "SG %02X Identifikation".format(module), null, "", r.copyOfRange(2, r.size).toAscii(), r.toHex("")))
            }
            rep["identification"] = ident

            progress("SG %02X: Fehlerspeicher".format(module))
            val d = ch.kwp(0x18, 0x02, 0xFF, 0x00)
            rep["dtc_raw"] = d?.toHex("")
            if (d != null && d.size >= 2 && d[0] == 0x58) {
                rep["dtcs"] = d.copyOfRange(2, d.size).toList().chunked(3).filter { it.size == 3 }.map { dtc(it[0], it[1], it[2]) }
            }

            val gr = mutableListOf<Map<String, Any?>>()
            var consecutiveFails = 0
            for (g in groups) {
                progress("SG %02X: Messwertblock %03d".format(module, g))
                val r = ch.kwp(0x21, g)
                if (r == null) {
                    consecutiveFails++
                    // channel lost? reopen once
                    if (!ch.isOpen || consecutiveFails >= 3) {
                        if (!ch.open()) { rep["aborted_at_group"] = g; break }
                        consecutiveFails = 0
                    }
                    continue
                }
                consecutiveFails = 0
                if (r[0] == 0x61) {
                    val readings = VwFormula.decodeGroup(module, g, r)
                    readings.forEach(onReading)
                    gr += mapOf("group" to g, "raw" to r.toHex(""), "fields" to readings.map { it.toMap() })
                } else if (r[0] == 0x7F) {
                    gr += mapOf("group" to g, "negative" to r.toHex(""))
                } else {
                    gr += mapOf("group" to g, "raw" to r.toHex(""))
                }
            }
            rep["groups"] = gr
        } finally {
            ch.close()
        }
        return rep
    }

    suspend fun scanAll(
        modules: List<Int>, groups: IntRange,
        progress: (String) -> Unit, onReading: (Reading) -> Unit,
    ): Map<String, Any?> {
        val found = mutableListOf<Map<String, Any?>>()
        val missing = mutableListOf<String>()
        for (m in modules) {
            progress("Suche SG %02X (%s) …".format(m, name(m)))
            val r = scanModule(m, groups, progress, onReading)
            if (r == null) missing += "%02X".format(m) else found += r
        }
        elm.ensureObd()
        return mapOf("modules" to found, "no_answer" to missing, "groups_scanned" to "${groups.first}-${groups.last}")
    }
}
