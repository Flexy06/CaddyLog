package de.flexy.caddylog.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import de.flexy.caddylog.data.LoggerEngine
import de.flexy.caddylog.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import de.flexy.caddylog.core.CoolantMonitor
import de.flexy.caddylog.monitor.MonitorEngine
import kotlinx.coroutines.launch

/**
 * Keeps the process (and the BLE connection) alive while connected, so logging continues with the screen off.
 * The work happens in [LoggerEngine] / [MonitorEngine]; this service holds the foreground state + a wake lock
 * and shows the current coolant temperature in its notification.
 */
class LoggingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Überwachung läuft", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, build("Verbunden"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CaddyLog:logging").apply { acquire(12 * 60 * 60 * 1000L) }
        scope.launch {
            combine(LoggerEngine.activity, LoggerEngine.conn, MonitorEngine.ui) { a, c, m ->
                val cool = m.coolant
                val temp = when {
                    cool.tempC == null -> "Kühlmittel: –"
                    cool.fresh -> "Kühlmittel ${CoolantMonitor.fmt(cool.tempC)} °C · ${cool.level.label}"
                    else -> "Kühlmittel: keine aktuelle Messung (zuletzt ${CoolantMonitor.fmt(cool.tempC)} °C)"
                }
                when {
                    c is LoggerEngine.Conn.Reconnecting -> "Verbindung verloren – verbinde neu (Versuch ${c.attempt}) · $temp"
                    a != null -> "$a · Überwachung pausiert"
                    else -> temp + (if (m.trip != null) " · Fahrt wird aufgezeichnet" else "") + " · " + hz(m.effectiveHz)
                }
            }.distinctUntilChanged().collect { nm.notify(ID, build(it)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun build(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("CaddyLog – Live-Überwachung")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun hz(v: Double) = if (v >= 1) "${v.toInt()} Hz" else "$v Hz"

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "logging"
        private const val ID = 1

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, LoggingService::class.java)) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LoggingService::class.java))
        }
    }
}
