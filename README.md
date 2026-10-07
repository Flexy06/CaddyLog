# CaddyLog

Android-App (Kotlin/Compose) zur **Live-Überwachung des Kühlsystems** und zum Datenlogging für den
**VW Caddy Maxi 2.0 EcoFuel (CNG, 2010)** über einen **Vgate iCar Pro BLE 4.0** (ELM327).

> Die App ist eine Diagnosehilfe. Sie liest nur, steuert nichts am Fahrzeug (kein Lüfter, keine Aktoren, kein Codieren,
> kein Fehlerspeicher-Löschen) und stellt keine Fehlerdiagnosen. Warnungen beruhen auf konfigurierbaren Schwellen
> und tatsächlich gemessenen Werten.

## Funktionen

**Live (Startbildschirm)**
- Kühlmitteltemperatur groß, Farbe je Zustand: Normal · Erhöht · Warnung · Starke Warnung · Kritisch · Extrem
- „Keine aktuelle Messung“, sobald kein frischer Wert kommt (der letzte Wert wird nie als aktuell angezeigt)
- Änderung °C/min, Maximum der Fahrt (mit Uhrzeit), bisheriges Maximum (mit Datum), 10-min-Verlauf
- Drehzahl, Geschwindigkeit, Last, Ansaugluft, MAP, Drosselklappe, Spannungen – jeweils mit Frische-Anzeige oder „nicht verfügbar“
- Status: Verbindung, Bus (K-Line/CAN), Steuergerät antwortet, Abtastrate, Fehlerzähler (Timeout, Prüfsumme, NO DATA, Re-Init)

**Warnlogik** (`core/CoolantMonitor.kt`)
- Schwellen (Standard 105 / 110 / 115 / 120 / 125 °C), in den Einstellungen änderbar
- Hysterese (5 °C) + 10 s Mindestdauer beim Absinken → kein Flattern
- Schneller Anstieg (≥ 6 °C/min oder +10 °C in 60 s, ab 95 °C)
- Dauer: „seit 2 min im Warnbereich“ wird anders bewertet als eine kurze Spitze
- Beobachtungen (nur Hinweise, keine Diagnosen): hohe Temperatur im Stand, Anstieg trotz hoher Geschwindigkeit,
  keine erkennbare Abkühlung, hohe Temperatur bei geringer Last, kontinuierlicher Anstieg über 5 min
- Unplausible Sprünge werden erst nach Bestätigung durch den nächsten Messwert übernommen

**Alarmierung** (`core/AlarmEngine.kt` → `alarm/AlarmOutputs.kt`)
- Kritisch: Vollbild-Warnung (auch über dem Sperrbildschirm), Alarmton auf dem Alarm-Kanal, Vibration, Benachrichtigung
- Wiederholung nur alle 60 s (nach „Bestätigen“ frühestens nach 180 s), sofort bei weiterem Anstieg (+3 °C) oder „Extrem“
- Warnung / schneller Anstieg / anhaltend hoch: Benachrichtigung + kurzer Hinweiston
- Messausfall während einer Warnung und Verbindungsverlust werden ebenfalls gemeldet

**Fahrten**
- Automatische Aufzeichnung nach dem Verbinden (abschaltbar), Live-Graph mit ein-/ausblendbaren Linien
- Fahrtanalyse nur aus gemessenen Daten: Dauer, Max/Ø, Warnungen, Zeit über den Schwellen, Werte beim Maximum, schnellster Anstieg
- Ereignisprotokoll, kompletter Graph der Fahrt, Export als ZIP

**Diagnose** (wie bisher): Verbindung, OBD2-Komplettscan, Fehlerspeicher lesen, alle Werte, Konsole (nur lesende Befehle).
Der VW-TP2.0-Scan wird bei K-Line automatisch übersprungen.

## K-Line (ISO 14230-4 KWP)

- Protokoll wird automatisch erkannt (`ATSP0`), das funktionierende wird gemerkt und beim nächsten Mal zuerst probiert
- Antworten mit Header und Prüfsumme werden geprüft, z. B. `83 F1 11 41 05 7E 49` → Kühlmittel 0x7E = 126 − 40 = **86 °C**
- Eine PID pro Anfrage (K-Line erlaubt keine Mehrfach-PIDs), mit Antwortzahl-Hinweis (`01051`) für schnellere Abfragen
- Antwortet das Steuergerät mehrmals nicht, wird der Bus neu initialisiert (`ATPC`); `BUS INIT: ...OK` wird als Re-Init protokolliert
- Abtastrate 1/2/5 Hz: Kühlmittel in jedem Zyklus, andere Werte abwechselnd; zu langsam → automatischer Rückfall (5 → 2 → 1 → 0,5 Hz)

## Architektur

```
ble/BleElmLink            BLE-Transport zum iCar Pro
core/Elm327, KLine        ELM327-Befehle, Bus-Erkennung, KWP-Header/Prüfsumme
core/Obd, ObdPids         PID-Abfrage mit Status (OK, NO DATA, Timeout, Prüfsumme, …) und Dekodierung
core/VehicleData          Signal, SignalState (Frische), PidResult
core/LiveSampler          Abfrageplan + adaptive Abtastrate
core/CoolantMonitor       Schwellen, Hysterese, Anstieg, Dauer, Beobachtungen
core/AlarmEngine          Wann wird wie alarmiert (UI-unabhängig)
core/Trip                 Fahrtaufzeichnung, Analyse, CSV-Dateien
monitor/MonitorEngine     verbindet alles zur Live-Pipeline (läuft im Foreground-Service)
alarm/AlarmOutputs        Ton, Vibration, Benachrichtigungen
data/LoggerEngine         Verbindung, Sessions, Scans, Konsole
ui/…                      Compose-Oberfläche
```

Der gesamte `core`-Teil ist reines Kotlin ohne Android und per Unit-Test abgedeckt: `./gradlew testDebugUnitTest`.

## Dateien einer Fahrt (ZIP-Export)

| Datei | Inhalt |
|---|---|
| `trip_series.csv` | eine Zeile pro Abfragezyklus: Kühlmittel, Drehzahl, Geschwindigkeit, Last, Ansaugluft, MAP, Drosselklappe, Spannungen, Frische, Zustand |
| `trip_events.csv` | Ereignisprotokoll (Zeit, Art, Zustand, Temperatur, Drehzahl, Geschwindigkeit, Last, Text) |
| `trip_summary.json` / `.txt` | Fahrtanalyse |
| `live.csv` | alle gelesenen Kanäle inkl. aller weiteren PIDs (Long-Format, mit Rohbytes) |
| `raw.log` | jede Anfrage und Antwort mit Zeitstempel |
| `meta.json`, `scan_*.json` | Adapter-/Protokollinfos, Scan-Ergebnisse |

## Hinweise

- Ab Version 0.2 sind alle Builds (GitHub Actions und Android Studio) mit demselben Schlüssel (`app/debug.keystore`) signiert.
  Die erste 0.2 muss einmalig nach Deinstallation der 0.1 installiert werden, danach funktionieren Updates direkt.
- Den Alarmton bestimmt die **Alarm-Lautstärke** des Handys. Unter Einstellungen → „Alarmton testen“ prüfen.
- Für die Vollbild-Warnung auf dem Sperrbildschirm muss Android der App „Vollbild-Benachrichtigungen“ erlauben
  (Android 14+: Einstellungen → Apps → CaddyLog → Benachrichtigungen).
- Der Kühlerlüfter-Zustand ist über Standard-OBD2 nicht lesbar und wird deshalb nicht angezeigt.
- Während der Fahrt nicht bedienen.
