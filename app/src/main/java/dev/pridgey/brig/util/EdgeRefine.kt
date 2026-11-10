package dev.pridgey.brig.util

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "Smart edges" for the pop-out painter: tightens a painted stroke to the object underneath it.
 *
 * The line the finger traced is taken as mostly on the object, and the band just outside the
 * stroke as background. Each pixel inside the stroke is scored by whether its color looks more
 * like the object or the background; the scores are smoothed with a guided filter so the boundary
 * settles on real edges in the photo; and only parts connected to the traced line are kept, so
 * stray specks of a similar color are dropped. It never adds anything outside the stroke. When the
 * object and background are too alike to tell apart, it keeps the stroke as painted.
 *
 * Works on plain arrays (no Android types) so it can be tested directly.
 */
object EdgeRefine {
    /**
     * [alpha] covers the box [x0], [y0], [width] × [height] in photo pixels, 0..1 per pixel.
     * [kept] is how much of the painted stroke survived (0..1). [tightened] is false when it fell
     * back to the stroke as painted.
     */
    class Result(val x0: Int, val y0: Int, val width: Int, val height: Int, val alpha: FloatArray, val kept: Float, val tightened: Boolean)

    /**
     * [pixels] is the whole photo, ARGB, [w] × [h]. [points] is the finger's path in photo pixels
     * (x, y pairs) and [radius] the brush radius in photo pixels.
     */
    fun refine(pixels: IntArray, w: Int, h: Int, points: List<Pair<Float, Float>>, radius: Float): Result? {
        if (points.isEmpty() || radius <= 0f) return null
        val path = thin(points, radius / 4f)
        val r = radius
        val margin = r * 0.8f
        val guideR = max(2, (r * 0.12f).toInt())
        val pad = (r + margin).toInt() + guideR + 2
        val x0 = max(0, (path.minOf { it.first } - pad).toInt())
        val y0 = max(0, (path.minOf { it.second } - pad).toInt())
        val x1 = min(w, (path.maxOf { it.first } + pad).toInt() + 1)
        val y1 = min(h, (path.maxOf { it.second } + pad).toInt() + 1)
        val bw = x1 - x0
        val bh = y1 - y0
        if (bw <= 2 || bh <= 2) return null
        val n = bw * bh

        // Distance from each pixel to the finger's path, and whether its nearest point is one of
        // the path's two ends (the rounded caps beyond the stroke's ends).
        val beyondEnds = BooleanArray(n)
        val dist = distances(path, x0, y0, bw, bh, r + margin + 1f, beyondEnds)

        // The stroke as painted, with a slightly soft edge.
        val stroke = FloatArray(n) { i -> smoothstep(r, r * 0.85f, dist[i]) }
        val core = BooleanArray(n) { i -> dist[i] <= r * 0.3f }

        // Colors: the traced line (object) and the band outside the stroke (background). Background
        // beyond the stroke's two ends is left out, since the object often carries on past them.
        val fgSamples = ArrayList<Int>()
        val bgSamples = ArrayList<Int>()
        for (y in 0 until bh) for (x in 0 until bw) {
            val i = y * bw + x
            val d = dist[i]
            val px = pixels[(y + y0) * w + (x + x0)]
            if (core[i]) fgSamples += px
            else if (d > r && d <= r + margin && (path.size == 1 || !beyondEnds[i])) bgSamples += px
        }
        val asPainted = Result(x0, y0, bw, bh, stroke, 1f, false)
        if (fgSamples.size < 8 || bgSamples.size < 8) return asPainted
        val bg = kmeans(sample(bgSamples, 1500), 5, minShare = 0.04f)
        // The traced line usually strays off the object here and there. Whatever on it looks like
        // the background is background, so only the rest describes the object.
        val traced = sample(fgSamples, 1500)
        val distinct = traced.filter { nearest(bg, it) > ALIKE }
        // Too little of the traced line stands apart from the background: keep it as painted.
        if (distinct.size < 8 || distinct.size < traced.size / 20) return asPainted
        val fg = kmeans(distinct, 4)

        // Object-likeness of each pixel inside the stroke.
        val prob = FloatArray(n)
        val guide = FloatArray(n)
        for (y in 0 until bh) for (x in 0 until bw) {
            val i = y * bw + x
            val px = pixels[(y + y0) * w + (x + x0)]
            val c = rgb(px)
            guide[i] = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]
            if (stroke[i] <= 0f) {
                prob[i] = 0f
            } else {
                val df = nearest(fg, c)
                val db = nearest(bg, c)
                prob[i] = db / (df + db + 1e-6f)
            }
        }

        // Settle the boundary on the photo's own edges.
        val smooth = guidedFilter(guide, prob, bw, bh, guideR, 0.004f)
        val alpha = FloatArray(n) { i -> smoothstep(0.38f, 0.62f, smooth[i]) * stroke[i] }

        // Keep only what's connected to the traced line.
        keepConnected(alpha, core, bw, bh)

        var painted = 0f
        var kept = 0f
        for (i in 0 until n) {
            painted += stroke[i]
            kept += alpha[i]
        }
        val share = if (painted > 0f) kept / painted else 0f
        // Almost nothing left means the guess went wrong; the stroke as painted is safer.
        if (share < 0.04f) return asPainted
        return Result(x0, y0, bw, bh, alpha, share, true)
    }

    /** Colors closer than this (weighted squared distance, 0..1 per channel) count as the same. */
    private const val ALIKE = 0.0025f

    // ---------------------------------------------------------------- Pieces

    /** Drops path points closer together than [step], keeping the ends. */
    private fun thin(points: List<Pair<Float, Float>>, step: Float): List<Pair<Float, Float>> {
        if (points.size <= 2) return points
        val out = ArrayList<Pair<Float, Float>>()
        out += points.first()
        for (p in points) {
            val last = out.last()
            if (hypot(p.first - last.first, p.second - last.second) >= step) out += p
        }
        if (out.last() != points.last()) out += points.last()
        return out
    }

    /** Distance to the path for each pixel in the box, capped at [cap]. Only touches pixels near a segment. */
    private fun distances(path: List<Pair<Float, Float>>, x0: Int, y0: Int, bw: Int, bh: Int, cap: Float, beyondEnds: BooleanArray): FloatArray {
        val dist = FloatArray(bw * bh) { cap }
        val segments = if (path.size == 1) listOf(path[0] to path[0]) else path.zipWithNext()
        val lastSeg = segments.lastIndex
        for ((si, seg) in segments.withIndex()) {
            val (a, b) = seg
            val ax = a.first - x0
            val ay = a.second - y0
            val bx = b.first - x0
            val by = b.second - y0
            val lx = max(0, (min(ax, bx) - cap).toInt())
            val ly = max(0, (min(ay, by) - cap).toInt())
            val hx = min(bw - 1, (max(ax, bx) + cap).toInt() + 1)
            val hy = min(bh - 1, (max(ay, by) + cap).toInt() + 1)
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            for (y in ly..hy) for (x in lx..hx) {
                val t = if (len2 == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f)
                val d = hypot(x - (ax + t * dx), y - (ay + t * dy))
                val i = y * bw + x
                if (d < dist[i]) {
                    dist[i] = d
                    beyondEnds[i] = (si == 0 && t <= 0f) || (si == lastSeg && t >= 1f)
                }
            }
        }
        return dist
    }

    private fun rgb(px: Int) = floatArrayOf(((px shr 16) and 0xFF) / 255f, ((px shr 8) and 0xFF) / 255f, (px and 0xFF) / 255f)

    private fun sample(list: List<Int>, max: Int): List<FloatArray> {
        val step = max(1, list.size / max)
        return (list.indices step step).map { rgb(list[it]) }
    }

    /** A few k-means rounds; enough to capture the main colors of a small area. */
    private fun kmeans(points: List<FloatArray>, k: Int, minShare: Float = 0f): List<FloatArray> {
        val count = min(k, points.size)
        val centers = List(count) { points[(it * points.size) / count].copyOf() }
        val sums = Array(count) { FloatArray(3) }
        val sizes = IntArray(count)
        repeat(6) {
            sums.forEach { it.fill(0f) }
            sizes.fill(0)
            for (p in points) {
                var best = 0
                var bestD = Float.MAX_VALUE
                for (c in 0 until count) {
                    val d = sq(p, centers[c])
                    if (d < bestD) { bestD = d; best = c }
                }
                sizes[best]++
                for (j in 0..2) sums[best][j] += p[j]
            }
            for (c in 0 until count) if (sizes[c] > 0) for (j in 0..2) centers[c][j] = sums[c][j] / sizes[c]
        }
        // Tiny clusters are usually strays (a bit of the object poking out); drop them.
        val kept = centers.filterIndexed { c, _ -> sizes[c] > 0 && sizes[c] >= points.size * minShare }
        return kept.ifEmpty { centers }
    }

    private fun nearest(centers: List<FloatArray>, c: FloatArray): Float = centers.minOf { sq(it, c) }

    private fun sq(a: FloatArray, b: FloatArray): Float {
        val r = a[0] - b[0]
        val g = a[1] - b[1]
        val bl = a[2] - b[2]
        // Weighted roughly like the eye's sensitivity.
        return 0.3f * r * r + 0.59f * g * g + 0.11f * bl * bl
    }

    /** He et al.'s guided filter: smooths [p] while keeping the edges of [guide]. */
    internal fun guidedFilter(guide: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val meanI = box(guide, w, h, r)
        val meanP = box(p, w, h, r)
        val ii = FloatArray(n) { guide[it] * guide[it] }
        val ip = FloatArray(n) { guide[it] * p[it] }
        val corrI = box(ii, w, h, r)
        val corrIp = box(ip, w, h, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = corrI[i] - meanI[i] * meanI[i]
            val cov = corrIp[i] - meanI[i] * meanP[i]
            a[i] = cov / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = box(a, w, h, r)
        val meanB = box(b, w, h, r)
        return FloatArray(n) { (meanA[it] * guide[it] + meanB[it]).coerceIn(0f, 1f) }
    }

    /** Mean over a (2r+1)² window, via a summed-area table; windows are clipped at the edges. */
    private fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val sat = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += src[y * w + x]
                sat[(y + 1) * (w + 1) + (x + 1)] = sat[y * (w + 1) + (x + 1)] + row
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val ya = max(0, y - r)
            val yb = min(h, y + r + 1)
            for (x in 0 until w) {
                val xa = max(0, x - r)
                val xb = min(w, x + r + 1)
                val sum = sat[yb * (w + 1) + xb] - sat[ya * (w + 1) + xb] - sat[yb * (w + 1) + xa] + sat[ya * (w + 1) + xa]
                out[y * w + x] = (sum / ((yb - ya) * (xb - xa))).toFloat()
            }
        }
        return out
    }

    /** Zeroes everything not connected (through mostly-chosen pixels) to the traced line. */
    private fun keepConnected(alpha: FloatArray, core: BooleanArray, w: Int, h: Int) {
        val n = w * h
        val keep = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        for (i in 0 until n) if (core[i] && alpha[i] > 0.5f) { keep[i] = true; queue[tail++] = i }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            fun visit(j: Int) {
                if (!keep[j] && alpha[j] > 0.5f) { keep[j] = true; queue[tail++] = j }
            }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }
        // Grow by two pixels so soft edges around what's kept survive.
        repeat(2) {
            val grown = keep.copyOf()
            for (i in 0 until n) if (keep[i]) {
                val x = i % w
                val y = i / w
                if (x > 0) grown[i - 1] = true
                if (x < w - 1) grown[i + 1] = true
                if (y > 0) grown[i - w] = true
                if (y < h - 1) grown[i + w] = true
            }
            System.arraycopy(grown, 0, keep, 0, n)
        }
        for (i in 0 until n) if (!keep[i]) alpha[i] = 0f
    }

    /** 0 at [edge0], 1 at [edge1], smooth between (edges may be in either order). */
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun hypot(x: Float, y: Float) = sqrt(x * x + y * y)
}
