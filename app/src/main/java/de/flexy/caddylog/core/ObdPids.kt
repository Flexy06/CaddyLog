package de.flexy.caddylog.core

/** One measured value. channel is a stable id (used as CSV key), raw always holds the undecoded bytes. */
data class Reading(
    val channel: String,
    val name: String,
    val value: Double?,
    val unit: String,
    val text: String? = null,
    val raw: String = "",
    val timeMs: Long = System.currentTimeMillis(),
)

data class PidValue(val suffix: String, val name: String, val value: Double?, val unit: String, val text: String? = null)

class PidDef(val pid: Int, val len: Int, val name: String, val decode: (IntArray) -> List<PidValue>)

/** SAE J1979 mode 01 PIDs (also used for mode 02 freeze frames). */
object ObdPids {
    private fun one(name: String, unit: String, f: (IntArray) -> Double) =
        { d: IntArray -> listOf(PidValue("", name, f(d), unit)) }

    private fun ab(d: IntArray) = d[0] * 256 + d[1]
    private fun cd(d: IntArray) = d[2] * 256 + d[3]
    private fun s16(v: Int) = if (v >= 32768) v - 65536 else v
    private fun ab2(d: IntArray, o: Int) = d[o] * 256 + d[o + 1]

    /** multi-sensor PIDs: bit i of byte A says whether sensor i exists (null → leave the value out) */
    private fun bit(d: IntArray, i: Int): Unit? = if ((d[0] shr i) and 1 == 1) Unit else null

    /** byte A = sensor bitmask, then [n] 2-byte values */
    private fun sensors(d: IntArray, n: Int, name: String, unit: String, f: (Int) -> Double) =
        (0 until n).mapNotNull { i -> bit(d, i)?.let { PidValue(".s${i + 1}", "$name${i + 1}", f(ab2(d, 1 + 2 * i)), unit) } }

    /** byte A = sensor bitmask, then [n] 1-byte values */
    private fun sensors1(d: IntArray, n: Int, name: String, unit: String, f: (Int) -> Double) =
        (0 until n).mapNotNull { i -> bit(d, i)?.let { PidValue(".s${i + 1}", "$name ${i + 1}", f(d[1 + i]), unit) } }

    private fun turboTemps(d: IntArray) = listOfNotNull(
        bit(d, 0)?.let { PidValue(".v_ein", "Verdichter Eingang", d[1] - 40.0, "°C") },
        bit(d, 1)?.let { PidValue(".v_aus", "Verdichter Ausgang", d[2] - 40.0, "°C") },
        bit(d, 2)?.let { PidValue(".t_ein", "Turbine Eingang", ab2(d, 3) / 10.0 - 40, "°C") },
        bit(d, 3)?.let { PidValue(".t_aus", "Turbine Ausgang", ab2(d, 5) / 10.0 - 40, "°C") },
    )

    private fun dpfPressure(d: IntArray) = listOfNotNull(
        bit(d, 0)?.let { PidValue(".delta", "Differenzdruck", ab2(d, 1) * 0.01, "kPa") },
        bit(d, 1)?.let { PidValue(".ein", "Eingangsdruck", ab2(d, 3) * 0.01, "kPa") },
        bit(d, 2)?.let { PidValue(".aus", "Ausgangsdruck", ab2(d, 5) * 0.01, "kPa") },
    )

    private val FUEL_SYSTEM = mapOf(
        0 to "aus", 1 to "Open loop (zu kalt)", 2 to "Closed loop (Lambda)", 4 to "Open loop (Last/Schub)",
        8 to "Open loop (Fehler)", 16 to "Closed loop (Fehler Lambda)"
    )
    val FUEL_TYPES = mapOf(
        0 to "n/a", 1 to "Benzin", 2 to "Methanol", 3 to "Ethanol", 4 to "Diesel", 5 to "LPG", 6 to "CNG",
        7 to "Propan", 8 to "Elektro", 9 to "Bifuel Benzin", 10 to "Bifuel Methanol", 11 to "Bifuel Ethanol",
        12 to "Bifuel LPG", 13 to "Bifuel CNG", 14 to "Bifuel Propan", 15 to "Bifuel Elektro",
        16 to "Bifuel Benzin/Elektro", 17 to "Hybrid Benzin", 18 to "Hybrid Ethanol", 19 to "Hybrid Diesel",
        20 to "Hybrid Elektro", 21 to "Hybrid gemischt", 22 to "Hybrid regenerativ", 23 to "Bifuel Diesel"
    )

    val defs: Map<Int, PidDef> = buildList {
        fun add(pid: Int, len: Int, name: String, dec: (IntArray) -> List<PidValue>) = add(PidDef(pid, len, name, dec))
        for (b in listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0))
            add(b, 4, "Unterstützte PIDs %02X-%02X".format(b + 1, b + 0x20)) { d -> listOf(PidValue("", "Bitmaske", null, "", d.toHex(""))) }
        add(0x01, 4, "Monitorstatus seit Löschen") { d ->
            listOf(
                PidValue(".mil", "MIL (Motorkontrollleuchte)", ((d[0] shr 7) and 1).toDouble(), ""),
                PidValue(".dtc", "Anzahl Fehler", (d[0] and 0x7F).toDouble(), ""),
                PidValue(".mon", "Monitore", null, "", d.toHex(""))
            )
        }
        add(0x02, 2, "Fehler für Freeze Frame") { d -> listOf(PidValue("", "DTC", null, "", Obd.dtcString(d[0], d[1]))) }
        add(0x03, 2, "Kraftstoffsystem-Status") { d ->
            listOf(
                PidValue(".1", "System 1", d[0].toDouble(), "", FUEL_SYSTEM[d[0]]),
                PidValue(".2", "System 2", d[1].toDouble(), "", FUEL_SYSTEM[d[1]])
            )
        }
        add(0x04, 1, "Motorlast berechnet", one("Last", "%") { it[0] * 100.0 / 255 })
        add(0x05, 1, "Kühlmitteltemperatur", one("Kühlmittel", "°C") { it[0] - 40.0 })
        add(0x06, 1, "Kurzzeit-Trim Bank 1", one("STFT B1", "%") { (it[0] - 128) * 100.0 / 128 })
        add(0x07, 1, "Langzeit-Trim Bank 1", one("LTFT B1", "%") { (it[0] - 128) * 100.0 / 128 })
        add(0x08, 1, "Kurzzeit-Trim Bank 2", one("STFT B2", "%") { (it[0] - 128) * 100.0 / 128 })
        add(0x09, 1, "Langzeit-Trim Bank 2", one("LTFT B2", "%") { (it[0] - 128) * 100.0 / 128 })
        add(0x0A, 1, "Kraftstoffdruck", one("Kraftstoffdruck", "kPa") { it[0] * 3.0 })
        add(0x0B, 1, "Saugrohrdruck", one("MAP", "kPa") { it[0].toDouble() })
        add(0x0C, 2, "Drehzahl", one("Drehzahl", "1/min") { ab(it) / 4.0 })
        add(0x0D, 1, "Geschwindigkeit", one("Geschwindigkeit", "km/h") { it[0].toDouble() })
        add(0x0E, 1, "Zündzeitpunkt", one("Zündwinkel", "° v. OT") { it[0] / 2.0 - 64 })
        add(0x0F, 1, "Ansauglufttemperatur", one("Ansaugluft", "°C") { it[0] - 40.0 })
        add(0x10, 2, "Luftmasse (MAF)", one("MAF", "g/s") { ab(it) / 100.0 })
        add(0x11, 1, "Drosselklappe", one("Drosselklappe", "%") { it[0] * 100.0 / 255 })
        add(0x12, 1, "Sekundärluft-Status", one("Sekundärluft", "") { it[0].toDouble() })
        add(0x13, 1, "Lambdasonden vorhanden", one("Sonden (Bitmaske)", "") { it[0].toDouble() })
        for (i in 0..7) add(0x14 + i, 2, "Lambdasonde ${i + 1} (Spannung)") { d ->
            listOf(
                PidValue(".v", "Sonde ${i + 1} Spannung", d[0] / 200.0, "V"),
                PidValue(".trim", "Sonde ${i + 1} Trim", if (d[1] == 0xFF) null else (d[1] - 128) * 100.0 / 128, "%")
            )
        }
        add(0x1C, 1, "OBD-Standard", one("OBD-Standard", "") { it[0].toDouble() })
        add(0x1D, 1, "Lambdasonden vorhanden (4 Bänke)", one("Sonden", "") { it[0].toDouble() })
        add(0x1E, 1, "Nebenabtrieb-Status", one("PTO", "") { it[0].toDouble() })
        add(0x1F, 2, "Laufzeit seit Motorstart", one("Laufzeit", "s") { ab(it).toDouble() })
        add(0x21, 2, "Strecke mit MIL an", one("Strecke MIL", "km") { ab(it).toDouble() })
        add(0x22, 2, "Kraftstoffrail-Druck (rel.)", one("Raildruck rel.", "kPa") { ab(it) * 0.079 })
        add(0x23, 2, "Kraftstoffrail-Druck (Gauge)", one("Raildruck", "kPa") { ab(it) * 10.0 })
        for (i in 0..7) add(0x24 + i, 4, "Lambdasonde ${i + 1} (Breitband, Spannung)") { d ->
            listOf(
                PidValue(".lambda", "Sonde ${i + 1} Lambda", ab(d) * 2.0 / 65536, ""),
                PidValue(".v", "Sonde ${i + 1} Spannung", cd(d) * 8.0 / 65536, "V")
            )
        }
        add(0x2C, 1, "AGR soll", one("AGR soll", "%") { it[0] * 100.0 / 255 })
        add(0x2D, 1, "AGR Fehler", one("AGR Fehler", "%") { (it[0] - 128) * 100.0 / 128 })
        add(0x2E, 1, "Tankentlüftung soll", one("Tankentlüftung", "%") { it[0] * 100.0 / 255 })
        add(0x2F, 1, "Tankfüllstand", one("Tankfüllstand", "%") { it[0] * 100.0 / 255 })
        add(0x30, 1, "Warmläufe seit Löschen", one("Warmläufe", "") { it[0].toDouble() })
        add(0x31, 2, "Strecke seit Löschen", one("Strecke seit Löschen", "km") { ab(it).toDouble() })
        add(0x32, 2, "Tankdampfdruck", one("Dampfdruck", "Pa") { s16(ab(it)) / 4.0 })
        add(0x33, 1, "Luftdruck (Umgebung)", one("Barometer", "kPa") { it[0].toDouble() })
        for (i in 0..7) add(0x34 + i, 4, "Lambdasonde ${i + 1} (Breitband, Strom)") { d ->
            listOf(
                PidValue(".lambda", "Sonde ${i + 1} Lambda", ab(d) * 2.0 / 65536, ""),
                PidValue(".ma", "Sonde ${i + 1} Strom", cd(d) / 256.0 - 128, "mA")
            )
        }
        for ((i, n) in listOf("B1 S1", "B2 S1", "B1 S2", "B2 S2").withIndex())
            add(0x3C + i, 2, "Katalysatortemperatur $n", one("Kat-Temp $n", "°C") { ab(it) / 10.0 - 40 })
        add(0x41, 4, "Monitorstatus dieser Zyklus") { d -> listOf(PidValue("", "Monitore", null, "", d.toHex(""))) }
        add(0x42, 2, "Steuergeräte-Spannung", one("Spannung SG", "V") { ab(it) / 1000.0 })
        add(0x43, 2, "Absolute Last", one("Absolute Last", "%") { ab(it) * 100.0 / 255 })
        add(0x44, 2, "Lambda soll", one("Lambda soll", "") { ab(it) * 2.0 / 65536 })
        add(0x45, 1, "Drosselklappe relativ", one("DK relativ", "%") { it[0] * 100.0 / 255 })
        add(0x46, 1, "Umgebungstemperatur", one("Außentemperatur", "°C") { it[0] - 40.0 })
        add(0x47, 1, "Drosselklappe B", one("DK B", "%") { it[0] * 100.0 / 255 })
        add(0x48, 1, "Drosselklappe C", one("DK C", "%") { it[0] * 100.0 / 255 })
        add(0x49, 1, "Gaspedal D", one("Pedal D", "%") { it[0] * 100.0 / 255 })
        add(0x4A, 1, "Gaspedal E", one("Pedal E", "%") { it[0] * 100.0 / 255 })
        add(0x4B, 1, "Gaspedal F", one("Pedal F", "%") { it[0] * 100.0 / 255 })
        add(0x4C, 1, "Drosselklappe soll", one("DK soll", "%") { it[0] * 100.0 / 255 })
        add(0x4D, 2, "Zeit mit MIL an", one("Zeit MIL", "min") { ab(it).toDouble() })
        add(0x4E, 2, "Zeit seit Löschen", one("Zeit seit Löschen", "min") { ab(it).toDouble() })
        add(0x4F, 4, "Maximalwerte (Lambda, V, mA, MAP)") { d ->
            listOf(
                PidValue(".lambda", "Max Lambda", d[0].toDouble(), ""), PidValue(".v", "Max Sondenspannung", d[1].toDouble(), "V"),
                PidValue(".ma", "Max Sondenstrom", d[2].toDouble(), "mA"), PidValue(".map", "Max MAP", d[3] * 10.0, "kPa")
            )
        }
        add(0x50, 4, "Max. Luftmasse", one("Max MAF", "g/s") { it[0] * 10.0 })
        add(0x51, 1, "Kraftstoffart") { d -> listOf(PidValue("", "Kraftstoffart", d[0].toDouble(), "", FUEL_TYPES[d[0]])) }
        add(0x52, 1, "Ethanolanteil", one("Ethanol", "%") { it[0] * 100.0 / 255 })
        add(0x53, 2, "Tankdampfdruck absolut", one("Dampfdruck abs.", "kPa") { ab(it) / 200.0 })
        add(0x54, 2, "Tankdampfdruck", one("Dampfdruck", "Pa") { s16(ab(it)).toDouble() })
        for ((i, n) in listOf("B1/B3", "B2/B4").withIndex()) {
            add(0x55 + 2 * i, 2, "Sek. Lambda Kurzzeit-Trim $n") { d ->
                listOf(PidValue(".a", "Sek. STFT A", (d[0] - 128) * 100.0 / 128, "%"), PidValue(".b", "Sek. STFT B", (d[1] - 128) * 100.0 / 128, "%"))
            }
            add(0x56 + 2 * i, 2, "Sek. Lambda Langzeit-Trim $n") { d ->
                listOf(PidValue(".a", "Sek. LTFT A", (d[0] - 128) * 100.0 / 128, "%"), PidValue(".b", "Sek. LTFT B", (d[1] - 128) * 100.0 / 128, "%"))
            }
        }
        add(0x59, 2, "Kraftstoffrail-Druck absolut", one("Raildruck abs.", "kPa") { ab(it) * 10.0 })
        add(0x5A, 1, "Gaspedal relativ", one("Pedal relativ", "%") { it[0] * 100.0 / 255 })
        add(0x5B, 1, "Hybridbatterie", one("Hybridbatterie", "%") { it[0] * 100.0 / 255 })
        add(0x5C, 1, "Öltemperatur", one("Öltemperatur", "°C") { it[0] - 40.0 })
        add(0x5D, 2, "Einspritzzeitpunkt", one("Einspritzbeginn", "°") { ab(it) / 128.0 - 210 })
        add(0x5E, 2, "Kraftstoffverbrauch", one("Verbrauch", "L/h") { ab(it) / 20.0 })
        add(0x5F, 1, "Emissionsanforderungen", one("Emissionsnorm", "") { it[0].toDouble() })
        add(0x61, 1, "Fahrerwunsch-Moment", one("Wunschmoment", "%") { it[0] - 125.0 })
        add(0x62, 1, "Ist-Moment", one("Ist-Moment", "%") { it[0] - 125.0 })
        add(0x63, 2, "Referenzmoment", one("Referenzmoment", "Nm") { ab(it).toDouble() })

        // ---- PIDs 0x64+ (multi-sensor PIDs: byte A = bitmask which sensors exist, then the values) ----
        add(0x64, 5, "Motormoment-Kennpunkte") { d ->
            (0..4).map { i -> PidValue(".$i", "Punkt ${i + 1}", d[i] - 125.0, "%") }
        }
        add(0x66, 5, "Luftmassenmesser (Sensoren)") { d -> sensors(d, 2, "MAF ", "g/s") { it / 32.0 } }
        add(0x67, 3, "Kühlmitteltemperatur (Sensoren)") { d -> sensors1(d, 2, "Kühlmittel", "°C") { it - 40.0 } }
        add(0x68, 7, "Ansauglufttemperatur (Sensoren)") { d -> sensors1(d, 6, "Ansaugluft", "°C") { it - 40.0 } }
        add(0x6B, 5, "AGR-Temperatur") { d -> sensors1(d, 4, "AGR-Temp", "°C") { it - 40.0 } }
        add(0x6F, 3, "Verdichter-Eingangsdruck") { d -> sensors1(d, 2, "Turbo-Eingang", "kPa") { it.toDouble() } }
        add(0x70, 10, "Ladedruckregelung") { d ->
            listOfNotNull(
                bit(d, 0)?.let { PidValue(".soll_a", "Ladedruck soll A", ab2(d, 1) * 0.03125, "kPa") },
                bit(d, 1)?.let { PidValue(".ist_a", "Ladedruck ist A", ab2(d, 3) * 0.03125, "kPa") },
                bit(d, 2)?.let { PidValue(".soll_b", "Ladedruck soll B", ab2(d, 5) * 0.03125, "kPa") },
                bit(d, 3)?.let { PidValue(".ist_b", "Ladedruck ist B", ab2(d, 7) * 0.03125, "kPa") },
            )
        }
        add(0x73, 5, "Abgasdruck") { d -> sensors(d, 2, "Abgasdruck Bank ", "kPa") { it * 0.01 } }
        add(0x74, 5, "Turboladerdrehzahl") { d -> sensors(d, 2, "Turbo ", "1/min") { it * 10.0 } }
        add(0x75, 7, "Turbolader-Temperatur A") { d -> turboTemps(d) }
        add(0x76, 7, "Turbolader-Temperatur B") { d -> turboTemps(d) }
        add(0x77, 5, "Ladeluftkühler-Temperatur") { d -> sensors1(d, 4, "LLK-Temp", "°C") { it - 40.0 } }
        add(0x78, 9, "Abgastemperatur Bank 1") { d -> sensors(d, 4, "Abgas B1 S", "°C") { it / 10.0 - 40 } }
        add(0x79, 9, "Abgastemperatur Bank 2") { d -> sensors(d, 4, "Abgas B2 S", "°C") { it / 10.0 - 40 } }
        add(0x7A, 7, "Partikelfilter Druck Bank 1") { d -> dpfPressure(d) }
        add(0x7B, 7, "Partikelfilter Druck Bank 2") { d -> dpfPressure(d) }
        add(0x7C, 9, "Partikelfilter-Temperatur") { d ->
            listOfNotNull(
                bit(d, 0)?.let { PidValue(".b1_ein", "DPF B1 Eingang", ab2(d, 1) / 10.0 - 40, "°C") },
                bit(d, 1)?.let { PidValue(".b1_aus", "DPF B1 Ausgang", ab2(d, 3) / 10.0 - 40, "°C") },
                bit(d, 2)?.let { PidValue(".b2_ein", "DPF B2 Eingang", ab2(d, 5) / 10.0 - 40, "°C") },
                bit(d, 3)?.let { PidValue(".b2_aus", "DPF B2 Ausgang", ab2(d, 7) / 10.0 - 40, "°C") },
            )
        }
        add(0x7F, 13, "Motorlaufzeit") { d ->
            fun u32(o: Int) = ((d[o].toLong() shl 24) + (d[o + 1] shl 16) + (d[o + 2] shl 8) + d[o + 3]).toDouble()
            listOfNotNull(
                bit(d, 0)?.let { PidValue(".gesamt", "Laufzeit gesamt", u32(1), "s") },
                bit(d, 1)?.let { PidValue(".leerlauf", "Laufzeit Leerlauf", u32(5), "s") },
                bit(d, 2)?.let { PidValue(".pto", "Laufzeit Nebenabtrieb", u32(9), "s") },
            )
        }
        add(0x84, 1, "Saugrohr-Oberflächentemperatur", one("Saugrohr-Oberfläche", "°C") { it[0] - 40.0 })
        add(0x8D, 1, "Drosselklappe G", one("DK G", "%") { it[0] * 100.0 / 255 })
        add(0x8E, 1, "Reibmoment", one("Reibmoment", "%") { it[0] - 125.0 })
        add(0xA6, 4, "Kilometerstand") { d ->
            listOf(PidValue("", "Kilometerstand", ((d[0].toLong() shl 24) + (d[1] shl 16) + (d[2] shl 8) + d[3]) / 10.0, "km"))
        }
    }.associateBy { it.pid }

    fun isBitmapPid(pid: Int) = pid % 0x20 == 0

    /** PIDs whose value does not change while driving – read once per trip instead of in the polling loop */
    val STATIC_PIDS = setOf(0x13, 0x1C, 0x1D, 0x5F, 0x63, 0x64)
}
