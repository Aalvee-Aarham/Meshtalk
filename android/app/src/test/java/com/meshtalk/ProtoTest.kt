package com.meshtalk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtoTest {
    @Test
    fun matchesEspByteLayout() {
        // Exactly what meshtalk.ino hdr()+sendData() emits for MSG "hi", src 0x3F2A -> dst 0x0102, id 0x0506.
        val esp = ByteArray(24).also {
            byteArrayOf(0x4D, 3, 7, 0x2A, 0x3F, 0x02, 0x01, 0x06, 0x05, 0x00, 'h'.code.toByte(), 'i'.code.toByte()).copyInto(it)
        }
        val p = Packet.fragment(Proto.MSG, 0x3F2A, 0x0102, 0x0506, "hi".toByteArray(), Proto.TTL).single()
        assertArrayEquals(esp, p.encode())
        val d = Packet.decode(esp)!!
        assertEquals(0x3F2A, d.src); assertEquals(0x0102, d.dst); assertEquals(7, d.ttl); assertEquals("hi", d.payload.cString())
    }

    @Test
    fun fragmentsRelayAndReassemble() {
        val text = "Hello from node A — this message is long enough to need several fragments ✓"
        val frags = Packet.fragment(Proto.MSG, 1, 2, 99, utf8Clip(text, Proto.MAX_TXT), Proto.TTL)
        val r = Reassembler()
        var out: ByteArray? = null
        for (f in frags.reversed()) out = r.add(Packet.decode(f.relayed().encode())!!, 0) ?: out
        assertEquals(text, out!!.cString())
        val hop = Packet.decode(frags[0].relayed().encode())!!
        assertEquals(6, hop.ttl); assertEquals(1, hop.hops)
    }

    @Test
    fun rejectsJunkAndClipsUtf8() {
        assertNull(Packet.decode(ByteArray(24)))
        assertEquals("ab", String(utf8Clip("ab✓", 4), Charsets.UTF_8))  // ✓ is 3 bytes, must not be split
    }
}
