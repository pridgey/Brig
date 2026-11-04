package dev.pridgey.brig.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import kotlin.math.abs
import kotlin.math.max

/**
 * Decides whether the depth effect's cut-out subject would hide too much of the clock.
 * Pure math so it can be tested; callers supply how to read the cutout's alpha.
 */
object DepthFit {
    /** More of the clock than this hidden behind the subject, and the photo shows without pop-out. */
    const val MAX_COVERAGE = 0.4f

    /**
     * Maps a point on screen to a pixel of the photo, undoing the photo's zoom and pan and the
     * center crop that fills the screen. Mirrors the lock screen's photo transform (Ken Burns aside).
     */
    fun toBitmap(
        x: Float, y: Float,
        screenW: Float, screenH: Float,
        bitmapW: Int, bitmapH: Int,
        zoom: Float, panX: Float, panY: Float,
    ): Pair<Float, Float> {
        val maxX = (zoom - 1f) * screenW / 2f
        val maxY = (zoom - 1f) * screenH / 2f
        val tx = (-panX * maxX).coerceIn(-maxX, maxX)
        val ty = (-panY * maxY).coerceIn(-maxY, maxY)
        val cx = screenW / 2f
        val cy = screenH / 2f
        // Undo zoom and pan (scaled about the center, then moved).
        val ux = cx + (x - tx - cx) / zoom
        val uy = cy + (y - ty - cy) / zoom
        // Undo the center crop.
        val s = max(screenW / bitmapW, screenH / bitmapH)
        val ox = (screenW - bitmapW * s) / 2f
        val oy = (screenH - bitmapH * s) / 2f
        return (ux - ox) / s to (uy - oy) / s
    }

    /** Share of [clock] (screen px) where the subject is solid, sampled on a grid. 0..1. */
    fun coverage(
        clock: Rect,
        screenW: Float, screenH: Float,
        bitmapW: Int, bitmapH: Int,
        zoom: Float, panX: Float, panY: Float,
        alphaAt: (Int, Int) -> Int,
        cols: Int = 24, rows: Int = 12,
    ): Float {
        if (clock.width <= 0f || clock.height <= 0f) return 0f
        var hit = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = clock.left + (c + 0.5f) * clock.width / cols
            val y = clock.top + (r + 0.5f) * clock.height / rows
            val (bx, by) = toBitmap(x, y, screenW, screenH, bitmapW, bitmapH, zoom, panX, panY)
            val ix = bx.toInt()
            val iy = by.toInt()
            if (ix in 0 until bitmapW && iy in 0 until bitmapH && alphaAt(ix, iy) > 128) hit++
        }
        return hit.toFloat() / (cols * rows)
    }
}

/**
 * Where the clock sits, relative to the lock screen's root, so the depth effect can check it.
 * Only updates on real moves, so small wobbles (tilt, the entrance spring) don't cause churn.
 */
class ClockTracker {
    var root: LayoutCoordinates? = null
    var bounds by mutableStateOf<Rect?>(null)
        private set

    fun report(clock: LayoutCoordinates) {
        val r = root?.takeIf { it.isAttached } ?: return
        if (!clock.isAttached) return
        val b = r.localBoundingBoxOf(clock)
        val old = bounds
        if (old == null || abs(old.left - b.left) + abs(old.top - b.top) + abs(old.width - b.width) + abs(old.height - b.height) > 24f) {
            bounds = b
        }
    }
}
