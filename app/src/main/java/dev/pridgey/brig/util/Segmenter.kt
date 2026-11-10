package dev.pridgey.brig.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/**
 * Cuts the main subject out of a photo (ML Kit, on device) for the depth effect, where the subject
 * is drawn over the clock. Results are cached as PNGs next to the photo's other files.
 */
object Segmenter {
    /** Same size the lock screen decodes photos at, so the cutout lines up exactly. */
    const val MAX_DIM = 1440
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

    /** One thing ML Kit found in a photo, with how sure it is of each pixel (0..1). */
    class Region(val x: Int, val y: Int, val width: Int, val height: Int, val mask: FloatArray) {
        fun covers(px: Int, py: Int): Boolean {
            val lx = px - x
            val ly = py - y
            return lx in 0 until width && ly in 0 until height && mask[ly * width + lx] > 0.5f
        }

        /** The mask as an alpha-only bitmap, for drawing. */
        fun alphaBitmap(): Bitmap = alphaOf(mask, width, height)
    }

    /**
     * A smaller copy of a photo for painting on, with everything found in it. The cut-out itself
     * is made at full size when saved, so this only has to be big enough to see and touch.
     */
    class Analysis(val photo: Bitmap, val regions: List<Region>, val foreground: Bitmap?, val mode: Int = 0)

    /**
     * How much object finding the painter asks for: 0 = each object separately, 1 = just the
     * main subject, 2 = none (paint by hand). If the app dies mid-analysis, the next try steps
     * down a level, so a phone where ML Kit crashes can't crash the same way twice.
     */
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("segmenter", Context.MODE_PRIVATE)

    private fun safeMode(ctx: Context): Int {
        val p = prefs(ctx)
        var mode = p.getInt("mode", 0)
        if (p.getBoolean("running", false)) {
            mode = (mode + 1).coerceAtMost(2)
            p.edit().putInt("mode", mode).putBoolean("running", false).commit()
        }
        return mode
    }

    private fun running(ctx: Context, on: Boolean) {
        // commit, not apply: it has to be on disk before a crash could happen.
        prefs(ctx).edit().putBoolean("running", on).commit()
    }

    /** Longest side of the photo used for painting. Keeps memory low; plenty for a phone screen. */
    private const val PAINT_DIM = 1280

    /** Finds each separate subject in the photo. Null if the photo can't be read or ML Kit fails. */
    suspend fun analyze(ctx: Context, path: String): Analysis? = lock.withLock {
        withContext(Dispatchers.Default) {
            val bmp = try {
                decodeAtMost(path, PAINT_DIM)
            } catch (e: Throwable) {
                null
            } ?: return@withContext null
            val mode = safeMode(ctx)
            if (mode >= 2) return@withContext Analysis(bmp, emptyList(), null, mode)
            val segmenter = try {
                val options = SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask()
                if (mode == 0) options.enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build())
                SubjectSegmentation.getClient(options.build())
            } catch (e: Throwable) {
                return@withContext Analysis(bmp, emptyList(), null, mode)
            }
            try {
                runCatching {
                    ModuleInstall.getClient(ctx).installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).build()).await()
                }
                running(ctx, true)
                val r = try {
                    segmenter.process(InputImage.fromBitmap(bmp, 0)).await()
                } finally {
                    running(ctx, false)
                }
                val regions = r.subjects.orEmpty().mapNotNull { sub ->
                    val buf = sub.confidenceMask ?: return@mapNotNull null
                    if (sub.width <= 0 || sub.height <= 0) return@mapNotNull null
                    val arr = FloatArray(sub.width * sub.height)
                    buf.rewind()
                    buf.get(arr, 0, minOf(arr.size, buf.remaining()))
                    Region(sub.startX, sub.startY, sub.width, sub.height, arr)
                }
                val fg = r.foregroundConfidenceMask?.let { buf ->
                    val arr = FloatArray(bmp.width * bmp.height)
                    buf.rewind()
                    buf.get(arr, 0, minOf(arr.size, buf.remaining()))
                    alphaOf(arr, bmp.width, bmp.height)
                }
                Analysis(bmp, regions, fg, mode)
            } catch (e: Throwable) {
                // Out of memory or ML Kit trouble: still let the user paint by hand.
                Analysis(bmp, emptyList(), null, mode)
            } finally {
                runCatching { segmenter.close() }
            }
        }
    }

    /** An alpha-only bitmap from 0..1 values, one byte per pixel. */
    private fun alphaOf(mask: FloatArray, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val row = out.rowBytes
        val bytes = ByteArray(row * h)
        for (y in 0 until h) {
            val src = y * w
            val dst = y * row
            for (x in 0 until w) bytes[dst + x] = (mask[src + x].coerceIn(0f, 1f) * 255f).toInt().toByte()
        }
        out.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
        return out
    }

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

    /** Returns the cutout PNG path, creating it if needed. Null if there's no clear subject or ML Kit is unavailable. */
    suspend fun cutout(ctx: Context, path: String): String? = lock.withLock {
        cached(ctx, path)?.let { return@withLock it }
        withContext(Dispatchers.Default) {
            val bmp = decode(path) ?: return@withContext null
            val segmenter = SubjectSegmentation.getClient(
                SubjectSegmenterOptions.Builder().enableForegroundBitmap().build(),
            )
            try {
                // The model ships through Play services; make sure it's downloaded first.
                runCatching {
                    ModuleInstall.getClient(ctx).installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).build()).await()
                }
                val fg = segmenter.process(InputImage.fromBitmap(bmp, 0)).await().foregroundBitmap ?: return@withContext null
                val out = cacheFile(ctx, path)
                out.outputStream().use { fg.compress(Bitmap.CompressFormat.PNG, 100, it) }
                out.absolutePath
            } catch (e: Exception) {
                null
            } finally {
                segmenter.close()
            }
        }
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, "cutouts").listFiles()?.forEach { it.delete() }
    }
}
