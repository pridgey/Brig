package dev.pridgey.brig.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.pridgey.brig.util.Device
import dev.pridgey.brig.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A photo from the user's own library, taken on today's date in an earlier year. */
data class Memory(val uri: Uri, val taken: Long) {
    val yearsAgo: Int get() = LocalDate.now().year - Instant.ofEpochMilli(taken).atZone(ZoneId.systemDefault()).year
}

object Memories {
    fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(ctx: Context) = Device.hasPermission(ctx, permission()) ||
        (Build.VERSION.SDK_INT >= 34 && Device.hasPermission(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

    /** True if [taken] falls within [days] of today's month/day in a past year. Pure for tests. */
    fun matchesDay(taken: LocalDate, today: LocalDate, days: Int = 0): Boolean {
        if (taken.year >= today.year) return false
        val sameYear = runCatching { taken.withYear(today.year) }.getOrNull() ?: return false // Feb 29
        return kotlin.math.abs(sameYear.toEpochDay() - today.toEpochDay()) <= days
    }

    /** Photos from this date in earlier years, oldest year first. Widens to ±3 days if there are none. */
    suspend fun onThisDay(ctx: Context, limit: Int = 12): List<Memory> = withContext(Dispatchers.IO) {
        if (!hasPermission(ctx)) return@withContext emptyList()
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val all = mutableListOf<Memory>()
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
                "${MediaStore.Images.Media.DATE_TAKEN} > 0", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val taken = c.getLong(1)
                    val d = Instant.ofEpochMilli(taken).atZone(zone).toLocalDate()
                    if (matchesDay(d, today, 3)) {
                        all += Memory(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), taken)
                    }
                }
            }
        }
        pick(all, { it.taken }, today, zone, limit)
    }

    /**
     * Exact-date photos first (oldest first), then nearby days (closest first) to fill up to [limit].
     * Skips shots taken within a couple of minutes of one already picked, so a burst counts once.
     */
    fun <T> pick(all: List<T>, taken: (T) -> Long, today: LocalDate, zone: ZoneId, limit: Int): List<T> {
        fun distance(m: T) = runCatching {
            val day = Instant.ofEpochMilli(taken(m)).atZone(zone).toLocalDate()
            kotlin.math.abs(day.withYear(today.year).toEpochDay() - today.toEpochDay())
        }.getOrDefault(Long.MAX_VALUE)
        val ranked = all.sortedWith(compareBy({ distance(it) }, { taken(it) }))
        val out = mutableListOf<T>()
        for (m in ranked) {
            if (out.size >= limit) break
            if (out.none { kotlin.math.abs(taken(it) - taken(m)) < BURST_MS }) out += m
        }
        return out
    }

    private const val BURST_MS = 2 * 60_000L

    fun thumbnail(ctx: Context, m: Memory, px: Int = 360): ImageBitmap? =
        runCatching { ctx.contentResolver.loadThumbnail(m.uri, Size(px, px), null).asImageBitmap() }.getOrNull()
}

/**
 * Picks the next background photo for sources other than "One photo" and keeps it in app storage.
 * The lock screen reads [current]; settings never change on rotation.
 */
object PhotoRotator {
    /** [url] is where a downloaded photo came from, so the same picture isn't fetched twice. */
    data class Current(val path: String, val caption: String?, val url: String? = null)

    val current = MutableStateFlow<Current?>(null)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("rotation", Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.filesDir, "rotation").apply { mkdirs() }

    fun restore(ctx: Context) {
        if (current.value != null) return
        val p = prefs(ctx)
        val path = p.getString("path", null) ?: return
        if (File(path).exists()) current.value = Current(path, p.getString("caption", null), p.getString("url", null))
    }

    /** Sources that only change once a day, whatever the rotation setting says. */
    private fun dailyOnly(cfg: PhotoSourceConfig) =
        cfg.source == PhotoSource.APOD || (cfg.source == PhotoSource.BING && cfg.rotation == Rotation.DAILY)

    /** Fetches a new photo if the rotation interval has passed (or the source changed). */
    suspend fun maybeRotate(ctx: Context, cfg: PhotoSourceConfig, force: Boolean = false) = lock.withLock {
        restore(ctx)
        if (cfg.source == PhotoSource.SINGLE) return@withLock
        val p = prefs(ctx)
        val sourceKey = cfg.source.name + cfg.immichAlbumId + cfg.unsplashQuery + cfg.subreddit + cfg.albumPaths.size
        val sameSource = p.getString("source", null) == sourceKey && current.value != null
        val today = LocalDate.now().toString()
        val due = if (dailyOnly(cfg)) p.getString("day", null) != today
        else System.currentTimeMillis() - p.getLong("at", 0) >= cfg.rotation.minutes * 60_000L
        if (!force && !due && sameSource) return@withLock
        val next = runCatching { fetch(ctx, cfg) }.getOrNull() ?: return@withLock
        if (next.path != current.value?.path) {
            // Make the cut-out first, so the photo and its pop-out appear together.
            if (dev.pridgey.brig.BrigApp.settings.flow.value.appearance.depth) {
                runCatching { dev.pridgey.brig.util.Segmenter.cutout(ctx, next.path) }
            }
            // Keep the previous file around until the new one is in place.
            dir(ctx).listFiles()?.filter { it.absolutePath != next.path && it.absolutePath != current.value?.path }?.forEach { it.delete() }
            current.value = next
        }
        p.edit().putString("path", next.path).putString("caption", next.caption).putString("url", next.url)
            .putLong("at", System.currentTimeMillis()).putString("day", today).putString("source", sourceKey).apply()
    }

    private fun newFile(ctx: Context, ext: String = "jpg") = File(dir(ctx), "photo_${System.currentTimeMillis()}.$ext")

    private suspend fun fetch(ctx: Context, cfg: PhotoSourceConfig): Current? = when (cfg.source) {
        PhotoSource.SINGLE -> null
        PhotoSource.ALBUM -> cfg.albumPaths.filter { File(it).exists() && it != current.value?.path }
            .ifEmpty { cfg.albumPaths.filter { File(it).exists() } }
            .randomOrNull()?.let { Current(it, null) }
        PhotoSource.ON_THIS_DAY -> Memories.onThisDay(ctx, 40)
            .filter { it.uri.toString() != current.value?.url }
            .randomOrNull()?.let { m ->
                val f = newFile(ctx)
                withContext(Dispatchers.IO) { saveUpright(ctx, m.uri, f) }
                val years = m.yearsAgo
                Current(f.absolutePath, if (years == 1) "1 year ago today" else "$years years ago today", m.uri.toString())
            }
        PhotoSource.APOD -> {
            val o = json.parseToJsonElement(Http.get("https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY&thumbs=true"))
            val url = if (o.str("media_type") == "image") o.str("hdurl") ?: o.str("url") else o.str("thumbnail_url")
            url?.let { download(ctx, it, o.str("title")) }
        }
        PhotoSource.BING -> {
            val o = json.parseToJsonElement(Http.get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US"))
            val images = o.arr("images").orEmpty()
            // Daily rotation shows today's; faster rotation walks back through the last week.
            val pick = if (cfg.rotation == Rotation.DAILY) images.firstOrNull() else images.randomOrNull()
            pick?.str("urlbase")?.let { download(ctx, "https://www.bing.com${it}_1080x1920.jpg", pick.str("title") ?: pick.str("copyright")) }
        }
        PhotoSource.UNSPLASH -> {
            if (cfg.unsplashKey.isBlank()) null else {
                val q = URLEncoder.encode(cfg.unsplashQuery.ifBlank { "nature" }, "UTF-8")
                val o = json.parseToJsonElement(
                    Http.get("https://api.unsplash.com/photos/random?orientation=portrait&query=$q", mapOf("Authorization" to "Client-ID ${cfg.unsplashKey.trim()}")),
                )
                o.obj("urls").str("regular")?.let { download(ctx, it, o.obj("user").str("name")?.let { n -> "Photo by $n on Unsplash" }) }
            }
        }
        PhotoSource.REDDIT -> {
            val sub = cfg.subreddit.trim().removePrefix("r/").ifBlank { "EarthPorn" }
            val o = json.parseToJsonElement(Http.get("https://www.reddit.com/r/$sub/top.json?t=week&limit=40&raw_json=1"))
            val posts = o.obj("data").arr("children").orEmpty().mapNotNull { it.obj("data") }
                .filter { d ->
                    val u = d.str("url").orEmpty()
                    d["over_18"]?.jsonPrimitive?.content != "true" && (u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".png"))
                }
            posts.randomOrNull()?.let { d -> download(ctx, d.str("url")!!, d.str("title")) }
        }
        PhotoSource.IMMICH -> {
            if (cfg.immichUrl.isBlank() || cfg.immichKey.isBlank() || cfg.immichAlbumId.isBlank()) null else {
                val base = cfg.immichUrl.trimEnd('/')
                val headers = mapOf("x-api-key" to cfg.immichKey.trim())
                val o = json.parseToJsonElement(Http.get("$base/api/albums/${cfg.immichAlbumId}", headers))
                val asset = o.arr("assets").orEmpty().filter { it.str("type") == "IMAGE" }.randomOrNull()
                asset?.str("id")?.let { id -> download(ctx, "$base/api/assets/$id/thumbnail?size=preview", cfg.immichAlbumName.ifBlank { null }, headers) }
            }
        }
    }

    private suspend fun download(ctx: Context, url: String, caption: String?, headers: Map<String, String> = emptyMap()): Current {
        // Same picture as now (NASA and Bing repeat all day): keep the file, and its cut-out, as is.
        current.value?.takeIf { it.url == url && File(it.path).exists() }?.let { return it.copy(caption = caption ?: it.caption) }
        val f = newFile(ctx)
        Http.download(url, f, headers)
        return Current(f.absolutePath, caption, url)
    }

    /**
     * Saves a gallery photo the right way up (phones often store photos sideways with a rotation
     * tag) and at a sensible size, as a JPEG. Also handles HEIC.
     */
    private fun saveUpright(ctx: Context, uri: Uri, out: File) {
        val src = android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_SAVE) {
                val f = MAX_SAVE.toFloat() / longest
                decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
            }
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
        out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
    }

    private const val MAX_SAVE = 2560

    data class ImmichAlbum(val id: String, val name: String, val count: Int)

    suspend fun immichAlbums(url: String, key: String): List<ImmichAlbum> {
        val arr = json.parseToJsonElement(Http.get("${url.trimEnd('/')}/api/albums", mapOf("x-api-key" to key.trim())))
        return (arr as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull {
            ImmichAlbum(it.str("id") ?: return@mapNotNull null, it.str("albumName") ?: "Album", it.num("assetCount")?.toInt() ?: 0)
        }
    }
}
