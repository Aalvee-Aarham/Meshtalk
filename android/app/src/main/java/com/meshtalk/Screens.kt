package com.meshtalk

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

fun ago(t: Long): String {
    val s = (System.currentTimeMillis() - t) / 1000
    return when {
        s < 10 -> "just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

fun clock(t: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))

@Composable
fun Avatar(name: String, color: Color = MaterialTheme.colorScheme.primaryContainer, online: Boolean = false) {
    Box {
        Box(Modifier.size(44.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase().ifEmpty { "?" }, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        if (online) Box(
            Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface).padding(2.dp).clip(CircleShape).background(Color(0xFF43A047)),
        )
    }
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

fun isOnline(s: Mesh.Snapshot, id: Int) = s.nodes.any { it.id == id && System.currentTimeMillis() - it.lastSeen < Mesh.ACTIVE_MS }

// ---------------- Chats ----------------

@Composable
fun ChatsScreen(s: Mesh.Snapshot, onOpen: (Int) -> Unit) {
    val peers = listOf(Proto.BCAST) + s.friends.keys
    LazyColumn(Modifier.fillMaxSize()) {
        items(peers, key = { it }) { peer ->
            val last = s.msgs.lastOrNull { it.peer == peer }
            val unread = s.msgs.count { it.peer == peer && !it.read && !it.out && it.type != Proto.SOS }
            val name = if (peer == Proto.BCAST) "Everyone" else s.friends[peer] ?: "#%04X".format(peer)
            ListItem(
                modifier = Modifier.clickable { onOpen(peer) },
                leadingContent = {
                    Avatar(if (peer == Proto.BCAST) "@" else name,
                        if (peer == Proto.BCAST) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                        peer != Proto.BCAST && isOnline(s, peer))
                },
                headlineContent = { Text(name, fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Normal) },
                supportingContent = {
                    Text(
                        last?.let { (if (it.out) "You: " else if (peer == Proto.BCAST) "${Mesh.nameOf(it.from)}: " else "") + it.text }
                            ?: if (peer == Proto.BCAST) "Broadcast to every node in range" else "Say hi",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        last?.let { Text(clock(it.time), style = MaterialTheme.typography.labelSmall) }
                        if (unread > 0) Badge { Text("$unread") }
                    }
                },
            )
        }
        if (s.friends.isEmpty()) item {
            Text("No friends yet. Open the Nearby tab and tap Add on a node to send a friend request.",
                Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(s: Mesh.Snapshot, peer: Int, onBack: () -> Unit) {
    val msgs = s.msgs.filter { it.peer == peer }.asReversed()
    val isFriend = peer == Proto.BCAST || peer in s.friends
    var text by rememberSaveable(peer) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    DisposableEffect(peer) {
        Mesh.openChat = peer
        onDispose { Mesh.openChat = null }
    }
    LaunchedEffect(peer, s.msgs.size) { Mesh.markRead(peer) }
    val node = s.nodes.firstOrNull { it.id == peer }
    val subtitle = when {
        peer == Proto.BCAST -> "Broadcast · every reachable node"
        node == null -> "Not seen yet · messages wait until they're reachable"
        System.currentTimeMillis() - node.lastSeen < Mesh.ACTIVE_MS ->
            if (node.hops == 0) "Online · nearby" else "Online · ${node.hops + 1} hops away"
        else -> "Last seen ${ago(node.lastSeen)}"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(Mesh.nameOf(peer), maxLines = 1)
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton({ menu = true }) { Icon(Icons.Filled.Delete, "Clear chat") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Clear this chat") }, { Mesh.clearChat(peer); menu = false })
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                if (!isFriend) Text("You're no longer friends. Send a new request from Nearby.", Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
                else Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val used = text.toByteArray().size
                    OutlinedTextField(
                        text, { if (it.toByteArray().size <= Proto.MAX_TXT) text = it },
                        Modifier.weight(1f), placeholder = { Text(if (peer == Proto.BCAST) "Message everyone" else "Message") },
                        supportingText = { if (used > Proto.MAX_TXT - 40) Text("$used/${Proto.MAX_TXT}") },
                        maxLines = 4, shape = RoundedCornerShape(24.dp),
                    )
                    IconButton({ if (Mesh.sendText(peer, text)) text = "" }, enabled = text.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
    ) { pad ->
        if (msgs.isEmpty()) Box(Modifier.padding(pad)) { Empty("No messages yet") }
        else LazyColumn(Modifier.padding(pad).fillMaxSize(), reverseLayout = true, contentPadding = PaddingValues(12.dp)) {
            items(msgs, key = { "${it.from}-${it.id}-${it.time}" }) { Bubble(it, peer == Proto.BCAST) }
        }
    }
}

@Composable
fun Bubble(m: Mesh.Msg, showAuthor: Boolean) {
    val sos = m.type == Proto.SOS
    val bg = when {
        sos -> SosRed
        m.out -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (sos || m.out) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val status = when (m.status) {
        Mesh.ST_PENDING -> "🕓 sending"
        Mesh.ST_SENT -> "✓ sent"
        Mesh.ST_DELIVERED -> "✓✓ delivered"
        Mesh.ST_FAILED -> "⚠ not delivered · tap to retry"
        else -> ""
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalAlignment = if (m.out) Alignment.End else Alignment.Start) {
        if (showAuthor && !m.out) Text(Mesh.nameOf(m.from), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
        Surface(
            color = bg, shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 300.dp).then(if (m.status == Mesh.ST_FAILED) Modifier.clickable { Mesh.retry(m) } else Modifier),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                if (sos) Text("SOS ALERT", color = fg, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
                Text(m.text, color = fg)
                Text(clock(m.time) + if (m.out) "  $status" else "", color = fg.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------- Nearby ----------------

@Composable
fun NearbyScreen(s: Mesh.Snapshot, onChat: (Int) -> Unit) {
    val now = System.currentTimeMillis()
    val list = s.nodes.filter { now - it.lastSeen < 5 * 60_000 }
        .sortedWith(compareBy({ now - it.lastSeen > Mesh.ACTIVE_MS }, { it.hops }, { -it.rssi }))
    if (list.isEmpty()) {
        Empty("Searching for nodes…\n\nRadio: ${s.radio}\nKeep other MeshTalk phones or ESP32 nodes within ~10–30 m.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { n ->
            val direct = now - n.lastDirect < Mesh.NEIGHBOR_MS
            val name = Mesh.nameOf(n.id)
            ListItem(
                leadingContent = { Avatar(if (n.kind == Mesh.KIND_ESP) "E" else name, online = now - n.lastSeen < Mesh.ACTIVE_MS) },
                headlineContent = { Text("$name  #%04X".format(n.id)) },
                supportingContent = {
                    val kind = when (n.kind) { Mesh.KIND_ESP -> "ESP32 node"; Mesh.KIND_PHONE -> "Phone"; else -> "Node" }
                    val link = if (direct) "${n.rssi} dBm · ~%.1f m".format(Mesh.distance(n.rssi)) else "${n.hops + 1} hops"
                    Text("$kind · $link · ${ago(n.lastSeen)}")
                },
                trailingContent = {
                    when {
                        n.id in s.friends -> FilledTonalButton({ onChat(n.id) }) { Text("Message") }
                        n.id in s.reqIn -> Button({ Mesh.answerRequest(n.id, true) }) { Text("Accept") }
                        n.id in s.reqOut -> OutlinedButton({}, enabled = false) { Text("Requested") }
                        else -> OutlinedButton({ Mesh.requestFriend(n.id) }) { Text("Add") }
                    }
                },
            )
        }
    }
}

// ---------------- Requests ----------------

@Composable
fun RequestsScreen(s: Mesh.Snapshot) {
    if (s.reqIn.isEmpty() && s.reqOut.isEmpty()) {
        Empty("No friend requests.\nSend one from the Nearby tab.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (s.reqIn.isNotEmpty()) item { Section("Incoming") }
        items(s.reqIn.entries.toList(), key = { "in${it.key}" }) { (id, name) ->
            ListItem(
                leadingContent = { Avatar(name) },
                headlineContent = { Text("$name  #%04X".format(id)) },
                supportingContent = { Text("wants to chat with you") },
                trailingContent = {
                    Row {
                        TextButton({ Mesh.answerRequest(id, false) }) { Text("Decline") }
                        Button({ Mesh.answerRequest(id, true) }) { Text("Accept") }
                    }
                },
            )
        }
        if (s.reqOut.isNotEmpty()) item { Section("Sent") }
        items(s.reqOut.toList(), key = { "out$it" }) { id ->
            ListItem(
                leadingContent = { Avatar(Mesh.nameOf(id)) },
                headlineContent = { Text(Mesh.nameOf(id)) },
                supportingContent = { Text("Waiting for an answer · retried automatically") },
                trailingContent = { TextButton({ Mesh.cancelRequest(id) }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
fun Section(t: String) = Text(t, Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary)

// ---------------- SOS ----------------

@Composable
fun SosDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var extra by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send SOS to everyone?") },
        text = {
            Column {
                Text("A high-priority alert goes to every node in the mesh, including ESP32 nodes.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(extra, { if (it.toByteArray().size <= 100) extra = it }, label = { Text("Details (optional)") },
                    placeholder = { Text("e.g. injured, at north gate") })
            }
        },
        confirmButton = {
            Button({ onSend(extra) }, colors = ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White)) {
                Text("SEND SOS", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SosBanner(m: Mesh.Msg, modifier: Modifier, onOpen: () -> Unit) {
    Surface(color = SosRed, modifier = modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.statusBarsPadding().padding(16.dp)) {
            Text("SOS from ${Mesh.nameOf(m.from)} · ${ago(m.time)}", color = Color.White, fontWeight = FontWeight.Black)
            Text(m.text, color = Color.White)
            Text("Tap to acknowledge", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------------- Settings ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(s: Mesh.Snapshot, onBack: () -> Unit, onStop: () -> Unit) {
    val ctx = LocalContext.current
    var name by rememberSaveable { mutableStateOf(s.myName) }
    var oldPin by rememberSaveable { mutableStateOf("") }
    var newPin by rememberSaveable { mutableStateOf("") }
    var pinMsg by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Node ID  #%04X".format(s.myId), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { if (it.toByteArray().size <= Proto.NAME_LEN) name = it }, label = { Text("Display name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton({ Mesh.setName(name) }, enabled = name.isNotBlank() && name.trim() != s.myName) { Text("Save") } })

            HorizontalDivider()
            Text("Admin PIN", style = MaterialTheme.typography.titleMedium)
            if (s.defaultPin) Text("You're still using the default PIN. Change it before deployment.", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField(oldPin, "Current", Modifier.weight(1f)) { oldPin = it }
                PinField(newPin, "New (4–8)", Modifier.weight(1f)) { newPin = it }
            }
            Button({
                pinMsg = when {
                    !Mesh.checkPin(oldPin) -> "Current PIN is wrong"
                    newPin.length !in 4..8 -> "New PIN must be 4–8 digits"
                    else -> { Mesh.setPin(newPin); oldPin = ""; newPin = ""; "PIN changed" }
                }
            }) { Text("Change PIN") }
            if (pinMsg.isNotEmpty()) Text(pinMsg)

            HorizontalDivider()
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(ctx.packageName) == false) {
                Text("For reliable relaying with the screen off, let MeshTalk run in the background.")
                OutlinedButton({
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }
                }) { Text("Allow background running") }
            }
            OutlinedButton(onStop, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Stop mesh node and exit")
            }
        }
    }
}

@Composable
fun PinField(v: String, label: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        v, { if (it.length <= 8 && it.all(Char::isDigit)) onChange(it) }, modifier, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}
