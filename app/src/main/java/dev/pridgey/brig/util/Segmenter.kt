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

    private fun cacheFile(ctx: Context, path: String): File {
        val f = File(path)
        val dir = File(ctx.filesDir, "cutouts").apply { mkdirs() }
        return File(dir, "${(path + f.lastModified() + f.length()).hashCode().toUInt()}.png")
    }

    fun cached(ctx: Context, path: String): String? = cacheFile(ctx, path).takeIf { it.exists() }?.absolutePath

    private fun decode(path: String): Bitmap? {
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
