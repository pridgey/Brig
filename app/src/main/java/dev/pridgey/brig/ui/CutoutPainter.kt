package dev.pridgey.brig.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.AutoFixNormal
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.pridgey.brig.util.Segmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private enum class Brush { ADD, ERASE }

/**
 * Choose what pops out in front of the clock by painting over it. With "Snap to objects" on, a
 * stroke that touches something ML Kit found adds that whole thing; a stroke that touches nothing
 * found, or any stroke with snapping off, adds just the painted area. The eraser trims by hand.
 */
@Composable
fun CutoutPainter(path: String, accent: Color, onSaved: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var analysis by remember { mutableStateOf<Segmenter.Analysis?>(null) }
    var failed by remember { mutableStateOf(false) }
    // The selection, as an alpha mask the size of the photo, drawn into with an Android canvas.
    var mask by remember { mutableStateOf<Bitmap?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val history = remember { mutableStateListOf<Bitmap>() }
    var brush by remember { mutableStateOf(Brush.ADD) }
    var snap by remember { mutableStateOf(true) }
    var brushDp by remember { mutableFloatStateOf(28f) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        // Anything going wrong here (even running out of memory) shows a message, never a crash.
        val result = runCatching {
            val a = Segmenter.analyze(ctx, path) ?: return@runCatching null
            val m = withContext(Dispatchers.Default) {
                val m = Bitmap.createBitmap(a.photo.width, a.photo.height, Bitmap.Config.ALPHA_8)
                val c = android.graphics.Canvas(m)
                val fill = android.graphics.Rect(0, 0, m.width, m.height)
                // Start from the cut-out in use now, or what ML Kit picks on its own.
                val existing = Segmenter.cached(ctx, path)?.let { Segmenter.decodeAtMost(it, maxOf(m.width, m.height)) }
                when {
                    existing != null -> { c.drawBitmap(existing, null, fill, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)); existing.recycle() }
                    a.foreground != null -> c.drawBitmap(a.foreground, 0f, 0f, null)
                }
                m
            }
            a to m
        }.getOrNull()
        if (result == null) { failed = true; return@LaunchedEffect }
        val (a, m) = result
        analysis = a
        mask = m
        note = if (a.regions.isEmpty()) "Nothing found automatically. Paint what should pop out." else null
    }

    fun snapshot() {
        val m = mask ?: return
        history += m.copy(Bitmap.Config.ALPHA_8, true)
        if (history.size > 8) history.removeAt(0).recycle()
    }

    fun applyStroke(points: List<Offset>, radius: Float) {
        val a = analysis ?: return
        val m = mask ?: return
        if (points.isEmpty()) return
        snapshot()
        scope.launch(Dispatchers.Default) {
          try {
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
                maskFilter = BlurMaskFilter(max(1f, radius * 0.2f), BlurMaskFilter.Blur.NORMAL)
            }
            var message: String? = null
            if (brush == Brush.ERASE) {
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                c.drawPath(strokePath, paint)
            } else {
                val hits = if (snap) a.regions.filter { r -> points.any { p -> r.covers(p.x.toInt(), p.y.toInt()) } } else emptyList()
                if (hits.isNotEmpty()) {
                    hits.forEach { r -> c.drawBitmap(r.alphaBitmap(), r.x.toFloat(), r.y.toFloat(), null) }
                    message = if (hits.size == 1) "Added the object you touched" else "Added ${hits.size} objects you touched"
                } else {
                    c.drawPath(strokePath, paint)
                    if (snap && a.regions.isNotEmpty()) message = "No object found there, so the painted area was added"
                }
            }
            withContext(Dispatchers.Main) {
                note = message
                version++
            }
          } catch (e: Throwable) {
            withContext(Dispatchers.Main) { note = "That didn't work. Try a smaller area." }
          }
        }
    }

    fun replaceMask(from: Bitmap?) {
        val m = mask ?: return
        snapshot()
        m.eraseColor(android.graphics.Color.TRANSPARENT)
        from?.let { android.graphics.Canvas(m).drawBitmap(it, 0f, 0f, null) }
        version++
    }

    fun undo() {
        val prev = history.removeLastOrNull() ?: return
        mask = prev
        version++
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

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = Color.Black, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cancel") }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text("Pop-out", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Paint over what should stand in front of the clock",
                            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    Button(onClick = ::save, enabled = mask != null && !busy, modifier = Modifier.padding(end = 8.dp)) { Text("Save") }
                }

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    val a = analysis
                    val m = mask
                    if (a != null && m != null) {
                        PaintCanvas(a.photo, m, version, accent, brushDp, brush, onStroke = ::applyStroke)
                    } else {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (failed) {
                                Text("Couldn't open this photo", style = MaterialTheme.typography.titleMedium)
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
                    val noteVisible = note != null
                    Box(Modifier.align(Alignment.TopCenter).padding(12.dp)) { NoteFade(noteVisible) {
                        Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.7f), contentColor = Color.White) {
                            Text(note.orEmpty(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    } }
                }

                // Tools
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 10.dp)) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                        val chip = FilterChipDefaults.filterChipColors(labelColor = MaterialTheme.colorScheme.onSurface)
                        FilterChip(
                            selected = snap, onClick = { snap = !snap }, colors = chip,
                            label = { Text("Snap to objects") }, shape = CircleShape,
                            leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)) },
                        )
                        FilterChip(
                            selected = false, onClick = { replaceMask(analysis?.foreground) }, colors = chip,
                            label = { Text("Auto") }, shape = CircleShape, enabled = analysis != null,
                        )
                        FilterChip(
                            selected = false, onClick = { replaceMask(null) }, colors = chip,
                            label = { Text("Clear") }, shape = CircleShape, enabled = analysis != null,
                            leadingIcon = { Icon(Icons.Filled.LayersClear, null, Modifier.size(18.dp)) },
                        )
                    }
                    Text(
                        "Tap or paint an object to add it. Pinch with two fingers to zoom in for detail.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteFade(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) { content() }
}

/**
 * The photo with the selection shown bright and everything else dimmed. One finger paints,
 * two fingers zoom and move the view.
 */
@Composable
private fun PaintCanvas(
    photo: Bitmap,
    mask: Bitmap,
    version: Int,
    accent: Color,
    brushDp: Float,
    brush: Brush,
    onStroke: (points: List<Offset>, radius: Float) -> Unit,
) {
    val photoImg = remember(photo) { photo.asImageBitmap() }
    val maskImg = remember(mask) { mask.asImageBitmap() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val stroke = remember { mutableStateListOf<Offset>() }
    var finger by remember { mutableStateOf<Offset?>(null) }

    // Photo fitted to the view, then the user's zoom and move.
    val fit = if (size.width == 0) 1f else min(size.width / photo.width.toFloat(), size.height / photo.height.toFloat())
    val scale = fit * zoom
    val origin = Offset(
        (size.width - photo.width * scale) / 2f + offset.x,
        (size.height - photo.height * scale) / 2f + offset.y,
    )
    val brushPx = with(androidx.compose.ui.platform.LocalDensity.current) { (brushDp / 2f).dp.toPx() }
    // The gesture below outlives recompositions, so it reads these through state.
    val brushNow by androidx.compose.runtime.rememberUpdatedState(brushPx)
    val strokeNow by androidx.compose.runtime.rememberUpdatedState(onStroke)

    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(photo) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    stroke.clear()
                    stroke += toPhotoNow(down.position, size, photo, zoom, offset)
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
                                stroke += toPhotoNow(ch.position, size, photo, zoom, offset)
                                finger = ch.position
                                ch.consume()
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                    finger = null
                    if (painting && stroke.isNotEmpty()) {
                        val fitNow = min(size.width / photo.width.toFloat(), size.height / photo.height.toFloat())
                        strokeNow(stroke.toList(), brushNow / (fitNow * zoom))
                    }
                    stroke.clear()
                }
            },
    ) {
        version // Redraw when the mask changes.
        translate(origin.x, origin.y) {
            scale(scale, scale, pivot = Offset.Zero) {
                // Dimmed photo, then the selected part at full brightness with a light tint.
                drawImage(photoImg, alpha = 0.35f)
                drawIntoCanvas { c ->
                    val bounds = androidx.compose.ui.geometry.Rect(0f, 0f, photo.width.toFloat(), photo.height.toFloat())
                    c.saveLayer(bounds, Paint())
                    drawImage(photoImg)
                    drawRect(accent.copy(alpha = 0.18f), size = bounds.size)
                    drawImage(maskImg, blendMode = BlendMode.DstIn)
                    c.restore()
                }
                if (stroke.isNotEmpty()) {
                    val p = Path().apply {
                        moveTo(stroke[0].x, stroke[0].y)
                        stroke.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(
                        p, if (brush == Brush.ADD) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.45f),
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
private fun toPhotoNow(p: Offset, size: IntSize, photo: Bitmap, zoom: Float, offset: Offset): Offset {
    val fit = min(size.width / photo.width.toFloat(), size.height / photo.height.toFloat())
    val s = fit * zoom
    val ox = (size.width - photo.width * s) / 2f + offset.x
    val oy = (size.height - photo.height * s) / 2f + offset.y
    return Offset((p.x - ox) / s, (p.y - oy) / s)
}
