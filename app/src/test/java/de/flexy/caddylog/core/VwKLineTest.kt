package de.flexy.caddylog.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake Caddy: functional OBD answers from 0x10; physical KWP to 0x01 after ATPC/ATSH8101F1. */
private class KLineVwLink : ElmLink {
    val log = mutableListOf<String>()
    var header = "C133F1"; var closed = false; var initDelayMs = 0L
    override val isConnected = true
    override suspend fun exchange(command: String, timeoutMs: Long): String {
        log += "$command (t=$timeoutMs)"
        val c = command.uppercase().replace(" ", "").let { if (Regex("^01[0-9A-F]{2}1$").matches(it)) it.dropLast(1) else it }
        val r = when {
            c == "ATDPN" -> "A4"; c == "ATDP" -> "AUTO, ISO 14230-4 (KWP 5BAUD)"; c == "ATRV" -> "13.0V"
            c == "ATPC" -> { closed = true; "OK" }
            c.startsWith("ATSH") -> { header = c.drop(4); "OK" }
            c.startsWith("AT") -> "OK"
            closed && timeoutMs < 4000 -> throw java.io.IOException("Zeitüberschreitung")   // 5-baud init needs ~4 s
            header == "8101F1" -> { closed = false; when (c) {
                "1089" -> kwp(0x50, 0x89, src = 0x01)
                "1A9B" -> kwp(0x5A, 0x9B, 0x30, 0x33, 0x47, src = 0x01)
                "1802FF00" -> kwp(0x58, 0x00, src = 0x01)
                "2101" -> kwp(0x61, 0x01, 0x01, 0xC8, 0x1F, 0x05, 0x0A, 0x83, src = 0x01)  // rpm, coolant
                "2102" -> kwp(0x7F, 0x21, 0x78, src = 0x01) + "\r" + kwp(0x61, 0x02, 0x05, 0x0A, 0x96, src = 0x01)
                else -> kwp(0x7F, 0x21, 0x31, src = 0x01)
            } }
            header == "C133F1" -> { closed = false; when (c) {
                "0105" -> kwp(0x41, 0x05, 0x85)
                "0178" -> kwp(0x41, 0x78, 0x01, 0x1F, 0x40, 0, 0, 0, 0, 0, 0)  // EGT sensor 1 = 0x1F40/10-40 = 760 °C
                "017C" -> kwp(0x41, 0x7C, 0x03, 0x0F, 0xA0, 0x0E, 0x10, 0, 0, 0, 0)
                else -> "NO DATA"
            } }
            else -> "NO DATA"
        }
        return "$r\r\r>"
    }
}


class VwKLineTest {

    private fun setup(): Pair<KLineVwLink, Elm327> {
        val link = KLineVwLink(); val elm = Elm327(link)
        runBlocking { elm.initialize("4") }
        return link to elm
    }

    @Test fun exhaustGasTemperatureOnlyPresentSensors() = runBlocking {
        val (_, elm) = setup()
        val r = Obd(elm).readPid(0x78)
        assertEquals(760.0, r.value)
        assertEquals(1, r.readings.size)
        assertEquals(listOf(360.0, 320.0), Obd(elm).readPid(0x7C).readings.map { it.value })
    }

    @Test fun firstRequestAfterReinitWaitsForBusInit() = runBlocking {
        val (_, elm) = setup()
        elm.reinitBus()
        assertEquals(93.0, Obd(elm).readPid(0x05).value)
        assertFalse(elm.busInitPending)
    }

    @Test fun measuringBlocksOverKLineAndBackToObd() = runBlocking {
        val (link, elm) = setup()
        val got = mutableListOf<Reading>()
        val rep = Vw(elm).scanAll(listOf(0x01), 1..3, {}, { got += it })
        assertEquals(1, (rep["modules"] as List<*>).size)
        assertTrue(got.any { it.channel.startsWith("VW.01.G002") })      // answer after "response pending"
        assertTrue(link.log.any { it.startsWith("ATIIA01") })
        assertEquals(93.0, Obd(elm).readPid(0x05).value)                  // OBD works again afterwards
        assertTrue(link.log.any { it.startsWith("ATSHC133F1") })
    }
}
