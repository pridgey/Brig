package dev.pridgey.brig.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/**
 * Finds the subject of a photo (ML Kit, on device) for the depth effect, where the subject is
 * drawn over the clock, and for the pop-out painter.
 *
 * ML Kit's native code can crash, or use a lot of memory, on some phones. So it never runs in
 * Brig's own process: the work happens in [SegmentService], in a separate process, and results come
 * back as files. If that process dies, Brig carries on without a cut-out (or with a simpler mode).
 */
object Segmenter {
    /** Same size the lock screen decodes photos at, so the cutout lines up exactly. */
    const val MAX_DIM = 1440

    /** Longest side of the photo used for painting. Keeps memory low; plenty for a phone screen. */
    private const val PAINT_DIM = 1280

    private val lock = Mutex()

    /** Bumps whenever a cut-out is replaced by hand, so screens showing one reload it. */
    val changes = MutableStateFlow(0)

    private fun key(path: String): String {
        val f = File(path)
        return (path + f.lastModified() + f.length()).hashCode().toUInt().toString()
    }

    private fun cacheFile(ctx: Context, path: String): File {
        val dir = File(ctx.filesDir, "cutouts").apply { mkdirs() }
        return File(dir, "${key(path)}.png")
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("segmenter", Context.MODE_PRIVATE)

    /** Cut-outs the user chose by painting. Kept apart from the automatic cache, which can be cleared. */
    private fun guidedDir(ctx: Context) = File(ctx.filesDir, "cutouts_guided").apply { mkdirs() }

    private fun guidedFiles(ctx: Context, path: String): List<File> {
        val k = key(path) + "_"
        return guidedDir(ctx).listFiles { f -> f.name.startsWith(k) }?.toList().orEmpty()
    }

    fun hasGuided(ctx: Context, path: String) = guidedFiles(ctx, path).isNotEmpty()

    /** The cut-out to use: the user's own if they painted one, else the automatic one if made. */
    fun cached(ctx: Context, path: String): String? =
        guidedFiles(ctx, path).maxByOrNull { it.name }?.absolutePath
            ?: cacheFile(ctx, path).takeIf { it.exists() }?.absolutePath

    /** Saves a hand-picked cut-out for [path]. A new file name each time, so viewers reload it. */
    fun saveGuided(ctx: Context, path: String, cutout: Bitmap): String {
        val old = guidedFiles(ctx, path)
        val f = File(guidedDir(ctx), "${key(path)}_${System.currentTimeMillis()}.png")
        f.outputStream().use { cutout.compress(Bitmap.CompressFormat.PNG, 100, it) }
        old.forEach { it.delete() }
        changes.value++
        return f.absolutePath
    }

    /** Goes back to the automatic cut-out for [path]. */
    fun forgetGuided(ctx: Context, path: String) {
        guidedFiles(ctx, path).forEach { it.delete() }
        changes.value++
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, "cutouts").listFiles()?.forEach { it.delete() }
        prefs(ctx).edit().remove("failed").apply()
    }

    // ---------------------------------------------------------------- Automatic cut-out

    /** Returns the cutout PNG path, creating it if needed. Null if there's no clear subject or ML Kit fails. */
    suspend fun cutout(ctx: Context, path: String): String? = lock.withLock {
        cached(ctx, path)?.let { return@withLock it }
        // Don't keep retrying a photo whose analysis took the helper process down.
        val failed = prefs(ctx).getStringSet("failed", emptySet()).orEmpty()
        if (key(path) in failed) return@withLock null
        val out = cacheFile(ctx, path)
        when (isolated(ctx, SegmentService.OP_CUTOUT, path, out)) {
            Outcome.OK -> out.takeIf { it.exists() }?.absolutePath
            Outcome.CRASHED -> {
                prefs(ctx).edit().putStringSet("failed", failed + key(path)).apply()
                null
            }
            Outcome.FAILED -> null
        }
    }

    /** Runs in [SegmentService]: makes the cut-out PNG for [path] at [out]. */
    internal suspend fun cutoutHere(ctx: Context, path: String, out: File): Boolean {
        val bmp = decode(path) ?: return false
        val segmenter = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
        return try {
            install(ctx, segmenter)
            val fg = segmenter.process(InputImage.fromBitmap(bmp, 0)).await().foregroundBitmap ?: return false
            val tmp = File(out.parentFile, out.name + ".tmp")
            tmp.outputStream().use { fg.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(out)
        } finally {
            segmenter.close()
        }
    }

    private suspend fun install(ctx: Context, segmenter: com.google.mlkit.vision.segmentation.subject.SubjectSegmenter) {
        // The model ships through Play services; make sure it's downloaded first.
        runCatching { ModuleInstall.getClient(ctx).installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).build()).await() }
    }

    // ---------------------------------------------------------------- Painter analysis

    /** One thing ML Kit found in a photo, with how sure it is of each pixel (0..255). */
    class Region(val x: Int, val y: Int, val width: Int, val height: Int, val mask: ByteArray) {
        fun covers(px: Int, py: Int): Boolean {
            val lx = px - x
            val ly = py - y
            return lx in 0 until width && ly in 0 until height && (mask[ly * width + lx].toInt() and 0xFF) > 127
        }

        /** The mask as an alpha-only bitmap, for drawing. */
        fun alphaBitmap(): Bitmap = alphaOf(mask, width, height)
    }

    /**
     * A smaller copy of a photo for painting on, with everything found in it. The cut-out itself
     * is made at full size when saved, so this only has to be big enough to see and touch.
     * [mode]: 0 = each object found separately, 1 = just the main subject, 2 = none (paint by hand).
     */
    class Analysis(val photo: Bitmap, val regions: List<Region>, val foreground: Bitmap?, val mode: Int = 0)

    /**
     * Finds each separate subject in the photo. If the helper process dies, tries again asking
     * for less (the main subject only), then gives up and lets the user paint by hand. The step
     * down is remembered, so a phone where ML Kit crashes doesn't pay for it every time.
     * Null only if the photo itself can't be read.
     */
    suspend fun analyze(ctx: Context, path: String): Analysis? = lock.withLock {
        withContext(Dispatchers.Default) {
            val bmp = runCatching { decodeAtMost(path, PAINT_DIM) }.getOrNull() ?: return@withContext null
            var mode = prefs(ctx).getInt("iso_mode", 0)
            while (mode < 2) {
                val dir = File(ctx.cacheDir, "segment").apply { deleteRecursively(); mkdirs() }
                when (isolated(ctx, SegmentService.OP_ANALYZE, path, dir, mode)) {
                    Outcome.OK -> return@withContext readAnalysis(dir, bmp, mode) ?: Analysis(bmp, emptyList(), null, mode)
                    Outcome.FAILED -> return@withContext Analysis(bmp, emptyList(), null, mode)
                    Outcome.CRASHED -> {
                        mode++
                        prefs(ctx).edit().putInt("iso_mode", mode).apply()
                    }
                }
            }
            Analysis(bmp, emptyList(), null, mode)
        }
    }

    /** Runs in [SegmentService]: analyzes [path] and writes the masks into [dir]. */
    internal suspend fun analyzeHere(ctx: Context, path: String, dir: File, mode: Int): Boolean {
        val bmp = decodeAtMost(path, PAINT_DIM) ?: return false
        val options = SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask()
        if (mode == 0) options.enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build())
        val segmenter = SubjectSegmentation.getClient(options.build())
        try {
            install(ctx, segmenter)
            val r = segmenter.process(InputImage.fromBitmap(bmp, 0)).await()
            r.foregroundConfidenceMask?.let { buf ->
                DataOutputStream(File(dir, "fg.bin").outputStream().buffered()).use { out ->
                    out.writeInt(bmp.width)
                    out.writeInt(bmp.height)
                    out.write(toBytes(buf, bmp.width * bmp.height))
                }
            }
            val subjects = r.subjects.orEmpty().filter { it.confidenceMask != null && it.width > 0 && it.height > 0 }
            DataOutputStream(File(dir, "regions.bin").outputStream().buffered()).use { out ->
                out.writeInt(bmp.width)
                out.writeInt(bmp.height)
                out.writeInt(subjects.size)
                subjects.forEach { s ->
                    out.writeInt(s.startX)
                    out.writeInt(s.startY)
                    out.writeInt(s.width)
                    out.writeInt(s.height)
                    out.write(toBytes(s.confidenceMask!!, s.width * s.height))
                }
            }
            return true
        } finally {
            segmenter.close()
        }
    }

    private fun toBytes(buf: java.nio.FloatBuffer, n: Int): ByteArray {
        buf.rewind()
        val out = ByteArray(n)
        val count = minOf(n, buf.remaining())
        for (i in 0 until count) out[i] = (buf.get().coerceIn(0f, 1f) * 255f).toInt().toByte()
        return out
    }

    private fun readAnalysis(dir: File, bmp: Bitmap, mode: Int): Analysis? = runCatching {
        val fgFile = File(dir, "fg.bin")
        val fg = if (!fgFile.exists()) null else DataInputStream(fgFile.inputStream().buffered()).use { inp ->
            val w = inp.readInt()
            val h = inp.readInt()
            val bytes = ByteArray(w * h).also { inp.readFully(it) }
            if (w == bmp.width && h == bmp.height) alphaOf(bytes, w, h) else null
        }
        val regions = mutableListOf<Region>()
        val regFile = File(dir, "regions.bin")
        if (regFile.exists()) DataInputStream(regFile.inputStream().buffered()).use { inp ->
            val w = inp.readInt()
            val h = inp.readInt()
            val n = inp.readInt()
            repeat(n) {
                val x = inp.readInt()
                val y = inp.readInt()
                val rw = inp.readInt()
                val rh = inp.readInt()
                val bytes = ByteArray(rw * rh).also { inp.readFully(it) }
                if (w == bmp.width && h == bmp.height) regions += Region(x, y, rw, rh, bytes)
            }
        }
        Analysis(bmp, regions, fg, mode)
    }.getOrNull()

    /** An alpha-only bitmap from 0..255 values, one byte per pixel. */
    private fun alphaOf(mask: ByteArray, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val row = out.rowBytes
        val bytes = if (row == w) mask else ByteArray(row * h).also { for (y in 0 until h) System.arraycopy(mask, y * w, it, y * row, w) }
        out.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
        return out
    }

    // ---------------------------------------------------------------- Decoding

    /** Decodes [path] with its longest side at most [maxDim] pixels. */
    fun decodeAtMost(path: String, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxDim) return bmp
        val f = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true)
            .also { if (it !== bmp) bmp.recycle() }
    }

    /** Decodes [path] at the size cut-outs are made at. */
    fun decode(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_DIM || bounds.outHeight / (sample * 2) >= MAX_DIM * 2) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---------------------------------------------------------------- Talking to the helper process

    private enum class Outcome { OK, FAILED, CRASHED }

    /**
     * Asks [SegmentService] to do [op] and waits for the answer. CRASHED means its process died
     * (ML Kit crashed or Android closed it for memory); Brig itself is unaffected.
     */
    private suspend fun isolated(ctx: Context, op: Int, path: String, out: File, mode: Int = 0): Outcome {
        val app = ctx.applicationContext
        return withTimeoutOrNull(120_000) {
            suspendCancellableCoroutine { cont ->
                var done = false
                lateinit var conn: ServiceConnection
                fun finish(o: Outcome) {
                    if (done) return
                    done = true
                    runCatching { app.unbindService(conn) }
                    if (cont.isActive) cont.resume(o)
                }
                conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder == null) return finish(Outcome.FAILED)
                        val reply = Messenger(Handler(Looper.getMainLooper()) { m -> finish(if (m.arg1 == 1) Outcome.OK else Outcome.FAILED); true })
                        val msg = Message.obtain(null, op).apply {
                            data = Bundle().apply {
                                putString(SegmentService.KEY_PATH, path)
                                putString(SegmentService.KEY_OUT, out.absolutePath)
                                putInt(SegmentService.KEY_MODE, mode)
                            }
                            replyTo = reply
                        }
                        runCatching { Messenger(binder).send(msg) }.onFailure { finish(Outcome.CRASHED) }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) = finish(Outcome.CRASHED)
                    override fun onBindingDied(name: ComponentName?) = finish(Outcome.CRASHED)
                    override fun onNullBinding(name: ComponentName?) = finish(Outcome.FAILED)
                }
                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post {
                        if (!done) {
                            done = true
                            runCatching { app.unbindService(conn) }
                        }
                    }
                }
                Handler(Looper.getMainLooper()).post {
                    val bound = runCatching {
                        app.bindService(Intent(app, SegmentService::class.java), conn, Context.BIND_AUTO_CREATE)
                    }.getOrDefault(false)
                    if (!bound) finish(Outcome.FAILED)
                }
            }
        } ?: Outcome.FAILED
    }
}
