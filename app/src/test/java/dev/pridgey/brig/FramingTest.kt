package dev.pridgey.brig

import dev.pridgey.brig.util.Framing
import org.junit.Assert.assertEquals
import org.junit.Test

class FramingTest {
    /** Where a photo point [u] (from the photo's middle) lands on screen. */
    private fun screen(u: Float, zoom: Float, pan: Float, half: Float) = u * zoom - pan * (zoom - 1f) * half

    @Test
    fun zoomingKeepsTheMiddleSteady() {
        val half = 200f
        val pan = 0.6f
        val before = Framing.center(1.5f, pan)
        val after = Framing.center(2f, Framing.rezoom(1.5f, pan, 2f))
        assertEquals(before, after, 1e-4f)
        // Back to 1x there's nothing to move.
        assertEquals(0f, Framing.rezoom(1.5f, pan, 1f))
        // The photo spot in the middle of the screen stays in the middle.
        val spot = before * half
        assertEquals(0f, screen(spot, 2f, Framing.rezoom(1.5f, pan, 2f), half), 0.5f)
    }

    @Test
    fun pinchKeepsTheSpotUnderTheFingers() {
        val half = 200f
        val focus = 80f
        val z = 1.4f
        val pan = -0.2f
        val spot = (focus - (-pan * (z - 1f) * half)) / z
        val next = Framing.follow(z, pan, 1.8f, focus, 0f, half)
        assertEquals(focus, screen(spot, 1.8f, next, half), 0.5f)
    }

    @Test
    fun dragMovesThePhotoWithTheFinger() {
        val next = Framing.follow(2f, 0f, 2f, 0f, 50f, 200f)
        assertEquals(50f, screen(0f, 2f, next, 200f), 0.5f)
    }

    @Test
    fun panStopsAtTheEdges() {
        assertEquals(1f, Framing.follow(1.2f, 0.9f, 1.2f, 0f, -10_000f, 200f))
        assertEquals(0f, Framing.follow(1f, 0f, 1f, 0f, 300f, 200f))
    }
}
