package com.meshtalk

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

class MainActivity : ComponentActivity() {
    private val openPeer = mutableStateOf<Int?>(null)
    private val openTab = mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Mesh.init(this)
        handle(intent)
        setContent { MeshTheme { Root(openPeer, openTab) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        Mesh.appVisible = true
    }

    override fun onStop() {
        Mesh.appVisible = false
        super.onStop()
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        if (i.hasExtra(EXTRA_PEER)) openPeer.value = i.getIntExtra(EXTRA_PEER, 0)
        if (i.hasExtra(EXTRA_TAB)) openTab.value = i.getIntExtra(EXTRA_TAB, 0)
    }

    companion object {
        const val EXTRA_PEER = "peer"
        const val EXTRA_TAB = "tab"
    }
}

val SosRed = Color(0xFFD32F2F)

@Composable
fun MeshTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF4DD0C4), onPrimary = Color(0xFF003732), primaryContainer = Color(0xFF00504A),
        onPrimaryContainer = Color(0xFF9EF2E7), secondary = Color(0xFFB0CCC8), error = Color(0xFFFF8A80),
    ) else lightColorScheme(
        primary = Color(0xFF00695F), onPrimary = Color.White, primaryContainer = Color(0xFFA6F0E6),
        onPrimaryContainer = Color(0xFF00201C), secondary = Color(0xFF4A635F), error = SosRed,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

fun requiredPermissions(): Array<String> {
    val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 31) p += listOf(
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
    )
    if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
    return p.toTypedArray()
}

private fun missing(ctx: Context) = requiredPermissions().filter {
    // Notifications and coarse location are nice-to-have; don't block the app on them.
    it != Manifest.permission.POST_NOTIFICATIONS && it != Manifest.permission.ACCESS_COARSE_LOCATION &&
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
}

private fun btOn(ctx: Context) = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
private fun locationOn(ctx: Context) = ctx.getSystemService(LocationManager::class.java)?.let {
    Build.VERSION.SDK_INT < 28 || it.isLocationEnabled
} ?: true

@SuppressLint("MissingPermission")
@Composable
fun Root(openPeer: MutableState<Int?>, openTab: MutableState<Int?>) {
    val ctx = LocalContext.current
    val s by Mesh.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }  // re-check system state on resume
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    val needPerms = remember(tick) { missing(ctx) }
    val bt = remember(tick, s.radio) { btOn(ctx) }

    when {
        needPerms.isNotEmpty() -> Gate(
            "Allow Bluetooth access",
            "MeshTalk talks to nearby phones and ESP32 nodes over Bluetooth. Android files BLE scanning under " +
                "\"location\", but MeshTalk never reads or shares your location.",
            "Grant permissions",
        ) { permLauncher.launch(requiredPermissions()) }

        !bt -> Gate("Turn on Bluetooth", "The mesh runs over Bluetooth Low Energy.", "Turn on") {
            btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }

        s.myName.isEmpty() -> NameSetup { Mesh.setName(it); MeshService.start(ctx) }

        else -> {
            LaunchedEffect(Unit) { if (!Mesh.running) MeshService.start(ctx) }
            MainScreen(s, openPeer, openTab, showLocationHint = !locationOn(ctx))
        }
    }
}

@Composable
fun Gate(title: String, text: String, button: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Share, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            Button(onClick, Modifier.fillMaxWidth().height(52.dp)) { Text(button) }
        }
    }
}

@Composable
fun NameSetup(onDone: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val bytes = name.trim().toByteArray().size
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Welcome to MeshTalk", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Pick a short name other nodes will see.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                name, { if (it.toByteArray().size <= Proto.NAME_LEN) name = it },
                label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("$bytes/${Proto.NAME_LEN} characters (fits the ESP LCD)") },
            )
            Spacer(Modifier.height(16.dp))
            Button({ onDone(name) }, Modifier.fillMaxWidth().height(52.dp), enabled = name.isNotBlank()) { Text("Start") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(s: Mesh.Snapshot, openPeer: MutableState<Int?>, openTab: MutableState<Int?>, showLocationHint: Boolean) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chat by rememberSaveable { mutableStateOf<Int?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var sosDialog by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(openPeer.value) { openPeer.value?.let { chat = it; settings = false; openPeer.value = null } }
    LaunchedEffect(openTab.value) { openTab.value?.let { tab = it; chat = null; settings = false; openTab.value = null } }
    LaunchedEffect(Unit) { Mesh.events.collect { if (it is Mesh.Event.Info) snack.showSnackbar(it.text) } }
    BackHandler(chat != null || settings) { chat = null; settings = false }

    val sos = s.msgs.lastOrNull { it.type == Proto.SOS && !it.out && !it.read }

    Box(Modifier.fillMaxSize()) {
        when {
            chat != null -> ChatScreen(s, chat!!) { chat = null }
            settings -> SettingsScreen(s, onBack = { settings = false }, onStop = { MeshService.stop(ctx); (ctx as? ComponentActivity)?.finish() })
            else -> Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("MeshTalk", fontWeight = FontWeight.SemiBold)
                                Text("${s.myName} · #%04X · ${s.radio}".format(s.myId), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        actions = {
                            FilledTonalButton(
                                { sosDialog = true },
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = SosRed, contentColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                            ) { Text("SOS", fontWeight = FontWeight.Bold) }
                            IconButton({ settings = true }) { Icon(Icons.Filled.Settings, "Settings") }
                        },
                    )
                },
                bottomBar = {
                    NavigationBar {
                        val unread = s.msgs.count { !it.read && !it.out && it.type != Proto.SOS }
                        NavItem(tab == 0, "Chats", Icons.Filled.Email, unread) { tab = 0 }
                        NavItem(tab == 1, "Nearby", Icons.Filled.LocationOn, 0) { tab = 1 }
                        NavItem(tab == 2, "Requests", Icons.Filled.Person, s.reqIn.size) { tab = 2 }
                        NavItem(tab == 3, "Admin", Icons.Filled.Lock, 0) { tab = 3 }
                    }
                },
                snackbarHost = { SnackbarHost(snack) },
            ) { pad ->
                Column(Modifier.padding(pad).fillMaxSize()) {
                    if (showLocationHint) Hint("Location is off. Some phones only report Bluetooth scans with Location on.") {
                        ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                    when (tab) {
                        0 -> ChatsScreen(s) { chat = it }
                        1 -> NearbyScreen(s) { chat = it }
                        2 -> RequestsScreen(s)
                        else -> AdminScreen(s)
                    }
                }
            }
        }
        if (sos != null) SosBanner(sos, Modifier.align(Alignment.TopCenter)) { Mesh.ackSos(); chat = Proto.BCAST }
    }
    if (sosDialog) SosDialog({ sosDialog = false }) { Mesh.sendSos(it); sosDialog = false }
}

@Composable
fun RowScope.NavItem(selected: Boolean, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, badge: Int, onClick: () -> Unit) {
    NavigationBarItem(
        selected, onClick,
        icon = { BadgedBox({ if (badge > 0) Badge { Text("$badge") } }) { Icon(icon, label) } },
        label = { Text(label) },
    )
}

@Composable
fun Hint(text: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, onClick = onClick) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}
