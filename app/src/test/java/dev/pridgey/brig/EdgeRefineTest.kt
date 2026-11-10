package dev.pridgey.brig

import dev.pridgey.brig.util.EdgeRefine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeRefineTest {
    private val sky = 0xFF7FB2FF.toInt()
    private val steel = 0xFF3A2A22.toInt()

    /** Sky with a dark horizontal bar (a "bridge") across rows [top]..[bottom], wider than the stroke. */
    private fun bridge(w: Int, h: Int, top: Int, bottom: Int) = IntArray(w * h) { i -> if (i / w in top..bottom) steel else sky }

    private fun alphaAt(r: EdgeRefine.Result, x: Int, y: Int) = r.alpha[(y - r.y0) * r.width + (x - r.x0)]

    @Test
    fun tightensAStrokeToTheBridge() {
        val w = 200
        val h = 120
        val px = bridge(w, h, 55, 64)
        // A sloppy stroke along the bridge, much thicker than it; the bridge carries on past both ends.
        val path = (40..160 step 4).map { it.toFloat() to (60f + if (it % 8 == 0) 3f else -3f) }
        val r = EdgeRefine.refine(px, w, h, path, radius = 24f)!!
        assertTrue("should tighten", r.tightened)
        // On the bridge: kept.
        assertTrue(alphaAt(r, 100, 60) > 0.9f)
        assertTrue(alphaAt(r, 60, 56) > 0.8f)
        // Sky inside the stroke, above and below the bridge: dropped.
        assertTrue(alphaAt(r, 100, 45) < 0.1f)
        assertTrue(alphaAt(r, 100, 75) < 0.1f)
        // Roughly the bridge's share of the stroke (10 of ~48 rows).
        assertTrue("kept ${r.kept}", r.kept in 0.12f..0.4f)
    }

    @Test
    fun neverAddsOutsideTheStroke() {
        val w = 200
        val h = 120
        val px = bridge(w, h, 55, 64)
        val r = EdgeRefine.refine(px, w, h, listOf(80f to 60f, 120f to 60f), radius = 20f)!!
        // The bridge carries on to x = 0, far past the stroke: not added.
        assertTrue(r.x0 > 0)
        for (y in 0 until r.height) {
            for (x in 0 until r.width) {
                val gx = x + r.x0
                val gy = y + r.y0
                val dx = when {
                    gx < 80 -> 80 - gx
                    gx > 120 -> gx - 120
                    else -> 0
                }
                val d = kotlin.math.hypot(dx.toFloat(), (gy - 60).toFloat())
                if (d > 21f) assertEquals("($gx,$gy)", 0f, r.alpha[y * r.width + x], 1e-4f)
            }
        }
    }

    @Test
    fun keepsTheStrokeWhenThereIsNothingToTellApart() {
        val w = 120
        val h = 120
        val px = IntArray(w * h) { sky }
        val r = EdgeRefine.refine(px, w, h, listOf(30f to 60f, 90f to 60f), radius = 15f)!!
        assertFalse(r.tightened)
        assertTrue(alphaAt(r, 60, 60) > 0.99f)
        assertTrue(alphaAt(r, 60, 70) > 0.5f)
    }

    @Test
    fun dropsMatchingSpecksNotConnectedToTheTracedLine() {
        val w = 200
        val h = 120
        val px = bridge(w, h, 57, 63).also { p ->
            // A small dark speck in the sky, inside the stroke but apart from the bridge.
            for (y in 42..44) for (x in 98..100) p[y * w + x] = steel
        }
        val r = EdgeRefine.refine(px, w, h, (40..160 step 5).map { it.toFloat() to 60f }, radius = 24f)!!
        assertTrue(r.tightened)
        assertTrue(alphaAt(r, 99, 43) < 0.1f)
        assertTrue(alphaAt(r, 99, 60) > 0.9f)
    }
}
