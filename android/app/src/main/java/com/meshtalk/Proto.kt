package com.meshtalk

import kotlin.math.min

/** Wire format shared with the ESP32 firmware (meshtalk.ino). Change both sides together. */
object Proto {
    const val COMPANY_ID = 0xFFFF
    const val MAGIC = 0x4D
    const val LEN = 24
    const val HDR = 10
    const val CHUNK = LEN - HDR
    const val MAX_FRAGS = 16
    const val BCAST = 0xFFFF
    const val TTL = 7
    const val TTL_SOS = 15
    const val MAX_TXT = 160
    const val NAME_LEN = 9

    const val HELLO = 1
    const val NBRS = 2
    const val MSG = 3
    const val BROADCAST = 4
    const val SOS = 5
    const val ACK = 6
    const val FREQ = 7
    const val FACC = 8
    const val FREJ = 9

    fun reliable(t: Int) = t == MSG || t == FREQ || t == FACC || t == FREJ

    fun typeName(t: Int) = when (t) {
        HELLO -> "HELLO"; NBRS -> "NBRS"; MSG -> "MSG"; BROADCAST -> "BCAST"; SOS -> "SOS"
        ACK -> "ACK"; FREQ -> "F-REQ"; FACC -> "F-ACC"; FREJ -> "F-REJ"; else -> "T$t"
    }
}

fun Byte.u() = toInt() and 0xFF
fun get16(b: ByteArray, o: Int) = b[o].u() or (b[o + 1].u() shl 8)
fun put16(b: ByteArray, o: Int, v: Int) {
    b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
}

/** Bytes up to the first NUL, as UTF-8. */
fun ByteArray.cString(): String {
    val n = indexOf(0).let { if (it < 0) size else it }
    return String(this, 0, n, Charsets.UTF_8)
}

/** UTF-8 bytes of [s], cut to at most [max] bytes without splitting a character. */
fun utf8Clip(s: String, max: Int): ByteArray {
    val b = s.toByteArray(Charsets.UTF_8)
    if (b.size <= max) return b
    var end = max
    while (end > 0 && (b[end].u() and 0xC0) == 0x80) end--
    return b.copyOf(end)
}

class Packet(
    val type: Int, val ttl: Int, val hops: Int, val src: Int, val dst: Int, val id: Int,
    val frag: Int, val cnt: Int, val payload: ByteArray,
) {
    fun encode(): ByteArray {
        val b = ByteArray(Proto.LEN)
        b[0] = Proto.MAGIC.toByte()
        b[1] = type.toByte()
        b[2] = ((min(hops, 15) shl 4) or (ttl and 15)).toByte()
        put16(b, 3, src); put16(b, 5, dst); put16(b, 7, id)
        b[9] = ((frag shl 4) or (cnt - 1)).toByte()
        payload.copyInto(b, Proto.HDR, 0, min(payload.size, Proto.CHUNK))
        return b
    }

    fun relayed() = Packet(type, ttl - 1, min(hops + 1, 15), src, dst, id, frag, cnt, payload)
    fun withTtl(t: Int) = Packet(type, t, hops, src, dst, id, frag, cnt, payload)

    companion object {
        fun decode(b: ByteArray): Packet? {
            if (b.size != Proto.LEN || b[0].u() != Proto.MAGIC) return null
            val frag = b[9].u() shr 4
            val cnt = (b[9].u() and 15) + 1
            if (frag >= cnt) return null
            return Packet(b[1].u(), b[2].u() and 15, b[2].u() shr 4, get16(b, 3), get16(b, 5), get16(b, 7),
                frag, cnt, b.copyOfRange(Proto.HDR, Proto.LEN))
        }

        fun fragment(type: Int, src: Int, dst: Int, id: Int, data: ByteArray, ttl: Int): List<Packet> {
            val c = Proto.CHUNK
            val cnt = if (data.isEmpty()) 1 else min((data.size + c - 1) / c, Proto.MAX_FRAGS)
            return (0 until cnt).map { f ->
                val part = data.copyOfRange(min(f * c, data.size), min((f + 1) * c, data.size)).copyOf(c)
                Packet(type, ttl, 0, src, dst, id, f, cnt, part)
            }
        }
    }
}

/** Collects fragments of multi-part messages; returns the full payload once every part arrived. */
class Reassembler {
    private class Part(val cnt: Int, val buf: ByteArray, var mask: Int, val t: Long)

    private val parts = HashMap<Long, Part>()

    fun add(p: Packet, now: Long): ByteArray? {
        if (p.cnt == 1) return p.payload
        val key = (p.src.toLong() shl 16) or p.id.toLong()
        val part = parts.getOrPut(key) { Part(p.cnt, ByteArray(p.cnt * Proto.CHUNK), 0, now) }
        if (part.cnt != p.cnt) return null
        p.payload.copyInto(part.buf, p.frag * Proto.CHUNK)
        part.mask = part.mask or (1 shl p.frag)
        if (part.mask != (1 shl p.cnt) - 1) return null
        parts.remove(key)
        return part.buf
    }

    fun expire(now: Long) {
        parts.entries.removeAll { now - it.value.t > 30_000 }
    }
}
