package dev.pridgey.brig

import dev.pridgey.brig.util.Tombstone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TombstoneTest {
    // A tiny protobuf writer, enough to build a tombstone like Android's.
    private class Pb {
        val out = ByteArrayOutputStream()
        private fun varint(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        fun int(field: Int, v: Long) = apply { varint((field shl 3).toLong()); varint(v) }
        fun bytes(field: Int, b: ByteArray) = apply { varint(((field shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
        fun str(field: Int, s: String) = bytes(field, s.toByteArray())
        fun msg(field: Int, m: Pb) = bytes(field, m.out.toByteArray())
        fun build(): ByteArray = out.toByteArray()
    }

    private fun frame(fn: String, file: String) = Pb().int(1, 0x1234).str(4, fn).int(5, 8).str(6, file).str(8, "abc")
    private fun thread(id: Int, name: String, vararg frames: Pb) =
        Pb().int(1, id.toLong()).str(2, name).also { t -> frames.forEach { t.msg(4, it) } }

    @Test
    fun readsSignalCrashedThreadAndLogs() {
        val bytes = Pb()
            .str(2, "google/husky")
            .int(5, 100).int(6, 222)
            .msg(10, Pb().int(1, 11).str(2, "SIGSEGV").int(3, 1).str(4, "SEGV_MAPERR").int(8, 1).int(9, 0xdead))
            .str(14, "")
            .msg(15, Pb().str(1, "null pointer dereference"))
            .msg(16, Pb().int(1, 100).msg(2, thread(100, "main", frame("nativePollOnce", "/system/lib64/libandroid_runtime.so"))))
            .msg(16, Pb().int(1, 222).msg(2, thread(222, "RenderThread", frame("SkBitmap::draw", "/system/lib64/libhwui.so"), frame("", "/vendor/lib64/egl/libGLES_mali.so"))))
            .msg(18, Pb().str(1, "main")
                .msg(2, Pb().str(1, "t").int(2, 100).int(3, 222).int(4, 6).str(5, "OpenGLRenderer").str(6, "Out of memory allocating texture"))
                .msg(2, Pb().str(1, "t").int(2, 100).int(3, 222).int(4, 3).str(5, "Chatty").str(6, "debug noise")))
            .build()
        val s = Tombstone.parse(bytes)!!
        assertEquals("SIGSEGV", s.signal)
        assertEquals("SEGV_MAPERR", s.code)
        assertEquals(0xdeadL, s.faultAddress)
        assertEquals("RenderThread", s.crashed?.name)
        val text = Tombstone.describe(s)
        assertTrue(text, "Signal: SIGSEGV (SEGV_MAPERR) at 0xdead" in text)
        assertTrue(text, "Cause: null pointer dereference" in text)
        assertTrue(text, "Crashed thread: RenderThread (222)" in text)
        assertTrue(text, "#00 libhwui.so SkBitmap::draw" in text)
        assertTrue(text, "#01 libGLES_mali.so +0x8" in text)
        assertTrue(text, "E OpenGLRenderer: Out of memory allocating texture" in text)
        assertTrue(text, "debug noise" !in text)
    }

    @Test
    fun rejectsJunk() {
        assertNull(Tombstone.parse("not a tombstone at all".toByteArray()))
        assertNull(Tombstone.parse(ByteArray(0)))
    }
}
