package de.flexy.caddylog.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.flexy.caddylog.alarm.AlarmOutputs
import de.flexy.caddylog.core.CoolantLevel
import de.flexy.caddylog.core.CoolantMonitor
import de.flexy.caddylog.core.WarningDialog
import de.flexy.caddylog.data.LoggerEngine
import de.flexy.caddylog.monitor.MonitorEngine
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LoggerEngine.init(this)
        AlarmOutputs.init(this)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) { App() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(AlarmOutputs.EXTRA_ACK, false) == true) MonitorEngine.acknowledge()
    }
}

val REQUIRED_PERMISSIONS: Array<String> = buildList {
    add(Manifest.permission.BLUETOOTH_SCAN)
    add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

fun android.content.Context.hasBlePermissions() =
    listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

private data class NavTab(val title: String, val icon: ImageVector)

private val TABS = listOf(
    NavTab("Live", Icons.Filled.Speed),
    NavTab("Graph", Icons.AutoMirrored.Filled.ShowChart),
    NavTab("Fahrten", Icons.Filled.Folder),
    NavTab("Diagnose", Icons.Filled.Build),
    NavTab("Einstellungen", Icons.Filled.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openTrip by remember { mutableStateOf<File?>(null) }
    val conn by LoggerEngine.conn.collectAsStateWithLifecycle()
    val activity by LoggerEngine.activity.collectAsStateWithLifecycle()
    val st by MonitorEngine.ui.collectAsStateWithLifecycle()
    val dialog by MonitorEngine.dialog.collectAsStateWithLifecycle()

    // critical alarm: allow the warning to appear over the lock screen and switch the display on
    val act = LocalContext.current as? Activity
    LaunchedEffect(dialog != null) {
        act?.setShowWhenLocked(dialog != null)
        act?.setTurnScreenOn(dialog != null)
    }

    val level = st.coolant.level
    val critical = st.coolant.fresh && level.atLeast(CoolantLevel.CRITICAL)
    val warning = st.coolant.fresh && level.atLeast(CoolantLevel.WARNING)
    val barColor = when {
        critical -> Color(0xFFD32F2F)
        warning -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.surface
    }
    val barContent = if (critical || warning) Color.White else MaterialTheme.colorScheme.onSurface

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor, titleContentColor = barContent),
                title = {
                    Text(
                        when {
                            critical -> "⚠ KÜHLSYSTEM KRITISCH · ${st.coolant.tempC?.let { CoolantMonitor.fmt(it) } ?: "–"} °C"
                            warning -> "⚠ ${level.label} · ${st.coolant.tempC?.let { CoolantMonitor.fmt(it) } ?: "–"} °C"
                            else -> when (val c = conn) {
                                is LoggerEngine.Conn.Ready -> "CaddyLog · ${c.name}"
                                is LoggerEngine.Conn.Connecting -> "CaddyLog · verbinde …"
                                is LoggerEngine.Conn.Reconnecting -> "CaddyLog · Verbindung verloren"
                                else -> "CaddyLog · nicht verbunden"
                            }
                        },
                        fontWeight = if (critical || warning) FontWeight.Bold else FontWeight.Normal,
                    )
                })
        },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i; if (i != 2) openTrip = null },
                        icon = { Icon(t.icon, contentDescription = t.title) }, label = { Text(t.title, maxLines = 1) })
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (activity != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> DashboardScreen(onOpenTrip = { openTrip = it; tab = 2; MonitorEngine.lastTrip.value = null })
                    1 -> GraphScreen()
                    2 -> TripsScreen(openTrip) { openTrip = it }
                    3 -> DiagnoseScreen()
                    4 -> SettingsScreen()
                }
            }
        }
    }

    dialog?.let { CriticalWarning(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnoseScreen() {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Verbindung", "Scan", "Alle Werte", "Konsole")
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = sub) {
            titles.forEachIndexed { i, t -> Tab(selected = sub == i, onClick = { sub = i }, text = { Text(t, maxLines = 1) }) }
        }
        Box(Modifier.weight(1f)) {
            when (sub) {
                0 -> ConnectScreen()
                1 -> ScanScreen()
                2 -> AllValuesScreen()
                3 -> ConsoleScreen()
            }
        }
    }
}

/** Full-screen critical warning. Only closed by the user's confirmation (or when the temperature drops). */
@Composable
private fun CriticalWarning(d: WarningDialog) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val bg by pulse.animateColor(Color(0xFFB71C1C), Color(0xFFE53935),
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "bg")
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = bg, contentColor = Color.White) {
            Column(
                Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.Warning, null, Modifier.size(96.dp))
                Spacer(Modifier.height(12.dp))
                Text(if (d.level == CoolantLevel.EXTREME) "EXTREME ÜBERHITZUNG" else "KÜHLSYSTEM WARNUNG",
                    fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Text("Kühlmitteltemperatur", fontSize = 20.sp)
                Text("${d.tempC?.let { CoolantMonitor.fmt(it) } ?: "–"} °C", fontSize = 84.sp, lineHeight = 90.sp, fontWeight = FontWeight.Black)
                if (d.text.isNotBlank()) Text(d.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Text("Motor möglichst nicht weiter belasten. Ursache prüfen.", fontSize = 18.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text("Ausgleichsbehälter im heißen Zustand nicht öffnen.", fontSize = 15.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = MonitorEngine::acknowledge,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB71C1C)),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) { Text("Warnung bestätigen", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(12.dp))
                Text("Diagnose-Hinweis auf Basis gemessener Werte – keine Fehlerdiagnose. " +
                        "Bei anhaltend kritischer Temperatur wird erneut gewarnt.", fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

fun shareIntent(uri: android.net.Uri): Intent =
    Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Aufzeichnung teilen"
    )
