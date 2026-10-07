# CaddyLog

Android-App (Kotlin/Compose), mit der du über den **Vgate iCar Pro BLE 4.0** alle lesbaren Daten aus dem **VW Caddy Maxi 2.0 EcoFuel (CNG, 2010)** loggst und als ZIP exportierst.

## Was die App ausliest (nur lesend, es wird nichts ins Auto geschrieben)

| Bereich | Inhalt |
|---|---|
| OBD2 Mode 01 | alle unterstützten Standard-PIDs (Drehzahl, Last, Temperaturen, Lambda, Trims, MAF, Kraftstoffart …) |
| OBD2 Mode 02 | Freeze Frame |
| OBD2 Mode 03/07/0A | Fehlerspeicher: gespeichert, temporär, permanent |
| OBD2 Mode 06 | On-Board-Monitortests (Kat, Lambda …) mit Grenzwerten |
| OBD2 Mode 09 | VIN, Kalibrierungs-IDs, CVN, Steuergerätename |
| VW TP2.0 / KWP2000 | Steuergeräte-Suche, Identifikation (Teilenummer), VAG-Fehlerspeicher, **Messwertblöcke** (dort stehen die VW-eigenen Werte, z. B. Gas-/CNG-Werte) |
| Adapter | Bordnetzspannung (ATRV) |

## Bauen & installieren

1. Ordner in Android Studio öffnen, Gradle-Sync abwarten. Falls Studio neuere AGP-/Kotlin-Versionen vorschlägt, kannst du die übernehmen.
2. Pixel per USB/WLAN-Debugging verbinden, dann **Run**.
3. minSdk 31, targetSdk 36.

Unit-Test ohne Auto: `./gradlew test`. Er spielt OBD2, TP2.0-Kanalaufbau, Multi-Frame mit ACK, „response pending“ (7F 78) und den Live-Zyklus gegen ein simuliertes Steuergerät durch.

## Benutzung

1. Adapter einstecken, Zündung an (für Live-Werte: Motor laufen lassen).
2. **Verbindung** → „Adapter suchen“ → Gerät antippen (heißt meist `IOS-Vlink`, `V-LINK` o. ä.). **Nicht** in den Android-Bluetooth-Einstellungen koppeln.
3. **Scan** → „OBD2-Scan“ und danach „VW-Scan“. Für den vollständigen Scan beide Häkchen setzen (dauert länger).
4. **Live**: Alle OBD-PIDs plus VW-Messwertblöcke loggen, z. B. `01:1-30`. Nach dem VW-Scan weißt du, welche Gruppen Werte liefern. Das Logging läuft mit ausgeschaltetem Bildschirm weiter.
5. **Daten** → „Teilen“ (oder „Speichern“) → ZIP an Claude schicken.

## Export (ein Ordner pro Verbindung/Session)

- `live.csv`: alle Messwerte im Long-Format, `t_ms,time,channel,name,value,text,unit,raw`
  - Kanal-IDs: `OBD.7E8.0C` (Steuergerät 7E8, PID 0C), `VW.01.G003.2` (SG 01, Gruppe 3, Feld 2), `ADAPTER.VBAT`
- `scan_obd_*.json`, `scan_vw_*.json`: vollständige Scan-Ergebnisse inkl. Rohbytes
- `raw.log`: **jede** Anfrage und Antwort mit Zeitstempel. Damit lassen sich alle Werte nachträglich neu dekodieren.
- `meta.json`, `live_config_*.json`

## Hinweise

- VW-Messwertformeln sind nach bestem Wissen dekodiert. Die Rohbytes (Formel, A, B) werden immer mitgeschrieben, damit sich falsche Formeln später korrigieren lassen.
- TP2.0 über einen ELM327-Klon ist anspruchsvoll. Antwortet ein Steuergerät nicht, findest du die genauen Antworten im `raw.log` bzw. im Tab **Konsole**.
- Während der Fahrt nicht bedienen: Logging vorher starten, Handy weglegen.
