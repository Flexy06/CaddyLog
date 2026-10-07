package de.flexy.caddylog.alarm

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import de.flexy.caddylog.core.AlarmNotification
import de.flexy.caddylog.core.AlarmOutput
import de.flexy.caddylog.core.AlarmSeverity
import de.flexy.caddylog.core.SoundKind
import de.flexy.caddylog.ui.MainActivity
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Platform side of the alarm engine: alarm tone (synthesised, played on the ALARM stream so it is audible
 * even when the ringer is muted), vibration and notifications (heads-up / lock screen / full-screen intent).
 */
object AlarmOutputs {
    const val CH_CRITICAL = "coolant_critical"
    const val CH_WARNING = "coolant_warning"
    const val CH_INFO = "coolant_info"
    const val EXTRA_ACK = "ack_alarm"
    private const val ID_COOLANT = 10
    private const val ID_INFO = 11

    private lateinit var app: Context
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_CRITICAL, "Kritische Kühlmittel-Warnung", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Kritische Kühlmitteltemperatur – Ton kommt von der App (Alarm-Lautstärke)"
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 800)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_WARNING, "Kühlmittel-Warnungen", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Erhöhte Kühlmitteltemperatur, schneller Anstieg, Messausfall"
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "Beobachtungen", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Hinweise aus der Datenkorrelation"
            setSound(null, null)
        })
    }

    fun handle(out: AlarmOutput, vibrate: Boolean) {
        if (!::app.isInitialized) return
        out.sound?.let { play(it) }
        if (vibrate && out.sound != null) vibrate(out.sound == SoundKind.ALARM)
        out.notification?.let { notify(it) }
    }

    // ------------------------------------------------------------------ notifications

    @SuppressLint("MissingPermission")
    fun notify(n: AlarmNotification) {
        val nm = app.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(app, 1, Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val channel = when (n.severity) {
            AlarmSeverity.CRITICAL -> CH_CRITICAL
            AlarmSeverity.WARNING -> CH_WARNING
            else -> CH_INFO
        }
        val b = Notification.Builder(app, channel)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setStyle(Notification.BigTextStyle().bigText(n.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(true)
        when (n.severity) {
            AlarmSeverity.CRITICAL -> {
                val ack = PendingIntent.getActivity(app, 2, Intent(app, MainActivity::class.java)
                    .putExtra(EXTRA_ACK, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                b.setCategory(Notification.CATEGORY_ALARM)
                    .setColor(0xFFD32F2F.toInt())
                    .addAction(Notification.Action.Builder(null, "Bestätigen", ack).build())
                val canFullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
                if (canFullScreen) b.setFullScreenIntent(open, true)
                nm.notify(ID_COOLANT, b.build())
            }
            AlarmSeverity.WARNING -> nm.notify(ID_COOLANT, b.setCategory(Notification.CATEGORY_STATUS).setColor(0xFFF57C00.toInt()).build())
            AlarmSeverity.INFO -> {
                // "wieder normal" replaces the coolant notification, observations get their own slot
                val id = if (n.title.startsWith("Kühlmittel")) ID_COOLANT else ID_INFO
                nm.notify(id, b.build())
            }
            AlarmSeverity.NONE -> nm.cancel(ID_COOLANT)
        }
    }

    fun cancelAll() {
        if (!::app.isInitialized) return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.cancel(ID_COOLANT); nm.cancel(ID_INFO)
    }

    // ------------------------------------------------------------------ vibration

    private fun vibrate(long: Boolean) {
        runCatching {
            val vm = app.getSystemService(VibratorManager::class.java) ?: return
            val pattern = if (long) longArrayOf(0, 500, 200, 500, 200, 800) else longArrayOf(0, 200, 120, 200)
            vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    // ------------------------------------------------------------------ sound

    /** (frequency Hz, tone ms, pause ms) */
    private val ALARM = List(4) { listOf(Triple(880.0, 140, 40), Triple(1175.0, 140, 40), Triple(1568.0, 240, 280)) }.flatten()
    private val CHIME = listOf(Triple(988.0, 130, 50), Triple(1319.0, 300, 0))

    @Synchronized
    fun play(kind: SoundKind) {
        if (!::app.isInitialized) return
        stop()
        val pcm = synth(if (kind == SoundKind.ALARM) ALARM else CHIME, if (kind == SoundKind.ALARM) 0.9 else 0.6)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val am = app.getSystemService(AudioManager::class.java)
        val fr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        runCatching { am.requestAudioFocus(fr) }
        focus = fr
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setNotificationMarkerPosition(pcm.size)
        t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) { stop() }
            override fun onPeriodicNotification(track: AudioTrack) {}
        })
        t.play()
        track = t
    }

    @Synchronized
    fun stop() {
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
        focus?.let { f -> runCatching { app.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(f) } }
        focus = null
    }

    private const val RATE = 44100

    private fun synth(pattern: List<Triple<Double, Int, Int>>, volume: Double): ShortArray {
        val total = pattern.sumOf { (_, on, off) -> (on + off) * RATE / 1000 }
        val out = ShortArray(total)
        var pos = 0
        for ((f, on, off) in pattern) {
            val n = on * RATE / 1000
            val fade = min(n / 4, RATE * 8 / 1000)
            for (i in 0 until n) {
                val env = when {
                    i < fade -> i.toDouble() / fade
                    i > n - fade -> (n - i).toDouble() / fade
                    else -> 1.0
                }
                val x = 2 * PI * i / RATE
                val s = sin(f * x) * 0.8 + sin(2 * f * x) * 0.15 + sin(3 * f * x) * 0.05
                out[pos + i] = (s * env * volume * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
            pos += n + off * RATE / 1000
        }
        return out
    }
}
