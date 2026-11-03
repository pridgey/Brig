package dev.pridgey.brig.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pridgey.brig.data.BrigSettings
import dev.pridgey.brig.data.ClockFont
import dev.pridgey.brig.data.ClockLayer
import dev.pridgey.brig.data.ClockShare
import dev.pridgey.brig.data.ClockStyle
import dev.pridgey.brig.data.ClockWeight
import dev.pridgey.brig.data.CustomClock
import dev.pridgey.brig.data.LayerColor
import dev.pridgey.brig.data.LayerKind
import dev.pridgey.brig.data.LayerShape
import dev.pridgey.brig.lock.ClockSpec
import dev.pridgey.brig.lock.CustomClockView
import dev.pridgey.brig.lock.formatLayer
import dev.pridgey.brig.lock.rememberNow
import kotlin.math.roundToInt

private val patterns = listOf(
    "HH" to "Hour (24h)", "h" to "Hour", "mm" to "Minute", "ss" to "Seconds", "a" to "AM/PM",
    "h:mm" to "Time", "EEEE" to "Weekday", "EEE" to "Day", "d" to "Date", "MMMM" to "Month", "MMM d" to "Mon 1",
)

@Composable
fun ClockBuilderScreen(settings: BrigSettings, update: SettingsUpdate, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clock = settings.appearance.customClock
    fun set(f: (CustomClock) -> CustomClock) = update {
        it.copy(appearance = it.appearance.copy(customClock = f(it.appearance.customClock), clockStyle = ClockStyle.CUSTOM))
    }
    fun setLayers(l: List<ClockLayer>) = set { it.copy(layers = l) }
    var selected by remember { mutableIntStateOf(clock.layers.lastIndex) }
    var importing by remember { mutableStateOf(false) }
    val now by rememberNow(false)
    val accent = resolveAccent(settings.appearance.accent)
    val spec = ClockSpec.from(settings.appearance, accent, scale = 1f)

    ScreenScaffold(
        "Clock builder", onBack, pinnedMax = 300.dp, pinnedMin = 170.dp,
        pinned = {
            // Real phone width, scaled down to fit, so positions match the lock screen.
            androidx.compose.foundation.layout.BoxWithConstraints(
                Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Color(0xFF101318)),
                contentAlignment = Alignment.Center,
            ) {
                val s = ((maxWidth.value - 24f) / 368f).coerceAtMost(maxHeight.value / (clock.height + 40f)).coerceAtMost(1f)
                Box(Modifier.requiredWidth(368.dp).graphicsLayer { scaleX = s; scaleY = s }) {
                    CustomClockView(clock, now, spec)
                }
            }
        },
    ) {
        item {
            SettingsGroup("Start from") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ClockShare.templates) { t ->
                        AssistChip(onClick = { set { t }; selected = t.layers.lastIndex }, label = { Text(t.name) }, shape = CircleShape)
                    }
                }
                SliderRow("Height", clock.height.toFloat(), 80f..400f, label = { "${it.roundToInt()} dp" }) { v -> set { it.copy(height = v.roundToInt()) } }
            }
        }
        item {
            SettingsGroup("Layers · bottom to top") {
                clock.layers.forEachIndexed { i, l ->
                    val isSel = i == selected
                    ListItem(
                        headlineContent = {
                            Text(if (l.kind == LayerKind.TEXT) "\"${formatLayer(l.pattern, now)}\"" else l.shape.label, maxLines = 1)
                        },
                        supportingContent = { Text("${l.kind.label} · ${l.color.label} · ${l.size.roundToInt()}") },
                        leadingContent = {
                            Icon(if (l.kind == LayerKind.TEXT) Icons.Filled.TextFields else Icons.Filled.Category, null, tint = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { setLayers(clock.layers.toMutableList().apply { add(i - 1, removeAt(i)) }); selected = i - 1 }, enabled = i > 0) {
                                    Icon(Icons.Filled.KeyboardArrowUp, "Move down a layer")
                                }
                                IconButton(onClick = { setLayers(clock.layers.toMutableList().apply { add(i + 1, removeAt(i)) }); selected = i + 1 }, enabled = i < clock.layers.lastIndex) {
                                    Icon(Icons.Filled.KeyboardArrowDown, "Move up a layer")
                                }
                                IconButton(onClick = { setLayers(clock.layers.filterIndexed { j, _ -> j != i }); selected = -1 }) { Icon(Icons.Filled.Delete, "Delete") }
                            }
                        },
                        modifier = Modifier.clickable { selected = if (isSel) -1 else i },
                        colors = ListItemDefaults.colors(containerColor = if (isSel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                    )
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { setLayers(clock.layers + ClockLayer(pattern = "h:mm", x = 0.05f, y = 0.3f, size = 64f)); selected = clock.layers.size }) {
                        Icon(Icons.Filled.TextFields, null); Spacer(Modifier.width(6.dp)); Text("Text")
                    }
                    FilledTonalButton(onClick = { setLayers(clock.layers + ClockLayer(kind = LayerKind.SHAPE, x = 0.1f, y = 0.1f, size = 120f, color = LayerColor.ACCENT, alpha = 0.5f)); selected = clock.layers.size }) {
                        Icon(Icons.Filled.Category, null); Spacer(Modifier.width(6.dp)); Text("Shape")
                    }
                }
            }
        }
        clock.layers.getOrNull(selected)?.let { layer ->
            item {
                androidx.compose.runtime.key(selected) {
                    LayerEditor(layer) { changed -> setLayers(clock.layers.mapIndexed { j, l -> if (j == selected) changed else l }) }
                }
            }
        }
        item {
            SettingsGroup("Share") {
                ClickRow("Share this clock", "Sends a code anyone with Brig can paste in", Icons.Filled.Share) {
                    val code = ClockShare.encode(clock)
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "My Brig clock \"${clock.name}\": $code"),
                            "Share clock",
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                ClickRow("Import a clock", "Paste a code someone shared", Icons.Filled.ContentPaste) { importing = true }
                Note("Codes work in any message, post or note, so you can swap designs with friends or a community thread.")
            }
        }
    }

    if (importing) {
        val clip = remember {
            ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
        }
        var text by remember { mutableStateOf(if (ClockShare.decode(clip) != null) clip else "") }
        val parsed = ClockShare.decode(text)
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("Import a clock") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, label = { Text("Code") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Text(
                        when {
                            text.isBlank() -> "Paste a brig-clock: code"
                            parsed == null -> "That code doesn't work"
                            else -> "\"${parsed.name}\" · ${parsed.layers.size} layers"
                        },
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    parsed?.let { c -> set { c }; selected = c.layers.lastIndex }
                    importing = false
                    Toast.makeText(ctx, "Clock imported", Toast.LENGTH_SHORT).show()
                }, enabled = parsed != null) { Text("Use it") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LayerEditor(l: ClockLayer, onChange: (ClockLayer) -> Unit) {
    SettingsGroup(if (l.kind == LayerKind.TEXT) "Text layer" else "Shape layer") {
        if (l.kind == LayerKind.TEXT) {
            var pattern by remember { mutableStateOf(l.pattern) }
            OutlinedTextField(
                pattern, { pattern = it; onChange(l.copy(pattern = it)) }, singleLine = true,
                label = { Text("Pattern") }, shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Note("Date patterns like HH, mm, EEEE or MMM d. Put plain text in single quotes: 'It is' h:mm")
            ChipChoice(patterns.map { it.first }, l.pattern, { p -> patterns.first { it.first == p }.second }) { pattern = it; onChange(l.copy(pattern = it)) }
            Text("Font", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
            ChipChoice(ClockFont.entries, l.font, { it.label }) { onChange(l.copy(font = it)) }
            Text("Weight", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
            ChipChoice(ClockWeight.entries, l.weight, { it.label }) { onChange(l.copy(weight = it)) }
        } else {
            ChipChoice(LayerShape.entries, l.shape, { it.label }) { onChange(l.copy(shape = it)) }
            SwitchRow("Spin", "One slow turn per minute", l.spin) { onChange(l.copy(spin = it)) }
        }
        Text("Color", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
        ChipChoice(LayerColor.entries, l.color, { it.label }) { onChange(l.copy(color = it)) }
        if (l.color == LayerColor.CUSTOM) ColorSwatches(l.customColor, allowAuto = false, colors = AccentChoices + 0xFFFFFFFF + 0xFF101318) { c -> c?.let { onChange(l.copy(customColor = it)) } }
        SliderRow("Opacity", l.alpha, 0f..1f, label = { "${(it * 100).roundToInt()}%" }) { onChange(l.copy(alpha = it)) }
        SliderRow("Size", l.size, if (l.kind == LayerKind.TEXT) 10f..220f else 10f..420f, label = { "${it.roundToInt()}" }) { onChange(l.copy(size = it)) }
        SliderRow("Left / right", l.x, -0.2f..1f, label = { "${(it * 100).roundToInt()}%" }) { onChange(l.copy(x = it)) }
        SliderRow("Up / down", l.y, -0.2f..1f, label = { "${(it * 100).roundToInt()}%" }) { onChange(l.copy(y = it)) }
        SliderRow("Rotation", l.rotation, -180f..180f, label = { "${it.roundToInt()}°" }) { onChange(l.copy(rotation = it)) }
    }
}
