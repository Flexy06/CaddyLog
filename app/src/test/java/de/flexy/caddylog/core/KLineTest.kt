package de.flexy.caddylog.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Fake ELM327 on a K-Line car (ISO 14230-4 KWP, engine ECU address 0x11). */
private class KLineLink(var responder: (String) -> String) : ElmLink {
    var connected = true
    override val isConnected get() = connected
    override suspend fun exchange(command: String, timeoutMs: Long): String {
        // adapter accepts the response-count hint ("0105" + "1")
        val c = command.uppercase().replace(" ", "").let { if (Regex("^01[0-9A-F]{2}1$").matches(it)) it.dropLast(1) else it }
        val r = when {
            c == "ATDPN" -> "A5"
            c == "ATDP" -> "AUTO, ISO 14230-4 (KWP FAST)"
            c == "ATRV" -> "13.9V"
            c.startsWith("AT") -> "OK"
            else -> responder(c)
        }
        return "$r\r\r>"
    }
}

/** builds a correct KWP response line incl. checksum */
fun kwp(vararg data: Int, src: Int = 0x11): String {
    val bytes = listOf(0x80 or data.size, 0xF1, src) + data.toList()
    return (bytes + KLine.checksum(bytes)).joinToString(" ") { "%02X".format(it) }
}

class KLineTest {

    @Test fun checksumIsSumModulo256() {
        assertEquals(0x49, KLine.checksum(listOf(0x83, 0xF1, 0x11, 0x41, 0x05, 0x7E)))
        assertEquals(0x00, KLine.checksum(listOf(0xFF, 0x01)))
    }

    @Test fun parsesKwpHeaderDataAndChecksum() {
        val f = KLine.parse("83 F1 11 41 05 7E 49").frames.single()
        assertEquals(0x11, f.source)
        assertEquals(0xF1, f.target)
        assertEquals(listOf(0x41, 0x05, 0x7E), f.data.toList())
        assertEquals(true, f.checksumOk)
    }

    @Test fun detectsWrongChecksum() {
        val r = KLine.parse("83 F1 11 41 05 7E 48")
        assertEquals(false, r.frames.single().checksumOk)
        assertEquals(1, r.checksumErrors)
    }

    @Test fun parsesWithoutSpacesAndIgnoresBusInitLine() {
        val r = KLine.parse("BUS INIT: ...OK\n83F11141057E49")
        assertEquals(1, r.frames.size)
        assertEquals(0x7E, r.frames[0].data[2])
    }

    @Test fun parsesIso9141Header() {
        val bytes = listOf(0x48, 0x6B, 0x10, 0x41, 0x05, 0x83)
        val line = (bytes + KLine.checksum(bytes)).joinToString(" ") { "%02X".format(it) }
        val f = KLine.parse(line).frames.single()
        assertEquals(0x10, f.source)
        assertEquals(true, f.checksumOk)
        assertEquals(listOf(0x41, 0x05, 0x83), f.data.toList())
    }

    @Test fun lengthMismatchIsInvalid() {
        val r = KLine.parse("86 F1 11 41 05 7E 49")   // says 6 data bytes, has 3 + cs
        assertTrue(r.frames.isEmpty())
        assertEquals(1, r.invalidLines.size)
    }

    @Test fun dtcFramesAreMerged() {
        val p = KLine.toPayloads(KLine.parse(kwp(0x43, 0x01, 0x33, 0x00, 0x00, 0x00, 0x00)).frames).single()
        assertEquals(listOf(0x43, 0x01, 0x33, 0, 0, 0, 0), p.second.toList())
    }

    private fun elmOnKLine(responder: (String) -> String): Pair<Elm327, KLineLink> = runBlocking {
        val link = KLineLink(responder)
        val elm = Elm327(link)
        elm.initialize("5")
        elm to link
    }

    @Test fun initDetectsKLine() {
        val (elm, _) = elmOnKLine { c -> if (c == "0100") kwp(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13) else "NO DATA" }
        assertEquals(Bus.KLINE, elm.bus)
        assertEquals("5", elm.protocolNumber)
    }

    @Test fun coolant0105_7E_is86C() = runBlocking {
        val (elm, _) = elmOnKLine { c -> if (c == "0105") kwp(0x41, 0x05, 0x7E) else kwp(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13) }
        val r = Obd(elm).readPid(0x05)
        assertEquals(ReadStatus.OK, r.status)
        assertEquals(86.0, r.value!!, 0.001)
        assertEquals("OBD.011.05", r.readings.first().channel)
    }

    @Test fun coolant0105_83_is91C() = runBlocking {
        val (elm, _) = elmOnKLine { c -> if (c == "0105") "BUS INIT: ...OK\r" + kwp(0x41, 0x05, 0x83) else kwp(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13) }
        val r = Obd(elm).readPid(0x05)
        assertEquals(91.0, r.value!!, 0.001)
        assertTrue("BUS INIT OK must be recognised as re-init, not as error", r.busInit)
    }

    @Test fun intakeAir010F() = runBlocking {
        val (elm, _) = elmOnKLine { c -> if (c == "010F") kwp(0x41, 0x0F, 0x4C) else kwp(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13) }
        assertEquals(36.0, Obd(elm).readPid(0x0F).value!!, 0.001)
    }

    @Test fun missingResponseIsNoDataNotAValue() = runBlocking {
        val (elm, _) = elmOnKLine { c -> if (c == "0105") "NO DATA" else kwp(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13) }
        val r = Obd(elm).readPid(0x05)
        assertEquals(ReadStatus.NO_DATA, r.status)
        assertNull(r.value)
        assertNull(r.toSignalValue())
    }

    @Test fun wrongChecksumIsRejected() = runBlocking {
        val (elm, _) = elmOnKLine { c -> if (c == "0105") "83 F1 11 41 05 7E 00" else kwp(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13) }
        val r = Obd(elm).readPid(0x05)
        assertEquals(ReadStatus.CHECKSUM_ERROR, r.status)
        assertNull(r.value)
    }

    @Test fun busInitErrorIsClassified() = runBlocking {
        val (elm, _) = elmOnKLine { c -> if (c == "0105") "BUS INIT: ...ERROR" else kwp(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13) }
        assertEquals(ReadStatus.BUS_INIT, Obd(elm).readPid(0x05).status)
    }

    @Test fun timeoutAndLinkLost() = runBlocking {
        var fail = false
        val link = object : ElmLink {
            var up = true
            override val isConnected get() = up
            override suspend fun exchange(command: String, timeoutMs: Long): String {
                if (fail && command.startsWith("0105")) throw IOException("timeout")
                return when (command) { "ATDPN" -> "A5\r>"; "0100" -> kwp(0x41, 0, 0xBE, 0x3F, 0xA8, 0x13) + "\r>"; else -> "OK\r>" }
            }
        }
        val elm = Elm327(link); elm.initialize()
        fail = true
        assertEquals(ReadStatus.TIMEOUT, Obd(elm).readPid(0x05).status)
        link.up = false
        assertEquals(ReadStatus.LINK_LOST, Obd(elm).readPid(0x05).status)
    }

    @Test fun noTp20OnKLine() {
        val (elm, _) = elmOnKLine { kwp(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13) }
        val e = runCatching { runBlocking { elm.ensureRawCan() } }.exceptionOrNull()
        assertNotNull("TP2.0 must be refused on K-Line", e)
        val opened = runBlocking { runCatching { Tp20(elm, 1).open() }.getOrDefault(false) }
        assertFalse(opened)
    }

    @Test fun kLineReadsOnePidPerRequest() = runBlocking {
        val sent = mutableListOf<String>()
        val (elm, _) = elmOnKLine { c ->
            sent += c
            when (c) {
                "010C" -> kwp(0x41, 0x0C, 0x0D, 0x48)
                "010D" -> kwp(0x41, 0x0D, 0x00)
                else -> kwp(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13)
            }
        }
        sent.clear()
        val rs = Obd(elm).readPids(listOf(0x0C, 0x0D))
        assertEquals(listOf("010C", "010D"), sent)
        assertEquals(850.0, rs.first { it.channel.endsWith(".0C") }.value!!, 0.01)
    }

    @Test fun busFromDpn() {
        assertEquals(Bus.KLINE, Bus.fromDpn("A5"))
        assertEquals(Bus.KLINE, Bus.fromDpn("3"))
        assertEquals(Bus.CAN, Bus.fromDpn("6"))
        assertEquals(Bus.CAN, Bus.fromDpn("A6"))
        assertEquals(Bus.UNKNOWN, Bus.fromDpn("?"))
    }
}

class ReadOnlyGuardTest {
    @Test fun readServicesAllowed() {
        for (c in listOf("0105", "01 0C", "03", "0902", "ATRV", "ATDPN", "ATSP5")) assertNull(c, ReadOnlyGuard.check(c))
    }

    @Test fun writeAndControlServicesBlocked() {
        for (c in listOf("04", "08", "2E F1 90 00", "3101", "14FF00", "1A9B", "ATPP 0C SV 23")) assertNotNull(c, ReadOnlyGuard.check(c))
    }
}

class ResponseCountTest {
    @Test fun usesResponseCountHintOnKLineAndFallsBack() = runBlocking {
        val sent = mutableListOf<String>()
        var acceptsHint = true
        val link = object : ElmLink {
            override suspend fun exchange(command: String, timeoutMs: Long): String {
                sent += command
                val r = when {
                    command == "ATDPN" -> "A5"
                    command.startsWith("AT") -> "OK"
                    command == "0100" -> kwp(0x41, 0, 0xBE, 0x3F, 0xA8, 0x13)
                    command == "01051" -> if (acceptsHint) kwp(0x41, 0x05, 0x7E) else "?"
                    command == "0105" -> kwp(0x41, 0x05, 0x83)
                    else -> "NO DATA"
                }
                return "$r\r>"
            }
        }
        val elm = Elm327(link); elm.initialize()
        assertEquals(86.0, Obd(elm).readPid(0x05).value!!, 0.0)
        assertEquals(true, elm.responseCountSupported)

        val elm2 = Elm327(link); elm2.initialize(); acceptsHint = false
        assertEquals(91.0, Obd(elm2).readPid(0x05).value!!, 0.0)   // fell back to plain "0105"
        assertEquals(false, elm2.responseCountSupported)
        sent.clear()
        Obd(elm2).readPid(0x05)
        assertEquals(listOf("0105"), sent)
    }
}
