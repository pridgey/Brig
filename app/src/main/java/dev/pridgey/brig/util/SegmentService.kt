package dev.pridgey.brig.util

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs ML Kit subject segmentation in its own process (":segment" in the manifest), so a crash or
 * memory spike in ML Kit's native code ends this process instead of Brig. Results are written to
 * files the main process reads; the reply only says whether it worked.
 */
class SegmentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            handle(Message.obtain(msg))
            true
        },
    )

    private fun handle(msg: Message) {
        val reply = msg.replyTo ?: return
        val data = msg.data
        val path = data?.getString(KEY_PATH)
        val out = data?.getString(KEY_OUT)
        val mode = data?.getInt(KEY_MODE) ?: 0
        val op = msg.what
        scope.launch {
            val ok = if (path == null || out == null) false else try {
                when (op) {
                    OP_CUTOUT -> Segmenter.cutoutHere(this@SegmentService, path, File(out))
                    OP_ANALYZE -> Segmenter.analyzeHere(this@SegmentService, path, File(out), mode)
                    else -> false
                }
            } catch (e: Throwable) {
                false
            }
            runCatching { reply.send(Message.obtain(null, op, if (ok) 1 else 0, 0)) }
        }
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val OP_CUTOUT = 1
        const val OP_ANALYZE = 2
        const val KEY_PATH = "path"
        const val KEY_OUT = "out"
        const val KEY_MODE = "mode"
    }
}
