package dev.pridgey.brig.ui

import android.util.LruCache
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pinch
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.pridgey.brig.BrigApp
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import dev.pridgey.brig.lock.BACKGROUND_OVERSCAN
import dev.pridgey.brig.lock.LocalLookOverride
import dev.pridgey.brig.lock.LockActivity
import dev.pridgey.brig.lock.LookOverride
import dev.pridgey.brig.util.Framing
import dev.pridgey.brig.util.Segmenter
import dev.pridgey.brig.data.BackgroundType
import dev.pridgey.brig.data.BrigSettings
import dev.pridgey.brig.data.PhotoEffect
import dev.pridgey.brig.data.PhotoLook
import dev.pridgey.brig.data.PhotoRotator
import dev.pridgey.brig.data.PhotoSource
import dev.pridgey.brig.data.PhotoSourceConfig
import dev.pridgey.brig.data.sharedLook
import dev.pridgey.brig.data.withLook
import dev.pridgey.brig.lock.loadPhoto
import dev.pridgey.brig.util.PhotoFx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** A change to a look. Changes set fields outright, so applying one twice is harmless. */
typealias LookChange = (PhotoLook) -> PhotoLook

// ---------------------------------------------------------------- Thumbnails

private object Thumbs {
    private val cache = LruCache<String, ImageBitmap>(48)

    suspend fun load(path: String, px: Int): ImageBitmap? {
        val key = "$path@$px"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) { loadPhoto(path, px) }?.also { cache.put(key, it) }
    }

    fun peek(path: String, px: Int): ImageBitmap? = cache.get("$path@$px")
}

@Composable
private fun rememberThumb(path: String?, px: Int = 360): ImageBitmap? {
    val img by produceState(path?.let { Thumbs.peek(it, px) }, path, px) {
        value = path?.let { Thumbs.load(it, px) }
    }
    return img
}

/** A photo drawn the way its look would show it: color, framing, blur and dim. */
@Composable
private fun LookedPhoto(path: String, look: PhotoLook, accent: Color, modifier: Modifier = Modifier) {
    val img = rememberThumb(path)
    val matrix = remember(look.photo, accent) { ColorMatrix(PhotoFx.matrix(look.photo, accent)) }
    val shown by animateFloatAsState(if (img != null) 1f else 0f, label = "thumbIn")
    Box(modifier.clipToBounds().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        img?.let {
            val cfg = look.photo
            Image(
                it, null,
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = shown
                        scaleX = cfg.zoom
                        scaleY = cfg.zoom
                        translationX = -cfg.panX * (cfg.zoom - 1f) * size.width / 2f
                        translationY = -cfg.panY * (cfg.zoom - 1f) * size.height / 2f
                    }
                    .then(if (look.blur > 0f) Modifier.blur((look.blur * 6).dp) else Modifier),
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(matrix),
            )
        }
        if (look.dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = look.dim * shown)))
    }
}

// ---------------------------------------------------------------- Manager

/** Photos taken off the list but not yet deleted, so the removal can be undone. */
private class Removal(val before: List<String>, val removed: List<String>, val looks: Map<String, PhotoLook>)

private data class EditRequest(val paths: List<String>, val start: Int, val group: Boolean)

/**
 * The "My picks" photo manager: a grid of the picked photos, each drawn with its own look.
 * Tap a photo to adjust it; long-press (or "Select") to pick several and adjust, reset or remove
 * them together. Removing can be undone for a few seconds.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumPicks(settings: BrigSettings, update: SettingsUpdate, onAdd: () -> Unit, rotate: (PhotoSourceConfig) -> Unit) {
    val ctx = LocalContext.current
    val cfg = settings.photos
    val paths = cfg.albumPaths
    val accent = resolveAccent(settings.appearance.accent)
    val shared = settings.appearance.sharedLook()
    val current by PhotoRotator.current.collectAsState()
    val haptics = LocalHapticFeedback.current

    var selection by remember { mutableStateOf<Set<String>?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditRequest?>(null) }
    var pending by remember { mutableStateOf<Removal?>(null) }
    val selecting = selection != null
    val chosen = selection.orEmpty().filter { it in paths }

    fun commit(r: Removal) {
        val keep = BrigApp.settings.flow.value.photos.albumPaths.toSet()
        BrigApp.instance.appScope.launch(Dispatchers.IO) {
            r.removed.filter { it !in keep }.forEach {
                Segmenter.forgetGuided(ctx, it)
                File(it).delete()
            }
        }
    }

    fun remove(gone: Collection<String>) {
        val removed = paths.filter { it in gone }
        if (removed.isEmpty()) return
        pending?.let(::commit)
        pending = Removal(paths, removed, cfg.looks.filterKeys { it in removed })
        selection = null
        update { it.copy(photos = it.photos.without(removed)) }
        // Don't leave a removed photo on the lock screen.
        val next = cfg.without(removed)
        if (current?.path in removed && next.albumPaths.isNotEmpty()) rotate(next)
    }

    fun undo() {
        val r = pending ?: return
        pending = null
        update { s ->
            val now = s.photos.albumPaths
            val restored = r.before.filter { it in now || it in r.removed } + now.filter { it !in r.before }
            s.copy(photos = s.photos.copy(albumPaths = restored, looks = s.photos.looks + r.looks))
        }
    }

    LaunchedEffect(pending) {
        val r = pending ?: return@LaunchedEffect
        delay(7_000)
        commit(r)
        if (pending === r) pending = null
    }
    DisposableEffect(Unit) { onDispose { pending?.let(::commit) } }

    fun setLooks(targets: Collection<String>, change: LookChange?) = update { s ->
        val p = s.photos
        val looks = if (change == null) p.looks - targets.toSet()
        else p.looks + targets.associateWith { change(p.looks[it] ?: s.appearance.sharedLook()) }
        s.copy(photos = p.copy(looks = looks))
    }

    val undoBar: @Composable () -> Unit = {
        AnimatedVisibility(pending != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            val n = pending?.removed?.size ?: 0
            Surface(
                shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 6.dp)) {
                    Text(
                        if (n == 1) "Removed 1 photo" else "Removed $n photos",
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = ::undo) { Text("Undo", color = MaterialTheme.colorScheme.inversePrimary) }
                }
            }
        }
    }

    Column(Modifier.animateContentSize()) {
        // Header: a summary, or the selection's actions.
        AnimatedContent(selecting && paths.isNotEmpty(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "picksHeader") { sel ->
            if (!sel) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 2.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (paths.size) { 0 -> "No photos yet"; 1 -> "1 photo"; else -> "${paths.size} photos" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val own = paths.count { it in cfg.looks }
                        Text(
                            when {
                                paths.isEmpty() -> "Each photo can have its own look"
                                own == 0 -> "Tap a photo to give it its own look"
                                own == paths.size -> "All have their own look"
                                else -> "$own with their own look · the rest use the shared look"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (paths.size > 1) TextButton(onClick = { selection = emptySet() }) { Text("Select") }
                }
            } else {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 8.dp)) {
                        IconButton(onClick = { selection = null }) { Icon(Icons.Filled.Close, "Stop selecting") }
                        Text(
                            if (chosen.isEmpty()) "Select photos" else "${chosen.size} selected",
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                        )
                        val all = chosen.size == paths.size
                        TextButton(onClick = { selection = if (all) emptySet() else paths.toSet() }) { Text(if (all) "None" else "All") }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
                    ) {
                        val on = chosen.isNotEmpty()
                        val pad = PaddingValues(horizontal = 12.dp)
                        FilledTonalButton(
                            onClick = { editing = EditRequest(chosen, 0, group = true) }, enabled = on,
                            contentPadding = pad, modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Filled.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Adjust", maxLines = 1) }
                        FilledTonalButton(
                            onClick = { setLooks(chosen, null); selection = null }, enabled = on && chosen.any { it in cfg.looks },
                            contentPadding = pad, modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Filled.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Reset", maxLines = 1) }
                        OutlinedButton(
                            onClick = { remove(chosen) }, enabled = on, contentPadding = pad, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, if (on) MaterialTheme.colorScheme.error.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant),
                        ) { Icon(Icons.Filled.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Remove", maxLines = 1) }
                    }
                }
            }
        }
        undoBar()

        if (paths.isEmpty()) {
            AddTile(Modifier.fillMaxWidth().padding(16.dp).height(132.dp), wide = true, onClick = onAdd)
        } else BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val gap = 8.dp
            val cols = if (maxWidth < 560.dp) 4 else 6
            val tileW = (maxWidth - gap * (cols - 1)) / cols
            // Collapsed: three rows, the add tile first and a "+N" tile last.
            val room = cols * 3 - 1
            val collapsed = !expanded && !selecting && paths.size > room
            val shownPaths = if (collapsed) paths.take(room) else paths
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
                maxItemsInEachRow = cols,
            ) {
                val tile = Modifier.width(tileW).aspectRatio(3f / 4f)
                if (!selecting) AddTile(tile, onClick = onAdd)
                shownPaths.forEachIndexed { i, p ->
                    val more = if (collapsed && i == shownPaths.lastIndex) paths.size - room + 1 else null
                    PickTile(
                        path = p, index = i, look = cfg.lookFor(p) ?: shared, own = p in cfg.looks,
                        showing = current?.path == p && settings.photos.source == PhotoSource.ALBUM,
                        selecting = selecting, selected = p in chosen, accent = accent, more = more,
                        modifier = tile,
                        onClick = {
                            when {
                                more != null -> expanded = true
                                selecting -> selection = chosen.toSet().let { s -> if (p in s) s - p else s + p }
                                else -> editing = EditRequest(paths, i, group = false)
                            }
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            selection = (selection ?: emptySet()) + p
                        },
                    )
                }
            }
        }
        if (expanded && !selecting && paths.size > 11) {
            TextButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Show less") }
        }
        if (paths.isNotEmpty() && !selecting) {
            Text(
                "Long-press to select several. Photos without their own look follow the Shared look below.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
    }

    editing?.let { req ->
        PhotoLookEditor(
            settings = settings, req = req, accent = accent,
            onChange = ::setLooks,
            onShared = { look, from ->
                update { s -> s.copy(appearance = s.appearance.withLook(look), photos = s.photos.copy(looks = s.photos.looks - from)) }
                Toast.makeText(ctx, "Every photo without its own look now uses this look", Toast.LENGTH_SHORT).show()
            },
            onShowNow = { p ->
                BrigApp.instance.appScope.launch { PhotoRotator.showNow(ctx, cfg, p) }
                Toast.makeText(ctx, "Showing on the lock screen", Toast.LENGTH_SHORT).show()
            },
            onRemove = { remove(listOf(it)) },
            onEnableDepth = { update { it.copy(appearance = it.appearance.copy(depth = true)) } },
            undoBar = undoBar,
            onDismiss = { editing = null; selection = null },
        )
    }
}

@Composable
private fun AddTile(modifier: Modifier, wide: Boolean = false, onClick: () -> Unit) {
    val line = MaterialTheme.colorScheme.outline
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .drawBehind {
                drawRoundRect(
                    line, cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            }
            .clickable(onClickLabel = "Add photos", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.AddPhotoAlternate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (wide) 36.dp else 26.dp))
            Text(
                "Add photos", style = if (wide) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp),
            )
            if (wide) {
                Text(
                    "Up to 30 at a time. Brig rotates through them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickTile(
    path: String,
    index: Int,
    look: PhotoLook,
    own: Boolean,
    showing: Boolean,
    selecting: Boolean,
    selected: Boolean,
    accent: Color,
    more: Int?,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val inset by animateFloatAsState(if (selected) 0.86f else 1f, label = "tileInset")
    val corner by animateDpAsState(if (selected) 20.dp else 14.dp, label = "tileCorner")
    val shape = RoundedCornerShape(corner)
    val desc = buildString {
        append("Photo ${index + 1}")
        append(if (own) ", own look" else ", shared look")
        if (showing) append(", on the lock screen now")
    }
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .semantics {
                contentDescription = desc
                if (selecting) this.selected = selected
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select"),
    ) {
        LookedPhoto(
            path, look, accent,
            Modifier.fillMaxSize().graphicsLayer { scaleX = inset; scaleY = inset }.clip(shape),
        )
        if (more != null) {
            Box(Modifier.fillMaxSize().clip(shape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Text("+$more", style = MaterialTheme.typography.titleLarge, color = Color.White)
            }
        }
        if (showing && more == null) {
            Surface(
                shape = CircleShape, color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp),
            ) { Text("Now", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)) }
        }
        if (own && more == null) {
            Surface(
                shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(20.dp),
            ) { Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(3.dp)) }
        }
        AnimatedVisibility(
            selecting, enter = scaleIn() + fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.3f))
                    .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- Editor

/**
 * Full-screen editor for one or more picked photos. Swipe the preview to move between photos;
 * pinch to zoom and drag to frame (once zoomed in, one finger moves the photo and swiping pauses);
 * double-tap to zoom in or reset. When opened from a selection, changes apply to every selected
 * photo unless "Change all" is turned off.
 */
@Composable
private fun PhotoLookEditor(
    settings: BrigSettings,
    req: EditRequest,
    accent: Color,
    onChange: (targets: Collection<String>, change: LookChange?) -> Unit,
    onShared: (look: PhotoLook, from: List<String>) -> Unit,
    onShowNow: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEnableDepth: () -> Unit,
    undoBar: @Composable () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val cfg = settings.photos
    val paths = req.paths.filter { it in cfg.albumPaths }
    if (paths.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val shared = settings.appearance.sharedLook()
    fun saved(p: String) = cfg.lookFor(p) ?: shared

    val pager = rememberPagerState(initialPage = req.start.coerceIn(0, paths.lastIndex)) { paths.size }
    val page = pager.currentPage.coerceIn(0, paths.lastIndex)
    val here = paths[page]
    var together by remember { mutableStateOf(req.group) }
    val targets = if (together && req.group) paths else listOf(here)
    var painting by remember { mutableStateOf<String?>(null) }

    // Unsaved changes (mid-drag) go to each preview's override, which only redraws the photo.
    val overrides = remember { mutableMapOf<String, LookOverride>() }
    fun override(p: String) = overrides.getOrPut(p) { LookOverride() }
    fun live(change: LookChange) = targets.forEach { override(it).look = change(saved(it)) }
    fun commit(change: LookChange) = onChange(targets, change)

    val phone = rememberPhoneSize()
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { it / 12 }) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Column(Modifier.fillMaxSize()) {
                        EditorTopBar(
                            title = if (req.group && together) "Adjusting ${paths.size} photos" else "Photo ${page + 1} of ${paths.size}",
                            subtitle = if (paths.size > 1) "Swipe for the next photo" else null,
                            canCopy = cfg.albumPaths.size > 1,
                            onClose = onDismiss,
                            onShowNow = { onShowNow(here) },
                            onCopyToAll = { val l = saved(here); onChange(cfg.albumPaths, { l }) },
                            onMakeShared = { onShared(saved(here), listOf(here)) },
                            onRemove = { onRemove(here) },
                        )
                        HorizontalPager(
                            state = pager,
                            contentPadding = PaddingValues(horizontal = 64.dp),
                            pageSpacing = 4.dp,
                            // Zoomed in, a swipe moves the photo instead of turning the page.
                            userScrollEnabled = saved(here).photo.zoom <= 1.01f,
                            modifier = Modifier.fillMaxWidth().weight(0.5f),
                        ) { i ->
                            val p = paths[i]
                            val look = saved(p)
                            val ov = override(p)
                            LaunchedEffect(look) { ov.look = null }
                            val startLook by rememberUpdatedState(look)
                            val preview = remember(settings, p, look) {
                                settings.copy(
                                    appearance = settings.appearance.copy(background = BackgroundType.IMAGE, imagePath = p).withLook(look),
                                    photos = settings.photos.copy(source = PhotoSource.SINGLE, looks = emptyMap()),
                                )
                            }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val off = abs((pager.currentPage - i) + pager.currentPageOffsetFraction).coerceAtMost(1f)
                                        val s = 1f - 0.1f * off
                                        scaleX = s
                                        scaleY = s
                                        alpha = 1f - 0.45f * off
                                    }
                                    .then(
                                        if (i == page) Modifier.frameGestures(
                                            p, phone.width.value / phone.height.value,
                                            start = { ov.look ?: startLook }, onLive = ::live, onDone = ::commit,
                                        ) else Modifier,
                                    ),
                            ) {
                                CompositionLocalProvider(LocalLookOverride provides ov) { LockPreview(preview, Modifier.fillMaxSize()) }
                            }
                        }
                        PageDots(paths.size, page)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        ) {
                            FilledTonalButton(onClick = { LockActivity.preview(ctx, here) }) {
                                Icon(Icons.Filled.Fullscreen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Full screen")
                            }
                            FilledTonalButton(onClick = { painting = here }) {
                                Icon(Icons.Filled.ContentCut, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pop-out")
                            }
                        }
                        LookStatus(
                            own = targets.any { it in cfg.looks },
                            count = targets.size,
                            group = req.group,
                            together = together,
                            onTogether = { together = it },
                            onUseShared = { onChange(targets, null) },
                        )
                        Column(Modifier.weight(0.5f).verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
                            PhotoLookControls(
                                look = saved(here), thumbPath = here, accent = accent, showDim = true,
                                onLive = ::live, onChange = ::commit,
                            )
                            TextButton(
                                onClick = { onChange(targets, null) },
                                enabled = targets.any { it in cfg.looks },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) {
                                Icon(Icons.Filled.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                                Text(if (targets.size > 1) "Reset these to the shared look" else "Reset to the shared look")
                            }
                        }
                    }
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) { undoBar() }
                }
            }
        }
    }

    painting?.let { p ->
        CutoutPainter(
            path = p, accent = accent,
            onSaved = {
                painting = null
                if (!settings.appearance.depth) {
                    onEnableDepth()
                    Toast.makeText(ctx, "Depth effect turned on", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { painting = null },
        )
    }
}

/**
 * Pinch to zoom and drag to move the photo inside a [LockPreview], keeping the spot under the
 * fingers under the fingers. Double-tap zooms in on that spot, or back out. At 1× one-finger swipes
 * are left alone so the pager turns pages. [aspect] is the phone's width / height.
 */
private fun Modifier.frameGestures(
    key: Any,
    aspect: Float,
    start: () -> PhotoLook,
    onLive: (LookChange) -> Unit,
    onDone: (LookChange) -> Unit,
): Modifier {
    fun frame(z: Float, x: Float, y: Float): LookChange = { it.copy(photo = it.photo.copy(zoom = z, panX = x, panY = y)) }
    // The phone frame fills the preview's height; the background is enlarged like the lock screen's.
    val o = BACKGROUND_OVERSCAN
    return pointerInput(key, aspect) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val look = start()
            var z = look.photo.zoom
            var x = look.photo.panX
            var y = look.photo.panY
            val halfH = size.height / 2f
            val halfW = halfH * aspect
            val mid = Offset(size.width / 2f, size.height / 2f)
            var moved = false
            var travel = 0f
            do {
                val ev = awaitPointerEvent()
                val multi = ev.changes.count { it.pressed } >= 2
                if (multi || z > 1.01f) {
                    val pan = ev.calculatePan()
                    travel += pan.getDistance()
                    // One finger: wait for a real drag so taps (and double-taps) still work.
                    if (!multi && travel < viewConfiguration.touchSlop) continue
                    val focus = ev.calculateCentroid(useCurrent = false).takeIf { it.isSpecified } ?: mid
                    val z2 = (z * if (multi) ev.calculateZoom() else 1f).coerceIn(1f, Framing.MAX_ZOOM)
                    x = Framing.follow(z, x, z2, (focus.x - mid.x) / o, pan.x / o, halfW)
                    y = Framing.follow(z, y, z2, (focus.y - mid.y) / o, pan.y / o, halfH)
                    z = z2
                    onLive(frame(z, x, y))
                    moved = true
                    ev.changes.forEach { it.consume() }
                }
            } while (ev.changes.any { it.pressed })
            if (moved) onDone(frame(z, x, y))
        }
    }.pointerInput(key, aspect) {
        detectTapGestures(onDoubleTap = { at ->
            val look = start()
            val change = if (look.photo.zoom > 1.01f) frame(1f, 0f, 0f) else {
                val halfH = size.height / 2f
                val halfW = halfH * aspect
                val mid = Offset(size.width / 2f, size.height / 2f)
                val z2 = 2f
                val x = Framing.follow(1f, 0f, z2, (at.x - mid.x) / o, 0f, halfW)
                val y = Framing.follow(1f, 0f, z2, (at.y - mid.y) / o, 0f, halfH)
                frame(z2, x, y)
            }
            onDone(change)
        })
    }
}

@Composable
private fun EditorTopBar(
    title: String,
    subtitle: String?,
    canCopy: Boolean,
    onClose: () -> Unit,
    onShowNow: () -> Unit,
    onCopyToAll: () -> Unit,
    onMakeShared: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Done") }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                DropdownMenuItem(
                    text = { Text("Show on lock screen now") },
                    leadingIcon = { Icon(Icons.Filled.Wallpaper, null) },
                    onClick = { menu = false; onShowNow() },
                )
                if (canCopy) DropdownMenuItem(
                    text = { Text("Copy this look to all photos") },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                    onClick = { menu = false; onCopyToAll() },
                )
                DropdownMenuItem(
                    text = { Text("Make this the shared look") },
                    leadingIcon = { Icon(Icons.Filled.Public, null) },
                    onClick = { menu = false; onMakeShared() },
                )
                DropdownMenuItem(
                    text = { Text("Remove this photo", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, page: Int) {
    Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
        if (count in 2..15) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(count) { i ->
                    val w by animateDpAsState(if (i == page) 18.dp else 6.dp, label = "dot")
                    Box(
                        Modifier.size(w, 6.dp).clip(CircleShape)
                            .background(if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
        } else if (count > 15) {
            Text("${page + 1} / $count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LookStatus(own: Boolean, count: Int, group: Boolean, together: Boolean, onTogether: (Boolean) -> Unit, onUseShared: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (group) {
            FilterChip(
                selected = together, onClick = { onTogether(!together) },
                label = { Text(if (together) "Changing all $count photos" else "Changing this photo only") },
                leadingIcon = if (together) { { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) } } else null,
                shape = CircleShape,
            )
        }
        AnimatedContent(own, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "lookStatus") { mine ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Surface(
                    shape = CircleShape,
                    color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Icon(if (mine) Icons.Filled.Tune else Icons.Filled.Public, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (mine) "Own look" else "Shared look", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.width(10.dp))
                if (mine) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onUseShared) { Text("Use shared look") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Pinch, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Any change gives ${if (count > 1) "them" else "it"} its own look. Pinch the preview to frame.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Controls

/**
 * Effect, color, framing and overlay controls for a photo look. Used for the shared look on the
 * Appearance screen and for each picked photo's own look.
 */
@Composable
internal fun PhotoLookControls(
    look: PhotoLook,
    thumbPath: String?,
    accent: Color,
    showDim: Boolean,
    onLive: (LookChange) -> Unit,
    onChange: (LookChange) -> Unit,
) {
    val p = look.photo
    val thumb = rememberThumb(thumbPath, 320)
    fun pct(v: Float) = "${(v * 100).roundToInt()}%"
    fun signed(v: Float) = (if (v > 0) "+" else "") + (v * 100).roundToInt()

    @Composable
    fun slider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: (Float) -> String, change: (Float) -> LookChange) =
        SliderRow(title, value, range, label = label, onLive = { onLive(change(it)) }) { onChange(change(it)) }

    Text("Effect", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(PhotoEffect.entries) { e ->
            val selected = e == p.effect
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(72.dp, 104.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)) else Modifier)
                        .background(Color.DarkGray)
                        .clickable { onChange { it.copy(photo = it.photo.copy(effect = e)) } },
                ) {
                    thumb?.let {
                        Image(
                            it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                            colorFilter = ColorFilter.colorMatrix(ColorMatrix(PhotoFx.effect(e, accent))),
                        )
                    }
                }
                Text(
                    e.label, style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    slider("Brightness", p.brightness, -0.5f..0.5f, ::signed) { v -> { it.copy(photo = it.photo.copy(brightness = v)) } }
    slider("Contrast", p.contrast, 0.5f..1.6f, ::pct) { v -> { it.copy(photo = it.photo.copy(contrast = v)) } }
    slider("Saturation", p.saturation, 0f..2f, ::pct) { v -> { it.copy(photo = it.photo.copy(saturation = v)) } }
    if (showDim) slider("Dim", look.dim, 0f..0.8f, ::pct) { v -> { it.copy(dim = v) } }
    slider("Blur", look.blur, 0f..1f, ::pct) { v -> { it.copy(blur = v) } }
    // Zooming keeps the middle of the photo where it is, instead of drifting toward an edge.
    slider("Zoom", p.zoom, 1f..Framing.MAX_ZOOM, { "%.1f×".format(it) }) { v ->
        { l ->
            val c = l.photo
            l.copy(photo = c.copy(zoom = v, panX = Framing.rezoom(c.zoom, c.panX, v), panY = Framing.rezoom(c.zoom, c.panY, v)))
        }
    }
    if (p.zoom > 1.01f) {
        slider("Move left / right", p.panX, -1f..1f, ::signed) { v -> { it.copy(photo = it.photo.copy(panX = v)) } }
        slider("Move up / down", p.panY, -1f..1f, ::signed) { v -> { it.copy(photo = it.photo.copy(panY = v)) } }
    }
    slider("Vignette", p.vignette, 0f..1f, ::pct) { v -> { it.copy(photo = it.photo.copy(vignette = v)) } }
    slider("Film grain", p.grain, 0f..1f, ::pct) { v -> { it.copy(photo = it.photo.copy(grain = v)) } }
    slider("Readability fade", p.scrim, 0f..1f, ::pct) { v -> { it.copy(photo = it.photo.copy(scrim = v)) } }
    SwitchRow("Ken Burns", "Very slow zoom and drift", p.kenBurns) { v -> onChange { it.copy(photo = it.photo.copy(kenBurns = v)) } }
}
