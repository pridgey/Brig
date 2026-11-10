package dev.pridgey.brig.util

/**
 * Reads the useful parts of an Android native crash record (a "tombstone", which Android 12+
 * stores in protobuf form): the signal, abort message, the crashing thread's stack, and the app's
 * last log lines. Hand-rolled to avoid a protobuf dependency; unknown fields are skipped. Pure, for
 * tests.
 */
object Tombstone {
    data class Frame(val function: String, val file: String, val offset: Long)
    data class Thread(val id: Int, val name: String, val frames: List<Frame>, val notes: List<String>)
    data class Log(val priority: Int, val tag: String, val message: String)
    data class Summary(
        val tid: Int,
        val signal: String?,
        val code: String?,
        val faultAddress: Long?,
        val abort: String?,
        val causes: List<String>,
        val threads: List<Thread>,
        val logs: List<Log>,
    ) {
        val crashed: Thread? get() = threads.firstOrNull { it.id == tid }
    }

    /** Null if [bytes] isn't a tombstone this can read. */
    fun parse(bytes: ByteArray): Summary? = runCatching {
        var tid = 0
        var signal: String? = null
        var code: String? = null
        var fault: Long? = null
        var abort: String? = null
        val causes = mutableListOf<String>()
        val threads = mutableListOf<Thread>()
        val logs = mutableListOf<Log>()
        Reader(bytes).fields { f, r ->
            when (f.number) {
                6 -> tid = f.varint.toInt()
                10 -> r.sub(f).fields { s, sr ->
                    when (s.number) {
                        2 -> signal = sr.string(s)
                        4 -> code = sr.string(s)
                        9 -> fault = s.varint
                    }
                }
                14 -> abort = r.string(f)
                15 -> r.sub(f).fields { c, cr -> if (c.number == 1) causes += cr.string(c) }
                16 -> r.sub(f).fields { e, er -> if (e.number == 2) threads += thread(er.sub(e)) }
                18 -> r.sub(f).fields { b, br ->
                    if (b.number == 2) {
                        var p = 0
                        var tag = ""
                        var msg = ""
                        br.sub(b).fields { m, mr ->
                            when (m.number) {
                                4 -> p = m.varint.toInt()
                                5 -> tag = mr.string(m)
                                6 -> msg = mr.string(m)
                            }
                        }
                        logs += Log(p, tag, msg)
                    }
                }
            }
        }
        if (signal == null && threads.isEmpty()) null
        else Summary(tid, signal, code, fault, abort, causes, threads, logs)
    }.getOrNull()

    private fun thread(r: Reader): Thread {
        var id = 0
        var name = ""
        val frames = mutableListOf<Frame>()
        val notes = mutableListOf<String>()
        r.fields { f, fr ->
            when (f.number) {
                1 -> id = f.varint.toInt()
                2 -> name = fr.string(f)
                7 -> notes += fr.string(f)
                4 -> {
                    var fn = ""
                    var file = ""
                    var off = 0L
                    fr.sub(f).fields { g, gr ->
                        when (g.number) {
                            4 -> fn = gr.string(g)
                            5 -> off = g.varint
                            6 -> file = gr.string(g)
                        }
                    }
                    frames += Frame(fn, file, off)
                }
            }
        }
        return Thread(id, name, frames, notes)
    }

    /** A readable report: what went wrong, where, and what the app logged just before. */
    fun describe(s: Summary, maxFrames: Int = 24, maxLogs: Int = 30): String = buildString {
        s.signal?.let { append("Signal: $it") }
        s.code?.let { append(" ($it)") }
        s.faultAddress?.let { append(" at 0x").append(java.lang.Long.toHexString(it)) }
        if (s.signal != null) append('\n')
        s.abort?.takeIf { it.isNotBlank() }?.let { append("Abort: $it\n") }
        s.causes.forEach { append("Cause: $it\n") }
        val t = s.crashed
        if (t != null) {
            append("Crashed thread: ${t.name.ifBlank { "?" }} (${t.id})\n")
            t.notes.forEach { append("  note: $it\n") }
            t.frames.take(maxFrames).forEachIndexed { i, f ->
                val lib = f.file.substringAfterLast('/').ifBlank { "?" }
                append("  #%02d %s %s\n".format(i, lib, f.function.ifBlank { "+0x" + java.lang.Long.toHexString(f.offset) }))
            }
        } else if (s.threads.isNotEmpty()) {
            append("Crashed thread not found; threads: ${s.threads.joinToString { it.name }}\n")
        }
        val shown = s.logs.filter { it.priority >= 4 }.takeLast(maxLogs)
        if (shown.isNotEmpty()) {
            append("\nLast log lines:\n")
            shown.forEach { append("${"??VDIWEF".getOrElse(it.priority) { '?' }} ${it.tag}: ${it.message.take(300)}\n") }
        }
    }

    // ---------------------------------------------------------------- Minimal protobuf reading

    private class Field(val number: Int, val wire: Int, val varint: Long, val start: Int, val length: Int)

    private class Reader(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
        fun sub(f: Field) = Reader(b, f.start, f.start + f.length)

        fun string(f: Field) = String(b, f.start, f.length, Charsets.UTF_8)

        private fun varint(): Long {
            var shift = 0
            var result = 0L
            while (pos < end && shift < 64) {
                val x = b[pos++].toInt() and 0xFF
                result = result or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalStateException("bad varint")
        }

        fun fields(each: (Field, Reader) -> Unit) {
            while (pos < end) {
                val key = varint()
                val number = (key ushr 3).toInt()
                val f = when (val wire = (key and 7).toInt()) {
                    0 -> Field(number, wire, varint(), pos, 0)
                    1 -> Field(number, wire, 0, pos, 8).also { pos += 8 }
                    2 -> {
                        val len = varint().toInt()
                        require(len >= 0 && pos + len <= end) { "bad length" }
                        Field(number, wire, 0, pos, len).also { pos += len }
                    }
                    5 -> Field(number, wire, 0, pos, 4).also { pos += 4 }
                    else -> throw IllegalStateException("wire $wire")
                }
                require(pos <= end) { "overrun" }
                each(f, this)
            }
        }
    }
}
