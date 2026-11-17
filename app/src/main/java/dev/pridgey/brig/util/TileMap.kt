package dev.pridgey.brig.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import dev.pridgey.brig.data.FlightMapStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Web Mercator math at 512 px per tile. OpenStreetMap's 256 px tiles are drawn at double size, which
 * keeps labels readable on a phone and means fewer tiles per map. Pure, so it can be tested.
 */
object Mercator {
    const val TILE = 512

    fun worldSize(z: Int) = TILE.toDouble() * (1 shl z)

    fun x(lon: Double, z: Int) = (lon + 180) / 360 * worldSize(z)

    fun y(lat: Double, z: Int): Double {
        val l = lat.coerceIn(-85.0, 85.0) * PI / 180
        return (1 - ln(tan(l) + 1 / kotlin.math.cos(l)) / PI) / 2 * worldSize(z)
    }

    /**
     * The deepest zoom in [minZ]..[maxZ] where all [points] fit inside [fill] of a [w]×[h] view,
     * and the center (world px) to put them in the middle. Longitudes are unwrapped around the first
     * point so routes across the date line stay together.
     */
    fun fit(points: List<Pair<Double, Double>>, w: Int, h: Int, minZ: Int, maxZ: Int, fill: Double = 0.78): Triple<Int, Double, Double> {
        require(points.isNotEmpty())
        val lon0 = points[0].second
        val pts = points.map { (la, lo) -> la to lon0 + ((lo - lon0 + 540) % 360 - 180) }
        for (z in maxZ downTo minZ) {
            val xs = pts.map { x(it.second, z) }
            val ys = pts.map { y(it.first, z) }
            val fits = xs.max() - xs.min() <= w * fill && ys.max() - ys.min() <= h * fill
            if (fits || z == minZ) return Triple(z, (xs.max() + xs.min()) / 2, (ys.max() + ys.min()) / 2)
        }
        error("unreachable")
    }
}

/**
 * A rendered map: [bitmap] (null while tiles are unavailable) covering [w]×[h] px whose top-left is
 * world pixel ([left], [top]) at zoom [z]. [project] places a coordinate on it.
 */
class MapShot(val bitmap: Bitmap?, val z: Int, val left: Double, val top: Double, val w: Int, val h: Int) {
    fun project(lat: Double, lon: Double): Pair<Float, Float> {
        val world = Mercator.worldSize(z)
        var x = Mercator.x(lon, z) - left
        // Pick the copy of the world nearest the view.
        while (x < -world / 2 + w / 2) x += world
        while (x > world / 2 + w / 2) x -= world
        return x.toFloat() to (Mercator.y(lat, z) - top).toFloat()
    }

    companion object {
        /** A view of [points] without tiles, for previews. */
        fun blank(points: List<Pair<Double, Double>>, w: Int = 1024, h: Int = 440, minZ: Int = 2, maxZ: Int = 9): MapShot {
            val (z, cx, cy) = Mercator.fit(points, w, h, minZ, maxZ)
            return MapShot(null, z, cx - w / 2, cy - h / 2, w, h)
        }
    }
}

/**
 * Stitches OpenStreetMap tiles into one image. Tiles are kept in the cache folder, so a route is
 * fetched once, as OSM's tile policy asks; requests carry Brig's user agent.
 */
object TileMap {
    private const val SOURCE = "osm"
    private fun url(z: Int, x: Int, y: Int) = "https://tile.openstreetmap.org/$z/$x/$y.png"

    /** Color tweaks that turn OSM's standard tiles into each map style. */
    fun filterFor(style: FlightMapStyle): ColorMatrix? = when (style) {
        FlightMapStyle.COLOR -> null
        FlightMapStyle.LIGHT -> ColorMatrix().apply { setSaturation(0.2f) }
        FlightMapStyle.DARK -> ColorMatrix(
            // Invert, so land goes dark and labels light...
            floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f),
        ).apply {
            // ...turn the hues back (water stays blue, parks green)...
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        -0.574f, 1.430f, 0.144f, 0f, 0f,
                        0.426f, 0.430f, 0.144f, 0f, 0f,
                        0.426f, 1.430f, -0.856f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
            // ...and mute it so the plane and route stand out.
            postConcat(ColorMatrix().apply { setSaturation(0.45f) })
            postConcat(ColorMatrix(floatArrayOf(0.82f, 0f, 0f, 0f, 0f, 0f, 0.82f, 0f, 0f, 0f, 0f, 0f, 0.82f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        }
    }

    /** Removes tiles from other sources, like the "API key required" images CARTO started serving. */
    private fun dropOtherSources(ctx: Context) {
        File(ctx.cacheDir, "tiles").listFiles()?.filter { it.name != SOURCE }?.forEach { it.deleteRecursively() }
    }

    suspend fun render(
        ctx: Context, filter: ColorMatrix?, background: Int, points: List<Pair<Double, Double>>,
        minZ: Int, maxZ: Int, w: Int = 1024, h: Int = 440,
    ): MapShot = withContext(Dispatchers.IO) {
        dropOtherSources(ctx)
        val (z, cx, cy) = Mercator.fit(points, w, h, minZ, maxZ)
        val left = cx - w / 2
        val top = cy - h / 2
        val n = 1 shl z
        val t = Mercator.TILE
        val dir = File(ctx.cacheDir, "tiles/$SOURCE/$z").apply { mkdirs() }
        val tiles = (floor(left / t).toInt()..floor((left + w - 1) / t).toInt()).flatMap { tx ->
            (floor(top / t).toInt()..floor((top + h - 1) / t).toInt()).filter { it in 0 until n }.map { ty -> tx to ty }
        }
        val loaded = coroutineScope {
            tiles.map { (tx, ty) ->
                async {
                    val wx = ((tx % n) + n) % n
                    val f = File(dir, "${wx}_$ty.png")
                    if (!f.exists()) runCatching { Http.download(url(z, wx, ty), f) }
                    Triple(tx, ty, if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null)
                }
            }.awaitAll()
        }
        if (loaded.all { it.third == null }) return@withContext MapShot(null, z, left, top, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(background)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { filter?.let { colorFilter = ColorMatrixColorFilter(it) } }
        loaded.forEach { (tx, ty, b) ->
            b ?: return@forEach
            val x = (tx * t - left).toInt()
            val y = (ty * t - top).toInt()
            canvas.drawBitmap(b, null, Rect(x, y, x + t, y + t), paint)
            b.recycle()
        }
        MapShot(bmp, z, left, top, w, h)
    }

    /** Drops tiles not used for a month. */
    fun prune(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600_000L
        File(ctx.cacheDir, "tiles").walkBottomUp().filter { it.isFile && it.lastModified() < cutoff }.forEach { it.delete() }
    }
}
