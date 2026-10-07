package de.flexy.caddylog.core

/**
 * Decides WHEN the driver is alerted (sound, notification, warning dialog) – independent of the UI.
 *
 * Rules:
 *  - entering CRITICAL/EXTREME → alarm sound + high priority notification + warning dialog
 *  - staying critical: repeat only after [AlarmConfig.repeatMs] (not acknowledged) or
 *    [AlarmConfig.ackRepeatMs] (acknowledged), or immediately if it gets worse (EXTREME / +ackRiseDelta °C)
 *  - entering WARNING/STRONG WARNING, rapid rise, sustained high temperature → notification + short chime
 *  - observations → silent notification
 *  - falling back below critical closes the dialog; back to normal → "wieder normal" notification
 *  - no fresh coolant value while in a warning state → explicit notification ("keine aktuelle Messung")
 */
data class AlarmConfig(
    val soundEnabled: Boolean = true,
    val repeatMs: Long = 60_000,
    val ackRepeatMs: Long = 180_000,
    val ackRiseDelta: Double = 3.0,
)

enum class AlarmSeverity { NONE, INFO, WARNING, CRITICAL }
enum class SoundKind { ALARM, CHIME }

data class AlarmNotification(val severity: AlarmSeverity, val title: String, val text: String, val cancel: Boolean = false)

data class WarningDialog(val level: CoolantLevel, val tempC: Double?, val rising: Boolean, val text: String, val sinceMs: Long)

/** What the platform layer should do now. Everything null/false = nothing to do. */
data class AlarmOutput(
    val sound: SoundKind? = null,
    val notification: AlarmNotification? = null,
    /** non-null: show/update the dialog; [dismissDialog] true: close it */
    val dialog: WarningDialog? = null,
    val dismissDialog: Boolean = false,
) {
    val isEmpty get() = sound == null && notification == null && dialog == null && !dismissDialog
}

class AlarmEngine(var config: AlarmConfig = AlarmConfig()) {

    var activeDialog: WarningDialog? = null
        private set
    var acknowledged = false
        private set

    private var lastSeverity = AlarmSeverity.NONE
    private var lastLevel = CoolantLevel.UNKNOWN
    private var lastAlarmMs = 0L
    private var ackMs = 0L
    private var ackTemp = 0.0
    private var staleNotified = false

    fun severityOf(level: CoolantLevel): AlarmSeverity = when (level) {
        CoolantLevel.CRITICAL, CoolantLevel.EXTREME -> AlarmSeverity.CRITICAL
        CoolantLevel.WARNING, CoolantLevel.STRONG_WARNING -> AlarmSeverity.WARNING
        CoolantLevel.ELEVATED -> AlarmSeverity.INFO
        else -> AlarmSeverity.NONE
    }

    fun acknowledge(now: Long, tempC: Double?) {
        acknowledged = true
        ackMs = now
        ackTemp = tempC ?: ackTemp
    }

    fun update(now: Long, state: CoolantState, events: List<MonitorEvent>): AlarmOutput {
        val level = state.level
        val sev = severityOf(level)
        val temp = state.tempC
        val tempTxt = temp?.let { "${CoolantMonitor.fmt(it)} °C" } ?: "–"
        val risingTxt = if (state.rapidRise || (state.ratePerMin ?: 0.0) >= 1.0) "Temperatur weiterhin steigend." else ""
        var out = AlarmOutput()

        if (sev == AlarmSeverity.CRITICAL) {
            val entering = lastSeverity != AlarmSeverity.CRITICAL
            val worse = level.ordinal > lastLevel.ordinal && !entering
            val repeatDue = if (acknowledged) now - ackMs >= config.ackRepeatMs else now - lastAlarmMs >= config.repeatMs
            val risenSinceAck = acknowledged && temp != null && temp >= ackTemp + config.ackRiseDelta
            if (entering || worse || repeatDue || risenSinceAck) {
                acknowledged = false
                lastAlarmMs = now
                val dlg = WarningDialog(level, temp, state.rapidRise, risingTxt, activeDialog?.sinceMs ?: now)
                activeDialog = dlg
                out = out.copy(
                    sound = if (config.soundEnabled) SoundKind.ALARM else null,
                    notification = AlarmNotification(AlarmSeverity.CRITICAL,
                        if (level == CoolantLevel.EXTREME) "⚠ Extreme Kühlmitteltemperatur" else "⚠ Kritische Kühlmitteltemperatur",
                        "Kühlmittel: $tempTxt. $risingTxt Motor möglichst nicht weiter belasten.".replace("  ", " ")),
                    dialog = dlg,
                )
            } else if (!acknowledged) {
                // keep the dialog content current without new sound
                val dlg = WarningDialog(level, temp, state.rapidRise, risingTxt, activeDialog?.sinceMs ?: now)
                if (dlg != activeDialog) { activeDialog = dlg; out = out.copy(dialog = dlg) }
            }
        } else {
            if (activeDialog != null) {
                activeDialog = null
                out = out.copy(dismissDialog = true)
            }
            acknowledged = false
            if (sev.ordinal > lastSeverity.ordinal && sev == AlarmSeverity.WARNING) {
                out = out.copy(
                    sound = if (config.soundEnabled) SoundKind.CHIME else null,
                    notification = AlarmNotification(AlarmSeverity.WARNING, "Kühlmitteltemperatur: ${level.label}", "Kühlmittel: $tempTxt. $risingTxt".trim()),
                )
            } else if (level == CoolantLevel.STRONG_WARNING && lastLevel == CoolantLevel.WARNING) {
                out = out.copy(
                    sound = if (config.soundEnabled) SoundKind.CHIME else null,
                    notification = AlarmNotification(AlarmSeverity.WARNING, "Kühlmitteltemperatur: ${level.label}", "Kühlmittel: $tempTxt. $risingTxt".trim()),
                )
            } else if (sev.ordinal < lastSeverity.ordinal) {
                out = out.copy(notification = if (sev == AlarmSeverity.NONE)
                    AlarmNotification(AlarmSeverity.INFO, "Kühlmitteltemperatur wieder normal", "Kühlmittel: $tempTxt")
                else AlarmNotification(sev, "Kühlmitteltemperatur sinkt: ${level.label}", "Kühlmittel: $tempTxt"))
            }
        }

        // events that deserve a separate hint (only if nothing more important happened in this update)
        for (e in events) {
            if (out.notification?.severity == AlarmSeverity.CRITICAL) break
            when (e.kind) {
                EventKind.RAPID_RISE, EventKind.SUSTAINED -> out = out.copy(
                    sound = out.sound ?: if (config.soundEnabled) SoundKind.CHIME else null,
                    notification = AlarmNotification(AlarmSeverity.WARNING, "⚠ ${e.kind.label}", e.text))
                EventKind.OBSERVATION -> if (out.notification == null)
                    out = out.copy(notification = AlarmNotification(AlarmSeverity.INFO, "Beobachtung", e.text))
                else -> Unit
            }
        }

        // stale data while hot: the driver must know that the display is NOT live
        if (!state.fresh && state.lastValidMs != null && sev.ordinal >= AlarmSeverity.WARNING.ordinal) {
            if (!staleNotified) {
                staleNotified = true
                out = out.copy(
                    sound = out.sound ?: if (config.soundEnabled) SoundKind.CHIME else null,
                    notification = AlarmNotification(AlarmSeverity.WARNING, "Keine aktuelle Kühlmittelmessung",
                        "Letzter Wert $tempTxt – die Überwachung erhält keine neuen Daten."))
            }
        } else if (state.fresh) staleNotified = false

        lastSeverity = sev
        lastLevel = level
        return out
    }

    /** Adapter/vehicle connection lost while monitoring. */
    fun onConnectionLost(lastState: CoolantState): AlarmOutput {
        val sev = severityOf(lastState.level)
        return AlarmOutput(
            sound = if (config.soundEnabled && sev.ordinal >= AlarmSeverity.WARNING.ordinal) SoundKind.CHIME else null,
            notification = AlarmNotification(if (sev.ordinal >= AlarmSeverity.WARNING.ordinal) AlarmSeverity.WARNING else AlarmSeverity.INFO,
                "Verbindung zum OBD-Adapter verloren", "Keine Überwachung der Kühlmitteltemperatur mehr." +
                        (lastState.tempC?.let { " Letzter Wert: ${CoolantMonitor.fmt(it)} °C" } ?: ""))
        )
    }

    fun reset() {
        activeDialog = null; acknowledged = false; lastSeverity = AlarmSeverity.NONE; lastLevel = CoolantLevel.UNKNOWN
        lastAlarmMs = 0; staleNotified = false
    }
}
