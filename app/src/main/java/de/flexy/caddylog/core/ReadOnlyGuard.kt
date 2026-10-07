package de.flexy.caddylog.core

/**
 * The app only READS from the vehicle. This guard is applied to manually typed console commands:
 * adapter commands (AT…) and OBD2 read services are allowed; everything that could clear, write,
 * control actuators or code an ECU is refused.
 */
object ReadOnlyGuard {
    /** OBD2 read services: current data, freeze frame, DTCs, O2 tests, monitor tests, pending DTCs, vehicle info, permanent DTCs */
    private val ALLOWED_OBD = setOf(0x01, 0x02, 0x03, 0x05, 0x06, 0x07, 0x09, 0x0A)

    fun check(command: String): String? {
        val c = command.trim().uppercase().replace(" ", "")
        if (c.isEmpty()) return "Leerer Befehl"
        if (c.startsWith("AT") || c.startsWith("ST")) {
            // ATMA floods the BLE link, ATPP/ATSD write adapter EEPROM – not needed
            if (c.startsWith("ATPP") || c.startsWith("ATSD")) return "Adapter-Konfiguration wird nicht verändert"
            return null
        }
        if (!c.all { it.isDigit() || it in 'A'..'F' } || c.length < 2 || c.length % 2 != 0) return "Kein gültiger Hex-Befehl"
        val service = c.substring(0, 2).toInt(16)
        if (service !in ALLOWED_OBD) return "Service %02X ist nicht erlaubt – die App sendet nur lesende Anfragen (z. B. %s löschen/schreiben/steuern ist gesperrt)"
            .format(service, if (service == 0x04) "Fehlerspeicher" else "Steuergeräte")
        return null
    }
}
