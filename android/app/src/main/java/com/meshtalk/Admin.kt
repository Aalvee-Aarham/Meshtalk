package com.meshtalk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt
import kotlin.random.Random

data class Edge(val a: Int, val b: Int, val rssi: Int)

private const val GRAPH_MS = 120_000L

/** Links known from our own radio plus every node's NBRS report, deduplicated (strongest RSSI wins). */
fun topology(s: Mesh.Snapshot): Pair<List<Int>, List<Edge>> {
    val now = System.currentTimeMillis()
    val live = s.nodes.filter { now - it.lastSeen < GRAPH_MS }
    val ids = (listOf(s.myId) + live.map { it.id }).toSet()
    val edges = HashMap<Pair<Int, Int>, Int>()
    fun add(a: Int, b: Int, r: Int) {
        if (a == b || a !in ids || b !in ids) return
        val k = if (a < b) a to b else b to a
        edges[k] = maxOf(edges[k] ?: Int.MIN_VALUE, r)
    }
    live.filter { now - it.lastDirect < Mesh.NEIGHBOR_MS }.forEach { add(s.myId, it.id, it.rssi) }
    live.filter { now - it.nbrsAt < 90_000 }.forEach { n -> n.nbrs.forEach { (id, r) -> add(n.id, id, r) } }
    return ids.toList() to edges.map { Edge(it.key.first, it.key.second, it.value) }
}

fun rssiColor(r: Int) = when {
    r > -65 -> Color(0xFF2E7D32)
    r > -80 -> Color(0xFFF9A825)
    else -> Color(0xFFC62828)
}

@Composable
fun AdminScreen(s: Mesh.Snapshot) {
    var unlocked by rememberSaveable { mutableStateOf(false) }
    if (!unlocked) {
        AdminLock { unlocked = true }
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val now = System.currentTimeMillis()
    val (ids, edges) = remember(s) { topology(s) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Active nodes", "${s.nodes.count { now - it.lastSeen < Mesh.ACTIVE_MS } + 1}")
            Stat("Links", "${edges.size}")
            Stat("Relayed", "${s.relays}")
            Stat("RX / TX", "${s.rx} / ${s.tx}")
            Stat("Pending", "${s.pending}")
            Stat("SOS seen", "${s.msgs.count { it.type == Proto.SOS }}")
            Stat("Uptime", if (s.startedAt > 0) "${(now - s.startedAt) / 60_000} min" else "-")
            TextButton({ unlocked = false }) { Text("Lock") }
        }
        TabRow(tab) {
            listOf("Graph", "Nodes", "Links", "Live").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> GraphTab(s, ids, edges)
            1 -> NodesTab(s)
            2 -> LinksTab(edges)
            else -> LiveTab(s)
        }
    }
}

@Composable
fun AdminLock(onUnlock: () -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    var fails by rememberSaveable { mutableIntStateOf(0) }
    var lockedUntil by rememberSaveable { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lockedUntil) { while (now < lockedUntil) { delay(500); now = System.currentTimeMillis() } }
    val locked = now < lockedUntil
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Admin mode", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Network graph, node analytics and live traffic", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            pin, { if (it.length <= 8 && it.all(Char::isDigit)) { pin = it; err = false } },
            label = { Text("PIN") }, singleLine = true, isError = err, enabled = !locked,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            supportingText = {
                when {
                    locked -> Text("Too many attempts. Wait ${(lockedUntil - now) / 1000 + 1}s")
                    err -> Text("Wrong PIN")
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        Button({
            if (Mesh.checkPin(pin)) onUnlock()
            else {
                err = true; pin = ""; fails++
                if (fails % 5 == 0) { lockedUntil = System.currentTimeMillis() + 30_000; now = System.currentTimeMillis() }
            }
        }, enabled = pin.length >= 4 && !locked) { Text("Unlock") }
    }
}

@Composable
fun Stat(label: String, value: String) {
    Card {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun GraphTab(s: Mesh.Snapshot, ids: List<Int>, edges: List<Edge>) {
    // Force-directed layout in unit space; "me" is pinned to the centre.
    val pos = remember { mutableStateMapOf<Int, Offset>() }
    val idSet = ids.toSet()
    LaunchedEffect(idSet, edges) {
        pos.keys.retainAll(idSet)
        ids.forEach { if (it !in pos) pos[it] = if (it == s.myId) Offset.Zero else Offset(Random.nextFloat() - .5f, Random.nextFloat() - .5f) }
        repeat(240) {
            val f = HashMap<Int, Offset>()
            val list = pos.keys.toList()
            for (a in list) for (b in list) if (a != b) {
                val d = pos.getValue(a) - pos.getValue(b)
                val dist = sqrt(d.x * d.x + d.y * d.y).coerceAtLeast(0.05f)
                f[a] = (f[a] ?: Offset.Zero) + d / dist * (0.02f / (dist * dist))
            }
            for (e in edges) {
                val pa = pos[e.a] ?: continue
                val pb = pos[e.b] ?: continue
                val d = pb - pa
                val pull = d * 0.08f * (sqrt(d.x * d.x + d.y * d.y) - 0.45f)
                f[e.a] = (f[e.a] ?: Offset.Zero) + pull
                f[e.b] = (f[e.b] ?: Offset.Zero) - pull
            }
            for (id in list) if (id != s.myId) {
                val p = pos.getValue(id) + (f[id] ?: Offset.Zero).let { Offset(it.x.coerceIn(-.05f, .05f), it.y.coerceIn(-.05f, .05f)) } - pos.getValue(id) * 0.01f
                pos[id] = Offset(p.x.coerceIn(-1f, 1f), p.y.coerceIn(-1f, 1f))
            }
            delay(16)
        }
    }
    val tm = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val kinds = s.nodes.associate { it.id to it.kind }
    val now = System.currentTimeMillis()
    val sosFrom = s.msgs.filter { it.type == Proto.SOS && now - it.time < 10 * 60_000 }.map { it.from }.toSet()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(24.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val scale = size.minDimension / 2 * 0.85f
            fun at(id: Int) = c + (pos[id] ?: Offset.Zero) * scale
            for (e in edges) {
                val a = at(e.a); val b = at(e.b)
                drawLine(rssiColor(e.rssi), a, b, strokeWidth = 4f)
                val label = "${e.rssi}dBm ~%.0fm".format(Mesh.distance(e.rssi))
                drawText(tm, label, (a + b) / 2f, style = TextStyle(fontSize = 9.sp, color = onSurface.copy(alpha = .7f)))
            }
            for (id in ids) {
                val p = at(id)
                val col = when {
                    id in sosFrom -> SosRed
                    id == s.myId -> primary
                    kinds[id] == Mesh.KIND_ESP -> Color(0xFF00897B)
                    else -> Color(0xFF1E88E5)
                }
                drawCircle(col, 22f, p)
                if (id == s.myId) drawCircle(onSurface, 28f, p, style = Stroke(3f))
                drawText(tm, Mesh.nameOf(id), p + Offset(-20f, 30f), style = TextStyle(fontSize = 11.sp, color = onSurface, fontWeight = FontWeight.SemiBold))
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("You" to primary, "ESP32" to Color(0xFF00897B), "Phone" to Color(0xFF1E88E5), "SOS" to SosRed).forEach { (t, c) ->
                Text("●", color = c); Text(t, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            "Lines: green strong · yellow fair · red weak. Distances are RSSI estimates (±50%).",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
        )
        if (ids.size == 1) Text("No other nodes seen in the last 2 minutes.", Modifier.padding(16.dp))
    }
}

@Composable
fun NodesTab(s: Mesh.Snapshot) {
    val now = System.currentTimeMillis()
    LazyColumn(Modifier.fillMaxSize()) {
        items(s.nodes.sortedBy { now - it.lastSeen }, key = { it.id }) { n ->
            val kind = when (n.kind) { Mesh.KIND_ESP -> "ESP32"; Mesh.KIND_PHONE -> "Phone"; else -> "?" }
            ListItem(
                headlineContent = { Text("${Mesh.nameOf(n.id)}  #%04X  ·  $kind".format(n.id)) },
                supportingContent = {
                    Text(
                        (if (now - n.lastDirect < Mesh.NEIGHBOR_MS) "Direct ${n.rssi} dBm (~%.1f m)".format(Mesh.distance(n.rssi)) else "${n.hops + 1} hops") +
                            " · up ${n.uptimeMin} min · relayed ${n.relays} · ${n.nbrs.size} neighbours · seen ${ago(n.lastSeen)}",
                    )
                },
            )
        }
    }
}

@Composable
fun LinksTab(edges: List<Edge>) {
    if (edges.isEmpty()) { Empty("No links yet"); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(edges.sortedByDescending { it.rssi }) { e ->
            ListItem(
                headlineContent = { Text("${Mesh.nameOf(e.a)}  ↔  ${Mesh.nameOf(e.b)}") },
                supportingContent = { Text("${e.rssi} dBm · ~%.1f m".format(Mesh.distance(e.rssi)), color = rssiColor(e.rssi)) },
            )
        }
    }
}

@Composable
fun LiveTab(s: Mesh.Snapshot) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(s.log) { l ->
            val sos = l.type == Proto.SOS
            Text(
                "%s %-3s %-5s %s→%s ttl%d h%d %s".format(
                    fmt.format(Date(l.time)), l.dir, Proto.typeName(l.type), Mesh.nameOf(l.src),
                    if (l.dst == Proto.BCAST) "*" else Mesh.nameOf(l.dst), l.ttl, l.hops, if (l.dir == "TX") "" else "${l.rssi}dBm",
                ),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = if (sos) SosRed else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (sos) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
