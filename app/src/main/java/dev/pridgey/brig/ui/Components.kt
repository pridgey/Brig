package dev.pridgey.brig.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import dev.pridgey.brig.lock.ClockSpec
import dev.pridgey.brig.lock.LockMock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pridgey.brig.data.AppInfo
import dev.pridgey.brig.data.AppsRepo
import dev.pridgey.brig.data.BrigSettings
import dev.pridgey.brig.data.ClockStyle
import dev.pridgey.brig.lock.Clock
import dev.pridgey.brig.lock.LockBackground
import dev.pridgey.brig.lock.rememberNow
import dev.pridgey.brig.util.BrigShapes
import dev.pridgey.brig.util.PolygonShape
import dev.pridgey.brig.util.pressScale
import java.time.format.DateTimeFormatter

typealias SettingsUpdate = ((BrigSettings) -> BrigSettings) -> Unit

/**
 * Standard screen layout. With [pinned], that content stays fixed under a compact top bar and
 * shrinks from [pinnedMax] to [pinnedMin] as the list scrolls, so it never scrolls away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    pinned: (@Composable () -> Unit)? = null,
    pinnedMax: Dp = 340.dp,
    pinnedMin: Dp = 200.dp,
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val maxPx = with(density) { pinnedMax.toPx() }
    val minPx = with(density) { pinnedMin.toPx() }
    var pinnedPx by rememberSaveable { mutableFloatStateOf(maxPx) }
    val collapse = remember(maxPx, minPx, pinned != null) {
        object : NestedScrollConnection {
            // Scrolling up shrinks the header before the list moves...
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pinned == null || available.y >= 0f) return Offset.Zero
                val next = (pinnedPx + available.y).coerceAtLeast(minPx)
                val used = next - pinnedPx
                pinnedPx = next
                return Offset(0f, used)
            }

            // ...and it grows back once the list is scrolled to the top.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (pinned == null || available.y <= 0f) return Offset.Zero
                val next = (pinnedPx + available.y).coerceAtMost(maxPx)
                val used = next - pinnedPx
                pinnedPx = next
                return Offset(0f, used)
            }
        }
    }
    val scroll = if (pinned == null) {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    } else {
        TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    }
    val back: @Composable () -> Unit = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(collapse).nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            if (pinned == null) {
                LargeTopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.displaySmall) },
                    navigationIcon = back,
                    scrollBehavior = scroll,
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            } else {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.headlineMedium) },
                    navigationIcon = back,
                    scrollBehavior = scroll,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            if (pinned != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(with(density) { pinnedPx.toDp() })
                        .padding(bottom = 8.dp),
                ) { pinned() }
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = pad.calculateBottomPadding() + 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
fun SettingsGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        title?.let {
            Text(
                it.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
        }
        Surface(shape = RoundedCornerShape(30.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(vertical = 6.dp), content = content)
        }
    }
}

private val transparentItem @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked, onCheckedChange = onChange, enabled = enabled,
                thumbContent = if (checked) { { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) } } else null,
            )
        },
        modifier = Modifier.clickable(enabled) { onChange(!checked) },
        colors = transparentItem,
    )
}

/** Slider that only saves when the drag ends, so DataStore isn't hammered. */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    label: (Float) -> String,
    onLive: ((Float) -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(label(local), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
        Slider(value = local, onValueChange = { local = it; onLive?.invoke(it) }, onValueChangeFinished = { onChange(local) }, valueRange = range, steps = steps)
    }
}

@Composable
fun ClickRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.primary) } },
        modifier = Modifier.clickable(onClick = onClick),
        colors = transparentItem,
    )
}

@Composable
fun <T> DropdownRow(title: String, options: List<T>, selected: T, label: (T) -> String, subtitle: String? = null, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(subtitle ?: label(selected), color = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.Filled.ArrowDropDown, null) },
            modifier = Modifier.clickable { open = true },
            colors = transparentItem,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp)) {
            options.forEach {
                DropdownMenuItem(
                    text = { Text(label(it)) },
                    onClick = { onSelect(it); open = false },
                    trailingIcon = if (it == selected) { { Icon(Icons.Filled.Check, null) } } else null,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipChoice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach {
            FilterChip(
                selected = it == selected,
                onClick = { onSelect(it) },
                label = { Text(label(it), style = MaterialTheme.typography.labelLarge) },
                leadingIcon = if (it == selected) { { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) } } else null,
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

val AccentChoices = listOf(
    0xFF9DB7FF, 0xFFFF8A80, 0xFFFFD166, 0xFF7EE0A1, 0xFFC9A0FF,
    0xFFFF9ACB, 0xFF6FE3F2, 0xFFFFAD6B, 0xFFE8D5B5, 0xFFFFFFFF,
)

val SolidChoices = listOf(
    0xFF101318, 0xFF000000, 0xFF1B2440, 0xFF2B1B3D, 0xFF102E2A,
    0xFF3A1F1A, 0xFF22252B, 0xFF0F2A44,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorSwatches(selected: Long?, allowAuto: Boolean, colors: List<Long> = AccentChoices, autoLabel: String = "Auto", onSelect: (Long?) -> Unit) {
    FlowRow(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (allowAuto) {
            Swatch(null, selected == null, MaterialTheme.colorScheme.primaryContainer) { onSelect(null) }
        }
        colors.forEachIndexed { i, c ->
            Swatch(c, selected == c, Color(c), BrigShapes.badges[i % BrigShapes.badges.size]) { onSelect(c) }
        }
    }
}

@Composable
private fun Swatch(value: Long?, selected: Boolean, color: Color, polygon: androidx.graphics.shapes.RoundedPolygon = BrigShapes.circle, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = if (selected) PolygonShape(polygon) else CircleShape
    Box(
        Modifier
            .size(44.dp)
            .pressScale(source, 0.85f)
            .clip(shape)
            .background(color)
            .clickable(source, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            value == null -> Icon(Icons.Filled.AutoAwesome, "Auto", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            selected -> Icon(Icons.Filled.Check, null, tint = Color.Black.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) { AppsRepo.icon(ctx, pkg) }
    if (bmp != null) Image(bmp, null, Modifier.size(size))
    else Icon(Icons.Filled.Android, null, Modifier.size(size))
}

@Composable
fun AppPickerDialog(onDismiss: () -> Unit, onPick: (AppInfo) -> Unit) {
    val ctx = LocalContext.current
    val apps by produceState(emptyList<AppInfo>()) { value = AppsRepo.launchable(ctx) }
    var query by remember { mutableStateOf("") }
    val filtered = apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Choose an app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Search") },
                    shape = CircleShape, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(filtered, key = { it.pkg }) { app ->
                        ListItem(
                            headlineContent = { Text(app.label) },
                            leadingContent = { AppIcon(app.pkg, 36.dp) },
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onPick(app) },
                            colors = transparentItem,
                        )
                    }
                }
            }
        },
    )
}

private val PhoneWidth = 412.dp
private val PhoneHeight = 892.dp

/**
 * A live lock screen inside a phone frame. It lays out at real phone size and is scaled to fit,
 * so the preview matches the real thing exactly. Fills the height it is given.
 */
@Composable
fun LockPreview(settings: BrigSettings, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val frameH = maxHeight
        val frameW = frameH * (PhoneWidth.value / PhoneHeight.value)
        val s = frameW.value / PhoneWidth.value
        val shape = RoundedCornerShape(frameW * 0.13f)
        Box(
            Modifier
                .size(frameW, frameH)
                .clip(shape)
                .border(BorderStroke((frameW * 0.022f).coerceAtLeast(3.dp), MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)), shape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        ) {
            Box(
                Modifier
                    .requiredSize(PhoneWidth, PhoneHeight)
                    .graphicsLayer {
                        scaleX = s
                        scaleY = s
                    },
            ) {
                BrigTheme(accent = settings.appearance.accent, dark = true) { LockMock(settings) }
            }
        }
    }
}

/** A small live sample of one clock style, for the style picker. */
@Composable
fun ClockThumbnail(style: ClockStyle, settings: BrigSettings, selected: Boolean, onClick: () -> Unit) {
    val now by rememberNow(false)
    val a = settings.appearance
    val accent = a.accent?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(26.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(118.dp, 132.dp)
                .pressScale(source, 0.92f)
                .clip(shape)
                .background(Color(0xFF14161B))
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                .clickable(source, null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // Lay the clock out at full size, then shrink it to fit the card.
            Box(Modifier.requiredSize(360.dp, 400.dp).graphicsLayer { scaleX = 0.3f; scaleY = 0.3f }, contentAlignment = Alignment.Center) {
                Clock(style, now, ClockSpec.from(a, accent, scale = 1f).copy(showSeconds = false))
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp)
                        .clip(PolygonShape(BrigShapes.cookie9)).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            style.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The Brig logo: a slowly rotating cookie with a keyhole cut-out feel. */
@Composable
fun SpinningBadge(size: Dp, color: Color, polygon: androidx.graphics.shapes.RoundedPolygon = BrigShapes.cookie9, content: @Composable () -> Unit = {}) {
    val t = rememberInfiniteTransition(label = "badge")
    val r by t.animateFloat(0f, 360f, infiniteRepeatable(tween(20_000, easing = LinearEasing)), label = "r")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clip(PolygonShape(polygon, r)).background(color))
        content()
    }
}
