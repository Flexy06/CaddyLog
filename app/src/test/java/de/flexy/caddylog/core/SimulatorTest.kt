package de.flexy.caddylog.core

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.File

/** Very small simulated ELM327 + VW Caddy (engine ECU 7E8 for OBD, module 01 via TP2.0). */
class SimLink : ElmLink {
    var caf = true; var header = 0x7DF; var cra = -1
    val log = mutableListOf<String>()
    private var ecuSeq = 0
    private var pendingFrames = ArrayDeque<String>()   // waiting for ACK
    private var delayed: IntArray? = null              // response sent later (after 7F 78)
    private var channelOpen = false

    private val supported01 = sortedSetOf(0x01, 0x04, 0x05, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x1F, 0x20, 0x2F, 0x33, 0x40, 0x42, 0x46, 0x60, 0x51, 0x7F)
    private fun bitmap(base: Int, set: Set<Int>): IntArray {
        val b = IntArray(4)
        for (p in set) if (p in base + 1..base + 32) { val i = p - base - 1; b[i / 8] = b[i / 8] or (0x80 shr (i % 8)) }
        return b
    }
    private fun isoTp(id: Int, payload: IntArray): String {
        if (payload.size <= 7) return "%03X %02X %s".format(id, payload.size, payload.toHex()).trim()
        val lines = mutableListOf("%03X 10 %02X %s".format(id, payload.size, payload.take(6).toHex()))
        var seq = 1
        payload.drop(6).chunked(7).forEach { c -> lines += "%03X %02X %s".format(id, 0x20 or (seq and 0xF), c.toHex()); seq++ }
        return lines.joinToString("\r")
    }
    private fun pid(p: Int): IntArray = when (p) {
        0x01 -> intArrayOf(0x81, 0x07, 0x65, 0x00); 0x04 -> intArrayOf(0x40); 0x05 -> intArrayOf(0x7B)
        0x0C -> intArrayOf(0x1A, 0xF8); 0x0D -> intArrayOf(0x32); 0x0F -> intArrayOf(0x41); 0x10 -> intArrayOf(0x01, 0x90)
        0x11 -> intArrayOf(0x20); 0x1F -> intArrayOf(0x01, 0x2C); 0x2F -> intArrayOf(0x80); 0x33 -> intArrayOf(0x63)
        0x42 -> intArrayOf(0x36, 0xB0); 0x46 -> intArrayOf(0x3C); 0x51 -> intArrayOf(0x0D); 0x7F -> intArrayOf(1, 2, 3, 4, 5)
        else -> intArrayOf()
    }

    private fun obd(cmd: String): String {
        val b = cmd.chunked(2).map { it.toInt(16) }
        val mode = b[0]
        return when (mode) {
            0x01 -> {
                if (b.size == 2 && b[1] % 0x20 == 0) {
                    if (b[1] > 0x60) return "NO DATA"
                    return isoTp(0x7E8, intArrayOf(0x41, b[1], *bitmap(b[1], supported01)))
                }
                val out = mutableListOf(0x41)
                for (p in b.drop(1)) if (p in supported01) { out += p; out += pid(p).toList() }
                if (out.size == 1) "NO DATA" else isoTp(0x7E8, out.toIntArray())
            }
            0x03 -> isoTp(0x7E8, intArrayOf(0x43, 0x01, 0x01, 0x33))
            0x07 -> isoTp(0x7E8, intArrayOf(0x47, 0x00))
            0x09 -> when (b[1]) {
                0x00 -> isoTp(0x7E8, intArrayOf(0x49, 0x00, 0x54, 0x40, 0x00, 0x00))
                0x02 -> isoTp(0x7E8, intArrayOf(0x49, 0x02, 0x01) + "WV1ZZZ2KZAX000000".map { it.code }.toIntArray())
                0x0A -> isoTp(0x7E8, intArrayOf(0x49, 0x0A, 0x01) + "ECM-EngineControl".map { it.code }.toIntArray())
                else -> "NO DATA"
            }
            0x02 -> if (b[1] == 0) isoTp(0x7E8, intArrayOf(0x42, 0, 0, *bitmap(0, setOf(0x02, 0x05, 0x0C)))) else
                isoTp(0x7E8, intArrayOf(0x42, b[1], 0) + (if (b[1] == 2) intArrayOf(0x01, 0x33) else pid(b[1])))
            else -> "NO DATA"
        }
    }

    private fun kwp(req: List<Int>): IntArray = when {
        req == listOf(0x10, 0x89) -> intArrayOf(0x50, 0x89)
        req == listOf(0x1A, 0x9B) -> intArrayOf(0x5A, 0x9B) + "1K0907115AB 2.0l R4/2V CNG    0030".map { it.code }.toIntArray()
        req[0] == 0x1A -> intArrayOf(0x7F, 0x1A, 0x12)
        req == listOf(0x18, 0x02, 0xFF, 0x00) -> intArrayOf(0x58, 0x01, 0x40, 0x5C, 0x23)
        req == listOf(0x21, 0x01) -> intArrayOf(0x61, 0x01, 0x01, 0xC8, 0x2C, 0x05, 0x0A, 0xB0, 0x21, 0x55, 0x30, 0x10, 0xFF, 0x05)
        req == listOf(0x21, 0x02) -> { delayed = intArrayOf(0x61, 0x02, 0x12, 0x64, 0x50, 0x14, 0x80, 0x90, 0x06, 0x0A, 0x78, 0x45, 0x00, 0x96); intArrayOf(0x7F, 0x21, 0x78) }
        else -> intArrayOf(0x7F, req[0], 0x31)
    }

    /** builds TP2.0 frames; puts an ACK request after 3 frames to exercise the ACK path */
    private fun tp2Frames(msg: IntArray): List<String> {
        val bytes = listOf(msg.size shr 8, msg.size and 0xFF) + msg.toList()
        val chunks = bytes.chunked(7)
        return chunks.mapIndexed { i, c ->
            val last = i == chunks.size - 1
            val op = when { last -> 0x10; i == 2 -> 0x00; else -> 0x20 }
            val s = "300 %02X %s".format(op or ecuSeq, c.toHex()); ecuSeq = (ecuSeq + 1) and 0xF; s
        }
    }

    private fun sendUntilAck(frames: List<String>): String {
        val out = mutableListOf<String>()
        val q = ArrayDeque(frames)
        while (q.isNotEmpty()) {
            val f = q.removeFirst(); out += f
            val op = f.split(" ")[1].toInt(16) and 0xF0
            if (op == 0x00) { pendingFrames = q; break }
        }
        return out.joinToString("\r")
    }

    private fun raw(cmd: String): String {
        val b = cmd.chunked(2).map { it.toInt(16) }
        if (header == 0x200) return if (b[0] == 0x01 && b[1] == 0xC0) "201 00 D0 00 03 40 07 01" else "NO DATA"
        if (header != 0x740) return "NO DATA"
        val b0 = b[0]
        return when {
            b0 == 0xA0 -> { channelOpen = true; ecuSeq = 0; "300 A1 0F 8A FF 4A FF" }
            b0 == 0xA8 -> { channelOpen = false; "300 A8" }
            !channelOpen -> "NO DATA"
            b0 == 0xA3 -> {
                val d = delayed
                if (d != null) { delayed = null; "300 A1 0F 8A FF 4A FF\r" + sendUntilAck(tp2Frames(d)) } else "300 A1 0F 8A FF 4A FF"
            }
            (b0 and 0xF0) == 0xB0 -> if (pendingFrames.isNotEmpty()) sendUntilAck(pendingFrames.toList().also { pendingFrames = ArrayDeque() }) else "NO DATA"
            (b0 and 0xF0) == 0x10 -> {
                val len = b[1] * 256 + b[2]
                val req = b.drop(3).take(len)
                "300 B%X\r".format(((b0 and 0xF) + 1) and 0xF) + sendUntilAck(tp2Frames(kwp(req)))
            }
            else -> "NO DATA"
        }
    }

    override suspend fun exchange(command: String, timeoutMs: Long): String {
        val c = command.uppercase().replace(" ", "")
        val r = when {
            c == "ATZ" -> "\r\rELM327 v1.5"
            c == "ATI" -> "ELM327 v1.5"
            c == "AT@1" -> "OBDII to RS232 Interpreter"
            c == "ATRV" -> "12.6V"
            c == "ATDP" -> "ISO 15765-4 (CAN 11/500)"
            c == "ATDPN" -> "6"
            c == "ATCAF0" -> { caf = false; "OK" }
            c == "ATCAF1" -> { caf = true; "OK" }
            c.startsWith("ATSH") -> { header = c.substring(4).toInt(16); "OK" }
            c.startsWith("ATCRA") -> { cra = c.substring(5).toInt(16); "OK" }
            c == "ATAR" -> { cra = -1; "OK" }
            c.startsWith("AT") -> "OK"
            caf -> obd(c)
            else -> raw(c)
        }
        log += "$c -> ${r.replace("\r", " | ")}"
        return "$r\r\r>"
    }
}

private fun check(cond: Boolean, msg: String) = assertTrue(msg, cond)

class SimulatorTest {
@Test
fun fullFlowAgainstSimulatedCaddy() = runBlocking {
    val tmp = kotlin.io.path.createTempDirectory("caddylog").toFile()
    val dir = File(tmp, "session")
    val session = SessionWriter(dir)
    val link = SimLink()
    val elm = Elm327(link, session)
    val info = elm.initialize()
    check(info["Protokoll-Nr. (ATDPN)"] == "6", "init protocol")

    // frame parsing + iso-tp
    val frames = Elm327.parseFrames("7E8 10 14 49 02 01 57 56 31\n7E8 21 5A 5A 5A 32 4B 5A 41\n7E8 22 58 30 30 30 30 30 30")
    val msgs = IsoTp.reassemble(frames)
    check(msgs.size == 1 && msgs[0].second.size == 0x14, "isotp reassembly")
    check(Elm327.parseFrames("7E8064100BE3FA813")[0].data.size == 7, "frames without spaces")

    val obd = Obd(elm)
    val sup = obd.supported(1)
    check(sup[0x7E8]?.containsAll(listOf(0x0C, 0x51, 0x7F)) == true, "supported pids incl. 0x40 range: ${sup[0x7E8]}")
    val live = obd.readPids(listOf(0x0C, 0x0D, 0x05, 0x10, 0x04, 0x0F, 0x51, 0x7F))
    live.forEach { println("   ${it.channel} ${it.name} = ${it.value} ${it.unit} ${it.text ?: ""}") }
    check(live.first { it.channel == "OBD.7E8.0C" }.value == 1726.0, "rpm decoded via multi-pid")
    check(live.first { it.channel == "OBD.7E8.51" }.text == "Bifuel CNG", "fuel type")
    check(live.any { it.channel == "OBD.7E8.7F" }, "unknown pid raw")

    val all = mutableListOf<Reading>()
    val rep = obd.fullScan({ println("   [p] $it") }) { all += it; session.write(it) }
    session.writeJson("scan_obd.json", rep)
    check((rep["dtc_stored_03"] as Map<*, *>)["7E8"] == listOf("P0133"), "DTC P0133")
    check((rep["mode09"] as List<*>).any { (it as Map<*, *>)["ascii"] == "WV1ZZZ2KZAX000000" }, "VIN")
    check((rep["freeze_frame"] as List<*>).size == 3, "freeze frame")

    val vw = Vw(elm) { println("   [vw] $it") }
    val vrep = vw.scanAll(listOf(0x01, 0x17), 1..3, { println("   [p] $it") }) { all += it; session.write(it) }
    session.writeJson("scan_vw.json", vrep)
    val mods = vrep["modules"] as List<Map<String, Any?>>
    check(mods.size == 1 && vrep["no_answer"] == listOf("17"), "module scan found 01 only")
    val m = mods[0]
    println(Json.write(m).take(1500))
    check((m["dtcs"] as List<*>).size == 1, "VW DTC read")
    val groups = m["groups"] as List<Map<String, Any?>>
    check(groups.size == 3 && groups[1]["raw"] != null && groups[2]["negative"] != null, "groups incl. response-pending & negative")
    check(all.first { it.channel == "VW.01.G001.1" }.value == 1760.0, "rpm group 1 = ${all.first { it.channel == "VW.01.G001.1" }.value}")
    check(elm.mode == Elm327.Mode.OBD, "back in OBD mode")

    val spec = LivePoller.parseGroupSpec("01:1-3, 5; 17:1")
    check(spec == listOf(1 to 1, 1 to 2, 1 to 3, 1 to 5, 0x17 to 1), "group spec $spec")

    val poller = LivePoller(elm) { println("   [live] $it") }
    val cfg = LivePoller.Config(listOf(0x0C, 0x0D, 0x05), LivePoller.parseGroupSpec("01:1,2"))
    repeat(2) { i ->
        val rs = poller.cycle(cfg)
        rs.forEach(session::write)
        println("   cycle $i: ${rs.size} readings: " + rs.joinToString { "${it.channel}=${it.value ?: it.text}" })
        check(rs.any { it.channel == "OBD.7E8.0C" } && rs.any { it.channel == "VW.01.G002.1" } && rs.any { it.channel == "ADAPTER.VBAT" }, "live cycle $i mixed")
    }
    poller.stop()
    session.close()
    SessionWriter.zip(dir, File(tmp, "session.zip"))
    check(File(tmp, "session.zip").length() > 500, "zip export")
    println(File(dir, "live.csv").readLines().take(5).joinToString("\n"))
    println(File(dir, "raw.log").readLines().size.toString() + " raw lines")
    println("ALL OK")
}
}
