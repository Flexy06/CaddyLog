package de.flexy.caddylog.data

import android.content.Context
import android.content.SharedPreferences
import de.flexy.caddylog.core.AlarmConfig
import de.flexy.caddylog.core.CoolantThresholds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User settings (persisted in SharedPreferences). */
data class AppSettings(
    val elevated: Double = 105.0,
    val warning: Double = 110.0,
    val strong: Double = 115.0,
    val critical: Double = 120.0,
    val extreme: Double = 125.0,
    val hysteresis: Double = 5.0,
    val riseRatePerMin: Double = 6.0,
    val riseDelta: Double = 10.0,
    val sustainedMin: Double = 2.0,
    val sampleHz: Double = 1.0,
    val soundEnabled: Boolean = true,
    val vibrate: Boolean = true,
    val repeatSec: Int = 60,
    val ackRepeatSec: Int = 180,
    val autoTrip: Boolean = true,
    val autoReconnect: Boolean = true,
    /** ATDPN of the last successful connection (e.g. "5"), tried first next time */
    val preferredProtocol: String? = null,
    val allTimeMax: Double? = null,
    val allTimeMaxMs: Long? = null,
    /** written into meta.json of every session, so the logs of different cars can be told apart */
    val vehicleName: String = VEHICLE_CADDY,
) {
    companion object {
        const val VEHICLE_CADDY = "VW Caddy Maxi 2.0 EcoFuel (CNG), 2010"
        const val VEHICLE_T5 = "VW T5, ca. 2006"
    }

    fun thresholds() = CoolantThresholds(
        elevated = elevated, warning = warning, strong = strong, critical = critical, extreme = extreme,
        hysteresis = hysteresis, riseRatePerMin = riseRatePerMin, riseDelta = riseDelta,
        sustainedMs = (sustainedMin * 60_000).toLong(),
    )

    fun alarmConfig() = AlarmConfig(soundEnabled = soundEnabled, repeatMs = repeatSec * 1000L, ackRepeatMs = ackRepeatSec * 1000L)

    fun isValid() = thresholds().isValid() && sampleHz in 0.4..5.1 && repeatSec >= 10 && ackRepeatSec >= repeatSec && sustainedMin > 0
}

object Settings {
    private lateinit var prefs: SharedPreferences
    private val _flow = MutableStateFlow(AppSettings())
    val flow: StateFlow<AppSettings> = _flow
    val current: AppSettings get() = _flow.value

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val d = AppSettings()
        fun dbl(k: String, def: Double) = prefs.getString(k, null)?.toDoubleOrNull() ?: def
        _flow.value = AppSettings(
            elevated = dbl("elevated", d.elevated), warning = dbl("warning", d.warning), strong = dbl("strong", d.strong),
            critical = dbl("critical", d.critical), extreme = dbl("extreme", d.extreme), hysteresis = dbl("hysteresis", d.hysteresis),
            riseRatePerMin = dbl("riseRate", d.riseRatePerMin), riseDelta = dbl("riseDelta", d.riseDelta),
            sustainedMin = dbl("sustainedMin", d.sustainedMin), sampleHz = dbl("sampleHz", d.sampleHz),
            soundEnabled = prefs.getBoolean("sound", d.soundEnabled), vibrate = prefs.getBoolean("vibrate", d.vibrate),
            repeatSec = prefs.getInt("repeatSec", d.repeatSec), ackRepeatSec = prefs.getInt("ackRepeatSec", d.ackRepeatSec),
            autoTrip = prefs.getBoolean("autoTrip", d.autoTrip), autoReconnect = prefs.getBoolean("autoReconnect", d.autoReconnect),
            preferredProtocol = prefs.getString("protocol", null),
            allTimeMax = prefs.getString("allTimeMax", null)?.toDoubleOrNull(),
            allTimeMaxMs = prefs.getLong("allTimeMaxMs", 0).takeIf { it > 0 },
            vehicleName = prefs.getString("vehicleName", null)?.takeIf { it.isNotBlank() } ?: d.vehicleName,
        ).let {
            if (it.isValid()) it
            else d.copy(preferredProtocol = it.preferredProtocol, allTimeMax = it.allTimeMax, allTimeMaxMs = it.allTimeMaxMs, vehicleName = it.vehicleName)
        }
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings): Boolean {
        val n = transform(_flow.value)
        if (!n.isValid()) return false
        _flow.value = n
        if (::prefs.isInitialized) prefs.edit().apply {
            putString("elevated", n.elevated.toString()); putString("warning", n.warning.toString())
            putString("strong", n.strong.toString()); putString("critical", n.critical.toString())
            putString("extreme", n.extreme.toString()); putString("hysteresis", n.hysteresis.toString())
            putString("riseRate", n.riseRatePerMin.toString()); putString("riseDelta", n.riseDelta.toString())
            putString("sustainedMin", n.sustainedMin.toString()); putString("sampleHz", n.sampleHz.toString())
            putBoolean("sound", n.soundEnabled); putBoolean("vibrate", n.vibrate)
            putInt("repeatSec", n.repeatSec); putInt("ackRepeatSec", n.ackRepeatSec)
            putBoolean("autoTrip", n.autoTrip); putBoolean("autoReconnect", n.autoReconnect)
            if (n.preferredProtocol != null) putString("protocol", n.preferredProtocol) else remove("protocol")
            if (n.allTimeMax != null) putString("allTimeMax", n.allTimeMax.toString()) else remove("allTimeMax")
            putLong("allTimeMaxMs", n.allTimeMaxMs ?: 0)
            putString("vehicleName", n.vehicleName)
        }.apply()
        return true
    }
}
