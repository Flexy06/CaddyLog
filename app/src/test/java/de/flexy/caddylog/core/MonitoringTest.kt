package de.flexy.caddylog.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoolantMonitorTest {

    private fun feed(m: CoolantMonitor, startMs: Long, temp: Double, seconds: Int, ctx: DriveContext = DriveContext()): Pair<Long, List<MonitorEvent>> {
        var t = startMs
        val ev = mutableListOf<MonitorEvent>()
        repeat(seconds) { ev += m.onSample(t, temp, ctx); t += 1000 }
        return t to ev
    }

    @Test fun absoluteThresholdsGoingUp() {
        val m = CoolantMonitor()
        var t = 0L
        val expected = listOf(
            95.0 to CoolantLevel.NORMAL, 105.0 to CoolantLevel.ELEVATED, 109.0 to CoolantLevel.ELEVATED,
            110.0 to CoolantLevel.WARNING, 115.0 to CoolantLevel.STRONG_WARNING, 120.0 to CoolantLevel.CRITICAL,
            125.0 to CoolantLevel.EXTREME,
        )
        for ((temp, level) in expected) {
            m.onSample(t, temp); t += 20_000  // slow steps → no spike filter
            assertEquals("at $temp °C", level, m.state.level)
        }
    }

    @Test fun escalationIsImmediate() {
        val m = CoolantMonitor()
        m.onSample(0, 100.0)
        val ev = m.onSample(1000, 111.0)
        assertEquals(CoolantLevel.WARNING, m.state.level)
        assertTrue(ev.any { it.kind == EventKind.LEVEL && it.level == CoolantLevel.WARNING })
    }

    @Test fun fallingBackUsesHysteresisAndDwell() {
        val m = CoolantMonitor()
        var t = feed(m, 0, 120.0, 5).first
        assertEquals(CoolantLevel.CRITICAL, m.state.level)

        // 114 °C: right after the drop it stays critical (dwell), after >10 s it is "starke Warnung" (hysteresis 5 → 110)
        m.onSample(t, 114.0); assertEquals(CoolantLevel.CRITICAL, m.state.level)
        t = feed(m, t + 1000, 114.0, 12).first
        assertEquals(CoolantLevel.STRONG_WARNING, m.state.level)

        // 105 °C → Warnung (warning threshold 110 - hysteresis 5 = 105 still holds)
        t = feed(m, t, 105.0, 12).first
        assertEquals(CoolantLevel.WARNING, m.state.level)

        // 95 °C → normal
        feed(m, t, 95.0, 12)
        assertEquals(CoolantLevel.NORMAL, m.state.level)
    }

    @Test fun noFlappingAroundThreshold() {
        val m = CoolantMonitor()
        var t = 0L
        val changes = mutableListOf<CoolantLevel>()
        // oscillates 109/111 – crosses the warning threshold every second
        m.onSample(t, 104.0); t += 20_000
        repeat(60) { i ->
            m.onSample(t, if (i % 2 == 0) 111.0 else 109.0).filter { it.kind == EventKind.LEVEL }.forEach { changes += it.level }
            t += 1000
        }
        assertEquals("only one change (into WARNING), no toggling", listOf(CoolantLevel.WARNING), changes)
    }

    @Test fun criticalStaysWhileJustBelow() {
        val m = CoolantMonitor()
        var t = 0L
        m.onSample(t, 110.0); t += 20_000
        val levels = mutableSetOf<CoolantLevel>()
        repeat(30) { i -> m.onSample(t, if (i % 2 == 0) 121.0 else 118.0); levels += m.state.level; t += 1000 }
        assertEquals(setOf(CoolantLevel.CRITICAL), levels)
    }

    @Test fun rapidRiseIsDetected() {
        val m = CoolantMonitor()
        val temps = listOf(92.0, 94.0, 96.0, 98.0, 100.0, 102.0, 104.0, 106.0)  // +14 °C in 21 s
        var t = 0L
        val ev = mutableListOf<MonitorEvent>()
        for (v in temps) { ev += m.onSample(t, v); t += 3000 }
        val rise = ev.firstOrNull { it.kind == EventKind.RAPID_RISE }
        assertNotNull(rise)
        assertTrue(rise!!.text, rise.text.contains("steigt ungewöhnlich schnell"))
        assertTrue(rise.text, rise.text.contains("innerhalb von"))
        assertTrue(m.state.rapidRise)
        assertEquals(1, ev.count { it.kind == EventKind.RAPID_RISE })
    }

    @Test fun warmUpIsNotRapidRise() {
        val m = CoolantMonitor()
        var t = 0L
        val ev = mutableListOf<MonitorEvent>()
        for (v in 20..90 step 2) { ev += m.onSample(t, v.toDouble()); t += 3000 }
        assertTrue(ev.none { it.kind == EventKind.RAPID_RISE })
    }

    @Test fun sustainedHighTemperature() {
        val m = CoolantMonitor()
        val (_, ev) = feed(m, 0, 112.0, 130)
        val s = ev.filter { it.kind == EventKind.SUSTAINED }
        assertEquals(1, s.size)
        assertTrue(s[0].text.contains("2 min"))
    }

    @Test fun shortSpikeIsNotSustained() {
        val m = CoolantMonitor()
        var t = feed(m, 0, 95.0, 30).first
        t = feed(m, t, 108.0, 3).first   // gradual enough? 95→108 jump is 13 °C, below spike filter
        t = feed(m, t, 112.0, 20).first
        val (_, ev) = feed(m, t, 96.0, 200)
        assertTrue(ev.none { it.kind == EventKind.SUSTAINED })
    }

    @Test fun implausibleSpikeNeedsConfirmation() {
        val m = CoolantMonitor()
        m.onSample(0, 90.0)
        val ev = m.onSample(1000, 130.0)  // +40 °C in 1 s
        assertTrue(ev.any { it.kind == EventKind.DATA_QUALITY })
        assertEquals(90.0, m.state.tempC!!, 0.0)
        m.onSample(2000, 131.0)            // confirmed by the next reading
        assertEquals(131.0, m.state.tempC!!, 0.0)
        assertEquals(CoolantLevel.EXTREME, m.state.level)
    }

    @Test fun staleValueIsMarked() {
        val m = CoolantMonitor()
        m.onSample(0, 95.0)
        assertTrue(m.onTick(3000, ReadStatus.OK).isEmpty())
        assertTrue(m.state.fresh)
        val ev = m.onTick(7000, ReadStatus.NO_DATA)
        assertFalse(m.state.fresh)
        assertTrue(ev.single().text.startsWith("Keine aktuelle Kühlmittelmessung"))
        assertTrue(m.onTick(8000, ReadStatus.NO_DATA).isEmpty()) // reported once
    }

    @Test fun observationAtStandstill() {
        val m = CoolantMonitor()
        val (_, ev) = feed(m, 0, 108.0, 40, DriveContext(rpm = 850.0, speed = 0.0, load = 18.0))
        val o = ev.filter { it.kind == EventKind.OBSERVATION }
        assertTrue(o.any { it.text.contains("Fahrzeugstillstand") })
        assertTrue("worded as observation, no diagnosis", o.none { it.text.contains("defekt", ignoreCase = true) })
    }

    @Test fun tripMaxIsTracked() {
        val m = CoolantMonitor()
        m.onSample(0, 90.0, DriveContext(rpm = 800.0, speed = 0.0))
        m.onSample(20_000, 101.0, DriveContext(rpm = 840.0, speed = 0.0))
        m.onSample(40_000, 97.0)
        assertEquals(101.0, m.state.tripMax!!, 0.0)
        assertEquals(20_000L, m.state.tripMaxTimeMs)
        assertEquals(840.0, m.state.tripMaxContext?.rpm!!, 0.0)
    }

    @Test fun invalidThresholdsAreRejected() {
        assertFalse(CoolantThresholds(warning = 100.0, elevated = 105.0).isValid())
        assertTrue(CoolantThresholds().isValid())
    }
}

class AlarmEngineTest {

    private fun state(level: CoolantLevel, temp: Double, fresh: Boolean = true, t: Long = 0) =
        CoolantState(level = level, tempC = temp, fresh = fresh, lastValidMs = t)

    @Test fun criticalTriggersSoundNotificationAndDialog() {
        val a = AlarmEngine()
        assertTrue(a.update(0, state(CoolantLevel.NORMAL, 95.0), emptyList()).isEmpty)
        val out = a.update(1000, state(CoolantLevel.CRITICAL, 121.0), emptyList())
        assertEquals(SoundKind.ALARM, out.sound)
        assertEquals(AlarmSeverity.CRITICAL, out.notification?.severity)
        assertTrue(out.notification!!.text.contains("121 °C"))
        assertNotNull(out.dialog)
    }

    @Test fun noAlarmOnEveryReading() {
        val a = AlarmEngine()
        a.update(0, state(CoolantLevel.CRITICAL, 121.0), emptyList())
        var sounds = 0
        for (t in 1000L..59_000L step 1000) if (a.update(t, state(CoolantLevel.CRITICAL, 121.0), emptyList()).sound != null) sounds++
        assertEquals(0, sounds)
        assertEquals(SoundKind.ALARM, a.update(60_000, state(CoolantLevel.CRITICAL, 121.0), emptyList()).sound)
    }

    @Test fun acknowledgeDelaysRepeat() {
        val a = AlarmEngine()
        a.update(0, state(CoolantLevel.CRITICAL, 121.0), emptyList())
        a.acknowledge(5_000, 121.0)
        assertNull(a.update(70_000, state(CoolantLevel.CRITICAL, 121.0), emptyList()).sound)
        assertEquals(SoundKind.ALARM, a.update(185_000, state(CoolantLevel.CRITICAL, 121.0), emptyList()).sound)
    }

    @Test fun furtherRiseAfterAcknowledgeAlarmsAgain() {
        val a = AlarmEngine()
        a.update(0, state(CoolantLevel.CRITICAL, 121.0), emptyList())
        a.acknowledge(5_000, 121.0)
        assertNull(a.update(10_000, state(CoolantLevel.CRITICAL, 122.0), emptyList()).sound)
        assertEquals(SoundKind.ALARM, a.update(12_000, state(CoolantLevel.CRITICAL, 124.0), emptyList()).sound)
    }

    @Test fun escalationToExtremeAlarmsImmediately() {
        val a = AlarmEngine()
        a.update(0, state(CoolantLevel.CRITICAL, 121.0), emptyList())
        assertEquals(SoundKind.ALARM, a.update(5_000, state(CoolantLevel.EXTREME, 126.0), emptyList()).sound)
    }

    @Test fun backToNormalDismissesDialog() {
        val a = AlarmEngine()
        a.update(0, state(CoolantLevel.CRITICAL, 121.0), emptyList())
        val down = a.update(30_000, state(CoolantLevel.STRONG_WARNING, 113.0), emptyList())
        assertTrue(down.dismissDialog)
        assertNull(down.sound)
        assertNull(a.activeDialog)
        val normal = a.update(90_000, state(CoolantLevel.NORMAL, 95.0), emptyList())
        assertEquals("Kühlmitteltemperatur wieder normal", normal.notification?.title)
    }

    @Test fun warningChimesOnce() {
        val a = AlarmEngine()
        a.update(0, state(CoolantLevel.NORMAL, 100.0), emptyList())
        assertEquals(SoundKind.CHIME, a.update(1000, state(CoolantLevel.WARNING, 110.0), emptyList()).sound)
        assertNull(a.update(2000, state(CoolantLevel.WARNING, 111.0), emptyList()).sound)
    }

    @Test fun soundCanBeDisabled() {
        val a = AlarmEngine(AlarmConfig(soundEnabled = false))
        val out = a.update(0, state(CoolantLevel.CRITICAL, 121.0), emptyList())
        assertNull(out.sound)
        assertNotNull(out.notification)
    }

    @Test fun staleWhileHotIsReported() {
        val a = AlarmEngine()
        a.update(0, state(CoolantLevel.WARNING, 112.0), emptyList())
        val out = a.update(8000, state(CoolantLevel.WARNING, 112.0, fresh = false), emptyList())
        assertEquals("Keine aktuelle Kühlmittelmessung", out.notification?.title)
    }

    @Test fun fullChainSensorToAlarm() {
        val m = CoolantMonitor()
        val a = AlarmEngine()
        var t = 0L
        val sounds = mutableListOf<SoundKind>()
        for (v in listOf(96.0, 100.0, 104.0, 108.0, 112.0, 116.0, 119.0, 121.0, 122.0)) {
            val ev = m.onSample(t, v)
            a.update(t, m.state, ev).sound?.let { sounds += it }
            t += 5000
        }
        assertTrue(sounds.contains(SoundKind.ALARM))
        assertNotNull(a.activeDialog)
    }
}

class LiveSamplerTest {

    private class FakeCar(var requestMs: Long) {
        var now = 0L
        val requested = mutableListOf<Int>()
        suspend fun read(pid: Int): PidResult {
            now += requestMs; requested += pid
            return PidResult(pid, ReadStatus.OK, listOf(Reading("OBD.011.%02X".format(pid), "x", 90.0, "")))
        }
    }

    @Test fun slowVehicleLowersRate() = runBlocking {
        val car = FakeCar(150)
        val s = LiveSampler(car::read, clock = { car.now }, sleep = { car.now += it })
        val cfg = LiveSampler.Config(targetHz = 5.0)
        val events = mutableListOf<String>()
        repeat(12) { events += s.tick(cfg).events }
        assertEquals(2.0, s.effectiveHz, 0.0)
        assertTrue(events.single().contains("reduziert"))
    }

    @Test fun fastVehicleKeepsRateAndReadsManyPids() = runBlocking {
        val car = FakeCar(60)
        val s = LiveSampler(car::read, clock = { car.now }, sleep = { car.now += it })
        val cfg = LiveSampler.Config(targetHz = 1.0, otherPids = listOf(0x01, 0x06, 0x07))
        val t = s.tick(cfg)
        assertEquals(1.0, s.effectiveHz, 0.0)
        assertTrue("several secondary pids per 1 s tick", t.results.size >= 6)
        assertEquals(0x05, car.requested.first())
        assertEquals(1000L, car.now) // waited for the rest of the period
    }

    @Test fun coolantIsReadEveryTick() = runBlocking {
        val car = FakeCar(100)
        val s = LiveSampler(car::read, clock = { car.now }, sleep = { car.now += it })
        val cfg = LiveSampler.Config(targetHz = 2.0, otherPids = listOf(0x01))
        repeat(10) { s.tick(cfg) }
        assertEquals(10, car.requested.count { it == 0x05 })
    }

    @Test fun secondaryPidsRotateWithPriority() = runBlocking {
        val car = FakeCar(100)
        val s = LiveSampler(car::read, clock = { car.now }, sleep = { car.now += it })
        val cfg = LiveSampler.Config(targetHz = 5.0, priorityPids = listOf(0x0C, 0x0D), otherPids = listOf(0x33))
        repeat(8) { s.tick(cfg) }
        val secondary = car.requested.filter { it != 0x05 }
        assertTrue(secondary.count { it == 0x0C } >= 2)
        assertTrue(secondary.count { it == 0x33 } >= 1)
        assertTrue(secondary.count { it == 0x33 } < secondary.count { it == 0x0C } + secondary.count { it == 0x0D })
    }

    @Test fun rateSteps() {
        assertEquals(2.0, LiveSampler.nextLower(5.0)!!, 0.0)
        assertEquals(1.0, LiveSampler.nextLower(2.0)!!, 0.0)
        assertEquals(0.5, LiveSampler.nextLower(1.0)!!, 0.0)
        assertNull(LiveSampler.nextLower(0.5))
    }
}

class TripAnalysisTest {

    private fun point(t: Long, coolant: Double, rpm: Double = 840.0, speed: Double = 0.0, level: CoolantLevel = CoolantLevel.NORMAL): TripPoint {
        val v = DoubleArray(Signal.entries.size) { Double.NaN }
        v[Signal.COOLANT.ordinal] = coolant; v[Signal.RPM.ordinal] = rpm; v[Signal.SPEED.ordinal] = speed
        return TripPoint(t, v, true, level)
    }

    @Test fun summaryFromMeasuredData() {
        val pts = mutableListOf<TripPoint>()
        var t = 0L
        repeat(60) { pts += point(t, 94.0, speed = 50.0); t += 1000 }
        // 94 → 123 within 90 s
        for (i in 0..90) { pts += point(t, 94.0 + 29.0 * i / 90.0); t += 1000 }
        repeat(138) { pts += point(t, 123.0) ; t += 1000 }
        val events = listOf(
            MonitorEvent(70_000, EventKind.LEVEL, CoolantLevel.WARNING, ""),
            MonitorEvent(100_000, EventKind.LEVEL, CoolantLevel.CRITICAL, ""),
        )
        val s = TripAnalysis.summarize(0, t, pts, events, CoolantThresholds())
        assertEquals(123.0, s.coolantMax!!, 0.001)
        assertEquals(840.0, s.rpmAtMax!!, 0.0)
        assertEquals(0.0, s.speedAtMax!!, 0.0)
        assertEquals(1, s.warnings)
        assertEquals(1, s.criticals)
        assertNotNull(s.fastestRise)
        assertTrue(s.lines.joinToString("\n"), s.lines.any { it.startsWith("Höchste Temperatur: 123 °C bei 840 1/min und 0 km/h") })
        assertTrue(s.lines.any { it.startsWith("Die Temperatur stieg innerhalb von") && it.endsWith("auf 123 °C.") })
        assertTrue(s.lines.any { it.startsWith("Kühlmitteltemperatur überschritt 115 °C für") })
        assertTrue(s.lines.none { it.contains("defekt", ignoreCase = true) })
    }

    @Test fun tripFilesRoundTrip() {
        val dir = kotlin.io.path.createTempDirectory("trip").toFile()
        val w = TripWriter(dir)
        w.write(point(1000, 95.5, level = CoolantLevel.NORMAL))
        w.write(MonitorEvent(1000, EventKind.OBSERVATION, CoolantLevel.WARNING, "Text, mit \"Komma\"", 111.0, DriveContext(800.0, 0.0, 17.0)))
        w.close()
        val (pts, evs) = TripWriter.load(dir)
        assertEquals(95.5, pts.single()[Signal.COOLANT], 0.0)
        assertEquals("Text, mit \"Komma\"", evs.single().text)
        assertEquals(17.0, evs.single().context.load!!, 0.0)
    }
}
