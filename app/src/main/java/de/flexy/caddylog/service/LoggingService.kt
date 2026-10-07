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
import kotlinx.coroutines.launch

/**
 * Keeps the process (and the BLE connection) alive while connected, so logging continues with the screen off.
 * The actual work happens in [LoggerEngine]; this service only holds the foreground state + a wake lock.
 */
class LoggingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Logging", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, build("Verbunden"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CaddyLog:logging").apply { acquire(6 * 60 * 60 * 1000L) }
        scope.launch {
            combine(LoggerEngine.activity, LoggerEngine.live) { a, l ->
                when {
                    l.running -> "Live-Logging: ${l.cycles} Zyklen, ${l.readings} Werte"
                    a != null -> a
                    else -> "Verbunden – bereit"
                }
            }.collect { nm.notify(ID, build(it)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun build(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("CaddyLog")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

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
