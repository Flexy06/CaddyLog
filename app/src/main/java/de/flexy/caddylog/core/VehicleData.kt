package de.flexy.caddylog.core

/** Outcome of one request to the vehicle. Everything except OK means: there is NO fresh value. */
enum class ReadStatus(val label: String) {
    OK("OK"),
    NO_DATA("Steuergerät antwortet nicht (NO DATA)"),
    TIMEOUT("Zeitüberschreitung"),
    CHECKSUM_ERROR("Fehlerhafte Prüfsumme"),
    INVALID("Ungültige Antwort"),
    BUS_INIT("Bus-Initialisierung fehlgeschlagen"),
    NOT_SUPPORTED("Vom Steuergerät nicht unterstützt"),
    LINK_LOST("Verbindung zum Adapter verloren"),
}

data class PidResult(
    val pid: Int,
    val status: ReadStatus,
    val readings: List<Reading> = emptyList(),
    val raw: String = "",
    /** the adapter (re)initialised the K-Line during this request ("BUS INIT: ...OK") */
    val busInit: Boolean = false,
    val timeMs: Long = System.currentTimeMillis(),
) {
    /** main value of the PID (first decoded value of the first ECU) */
    val value: Double? get() = readings.firstOrNull { it.value != null }?.value
}

/** Signals the live monitor cares about. pid == null → not an OBD PID (adapter voltage). */
enum class Signal(val pid: Int?, val label: String, val unit: String, val csv: String, val defaultMin: Double, val defaultMax: Double) {
    COOLANT(0x05, "Kühlmittel", "°C", "coolant_c", 40.0, 130.0),
    RPM(0x0C, "Drehzahl", "1/min", "rpm", 0.0, 5000.0),
    SPEED(0x0D, "Geschwindigkeit", "km/h", "speed_kmh", 0.0, 160.0),
    LOAD(0x04, "Motorlast", "%", "load_pct", 0.0, 100.0),
    IAT(0x0F, "Ansaugluft", "°C", "iat_c", -10.0, 80.0),
    MAP(0x0B, "Saugrohrdruck", "kPa", "map_kpa", 20.0, 110.0),
    THROTTLE(0x11, "Drosselklappe", "%", "throttle_pct", 0.0, 100.0),
    ECU_VOLTAGE(0x42, "Steuergerätespannung", "V", "ecu_v", 10.0, 15.5),
    BATTERY(null, "Bordnetz (Adapter)", "V", "battery_v", 10.0, 15.5);

    companion object {
        fun forPid(pid: Int) = entries.firstOrNull { it.pid == pid }
    }
}

data class SignalValue(val value: Double, val timeMs: Long)

/**
 * Live state of one signal. [last] is the last value actually received; [lastStatus] tells whether the most recent
 * attempt succeeded. A value is only "fresh" if it was received recently – otherwise the UI must say so.
 */
data class SignalState(
    val signal: Signal,
    val last: SignalValue? = null,
    val lastStatus: ReadStatus? = null,
    val lastAttemptMs: Long = 0,
    val supported: Boolean? = null,
) {
    fun ageMs(now: Long): Long? = last?.let { now - it.timeMs }
    fun isFresh(now: Long, maxAgeMs: Long): Boolean = last != null && now - last.timeMs <= maxAgeMs
}

/** Converts a PID result into the signal value (if any) – the single place where PID → Signal mapping happens. */
fun PidResult.toSignalValue(): SignalValue? {
    if (status != ReadStatus.OK) return null
    val v = value ?: return null
    return SignalValue(v, timeMs)
}
