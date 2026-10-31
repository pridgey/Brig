package dev.pridgey.brig.ui

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.LaunchedEffect
import dev.pridgey.brig.data.ClockAlign
import dev.pridgey.brig.data.ClockFont
import dev.pridgey.brig.data.ClockWeight
import dev.pridgey.brig.lock.LockActivity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pridgey.brig.BrigApp
import dev.pridgey.brig.BuildConfig
import dev.pridgey.brig.data.AppInfo
import dev.pridgey.brig.data.AppNotificationMode
import dev.pridgey.brig.data.AppsRepo
import dev.pridgey.brig.data.Appearance
import dev.pridgey.brig.data.BackgroundType
import dev.pridgey.brig.data.BrigSettings
import dev.pridgey.brig.data.ClockStyle
import dev.pridgey.brig.data.GeoResult
import dev.pridgey.brig.data.GestureAction
import dev.pridgey.brig.data.GestureDir
import dev.pridgey.brig.data.NotificationRules
import dev.pridgey.brig.data.Profile
import dev.pridgey.brig.data.ProfileEngine
import dev.pridgey.brig.data.TriggerType
import dev.pridgey.brig.data.UnlockConfig
import dev.pridgey.brig.data.UnlockMethod
import dev.pridgey.brig.data.WeatherConfig
import dev.pridgey.brig.data.WeatherRepo
import dev.pridgey.brig.data.WidgetType
import dev.pridgey.brig.update.UpdateState
import dev.pridgey.brig.update.Updater
import dev.pridgey.brig.util.BrigShapes
import dev.pridgey.brig.util.Device
import dev.pridgey.brig.util.PinHasher
import dev.pridgey.brig.util.PolygonShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

private val itemColors @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

// ---------------------------------------------------------------- Appearance

private val zones = listOf(
    "", "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
    "America/Sao_Paulo", "Europe/London", "Europe/Paris", "Europe/Berlin", "Africa/Lagos",
    "Asia/Dubai", "Asia/Kolkata", "Asia/Singapore", "Asia/Tokyo", "Australia/Sydney", "Pacific/Auckland",
)

private fun copyBackground(ctx: Context, uri: Uri): String? {
    ctx.filesDir.listFiles { f -> f.name.startsWith("background_") }?.forEach { it.delete() }
    val f = File(ctx.filesDir, "background_${System.currentTimeMillis()}.img")
    ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: return null
    return f.absolutePath
}

@Composable
fun AppearanceScreen(settings: BrigSettings, update: SettingsUpdate, onBack: () -> Unit) {
    val a = settings.appearance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun set(f: (Appearance) -> Appearance) = update { it.copy(appearance = f(it.appearance)) }

    // While a slider is being dragged, the preview shows this unsaved value.
    var live by remember { mutableStateOf<Appearance?>(null) }
    LaunchedEffect(a) { live = null }
    val previewSettings = settings.copy(appearance = live ?: a)
    fun preview(f: (Appearance) -> Appearance) { live = f(live ?: a) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { copyBackground(ctx, uri) }
            if (path != null) set { it.copy(imagePath = path, background = BackgroundType.IMAGE) }
        }
    }

    ScreenScaffold(
        "Appearance", onBack,
        pinned = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LockPreview(previewSettings, Modifier.weight(1f)) { LockActivity.show(ctx) }
                Text(
                    "Tap the preview to try it full screen",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
    ) {
        item {
            SettingsGroup("Clock style") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(ClockStyle.entries) { style ->
                        ClockThumbnail(style, settings, selected = style == a.clockStyle) { set { it.copy(clockStyle = style) } }
                    }
                }
            }
        }
        item {
            SettingsGroup("Clock look") {
                Text("Font", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
                ChipChoice(ClockFont.entries, a.clockFont, { it.label }) { f -> set { it.copy(clockFont = f) } }
                Text("Weight", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
                ChipChoice(listOf<ClockWeight?>(null) + ClockWeight.entries, a.clockWeight, { it?.label ?: "Style default" }) { w ->
                    set { it.copy(clockWeight = w) }
                }
                Text("Color", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
                ColorSwatches(a.clockColor, allowAuto = true) { c -> set { it.copy(clockColor = c) } }
                Hint("Auto is white. The accent color is used for the second half of the clock.")
                Text("Position", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
                ChipChoice(ClockAlign.entries, a.clockAlign, { it.label }) { al -> set { it.copy(clockAlign = al) } }
                SliderRow(
                    "Size", a.clockScale, 0.5f..1.5f, label = { "${(it * 100).roundToInt()}%" },
                    onLive = { v -> preview { it.copy(clockScale = v) } },
                ) { v -> set { it.copy(clockScale = v) } }
            }
        }
        item {
            SettingsGroup("Time format") {
                SwitchRow("24-hour time", checked = a.use24h) { v -> set { it.copy(use24h = v) } }
                if (!a.use24h) SwitchRow("Leading zero", "09:41 instead of 9:41", a.leadingZero) { v -> set { it.copy(leadingZero = v) } }
                SwitchRow("Show seconds", "Uses a little more battery", a.showSeconds) { v -> set { it.copy(showSeconds = v) } }
                DropdownRow(
                    "Second time zone", zones, a.secondaryZone ?: "",
                    label = { if (it.isEmpty()) "None" else it.substringAfterLast('/').replace('_', ' ') },
                ) { z -> set { it.copy(secondaryZone = z.ifEmpty { null }) } }
            }
        }
        item {
            SettingsGroup("Background") {
                ChipChoice(BackgroundType.entries, a.background, { it.label }) { b ->
                    if (b == BackgroundType.IMAGE && a.imagePath == null) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } else set { it.copy(background = b) }
                }
                when (a.background) {
                    BackgroundType.IMAGE -> {
                        ClickRow("Choose photo", "Pick any image from your gallery", Icons.Filled.Image) {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        SliderRow(
                            "Blur", a.blur, 0f..1f, label = { "${(it * 100).roundToInt()}%" },
                            onLive = { v -> preview { it.copy(blur = v) } },
                        ) { v -> set { it.copy(blur = v) } }
                    }
                    BackgroundType.SOLID -> ColorSwatches(a.solidColor, allowAuto = false, colors = SolidChoices) { c ->
                        if (c != null) set { it.copy(solidColor = c) }
                    }
                    BackgroundType.AURORA -> Hint("Slow-moving light that follows your accent color.")
                    BackgroundType.TIME_OF_DAY -> Hint("The sky changes from dawn to dusk through the day.")
                    BackgroundType.PARTICLES -> Hint("Drifting, twinkling stars tinted with your accent.")
                }
                SliderRow(
                    "Dim", a.dim, 0f..0.8f, label = { "${(it * 100).roundToInt()}%" },
                    onLive = { v -> preview { it.copy(dim = v) } },
                ) { v -> set { it.copy(dim = v) } }
            }
        }
        item {
            SettingsGroup("Accent color") {
                ColorSwatches(a.accent, allowAuto = true) { c -> set { it.copy(accent = c) } }
                Hint("Auto follows your wallpaper (Material You). The accent also themes this app.")
            }
        }
        item {
            SettingsGroup("System") {
                SwitchRow("Hide status bar", "Full-screen lock screen", a.hideStatusBar) { v -> set { it.copy(hideStatusBar = v) } }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

// ---------------------------------------------------------------- Widgets

@Composable
fun WidgetsScreen(settings: BrigSettings, update: SettingsUpdate, onBack: () -> Unit) {
    val order = settings.widgetOrder()
    fun setWidgets(list: List<dev.pridgey.brig.data.WidgetConfig>) = update { it.copy(widgets = list) }

    ScreenScaffold("Widgets", onBack) {
        item {
            SettingsGroup("Order & visibility") {
                order.forEachIndexed { i, w ->
                    ListItem(
                        headlineContent = { Text(w.type.label, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = when (w.type) {
                            WidgetType.SHORTCUTS, WidgetType.TOGGLES -> { { Text("Pinned to the bottom") } }
                            WidgetType.ALARM, WidgetType.BATTERY, WidgetType.WEATHER, WidgetType.STATS -> { { Text("Shown as a glance chip") } }
                            else -> null
                        },
                        leadingContent = {
                            Column {
                                IconButton(onClick = {
                                    if (i > 0) setWidgets(order.toMutableList().apply { add(i - 1, removeAt(i)) })
                                }, enabled = i > 0, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                                IconButton(onClick = {
                                    if (i < order.lastIndex) setWidgets(order.toMutableList().apply { add(i + 1, removeAt(i)) })
                                }, enabled = i < order.lastIndex, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                            }
                        },
                        trailingContent = {
                            Switch(checked = w.enabled, onCheckedChange = { on ->
                                setWidgets(order.map { if (it.type == w.type) it.copy(enabled = on) else it })
                            })
                        },
                        colors = itemColors,
                    )
                }
            }
        }
        if (settings.isWidgetOn(WidgetType.WEATHER)) {
            item { WeatherSettings(settings.weather) { cfg -> update { it.copy(weather = cfg) } } }
        }
    }
}

@Composable
private fun WeatherSettings(cfg: WeatherConfig, onChange: (WeatherConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(cfg.city) }
    var results by remember { mutableStateOf<List<GeoResult>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    fun search() = scope.launch {
        status = "Searching…"
        results = runCatching { WeatherRepo.geocode(query) }
            .onFailure { status = "Search failed: ${it.message}" }
            .getOrDefault(emptyList())
        if (results.isNotEmpty() || status == "Searching…") status = if (results.isEmpty()) "No matches" else null
    }
    SettingsGroup("Weather") {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text("City") }, shape = CircleShape,
            trailingIcon = { IconButton(onClick = { search() }) { Icon(Icons.Filled.Search, "Search") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        status?.let { Hint(it) }
        results.forEach { r ->
            ListItem(
                headlineContent = { Text(r.name) },
                supportingContent = { Text(r.region) },
                modifier = Modifier.clickable {
                    onChange(cfg.copy(city = r.name, lat = r.lat, lon = r.lon))
                    query = r.name
                    results = emptyList()
                },
                colors = itemColors,
            )
        }
        if (cfg.lat != null) Hint("Using ${cfg.city}. Data from Open-Meteo, refreshed every 30 minutes.")
        SwitchRow("Fahrenheit", checked = cfg.fahrenheit) { onChange(cfg.copy(fahrenheit = it)) }
    }
}

// ---------------------------------------------------------------- Notifications

@Composable
fun NotificationsScreen(settings: BrigSettings, update: SettingsUpdate, onBack: () -> Unit) {
    val r = settings.notifications
    val ctx = LocalContext.current
    fun set(f: (NotificationRules) -> NotificationRules) = update { it.copy(notifications = f(it.notifications)) }
    val apps by produceState(emptyList<AppInfo>()) { value = AppsRepo.launchable(ctx) }
    var query by remember { mutableStateOf("") }
    val filtered = apps
        .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
        .sortedByDescending { r.appModes.containsKey(it.pkg) }

    ScreenScaffold("Notifications", onBack) {
        item {
            SettingsGroup("General") {
                SwitchRow("Hide all content", "Show only the app name and \"Content hidden\"", r.hideAllContent) { v -> set { it.copy(hideAllContent = v) } }
                SwitchRow("Show silent notifications", checked = r.showSilent) { v -> set { it.copy(showSilent = v) } }
                SwitchRow("Quick replies", "Reply to messages without unlocking", r.allowReply) { v -> set { it.copy(allowReply = v) } }
                SliderRow("Max shown", r.maxShown.toFloat(), 1f..8f, steps = 6, label = { it.roundToInt().toString() }) { v ->
                    set { it.copy(maxShown = v.roundToInt()) }
                }
            }
        }
        item {
            Column {
                Text(
                    "PER-APP RULES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Search apps") },
                    shape = CircleShape, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        items(filtered, key = { it.pkg }) { app ->
            val mode = r.appModes[app.pkg] ?: AppNotificationMode.SHOW
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                ListItem(
                    headlineContent = { Text(app.label) },
                    leadingContent = { AppIcon(app.pkg, 36.dp) },
                    trailingContent = {
                        AssistChip(
                            onClick = {
                                val next = AppNotificationMode.entries[(mode.ordinal + 1) % AppNotificationMode.entries.size]
                                set {
                                    it.copy(appModes = if (next == AppNotificationMode.SHOW) it.appModes - app.pkg else it.appModes + (app.pkg to next))
                                }
                            },
                            label = { Text(mode.label) },
                            shape = CircleShape,
                        )
                    },
                    colors = itemColors,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Unlock & gestures

@Composable
fun UnlockScreen(settings: BrigSettings, update: SettingsUpdate, onBack: () -> Unit) {
    val u = settings.unlock
    val g = settings.gestures
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun setU(f: (UnlockConfig) -> UnlockConfig) = update { it.copy(unlock = f(it.unlock)) }
    var showPin by remember { mutableStateOf(false) }
    var pickAppFor by remember { mutableStateOf<GestureDir?>(null) }

    ScreenScaffold("Unlock & gestures", onBack) {
        item {
            SettingsGroup("Unlock method") {
                UnlockMethod.entries.forEach { m ->
                    ListItem(
                        headlineContent = { Text(m.label, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text(m.description) },
                        leadingContent = { RadioButton(selected = u.method == m, onClick = null) },
                        modifier = Modifier.clickable {
                            if (m == UnlockMethod.PIN && u.pinHash == null) showPin = true else setU { it.copy(method = m) }
                        },
                        colors = itemColors,
                    )
                }
                if (u.method == UnlockMethod.PIN) ClickRow("Change PIN", icon = Icons.Filled.Password) { showPin = true }
                if (u.method == UnlockMethod.MATH) {
                    SliderRow("Difficulty", u.mathDifficulty.toFloat(), 1f..3f, steps = 1, label = {
                        when (it.roundToInt()) { 1 -> "Easy"; 2 -> "Medium"; else -> "Hard" }
                    }) { v -> setU { it.copy(mathDifficulty = v.roundToInt()) } }
                }
            }
        }
        item {
            SettingsGroup("System lock") {
                SwitchRow(
                    "Hide Brig on system unlock",
                    "Unlocking with your fingerprint or system PIN dismisses Brig too, so you only unlock once",
                    u.autoHideOnSystemUnlock,
                ) { v -> setU { it.copy(autoHideOnSystemUnlock = v) } }
                Hint("Keep a system PIN or fingerprint for real security. Brig sits on top of Android's lock screen; it can't replace it.")
            }
        }
        item {
            SettingsGroup("Gestures") {
                GestureDir.entries.forEach { d ->
                    val action = g.action(d)
                    val app = g.apps[d.name]
                    DropdownRow(
                        d.label, GestureAction.entries, action, { it.label },
                        subtitle = if (action == GestureAction.APP && app != null) "Open ${AppsRepo.label(ctx, app)}" else null,
                    ) { a ->
                        if (a == GestureAction.APP) pickAppFor = d
                        else update { it.copy(gestures = it.gestures.with(d, a)) }
                    }
                }
                if (!g.hasUnlock()) {
                    ListItem(
                        headlineContent = { Text("No gesture unlocks") },
                        supportingContent = { Text("Swipe up will still unlock so you can't lock yourself out.") },
                        leadingContent = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
                        colors = itemColors,
                    )
                }
            }
        }
    }

    pickAppFor?.let { dir ->
        AppPickerDialog(onDismiss = { pickAppFor = null }) { app ->
            update { it.copy(gestures = it.gestures.with(dir, GestureAction.APP).copy(apps = it.gestures.apps + (dir.name to app.pkg))) }
            pickAppFor = null
        }
    }
    if (showPin) {
        PinDialog(onDismiss = { showPin = false }) { pin ->
            showPin = false
            scope.launch {
                val salt = PinHasher.newSalt()
                val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin, salt) }
                setU { it.copy(method = UnlockMethod.PIN, pinHash = hash, pinSalt = salt) }
                toast(ctx, "PIN saved")
            }
        }
    }
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length in 4..12 && pin == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(Modifier.size(48.dp).clip(PolygonShape(BrigShapes.cookie9)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Password, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
        },
        title = { Text("Set Brig PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val opts = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                OutlinedTextField(pin, { if (it.all(Char::isDigit) && it.length <= 12) pin = it }, label = { Text("PIN (4–12 digits)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = opts)
                OutlinedTextField(confirm, { if (it.all(Char::isDigit) && it.length <= 12) confirm = it }, label = { Text("Confirm PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = opts, isError = confirm.isNotEmpty() && confirm != pin)
            }
        },
        confirmButton = { Button(onClick = { onSave(pin) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- Shortcuts

@Composable
fun ShortcutsScreen(settings: BrigSettings, update: SettingsUpdate, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val list = settings.shortcuts
    fun setList(l: List<String>) = update { it.copy(shortcuts = l) }

    ScreenScaffold("Shortcuts", onBack) {
        item {
            SettingsGroup("Lock screen apps · ${list.size}/6") {
                if (list.isEmpty()) Hint("No shortcuts yet. Add up to six apps.")
                list.forEachIndexed { i, pkg ->
                    ListItem(
                        headlineContent = { Text(AppsRepo.label(ctx, pkg), style = MaterialTheme.typography.titleMedium) },
                        leadingContent = {
                            Box(Modifier.size(48.dp).clip(PolygonShape(BrigShapes.badges[i % BrigShapes.badges.size])).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                                AppIcon(pkg, 30.dp)
                            }
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { setList(list.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                                IconButton(onClick = { setList(list.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < list.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                                IconButton(onClick = { setList(list - pkg) }) { Icon(Icons.Filled.Close, "Remove") }
                            }
                        },
                        colors = itemColors,
                    )
                }
                if (list.size < 6) ClickRow("Add app", icon = Icons.Filled.Add) { picking = true }
            }
        }
        item { Hint("Tapping a shortcut unlocks first (if needed), then opens the app.") }
    }
    if (picking) {
        AppPickerDialog(onDismiss = { picking = false }) { app ->
            if (app.pkg !in list) setList(list + app.pkg)
            picking = false
        }
    }
}

// ---------------------------------------------------------------- Profiles

private fun describe(p: Profile): String {
    val trigger = when (p.trigger) {
        TriggerType.TIME -> "${ProfileEngine.formatMinute(p.startMinute)} – ${ProfileEngine.formatMinute(p.endMinute)}"
        TriggerType.CHARGING_AND_TIME -> "Charging, ${ProfileEngine.formatMinute(p.startMinute)} – ${ProfileEngine.formatMinute(p.endMinute)}"
        else -> p.trigger.label
    }
    val effects = listOfNotNull(
        "bedside".takeIf { p.bedside },
        "no notifications".takeIf { p.hideNotifications },
        "private".takeIf { p.hideContent },
        p.clockStyle?.let { "${it.label.lowercase()} clock" },
        "accent".takeIf { p.accent != null },
        "dim".takeIf { p.dim != null },
    )
    return trigger + if (effects.isNotEmpty()) " → " + effects.joinToString(", ") else ""
}

@Composable
fun ProfilesScreen(settings: BrigSettings, update: SettingsUpdate, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<Profile?>(null) }
    fun save(p: Profile) = update { s ->
        s.copy(profiles = if (s.profiles.any { it.id == p.id }) s.profiles.map { if (it.id == p.id) p else it } else s.profiles + p)
    }

    ScreenScaffold("Profiles", onBack) {
        item { Hint("Profiles change the lock screen automatically. When several match, on/off effects combine and the top profile's overrides win.") }
        items(settings.profiles, key = { it.id }) { p ->
            val idx = settings.profiles.indexOf(p)
            Surface(
                onClick = { editing = p },
                shape = RoundedCornerShape(28.dp),
                color = if (p.enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                ListItem(
                    headlineContent = { Text(p.name, style = MaterialTheme.typography.titleLarge) },
                    supportingContent = { Text(describe(p)) },
                    leadingContent = {
                        Box(Modifier.size(44.dp).clip(PolygonShape(BrigShapes.badges[idx % BrigShapes.badges.size])).background(p.accent?.let { Color(it) } ?: MaterialTheme.colorScheme.primary))
                    },
                    trailingContent = { Switch(checked = p.enabled, onCheckedChange = { on -> save(p.copy(enabled = on)) }) },
                    colors = itemColors,
                )
            }
        }
        item {
            FilledTonalButton(
                onClick = { editing = Profile(id = UUID.randomUUID().toString(), name = "New profile") },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Add profile", style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    editing?.let { p ->
        ProfileEditor(
            p,
            onDismiss = { editing = null },
            onSave = { save(it); editing = null },
            onDelete = { update { s -> s.copy(profiles = s.profiles.filterNot { it.id == p.id }) }; editing = null },
        )
    }
}

@Composable
private fun ProfileEditor(initial: Profile, onDismiss: () -> Unit, onSave: (Profile) -> Unit, onDelete: () -> Unit) {
    var p by remember { mutableStateOf(initial) }
    var pickTime by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit profile") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(p.name, { p = p.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                DropdownRow("Trigger", TriggerType.entries, p.trigger, { it.label }) { p = p.copy(trigger = it) }
                if (p.trigger.usesTime) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                        OutlinedButton(onClick = { pickTime = true }) { Text("From ${ProfileEngine.formatMinute(p.startMinute)}") }
                        OutlinedButton(onClick = { pickTime = false }) { Text("To ${ProfileEngine.formatMinute(p.endMinute)}") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Effects", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
                SwitchRow("Bedside mode", "Dim red clock, nothing else", p.bedside) { p = p.copy(bedside = it) }
                SwitchRow("Hide notifications", checked = p.hideNotifications) { p = p.copy(hideNotifications = it) }
                SwitchRow("Hide notification content", checked = p.hideContent) { p = p.copy(hideContent = it) }
                DropdownRow("Clock style", listOf<ClockStyle?>(null) + ClockStyle.entries, p.clockStyle, { it?.label ?: "Don't change" }) { p = p.copy(clockStyle = it) }
                Text("Accent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                ColorSwatches(p.accent, allowAuto = true) { p = p.copy(accent = it) }
                SwitchRow("Override dim", checked = p.dim != null) { on -> p = p.copy(dim = if (on) 0.5f else null) }
                p.dim?.let { d -> SliderRow("Dim", d, 0f..0.9f, label = { "${(it * 100).roundToInt()}%" }) { p = p.copy(dim = it) } }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete profile", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(p) }, enabled = p.name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    pickTime?.let { start ->
        TimePickDialog(if (start) p.startMinute else p.endMinute, onDismiss = { pickTime = null }) { m ->
            p = if (start) p.copy(startMinute = m) else p.copy(endMinute = m)
            pickTime = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(initial / 60, initial % 60, false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state) },
    )
}

// ---------------------------------------------------------------- Updates & backup

@Composable
fun UpdatesScreen(settings: BrigSettings, update: SettingsUpdate, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(BrigApp.settings.exportJson().toByteArray()) }
        }.onSuccess { toast(ctx, "Settings exported") }.onFailure { toast(ctx, "Export failed") }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
                BrigApp.settings.importJson(text ?: error("empty"))
            }.onSuccess { toast(ctx, "Settings imported") }.onFailure { toast(ctx, "That doesn't look like a Brig backup") }
        }
    }

    ScreenScaffold("Updates & backup", onBack) {
        item {
            Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SpinningBadge(56.dp, MaterialTheme.colorScheme.primary, BrigShapes.cookie12) {
                            Text(BuildConfig.VERSION_CODE.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Brig ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
                            Text("Updates come from github.com/${BuildConfig.UPDATE_REPO}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    when (val s = state) {
                        UpdateState.Idle -> Unit
                        UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        UpdateState.UpToDate -> Text("You're on the latest build ✨", style = MaterialTheme.typography.titleMedium)
                        is UpdateState.Available -> Column {
                            Text("${s.release.tag} is available", style = MaterialTheme.typography.titleMedium)
                            if (s.release.notes.isNotBlank()) Text(s.release.notes.take(600), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = {
                                if (!Device.canInstallPackages(ctx)) {
                                    toast(ctx, "Allow Brig to install apps, then come back")
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                } else {
                                    scope.launch { Updater.downloadAndInstall(ctx, s.release) }
                                }
                            }) { Text("Download & install") }
                        }
                        is UpdateState.Downloading -> Column {
                            Text("Downloading… ${(s.progress * 100).roundToInt()}%")
                            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        }
                        UpdateState.Installing -> Text("Handing off to the installer…")
                        is UpdateState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(10.dp))
                    val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
                    FilledTonalButton(onClick = { scope.launch { Updater.check() } }, enabled = !busy) { Text("Check for updates") }
                    if (BuildConfig.DEBUG) {
                        Text("Debug builds use a different package name and won't update in place.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            SettingsGroup("Automatic") {
                SwitchRow("Check automatically", "Every 12 hours. You'll get a notification when a new build lands.", settings.updates.autoCheck) { v ->
                    update { it.copy(updates = it.updates.copy(autoCheck = v)) }
                }
            }
        }
        item {
            SettingsGroup("Backup") {
                ClickRow("Export settings", "Save everything to a JSON file", Icons.Filled.FileUpload) { exporter.launch("brig-settings.json") }
                ClickRow("Import settings", "Restore from a backup file", Icons.Filled.FileDownload) { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }
                ClickRow("Reset to defaults", "Start over", Icons.Filled.RestartAlt) { confirmReset = true }
            }
        }
        item {
            SettingsGroup("About") {
                ClickRow("Source on GitHub", BuildConfig.UPDATE_REPO, Icons.Filled.Code) {
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.UPDATE_REPO}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset everything?") },
            text = { Text("All Brig settings go back to their defaults. Brig stays switched on.") },
            confirmButton = {
                Button(onClick = {
                    update { BrigSettings(enabled = it.enabled) }
                    confirmReset = false
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
