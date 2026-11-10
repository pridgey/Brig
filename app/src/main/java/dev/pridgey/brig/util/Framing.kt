package dev.pridgey.brig.util

/**
 * Math for a photo's zoom and position. A pan of -1..1 means how far the photo is moved toward
 * one edge, out of as far as the zoom allows. Pure, for tests.
 */
object Framing {
    const val MAX_ZOOM = 2.5f

    /** Where the middle of the screen falls on the photo, as a fraction of half the screen. */
    fun center(zoom: Float, pan: Float): Float = if (zoom <= 1f) 0f else pan * (zoom - 1f) / zoom

    /** The pan that puts [center] in the middle of the screen at [zoom], as near as the edges allow. */
    fun panFor(zoom: Float, center: Float): Float =
        if (zoom <= 1.0001f) 0f else (center * zoom / (zoom - 1f)).coerceIn(-1f, 1f)

    /** New pan along one axis when zoom changes and nothing else moves: the middle stays put. */
    fun rezoom(zoom: Float, pan: Float, newZoom: Float): Float = panFor(newZoom, center(zoom, pan))

    /**
     * New pan along one axis after a pinch or drag, keeping the spot under the fingers under the
     * fingers. [focus] is where the fingers were and [move] how far they moved, both in pixels from
     * the view's middle; [half] is half the view's size along this axis.
     */
    fun follow(zoom: Float, pan: Float, newZoom: Float, focus: Float, move: Float, half: Float): Float {
        if (newZoom <= 1.0001f || half <= 0f) return 0f
        val t = -pan * (zoom - 1f) * half
        val spot = (focus - t) / zoom
        val t2 = focus + move - spot * newZoom
        return (-t2 / ((newZoom - 1f) * half)).coerceIn(-1f, 1f)
    }
}
