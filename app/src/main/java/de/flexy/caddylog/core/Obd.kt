package de.flexy.caddylog.core

/** Standard OBD2 (SAE J1979) client over the ELM327. All requests are functional (7DF), every ECU answering is recorded. */
class Obd(private val elm: Elm327) {

    /** Result of one request including the error classification (needed for live monitoring). */
    data class Response(val status: ReadStatus, val payloads: List<Pair<Int, IntArray>>, val raw: String, val busInit: Boolean = false)

    /**
     * Sends a request and returns the payload per responding ECU
     * (CAN: reassembled ISO-TP message keyed by CAN id; K-Line: KWP/ISO9141 frames keyed by source address).
     */
    suspend fun requestDetailed(vararg bytes: Int, timeoutMs: Long = 3000, expectOne: Boolean = false): Response {
        val useCount = expectOne && elm.bus == Bus.KLINE && elm.responseCountSupported != false
        val r = try {
            elm.ensureObd()
            val first = elm.cmd(bytes.toHex("") + if (useCount) "1" else "", timeoutMs)
            if (useCount && first.lines().any { it.trim() == "?" }) {
                elm.responseCountSupported = false   // old/clone adapter: fall back to normal requests
                elm.cmd(bytes.toHex(""), timeoutMs)
            } else {
                if (useCount && !Elm327.isError(first)) elm.responseCountSupported = true
                first
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Response(if (elm.linkConnected) ReadStatus.TIMEOUT else ReadStatus.LINK_LOST, emptyList(), e.message ?: "")
        }
        val busInit = r.lines().any { it.trim().startsWith("BUS INIT") && !it.contains("ERROR") }
        val upper = r.uppercase()
        val payloads: List<Pair<Int, IntArray>>
        var checksumErrors = 0
        if (elm.bus == Bus.KLINE) {
            val parsed = KLine.parse(r)
            checksumErrors = parsed.checksumErrors
            payloads = KLine.toPayloads(parsed.frames.filter { it.checksumOk != false })
        } else {
            val can = IsoTp.reassemble(Elm327.parseFrames(r))
            payloads = if (can.isEmpty() && elm.bus == Bus.UNKNOWN) {
                val parsed = KLine.parse(r)  // bus not known yet: K-Line answer?
                checksumErrors = parsed.checksumErrors
                KLine.toPayloads(parsed.frames.filter { it.checksumOk != false })
            } else can
        }
        val status = when {
            payloads.isNotEmpty() -> ReadStatus.OK
            checksumErrors > 0 || upper.contains("DATA ERROR") -> ReadStatus.CHECKSUM_ERROR
            upper.contains("BUS INIT") || upper.contains("UNABLE TO CONNECT") || upper.contains("BUS ERROR") || upper.contains("CAN ERROR") -> ReadStatus.BUS_INIT
            upper.contains("NO DATA") -> ReadStatus.NO_DATA
            upper.contains("STOPPED") -> ReadStatus.TIMEOUT
            r.isBlank() -> ReadStatus.NO_DATA
            else -> ReadStatus.INVALID
        }
        return Response(status, payloads, r, busInit)
    }

    suspend fun request(vararg bytes: Int, timeoutMs: Long = 3000): List<Pair<Int, IntArray>> =
        requestDetailed(*bytes, timeoutMs = timeoutMs).payloads

    /**
     * Reads ONE mode 01 PID and classifies the outcome. Used by the live monitor:
     * every reading carries the time it was actually received from the vehicle.
     */
    suspend fun readPid(pid: Int, timeoutMs: Long = 2000): PidResult {
        val resp = requestDetailed(0x01, pid, timeoutMs = timeoutMs, expectOne = true)
        if (resp.status != ReadStatus.OK) return PidResult(pid, resp.status, raw = resp.raw, busInit = resp.busInit)
        val matching = resp.payloads.filter { (_, p) -> p.size >= 2 && p[0] == 0x41 && p[1] == pid }
        if (matching.isEmpty()) {
            val negative = resp.payloads.any { (_, p) -> p.isNotEmpty() && p[0] == 0x7F }
            return PidResult(pid, if (negative) ReadStatus.NOT_SUPPORTED else ReadStatus.INVALID, raw = resp.raw, busInit = resp.busInit)
        }
        val readings = matching.flatMap { (ecu, p) -> decodeMode01(ecu, p) }
        val def = ObdPids.defs[pid]
        if (def != null && matching.all { (_, p) -> p.size < 2 + def.len }) return PidResult(pid, ReadStatus.INVALID, raw = resp.raw, busInit = resp.busInit)
        return PidResult(pid, ReadStatus.OK, readings, resp.raw, resp.busInit)
    }

    /** Supported PIDs per ECU for modes 01, 02 (freeze frame), 06, 09. */
    suspend fun supported(mode: Int): Map<Int, Set<Int>> {
        val result = linkedMapOf<Int, MutableSet<Int>>()
        var base = 0
        while (base <= 0xE0) {
            val req = if (mode == 0x02) intArrayOf(mode, base, 0) else intArrayOf(mode, base)
            val resp = request(*req)
            val off = if (mode == 0x02) 3 else 2
            var more = false
            for ((ecu, p) in resp) {
                if (p.size < off + 4 || p[0] != mode + 0x40 || p[1] != base) continue
                val set = result.getOrPut(ecu) { sortedSetOf() }
                for (i in 0 until 32) {
                    val byte = p[off + i / 8]
                    if ((byte shr (7 - i % 8)) and 1 == 1) set += base + i + 1
                }
                if ((base + 0x20) in set) more = true
            }
            if (!more) break
            base += 0x20
        }
        return result
    }

    /** Decodes a mode 01 (or 02, with dataOffset 3) response payload which may contain several PIDs. */
    fun decodeMode01(ecu: Int, p: IntArray, freeze: Boolean = false): List<Reading> {
        val out = mutableListOf<Reading>()
        val step = if (freeze) 3 else 2 // mode 02: PID + frame number
        var i = 1
        val prefix = if (freeze) "FF" else "OBD"
        while (i < p.size) {
            val pid = p[i]
            val def = ObdPids.defs[pid]
            val dataStart = i + step - 1
            if (def == null) {
                val rest = p.copyOfRange(minOf(dataStart, p.size), p.size)
                val v = if (rest.size in 1..4) rest.fold(0L) { acc, b -> acc * 256 + b }.toDouble() else null
                out += Reading("$prefix.%03X.%02X".format(ecu, pid), "PID %02X (unbekannt, roh)".format(pid), v, "", null, rest.toHex(""))
                break
            }
            if (dataStart + def.len > p.size) {
                out += Reading("$prefix.%03X.%02X".format(ecu, pid), def.name, null, "", "unvollständig", p.copyOfRange(minOf(dataStart, p.size), p.size).toHex(""))
                break
            }
            val d = p.copyOfRange(dataStart, dataStart + def.len)
            for (v in runCatching { def.decode(d) }.getOrElse { emptyList() }) {
                out += Reading("$prefix.%03X.%02X%s".format(ecu, pid, v.suffix), "${def.name}: ${v.name}".takeIf { v.name != def.name } ?: def.name,
                    v.value?.let { Math.round(it * 1000) / 1000.0 }, v.unit, v.text, d.toHex(""))
            }
            i = dataStart + def.len
        }
        return out
    }

    /** Reads mode 01 PIDs; known-length PIDs are batched (up to 6 per request), unknown ones are read alone. */
    suspend fun readPids(pids: List<Int>): List<Reading> {
        val out = mutableListOf<Reading>()
        val known = pids.filter { ObdPids.defs.containsKey(it) && !ObdPids.isBitmapPid(it) }
        val unknown = pids.filter { !ObdPids.defs.containsKey(it) && !ObdPids.isBitmapPid(it) }
        // multiple PIDs per request are only allowed on CAN (ISO 15765-4); K-Line: one PID per request
        val batchSize = if (elm.bus == Bus.CAN) 6 else 1
        for (batch in known.chunked(batchSize)) {
            val resp = request(0x01, *batch.toIntArray())
            for ((ecu, p) in resp) if (p.isNotEmpty() && p[0] == 0x41) out += decodeMode01(ecu, p)
        }
        for (pid in unknown) {
            for ((ecu, p) in request(0x01, pid)) if (p.isNotEmpty() && p[0] == 0x41) out += decodeMode01(ecu, p)
        }
        return out
    }

    suspend fun readDtcs(mode: Int): Map<Int, List<String>> {
        val out = linkedMapOf<Int, List<String>>()
        for ((ecu, p) in request(mode, timeoutMs = 5000)) {
            if (p.isEmpty() || p[0] != mode + 0x40) continue
            val body = if ((p.size - 2) % 2 == 0 && p.size >= 2 && p[1] * 2 == p.size - 2) p.copyOfRange(2, p.size) else p.copyOfRange(1, p.size)
            out[ecu] = body.toList().chunked(2).filter { it.size == 2 && (it[0] != 0 || it[1] != 0) }.map { dtcString(it[0], it[1]) }
        }
        return out
    }

    /** Full read-only scan of everything standard OBD2 offers. */
    suspend fun fullScan(progress: (String) -> Unit, onReading: (Reading) -> Unit): Map<String, Any?> {
        val report = linkedMapOf<String, Any?>()

        progress("OBD: unterstützte PIDs (Mode 01) …")
        val sup01 = supported(0x01)
        report["mode01_supported"] = sup01.map { (ecu, s) -> "%03X".format(ecu) to s.map { "%02X".format(it) } }.toMap()
        val m01 = mutableListOf<Map<String, Any?>>()
        for ((ecu, pids) in sup01) {
            for (pid in pids.filter { !ObdPids.isBitmapPid(it) }) {
                progress("OBD: Mode 01 PID %02X (SG %03X)".format(pid, ecu))
                for ((e, p) in request(0x01, pid)) {
                    if (e != ecu || p.isEmpty() || p[0] != 0x41) continue
                    val rs = decodeMode01(e, p)
                    rs.forEach(onReading)
                    m01 += mapOf("ecu" to "%03X".format(e), "pid" to "%02X".format(pid), "raw" to p.toHex(""),
                        "values" to rs.map { it.toMap() })
                }
            }
        }
        report["mode01"] = m01

        progress("OBD: Fehlerspeicher (Mode 03/07/0A) …")
        report["dtc_stored_03"] = readDtcs(0x03).mapKeys { "%03X".format(it.key) }
        report["dtc_pending_07"] = readDtcs(0x07).mapKeys { "%03X".format(it.key) }
        report["dtc_permanent_0A"] = readDtcs(0x0A).mapKeys { "%03X".format(it.key) }

        progress("OBD: Freeze Frame (Mode 02) …")
        val sup02 = supported(0x02)
        val ff = mutableListOf<Map<String, Any?>>()
        for ((ecu, pids) in sup02) for (pid in pids.filter { !ObdPids.isBitmapPid(it) }) {
            for ((e, p) in request(0x02, pid, 0)) {
                if (e != ecu || p.isEmpty() || p[0] != 0x42) continue
                ff += mapOf("ecu" to "%03X".format(e), "pid" to "%02X".format(pid), "raw" to p.toHex(""),
                    "values" to decodeMode01(e, p, freeze = true).map { it.toMap() })
            }
        }
        report["freeze_frame"] = ff

        progress("OBD: Fahrzeuginfo (Mode 09) …")
        val sup09 = supported(0x09)
        val m09 = mutableListOf<Map<String, Any?>>()
        for ((ecu, pids) in sup09) for (pid in pids.filter { !ObdPids.isBitmapPid(it) }) {
            for ((e, p) in request(0x09, pid, timeoutMs = 4000)) {
                if (e != ecu || p.size < 2 || p[0] != 0x49) continue
                val data = p.copyOfRange(minOf(3, p.size), p.size)
                val name = MODE09[pid] ?: "PID %02X".format(pid)
                m09 += mapOf("ecu" to "%03X".format(e), "pid" to "%02X".format(pid), "name" to name,
                    "raw" to p.toHex(""), "ascii" to data.toAscii().trim('.', ' '))
                onReading(Reading("INFO.%03X.%02X".format(e, pid), name, null, "", data.toAscii().trim('.', ' '), data.toHex("")))
            }
        }
        report["mode09"] = m09

        progress("OBD: On-Board-Monitortests (Mode 06) …")
        val sup06 = supported(0x06)
        val m06 = mutableListOf<Map<String, Any?>>()
        for ((ecu, mids) in sup06) for (mid in mids.filter { !ObdPids.isBitmapPid(it) }) {
            for ((e, p) in request(0x06, mid)) {
                if (e != ecu || p.size < 2 || p[0] != 0x46) continue
                val tests = if (elm.bus != Bus.CAN) emptyList() else p.copyOfRange(1, p.size).toList().chunked(9).filter { it.size == 9 }.map { t ->
                    val v = t[3] * 256 + t[4]; val mn = t[5] * 256 + t[6]; val mx = t[7] * 256 + t[8]
                    mapOf("mid" to "%02X".format(t[0]), "tid" to "%02X".format(t[1]), "uasid" to "%02X".format(t[2]),
                        "value" to v, "min" to mn, "max" to mx, "pass" to (v in mn..mx))
                }
                m06 += mapOf("ecu" to "%03X".format(e), "mid" to "%02X".format(mid), "raw" to p.toHex(""), "tests" to tests)
            }
        }
        report["mode06"] = m06
        progress("OBD-Scan fertig.")
        return report
    }

    companion object {
        val MODE09 = mapOf(
            0x01 to "VIN Nachrichtenanzahl", 0x02 to "Fahrgestellnummer (VIN)", 0x03 to "CALID Anzahl",
            0x04 to "Kalibrierungs-ID (CALID)", 0x05 to "CVN Anzahl", 0x06 to "Kalibrierungs-Prüfnummer (CVN)",
            0x08 to "In-use Performance (Benzin)", 0x0A to "Steuergerätename", 0x0B to "In-use Performance (Diesel)"
        )

        fun dtcString(a: Int, b: Int): String {
            val letter = "PCBU"[(a shr 6) and 3]
            return "%c%d%X%02X".format(letter, (a shr 4) and 3, a and 0x0F, b)
        }
    }
}

fun Reading.toMap(): Map<String, Any?> =
    mapOf("channel" to channel, "name" to name, "value" to value, "unit" to unit, "text" to text, "raw" to raw)
