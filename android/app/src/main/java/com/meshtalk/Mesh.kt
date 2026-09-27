package com.meshtalk

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * The mesh node. Same rules as the ESP firmware: TTL flooding, duplicate suppression,
 * store-and-forward for reliable packets, retry-until-ACK outbox.
 * Everything runs on the main thread, so no locking is needed.
 */
object Mesh {
    const val KIND_ESP = 0
    const val KIND_PHONE = 1

    const val ST_PENDING = 0
    const val ST_SENT = 1
    const val ST_DELIVERED = 2
    const val ST_FAILED = 3
    const val ST_RECEIVED = 4

    private const val DEDUP_MS = 20_000L
    private const val HELLO_MS = 20_000L
    private const val NBRS_MS = 30_000L
    const val NEIGHBOR_MS = 45_000L
    const val ACTIVE_MS = 60_000L
    private const val NODE_KEEP_MS = 30 * 60_000L
    private const val SF_KEEP_MS = 10 * 60_000L
    private const val OUT_EXPIRE_MS = 24 * 3600_000L
    private const val HOLD_MS = 400
    private const val SOS_HOLD_MS = 800
    private const val MAX_MSGS = 1000
    private const val MAX_LOG = 300

    // Distance estimate calibration: RSSI at 1 m and path-loss exponent. Tune per environment.
    var rssiAt1m = -59
    var pathLoss = 2.5

    data class Node(
        val id: Int, val name: String = "", val kind: Int = -1, val hops: Int = 0, val rssi: Int = 0,
        val lastSeen: Long = 0, val lastDirect: Long = 0, val uptimeMin: Int = 0, val relays: Int = 0,
        val nbrs: Map<Int, Int> = emptyMap(), val nbrsAt: Long = 0,
    )

    /** [peer] = conversation (a friend id, or BCAST for the Everyone channel), [from] = author. */
    data class Msg(
        val peer: Int, val from: Int, val id: Int, val out: Boolean, val type: Int,
        val status: Int, val time: Long, val text: String, val read: Boolean,
    )

    data class Out(val type: Int, val dst: Int, val id: Int, val text: String, val created: Long, val nextTry: Long, val tries: Int)
    data class LogLine(val time: Long, val dir: String, val type: Int, val src: Int, val dst: Int, val ttl: Int, val hops: Int, val rssi: Int)

    data class Snapshot(
        val myId: Int = 0, val myName: String = "", val running: Boolean = false, val radio: String = "Stopped",
        val nodes: List<Node> = emptyList(), val friends: Map<Int, String> = emptyMap(),
        val reqIn: Map<Int, String> = emptyMap(), val reqOut: Set<Int> = emptySet(),
        val msgs: List<Msg> = emptyList(), val pending: Int = 0, val log: List<LogLine> = emptyList(),
        val relays: Int = 0, val rx: Int = 0, val tx: Int = 0, val startedAt: Long = 0, val defaultPin: Boolean = true,
    )

    sealed interface Event {
        data class Message(val m: Msg) : Event
        data class Request(val id: Int, val name: String) : Event
        data class Info(val text: String) : Event
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var file: File
    private var radio: BleRadio? = null

    var myId = 0; private set
    var myName = ""; private set
    private var pinHash = sha256("1234")
    private val nodes = HashMap<Int, Node>()
    private val friends = LinkedHashMap<Int, String>()
    private val reqIn = LinkedHashMap<Int, String>()
    private val reqOut = LinkedHashSet<Int>()
    private val msgs = ArrayList<Msg>()
    private val outbox = ArrayList<Out>()
    private val seen = HashMap<Long, Long>()
    private val done = LinkedHashSet<Long>()
    private val sf = LinkedHashMap<Long, Pair<Packet, Long>>()
    private val reasm = Reassembler()
    private val log = ArrayDeque<LogLine>()
    private var relays = 0
    private var rxCount = 0
    private var txCount = 0
    private var startedAt = 0L
    private var nextId = Random.nextInt(0x10000)
    private var nextHello = 0L
    private var nextNbrs = 0L
    private var lastPublish = 0L
    private var lastSave = 0L
    private var lastCleanup = 0L
    private var dirty = false
    private var saveDirty = false
    var running = false; private set

    @Volatile var appVisible = false
    var openChat: Int? = null

    val state = MutableStateFlow(Snapshot())
    val events = MutableSharedFlow<Event>(extraBufferCapacity = 64)

    private fun now() = System.currentTimeMillis()
    private fun newId() = (nextId++) and 0xFFFF

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, "mesh.json")
        load()
        publish()
    }

    fun start(ctx: Context) {
        init(ctx)
        if (running || myName.isEmpty()) return
        running = true
        startedAt = now()
        nextHello = startedAt + Random.nextLong(500, 2000)
        nextNbrs = startedAt + 10_000
        radio = BleRadio(ctx.applicationContext) { data, rssi -> main.post { onRaw(data, rssi) } }
        main.post(tick)
        publish()
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        radio?.stop()
        radio = null
        save()
        publish()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = now()
            radio?.service(now)
            beacons(now)
            outboxService(now)
            if (now - lastCleanup > 5_000) cleanup(now)
            if (dirty && now - lastPublish >= 250) publish()
            if (saveDirty && now - lastSave > 2_000) save()
            main.postDelayed(this, 50)
        }
    }

    // ---------------- sending ----------------

    private fun send(type: Int, dst: Int, id: Int, data: ByteArray) {
        val sos = type == Proto.SOS
        for (p in Packet.fragment(type, myId, dst, id, data, if (sos) Proto.TTL_SOS else Proto.TTL)) {
            radio?.enqueue(p.encode(), 0, if (sos) SOS_HOLD_MS else HOLD_MS, sos)
            txCount++
            addLog("TX", p, 0)
        }
    }

    private fun sendAck(to: Int, id: Int, type: Int) {
        val d = ByteArray(3)
        put16(d, 0, id); d[2] = type.toByte()
        send(Proto.ACK, to, newId(), d)
    }

    private fun queueOut(type: Int, dst: Int, text: String): Int {
        val id = newId()
        outbox.add(Out(type, dst, id, text, now(), now(), 0))
        saveDirty = true
        return id
    }

    private fun outboxService(now: Long) {
        val it = outbox.listIterator()
        while (it.hasNext()) {
            val o = it.next()
            if (now - o.created > OUT_EXPIRE_MS) {
                it.remove()
                setStatus(o.dst, o.id, ST_FAILED)
                continue
            }
            if (now < o.nextTry) continue
            send(o.type, o.dst, o.id, utf8Clip(o.text, Proto.MAX_TXT))
            val backoff = min(30_000L shl min(o.tries, 3), 300_000L)  // 30s, 60s, 120s, 240s, then 5 min
            it.set(o.copy(nextTry = now + backoff, tries = o.tries + 1))
            saveDirty = true
        }
    }

    private fun beacons(now: Long) {
        if (now >= nextHello) {
            nextHello = now + HELLO_MS + Random.nextLong(0, 5000)
            val d = ByteArray(Proto.CHUNK)
            d[0] = KIND_PHONE.toByte()
            put16(d, 1, min((now - startedAt) / 60_000, 0xFFFF).toInt())
            put16(d, 3, min(relays, 0xFFFF))
            utf8Clip(myName, Proto.NAME_LEN).copyInto(d, 5)
            send(Proto.HELLO, Proto.BCAST, newId(), d)
        }
        if (now >= nextNbrs) {
            nextNbrs = now + NBRS_MS + Random.nextLong(0, 5000)
            val nb = nodes.values.filter { now - it.lastDirect < NEIGHBOR_MS }.take(20)
            if (nb.isNotEmpty()) {
                val d = ByteArray(1 + nb.size * 3)
                d[0] = nb.size.toByte()
                nb.forEachIndexed { i, n -> put16(d, 1 + i * 3, n.id); d[3 + i * 3] = n.rssi.toByte() }
                send(Proto.NBRS, Proto.BCAST, newId(), d)
            }
        }
    }

    private fun cleanup(now: Long) {
        lastCleanup = now
        seen.entries.removeAll { now - it.value > DEDUP_MS }
        sf.entries.removeAll { now - it.value.second > SF_KEEP_MS }
        nodes.entries.removeAll { now - it.value.lastSeen > NODE_KEEP_MS }
        reasm.expire(now)
        dirty = true  // refresh "last seen" labels
    }

    // ---------------- receiving ----------------

    private fun onRaw(data: ByteArray, rssi: Int) {
        if (!running) return
        val p = Packet.decode(data) ?: return
        val now = now()
        if (p.src == myId || p.src == 0 || p.src == Proto.BCAST) return
        val key = (p.src.toLong() shl 24) or (p.id.toLong() shl 8) or p.frag.toLong()
        val t = seen[key]
        if (t != null && now - t < DEDUP_MS) return
        seen[key] = now
        rxCount++
        addLog("RX", p, rssi)

        val old = nodes[p.src]
        val wasAbsent = old == null || now - old.lastSeen > ACTIVE_MS
        var n = (old ?: Node(p.src)).copy(lastSeen = now, hops = p.hops)
        if (p.hops == 0) n = n.copy(lastDirect = now, rssi = rssi)
        nodes[p.src] = n
        if (wasAbsent) onNodeBack(p.src)
        if (p.type == Proto.ACK) sfAcked(origSrc = p.dst, origDst = p.src, id = get16(p.payload, 0))

        if (p.dst != myId && p.ttl > 1) {
            val sos = p.type == Proto.SOS
            val fwd = p.relayed()
            radio?.enqueue(fwd.encode(), if (sos) 0 else Random.nextLong(20, 150), if (sos) SOS_HOLD_MS else HOLD_MS, sos)
            relays++
            addLog("FWD", fwd, rssi)
            if (Proto.reliable(p.type) && p.dst != Proto.BCAST) sf[sfKey(p)] = p to now
        }
        dirty = true
        if (p.dst != myId && p.dst != Proto.BCAST) return
        val payload = reasm.add(p, now) ?: return
        deliver(p.type, p.src, p.dst, p.id, payload, now)
    }

    private fun sfKey(p: Packet) = (p.src.toLong() shl 40) or (p.dst.toLong() shl 24) or (p.id.toLong() shl 8) or p.frag.toLong()

    private fun sfAcked(origSrc: Int, origDst: Int, id: Int) {
        sf.entries.removeAll { (_, v) -> v.first.src == origSrc && v.first.dst == origDst && v.first.id == id }
    }

    /** A node reappeared after being away: push everything we hold for it immediately. */
    private fun onNodeBack(id: Int) {
        outbox.replaceAll { if (it.dst == id) it.copy(nextTry = 0) else it }
        for ((p, _) in sf.values) if (p.dst == id) {
            radio?.enqueue(p.withTtl(Proto.TTL).encode(), Random.nextLong(20, 200), HOLD_MS, false)
        }
    }

    private fun deliver(type: Int, src: Int, dst: Int, id: Int, data: ByteArray, now: Long) {
        when (type) {
            Proto.HELLO -> {
                val name = data.copyOfRange(5, 5 + Proto.NAME_LEN).cString()
                nodes[src] = nodes.getValue(src).copy(kind = data[0].u(), uptimeMin = get16(data, 1), relays = get16(data, 3), name = name)
                if (src in friends && name.isNotEmpty() && friends[src] != name) { friends[src] = name; saveDirty = true }
            }
            Proto.NBRS -> {
                val c = min(data[0].u(), (data.size - 1) / 3)
                val m = (0 until c).associate { get16(data, 1 + it * 3) to data[3 + it * 3].toInt() }
                nodes[src] = nodes.getValue(src).copy(nbrs = m, nbrsAt = now)
            }
            Proto.ACK -> if (dst == myId) {
                val acked = get16(data, 0)
                if (outbox.removeAll { it.dst == src && it.id == acked }) saveDirty = true
                setStatus(src, acked, ST_DELIVERED)
                if (data[2].u() == Proto.FREQ) info("Friend request delivered to ${nameOf(src)}")
            }
            Proto.BROADCAST, Proto.SOS -> {
                val m = Msg(Proto.BCAST, src, id, false, type, ST_RECEIVED, now, data.cString(),
                    read = type != Proto.SOS && appVisible && openChat == Proto.BCAST)
                addMsg(m)
                events.tryEmit(Event.Message(m))
            }
            else -> if (Proto.reliable(type) && dst == myId) deliverReliable(type, src, id, data.cString(), now)
        }
    }

    private fun deliverReliable(type: Int, src: Int, id: Int, text: String, now: Long) {
        if (type == Proto.MSG && src !in friends) return  // private chat only after an accepted request
        sendAck(src, id, type)
        val key = (src.toLong() shl 16) or id.toLong()
        if (!done.add(key)) return  // retry of something we already have
        if (done.size > 512) done.remove(done.first())
        when (type) {
            Proto.MSG -> {
                val m = Msg(src, src, id, false, type, ST_RECEIVED, now, text, read = appVisible && openChat == src)
                addMsg(m)
                events.tryEmit(Event.Message(m))
            }
            Proto.FREQ -> {
                nodes[src]?.let { if (text.isNotEmpty()) nodes[src] = it.copy(name = text) }
                if (src in friends) { queueOut(Proto.FACC, src, myName); return }  // they lost us: re-accept
                reqIn[src] = text.ifEmpty { nameOf(src) }
                saveDirty = true
                events.tryEmit(Event.Request(src, reqIn.getValue(src)))
            }
            Proto.FACC -> if (reqOut.remove(src) || src in friends) {
                friends[src] = text.ifEmpty { nameOf(src) }
                saveDirty = true
                info("${friends[src]} accepted your friend request")
            }
            Proto.FREJ -> if (reqOut.remove(src)) {
                saveDirty = true
                info("${nameOf(src)} declined your friend request")
            }
        }
    }

    // ---------------- UI actions ----------------

    fun nameOf(id: Int): String = when {
        id == myId -> myName
        id == Proto.BCAST -> "Everyone"
        else -> friends[id] ?: nodes[id]?.name?.ifEmpty { null } ?: reqIn[id] ?: "#%04X".format(id)
    }

    fun sendText(peer: Int, text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val clipped = String(utf8Clip(t, Proto.MAX_TXT), Charsets.UTF_8)
        if (peer == Proto.BCAST) {
            val id = newId()
            send(Proto.BROADCAST, Proto.BCAST, id, utf8Clip(clipped, Proto.MAX_TXT))
            addMsg(Msg(Proto.BCAST, myId, id, true, Proto.BROADCAST, ST_SENT, now(), clipped, true))
        } else {
            if (peer !in friends) return false
            val id = queueOut(Proto.MSG, peer, clipped)
            addMsg(Msg(peer, myId, id, true, Proto.MSG, ST_PENDING, now(), clipped, true))
        }
        return true
    }

    fun retry(m: Msg) {
        if (!m.out || m.status != ST_FAILED || m.peer !in friends) return
        msgs.remove(m)
        sendText(m.peer, m.text)
    }

    fun sendSos(extra: String) {
        val text = "SOS from $myName!" + if (extra.isBlank()) " Need help." else " ${extra.trim()}"
        val clipped = String(utf8Clip(text, Proto.MAX_TXT), Charsets.UTF_8)
        val id = newId()
        send(Proto.SOS, Proto.BCAST, id, utf8Clip(clipped, Proto.MAX_TXT))
        addMsg(Msg(Proto.BCAST, myId, id, true, Proto.SOS, ST_SENT, now(), clipped, true))
    }

    fun requestFriend(id: Int) {
        if (id in friends) return
        if (id in reqIn) { answerRequest(id, true); return }
        if (!reqOut.add(id)) return
        queueOut(Proto.FREQ, id, myName)
        info("Friend request queued for ${nameOf(id)}")
        changed()
    }

    fun cancelRequest(id: Int) {
        reqOut.remove(id)
        outbox.removeAll { it.dst == id && it.type == Proto.FREQ }
        changed()
    }

    fun answerRequest(id: Int, accept: Boolean) {
        val name = reqIn.remove(id) ?: nameOf(id)
        queueOut(if (accept) Proto.FACC else Proto.FREJ, id, myName)
        if (accept) friends[id] = name
        changed()
    }

    fun removeFriend(id: Int) {
        friends.remove(id)
        outbox.removeAll { it.dst == id && it.type == Proto.MSG }
        msgs.replaceAll { if (it.peer == id && it.status == ST_PENDING) it.copy(status = ST_FAILED) else it }
        changed()
    }

    fun setName(name: String) {
        myName = String(utf8Clip(name.trim(), Proto.NAME_LEN), Charsets.UTF_8)
        nextHello = 0  // announce right away
        changed()
    }

    fun markRead(peer: Int) {
        if (msgs.none { it.peer == peer && !it.read && it.type != Proto.SOS }) return
        msgs.replaceAll { if (it.peer == peer && it.type != Proto.SOS) it.copy(read = true) else it }
        changed()
    }

    fun ackSos() {
        msgs.replaceAll { if (it.type == Proto.SOS) it.copy(read = true) else it }
        changed()
    }

    fun clearChat(peer: Int) {
        msgs.removeAll { it.peer == peer }
        changed()
    }

    fun checkPin(pin: String) = sha256(pin) == pinHash

    fun setPin(pin: String) {
        pinHash = sha256(pin)
        changed()
    }

    fun distance(rssi: Int): Double = 10.0.pow((rssiAt1m - rssi) / (10 * pathLoss))

    // ---------------- internals ----------------

    private fun setStatus(peer: Int, id: Int, st: Int) {
        msgs.replaceAll { if (it.out && it.peer == peer && it.id == id && it.status == ST_PENDING) it.copy(status = st) else it }
        changed()
    }

    private fun addMsg(m: Msg) {
        msgs.add(m)
        if (msgs.size > MAX_MSGS) msgs.removeAt(0)
        changed()
    }

    private fun info(text: String) {
        events.tryEmit(Event.Info(text))
    }

    private fun addLog(dir: String, p: Packet, rssi: Int) {
        log.addFirst(LogLine(now(), dir, p.type, p.src, p.dst, p.ttl, p.hops, rssi))
        if (log.size > MAX_LOG) log.removeLast()
    }

    private fun changed() {
        dirty = true
        saveDirty = true
        if (!running) { publish(); save() }
    }

    private fun publish() {
        lastPublish = now()
        dirty = false
        state.value = Snapshot(
            myId, myName, running, radio?.status ?: "Stopped", nodes.values.toList(), LinkedHashMap(friends),
            LinkedHashMap(reqIn), reqOut.toSet(), msgs.toList(), outbox.size, log.toList(),
            relays, rxCount, txCount, startedAt, pinHash == sha256("1234"),
        )
    }

    private fun save() {
        if (!::file.isInitialized) return
        lastSave = now()
        saveDirty = false
        val j = JSONObject()
            .put("id", myId).put("name", myName).put("pin", pinHash)
            .put("friends", JSONArray(friends.map { JSONObject().put("id", it.key).put("name", it.value) }))
            .put("reqIn", JSONArray(reqIn.map { JSONObject().put("id", it.key).put("name", it.value) }))
            .put("reqOut", JSONArray(reqOut.toList()))
            .put("msgs", JSONArray(msgs.map {
                JSONObject().put("peer", it.peer).put("from", it.from).put("id", it.id).put("out", it.out).put("type", it.type)
                    .put("st", it.status).put("t", it.time).put("text", it.text).put("read", it.read)
            }))
            .put("outbox", JSONArray(outbox.map {
                JSONObject().put("type", it.type).put("dst", it.dst).put("id", it.id).put("text", it.text)
                    .put("created", it.created).put("tries", it.tries)
            }))
        val s = j.toString()
        val f = file
        io.execute {
            runCatching {
                val tmp = File(f.path + ".tmp")
                tmp.writeText(s)
                tmp.renameTo(f)  // atomic replace: a crash never leaves a half-written file
            }
        }
    }

    private fun load() {
        val j = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (j == null) {
            myId = Random.nextInt(1, 0xFFFF)
            save()
            return
        }
        myId = j.optInt("id").takeIf { it in 1 until 0xFFFF } ?: Random.nextInt(1, 0xFFFF)
        myName = j.optString("name")
        pinHash = j.optString("pin").ifEmpty { pinHash }
        j.optJSONArray("friends")?.forEachObj { friends[it.getInt("id")] = it.getString("name") }
        j.optJSONArray("reqIn")?.forEachObj { reqIn[it.getInt("id")] = it.getString("name") }
        j.optJSONArray("reqOut")?.let { a -> for (i in 0 until a.length()) reqOut.add(a.getInt(i)) }
        j.optJSONArray("msgs")?.forEachObj {
            msgs.add(Msg(it.getInt("peer"), it.getInt("from"), it.getInt("id"), it.getBoolean("out"), it.getInt("type"),
                it.getInt("st"), it.getLong("t"), it.getString("text"), it.getBoolean("read")))
        }
        j.optJSONArray("outbox")?.forEachObj {
            outbox.add(Out(it.getInt("type"), it.getInt("dst"), it.getInt("id"), it.getString("text"), it.getLong("created"), 0, it.getInt("tries")))
        }
    }

    private inline fun JSONArray.forEachObj(f: (JSONObject) -> Unit) {
        for (i in 0 until length()) runCatching { f(getJSONObject(i)) }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
