package dev.pridgey.brig.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pridgey.brig.util.EdgeRefine
import dev.pridgey.brig.util.Segmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private enum class Brush { ADD, ERASE }

/**
 * Fixed pictures for the painter, built on the CPU: [photo] as is, [base] in gray and dark for
 * everything not chosen, and [found], a glow over what snapping can add. The chosen part is
 * rebuilt after each edit. The screen only ever draws ordinary images on top of each other,
 * which every phone's graphics handle the same way.
 */
private class Pictures(val photo: ImageBitmap, val base: ImageBitmap, val found: ImageBitmap?)

/**
 * Choose what pops out in front of the clock by painting over it. With "Snap to objects" on, a
 * stroke that touches something ML Kit found adds that whole thing; a stroke that touches nothing
 * found, or any stroke with snapping off, adds just the painted area. The eraser trims by hand.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CutoutPainter(path: String, accent: Color, onSaved: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var analysis by remember { mutableStateOf<Segmenter.Analysis?>(null) }
    var pictures by remember { mutableStateOf<Pictures?>(null) }
    var failed by remember { mutableStateOf(false) }
    // The selection: a mask the size of the (painting-size) photo; only its alpha matters.
    var mask by remember { mutableStateOf<Bitmap?>(null) }
    var chosen by remember { mutableStateOf<ImageBitmap?>(null) }
    var coverage by remember { mutableIntStateOf(-1) }
    val history = remember { mutableStateListOf<Bitmap>() }
    var brush by remember { mutableStateOf(Brush.ADD) }
    var snap by remember { mutableStateOf(true) }
    var smart by remember { mutableStateOf(true) }
    // The photo's pixels, read once, for smart edges.
    val pixelsRef = remember { arrayOfNulls<IntArray>(1) }
    var brushDp by remember { mutableFloatStateOf(28f) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val tint = accent.toArgb()

    /** The chosen part in full color (transparent elsewhere), and how much of the photo it is. */
    fun render(photo: Bitmap, m: Bitmap): Pair<ImageBitmap, Int> {
        val out = photo.copy(Bitmap.Config.ARGB_8888, true)
        // A copy of a JPEG is marked "no transparency", which would make cutting away do nothing.
        out.setHasAlpha(true)
        val c = android.graphics.Canvas(out)
        c.drawBitmap(m, 0f, 0f, android.graphics.Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        // A light accent tint, so the chosen part reads as "selected".
        c.drawColor((tint and 0x00FFFFFF) or (0x30 shl 24), PorterDuff.Mode.SRC_ATOP)
        return out.asImageBitmap() to percentChosen(m)
    }

    suspend fun refresh(m: Bitmap) {
        val a = analysis ?: return
        val (img, pct) = withContext(Dispatchers.Default) { render(a.photo, m) }
        chosen = img
        coverage = pct
    }

    LaunchedEffect(path) {
        // Anything going wrong here (even running out of memory) shows a message, never a crash.
        val result = runCatching {
            val a = Segmenter.analyze(ctx, path) ?: return@runCatching null
            withContext(Dispatchers.Default) {
                val w = a.photo.width
                val h = a.photo.height
                val m = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(m)
                // Start from the cut-out in use now, or what ML Kit picks on its own.
                val existing = Segmenter.cached(ctx, path)?.let { Segmenter.decodeAtMost(it, max(w, h)) }
                when {
                    existing != null -> {
                        c.drawBitmap(existing, null, android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                        existing.recycle()
                    }
                    a.foreground != null -> c.drawBitmap(a.foreground, 0f, 0f, null)
                }
                // A solid saved cut-out (the whole photo) is never what was meant: start from the automatic pick.
                if (existing != null && percentChosen(m) >= 99) {
                    m.eraseColor(android.graphics.Color.TRANSPARENT)
                    a.foreground?.let { c.drawBitmap(it, 0f, 0f, null) }
                }
                // The photo in gray and dark, for everything not chosen.
                val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(base).apply {
                    drawColor(android.graphics.Color.BLACK)
                    drawBitmap(a.photo, 0f, 0f, android.graphics.Paint().apply {
                        alpha = 110
                        colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
                    })
                }
                // Everything snapping could add, in the accent color.
                val found = if (a.regions.isEmpty()) null else runCatching {
                    val f = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val fc = android.graphics.Canvas(f)
                    val p = android.graphics.Paint().apply { color = tint }
                    a.regions.forEach { r -> fc.drawBitmap(r.alphaBitmap(), r.x.toFloat(), r.y.toFloat(), p) }
                    f.asImageBitmap()
                }.getOrNull()
                Triple(a, m, Pictures(a.photo.asImageBitmap(), base.asImageBitmap(), found))
            }
        }.getOrNull()
        if (result == null) { failed = true; return@LaunchedEffect }
        val (a, m, pics) = result
        analysis = a
        pictures = pics
        mask = m
        refresh(m)
        note = when {
            a.mode >= 2 -> "Object finding is off on this phone after a problem. Paint what should pop out."
            a.regions.isNotEmpty() -> "Found ${a.regions.size} object${if (a.regions.size == 1) "" else "s"}. Tap one to add it."
            a.foreground != null -> "Paint what should pop out. Auto picks the main subject."
            else -> "Nothing found automatically. Paint what should pop out."
        }
    }

    fun snapshot() {
        val m = mask ?: return
        history += m.copy(Bitmap.Config.ARGB_8888, true)
        if (history.size > 6) history.removeAt(0).recycle()
    }

    fun applyStroke(points: List<Offset>, radius: Float) {
        val a = analysis ?: return
        val m = mask ?: return
        if (points.isEmpty()) return
        snapshot()
        val erase = brush == Brush.ERASE
        val snapping = snap
        val tighten = smart
        scope.launch {
            val message = withContext(Dispatchers.Default) {
                runCatching {
                    val c = android.graphics.Canvas(m)
                    val strokePath = android.graphics.Path().apply {
                        moveTo(points[0].x, points[0].y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                        if (points.size == 1) lineTo(points[0].x + 0.1f, points[0].y)
                    }
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        strokeJoin = android.graphics.Paint.Join.ROUND
                        strokeWidth = radius * 2f
                        color = android.graphics.Color.BLACK
                        maskFilter = BlurMaskFilter(max(1f, radius * 0.2f), BlurMaskFilter.Blur.NORMAL)
                    }
                    if (erase) {
                        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                        c.drawPath(strokePath, paint)
                        "Erased"
                    } else {
                        val hits = if (snapping) a.regions.filter { r -> points.any { p -> r.covers(p.x.toInt(), p.y.toInt()) } } else emptyList()
                        if (hits.isNotEmpty()) {
                            hits.forEach { r -> c.drawBitmap(r.alphaBitmap(), r.x.toFloat(), r.y.toFloat(), null) }
                            if (hits.size == 1) "Added the object you touched" else "Added ${hits.size} objects you touched"
                        } else if (tighten) {
                            val w = a.photo.width
                            val h = a.photo.height
                            val px = pixelsRef[0] ?: IntArray(w * h).also { a.photo.getPixels(it, 0, w, 0, 0, w, h); pixelsRef[0] = it }
                            val res = EdgeRefine.refine(px, w, h, points.map { it.x to it.y }, radius)
                            if (res != null && res.tightened) {
                                val colors = IntArray(res.alpha.size) { i -> (res.alpha[i].coerceIn(0f, 1f) * 255f).toInt() shl 24 }
                                val piece = Bitmap.createBitmap(colors, res.width, res.height, Bitmap.Config.ARGB_8888)
                                c.drawBitmap(piece, res.x0.toFloat(), res.y0.toFloat(), null)
                                piece.recycle()
                                "Tightened to the edges · kept ${(res.kept * 100).toInt().coerceIn(1, 100)}% of your stroke"
                            } else {
                                c.drawPath(strokePath, paint)
                                "No clear edges there, so the painted area was added"
                            }
                        } else {
                            c.drawPath(strokePath, paint)
                            if (snapping && a.regions.isNotEmpty()) "No object there, so the painted area was added" else "Added the painted area"
                        }
                    }
                }.getOrElse { "That didn't work. Try a smaller area." }
            }
            refresh(m)
            note = message
        }
    }

    fun replaceMask(from: Bitmap?, message: String) {
        val m = mask ?: return
        snapshot()
        scope.launch {
            withContext(Dispatchers.Default) {
                m.eraseColor(android.graphics.Color.TRANSPARENT)
                from?.let { android.graphics.Canvas(m).drawBitmap(it, 0f, 0f, null) }
            }
            refresh(m)
            note = message
        }
    }

    fun undo() {
        val prev = history.removeLastOrNull() ?: return
        mask = prev
        scope.launch {
            refresh(prev)
            note = "Undone"
        }
    }

    fun save() {
        val m = mask ?: return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.Default) {
                runCatching {
                    // Made at the size the lock screen shows the photo, with the painted mask scaled up.
                    val full = Segmenter.decode(path) ?: return@runCatching false
                    val out = full.copy(Bitmap.Config.ARGB_8888, true)
                    full.recycle()
                    // Without this, a JPEG's copy stays solid and the whole photo would be saved.
                    out.setHasAlpha(true)
                    val c = android.graphics.Canvas(out)
                    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    }
                    c.drawBitmap(m, null, android.graphics.Rect(0, 0, out.width, out.height), paint)
                    Segmenter.saveGuided(ctx, path, out)
                    out.recycle()
                    true
                }.getOrDefault(false)
            }
            busy = false
            if (ok) onSaved() else note = "Couldn't save the pop-out. Try again."
        }
    }

    FullScreenDialog(onDismiss, color = Color.Black) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cancel", tint = Color.White) }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("Pop-out", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(
                        when {
                            coverage < 0 -> "Bright = in front of the clock"
                            coverage == 0 -> "Nothing pops out yet"
                            else -> "$coverage% of the photo pops out"
                        },
                        style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f),
                    )
                }
                Button(onClick = ::save, enabled = mask != null && !busy, modifier = Modifier.padding(end = 8.dp)) {
                    Text(if (busy) "Saving…" else "Save")
                }
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                val a = analysis
                val pics = pictures
                val sel = chosen
                if (a != null && pics != null && sel != null) {
                    PaintCanvas(
                        a.photo.width, a.photo.height, pics.base, sel,
                        found = pics.found.takeIf { snap && brush == Brush.ADD },
                        accent = accent, brushDp = brushDp, brush = brush, onStroke = ::applyStroke,
                    )
                    PopPreview(
                        pics.photo, sel, a.photo.width / a.photo.height.toFloat(),
                        Modifier.align(Alignment.TopEnd).padding(12.dp),
                    )
                } else {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (failed) {
                            Text("Couldn't open this photo", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        } else {
                            CircularProgressIndicator(color = accent)
                            Text(
                                "Finding objects… (the first time downloads a small model)",
                                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                    }
                }
                Box(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                    NoteFade(note != null) {
                        Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.75f), contentColor = Color.White) {
                            Text(note.orEmpty(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                }
            }

            // Tools. Scrolls if the screen is short, so nothing is ever out of reach.
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        SegmentedButton(
                            selected = brush == Brush.ADD, onClick = { brush = Brush.ADD },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            icon = { Icon(Icons.Filled.Brush, null, Modifier.size(18.dp)) },
                        ) { Text("Add") }
                        SegmentedButton(
                            selected = brush == Brush.ERASE, onClick = { brush = Brush.ERASE },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            icon = { Icon(Icons.Filled.AutoFixNormal, null, Modifier.size(18.dp)) },
                        ) { Text("Erase") }
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = ::undo, enabled = history.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Brush", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Slider(brushDp, { brushDp = it }, valueRange = 8f..72f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size((brushDp / 2f).dp).background(accent, CircleShape))
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    val chip = FilterChipDefaults.filterChipColors(labelColor = MaterialTheme.colorScheme.onSurface)
                    FilterChip(
                        selected = smart, onClick = { smart = !smart }, colors = chip,
                        label = { Text("Smart edges") }, shape = CircleShape,
                        leadingIcon = { Icon(Icons.Filled.Gesture, null, Modifier.size(18.dp)) },
                    )
                    val canSnap = analysis?.regions?.isNotEmpty() == true
                    FilterChip(
                        selected = snap && canSnap, onClick = { snap = !snap }, colors = chip, enabled = canSnap,
                        label = { Text("Snap to objects") }, shape = CircleShape,
                        leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)) },
                    )
                    FilterChip(
                        selected = false, onClick = { replaceMask(analysis?.foreground, "Back to the automatic pick") }, colors = chip,
                        label = { Text("Auto") }, shape = CircleShape, enabled = analysis?.foreground != null,
                    )
                    FilterChip(
                        selected = false, onClick = { replaceMask(null, "Cleared. Nothing pops out now.") }, colors = chip,
                        label = { Text("Clear") }, shape = CircleShape, enabled = analysis != null,
                        leadingIcon = { Icon(Icons.Filled.LayersClear, null, Modifier.size(18.dp)) },
                    )
                }
                Text(
                    when {
                        brush == Brush.ERASE -> "Paint over anything that shouldn't pop out."
                        snap && analysis?.regions?.isNotEmpty() == true && smart ->
                            "Tap a glowing object to add all of it. Elsewhere, paint along what should pop out and smart edges trim each stroke to it."
                        snap && analysis?.regions?.isNotEmpty() == true -> "Tap a glowing object to add all of it, or paint any area. Pinch to zoom."
                        smart -> "Paint along what should pop out; smart edges trim each stroke to it. Pinch with two fingers to zoom."
                        else -> "Paint over what should pop out. Pinch with two fingers to zoom."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Share of the mask that's chosen, 0..100, sampled every 4th pixel for speed. */
private fun percentChosen(m: Bitmap): Int {
    val w = m.width
    val row = IntArray(w)
    var on = 0
    var all = 0
    var y = 0
    while (y < m.height) {
        m.getPixels(row, 0, w, 0, y, w, 1)
        var x = 0
        while (x < w) {
            if ((row[x] ushr 24) > 127) on++
            all++
            x += 4
        }
        y += 4
    }
    if (all == 0 || on == 0) return 0
    return max(1, (on * 100) / all)
}

@Composable
private fun NoteFade(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) { content() }
}

/**
 * A small picture of the result: the photo, a clock, then the chosen part in front of it.
 * Tap to make it bigger.
 */
@Composable
private fun PopPreview(photo: ImageBitmap, chosen: ImageBitmap, aspect: Float, modifier: Modifier) {
    var big by remember { mutableStateOf(false) }
    val w by animateDpAsState(if (big) 168.dp else 96.dp, label = "pip")
    val time = remember { java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("h:mm")) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(w)
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(14.dp))
                .border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                .clickable { big = !big },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(photo, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                drawRect(Color.Black.copy(alpha = 0.4f))
            }
            Text(
                time, color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (w.value * 0.28f).sp,
                modifier = Modifier.align(Alignment.Center),
            )
            Canvas(Modifier.fillMaxSize()) {
                drawImage(chosen, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            }
        }
        Text("Preview", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * The photo, gray and dark, with the chosen part in full color on top. Objects that snapping can
 * add glow faintly. One finger paints, two fingers zoom and move the view.
 */
@Composable
private fun PaintCanvas(
    photoW: Int,
    photoH: Int,
    base: ImageBitmap,
    chosen: ImageBitmap,
    found: ImageBitmap?,
    accent: Color,
    brushDp: Float,
    brush: Brush,
    onStroke: (points: List<Offset>, radius: Float) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val stroke = remember { mutableStateListOf<Offset>() }
    var finger by remember { mutableStateOf<Offset?>(null) }

    // Photo fitted to the view, then the user's zoom and move.
    val fit = if (size.width == 0) 1f else min(size.width / photoW.toFloat(), size.height / photoH.toFloat())
    val scale = fit * zoom
    val origin = Offset(
        (size.width - photoW * scale) / 2f + offset.x,
        (size.height - photoH * scale) / 2f + offset.y,
    )
    val brushPx = with(LocalDensity.current) { (brushDp / 2f).dp.toPx() }
    // The gesture below outlives recompositions, so it reads these through state.
    val brushNow by rememberUpdatedState(brushPx)
    val strokeNow by rememberUpdatedState(onStroke)

    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(photoW, photoH) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    stroke.clear()
                    stroke += toPhoto(down.position, size, photoW, photoH, zoom, offset)
                    finger = down.position
                    var painting = true
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed >= 2) {
                            // Two fingers: cancel the stroke and move the view instead.
                            painting = false
                            stroke.clear()
                            finger = null
                            val c = ev.calculateCentroid(useCurrent = false)
                            val z = (zoom * ev.calculateZoom()).coerceIn(1f, 6f)
                            val pan = ev.calculatePan()
                            val center = Offset(size.width / 2f, size.height / 2f)
                            // Keep the spot under the fingers under the fingers.
                            offset = (offset - (c - center)) * (z / zoom) + (c - center) + pan
                            zoom = z
                            ev.changes.forEach { it.consume() }
                        } else if (painting) {
                            ev.changes.firstOrNull { it.pressed }?.let { ch ->
                                stroke += toPhoto(ch.position, size, photoW, photoH, zoom, offset)
                                finger = ch.position
                                ch.consume()
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                    finger = null
                    if (painting && stroke.isNotEmpty()) {
                        val fitNow = min(size.width / photoW.toFloat(), size.height / photoH.toFloat())
                        strokeNow(stroke.toList(), brushNow / (fitNow * zoom))
                    }
                    stroke.clear()
                }
            },
    ) {
        translate(origin.x, origin.y) {
            scale(scale, scale, pivot = Offset.Zero) {
                drawImage(base)
                found?.let { drawImage(it, alpha = 0.35f) }
                drawImage(chosen)
                if (stroke.isNotEmpty()) {
                    val p = Path().apply {
                        moveTo(stroke[0].x, stroke[0].y)
                        stroke.drop(1).forEach { lineTo(it.x, it.y) }
                        if (stroke.size == 1) lineTo(stroke[0].x + 0.1f, stroke[0].y)
                    }
                    drawPath(
                        p, if (brush == Brush.ADD) accent.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.6f),
                        style = Stroke(width = brushPx * 2f / scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
        finger?.let {
            drawCircle(Color.White, radius = brushPx, center = it, style = Stroke(2.dp.toPx()))
        }
    }
}

/** Screen point to photo pixels, from the current view state (gesture code can't read composition values). */
private fun toPhoto(p: Offset, size: IntSize, w: Int, h: Int, zoom: Float, offset: Offset): Offset {
    val fit = min(size.width / w.toFloat(), size.height / h.toFloat())
    val s = fit * zoom
    val ox = (size.width - w * s) / 2f + offset.x
    val oy = (size.height - h * s) / 2f + offset.y
    return Offset((p.x - ox) / s, (p.y - oy) / s)
}
