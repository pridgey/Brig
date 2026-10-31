package dev.pridgey.brig.ui

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title, style = MaterialTheme.typography.displaySmall) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
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
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range, steps = steps)
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

/** A live, scaled-down lock screen inside a phone frame. */
@Composable
fun LockPreview(settings: BrigSettings, modifier: Modifier = Modifier, height: Dp = 320.dp) {
    val now by rememberNow(false)
    val a = settings.appearance
    val accent = a.accent?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(9f / 19.5f)
                .clip(RoundedCornerShape(34.dp))
                .border(BorderStroke(5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)), RoundedCornerShape(34.dp)),
        ) {
            LockBackground(a, accent)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = a.dim)))
            Column(Modifier.padding(horizontal = 14.dp).padding(top = 30.dp)) {
                val centered = a.clockStyle == ClockStyle.DIGITAL || a.clockStyle == ClockStyle.ANALOG
                Clock(
                    a.clockStyle, now, a.use24h, false, a.clockScale * 0.36f, accent,
                    Modifier.align(if (centered) Alignment.CenterHorizontally else Alignment.Start),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    now.format(DateTimeFormatter.ofPattern("EEEE, MMM d")),
                    color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(if (centered) Alignment.CenterHorizontally else Alignment.Start),
                )
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .width(48.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.6f)),
            )
        }
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
