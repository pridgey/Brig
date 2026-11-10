package dev.pridgey.brig.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.pridgey.brig.data.Appearance
import dev.pridgey.brig.data.BackgroundType
import dev.pridgey.brig.data.BrigSettings
import dev.pridgey.brig.data.HealthConfig
import dev.pridgey.brig.data.HealthRepo
import dev.pridgey.brig.data.HealthStyle
import dev.pridgey.brig.data.Memories
import dev.pridgey.brig.data.PackageParse
import dev.pridgey.brig.data.PackageRepo
import dev.pridgey.brig.data.PackagesConfig
import dev.pridgey.brig.data.PhotoRotator
import dev.pridgey.brig.data.PhotoSource
import dev.pridgey.brig.data.PhotoSourceConfig
import dev.pridgey.brig.data.Rotation
import dev.pridgey.brig.data.SportsRepo
import dev.pridgey.brig.data.SportsTeam
import dev.pridgey.brig.data.AppsRepo
import dev.pridgey.brig.util.Segmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val rowColors @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

@Composable
internal fun Note(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label) },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Copies a picked file into app storage so it survives the gallery entry being deleted. */
internal fun copyInto(ctx: Context, uri: Uri, dir: String, name: String): String? = runCatching {
    val d = File(ctx.filesDir, dir).apply { mkdirs() }
    val f = File(d, name)
    ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: return null
    f.absolutePath
}.getOrNull()

// ---------------------------------------------------------------- Photo sources

@Composable
fun PhotoSourceSettings(settings: BrigSettings, update: SettingsUpdate) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = settings.photos
    fun set(f: (PhotoSourceConfig) -> PhotoSourceConfig) = update { it.copy(photos = f(it.photos)) }
    var busy by remember { mutableStateOf(false) }
    val current by PhotoRotator.current.collectAsState()

    fun rotateNow(next: PhotoSourceConfig = cfg) = scope.launch {
        busy = true
        PhotoRotator.maybeRotate(ctx, next, force = true)
        busy = false
        if (PhotoRotator.current.value == null) toast(ctx, "Couldn't get a photo. Check the source settings.")
    }

    val albumPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val paths = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { i, u -> copyInto(ctx, u, "album", "pick_${System.currentTimeMillis()}_$i.img") }
            }
            val first = cfg.albumPaths.isEmpty()
            set { it.copy(albumPaths = it.albumPaths + paths) }
            // The first picks should show up right away.
            if (first && paths.isNotEmpty() && cfg.source == PhotoSource.ALBUM) rotateNow(cfg.copy(albumPaths = paths))
        }
    }
    val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) rotateNow() else toast(ctx, "Brig needs photo access for On this day")
    }

    SettingsGroup("Photo source") {
        DropdownRow("Source", PhotoSource.entries, cfg.source, { it.label }, subtitle = cfg.source.description) { src ->
            val next = cfg.copy(source = src)
            set { it.copy(source = src) }
            if (src == PhotoSource.ON_THIS_DAY && !Memories.hasPermission(ctx)) mediaPermission.launch(Memories.permission())
            else if (src != PhotoSource.SINGLE) rotateNow(next)
        }
        if (cfg.source != PhotoSource.SINGLE) {
            DropdownRow("New photo", Rotation.entries, cfg.rotation, { it.label }) { r -> set { it.copy(rotation = r) } }
        }
        when (cfg.source) {
            PhotoSource.SINGLE -> Unit
            PhotoSource.ALBUM -> AlbumPicks(
                settings, update,
                onAdd = { albumPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                rotate = { rotateNow(it) },
            )
            PhotoSource.ON_THIS_DAY -> if (!Memories.hasPermission(ctx)) {
                ClickRow("Allow photo access", "To find photos from this date in past years", Icons.Filled.PhotoLibrary) {
                    mediaPermission.launch(Memories.permission())
                }
            } else Note("Picks one of your photos taken on this date in an earlier year (or within a few days of it).")
            PhotoSource.IMMICH -> ImmichFields(cfg, ::set)
            PhotoSource.APOD -> Note("NASA's Astronomy Picture of the Day. On video days, Brig uses the video's thumbnail.")
            PhotoSource.BING -> Note("Bing's daily photo, in portrait. Faster rotation cycles through the past week.")
            PhotoSource.UNSPLASH -> {
                var key by remember { mutableStateOf(cfg.unsplashKey) }
                var q by remember { mutableStateOf(cfg.unsplashQuery) }
                Field("Access key", key, secret = true) { key = it }
                Field("Search term", q) { q = it }
                FilledTonalButton(
                    onClick = { set { it.copy(unsplashKey = key.trim(), unsplashQuery = q.trim()) }; rotateNow(cfg.copy(unsplashKey = key.trim(), unsplashQuery = q.trim())) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text("Save") }
                Note("Get a free key at unsplash.com/developers (create an app, copy its Access Key).")
            }
            PhotoSource.REDDIT -> {
                var sub by remember { mutableStateOf(cfg.subreddit) }
                Field("Subreddit", sub) { sub = it }
                FilledTonalButton(
                    onClick = { set { it.copy(subreddit = sub.trim()) }; rotateNow(cfg.copy(subreddit = sub.trim())) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text("Save") }
                Note("Uses the week's top image posts, skipping NSFW ones. Reddit sometimes blocks apps without an account; if nothing loads, try another source.")
            }
        }
        if (cfg.source != PhotoSource.SINGLE) {
            ClickRow(
                if (busy) "Getting a photo…" else "New photo now",
                current?.caption, Icons.Filled.Refresh,
            ) { if (!busy) rotateNow() }
        }
    }
}

@Composable
private fun ImmichFields(cfg: PhotoSourceConfig, set: ((PhotoSourceConfig) -> PhotoSourceConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(cfg.immichUrl) }
    var key by remember { mutableStateOf(cfg.immichKey) }
    var albums by remember { mutableStateOf<List<PhotoRotator.ImmichAlbum>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    Field("Server URL", url) { url = it }
    Field("API key", key, secret = true) { key = it }
    FilledTonalButton(
        onClick = {
            set { it.copy(immichUrl = url.trim(), immichKey = key.trim()) }
            scope.launch {
                status = "Loading albums…"
                albums = runCatching { PhotoRotator.immichAlbums(url.trim(), key.trim()) }
                    .onFailure { status = "Couldn't reach Immich: ${it.message}" }.getOrDefault(emptyList())
                if (albums.isNotEmpty()) status = null else if (status == "Loading albums…") status = "No albums found"
            }
        },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) { Text("Load albums") }
    status?.let { Note(it) }
    albums.forEach { a ->
        ListItem(
            headlineContent = { Text(a.name) },
            supportingContent = { Text("${a.count} items") },
            modifier = Modifier.clickable { set { it.copy(immichAlbumId = a.id, immichAlbumName = a.name) }; albums = emptyList() },
            colors = rowColors,
        )
    }
    if (cfg.immichAlbumId.isNotBlank()) Note("Using album: ${cfg.immichAlbumName}")
    Note("Create an API key in Immich under Account settings → API keys.")
}

// ---------------------------------------------------------------- Live effects

@Composable
fun LiveEffectsSettings(a: Appearance, set: ((Appearance) -> Appearance) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var depthStatus by remember { mutableStateOf<String?>(null) }
    val rotated by PhotoRotator.current.collectAsState()
    SettingsGroup("Effects") {
        if (a.background == BackgroundType.IMAGE) {
            SwitchRow("Depth effect", depthStatus ?: "The photo's subject stands in front of the clock", a.depth) { on ->
                set { it.copy(depth = on) }
                val path = rotated?.path ?: a.imagePath
                if (on && path != null) scope.launch {
                    depthStatus = "Finding the subject… (first time downloads a small model)"
                    val cut = Segmenter.cutout(ctx, path)
                    depthStatus = if (cut != null) "Subject found. It's drawn over the clock." else "Couldn't find a clear subject in this photo"
                }
            }
            if (a.depth) {
                SwitchRow(
                    "Keep the clock clear",
                    "Skip the effect on photos where the subject would hide most of the clock",
                    a.depthKeepClock,
                ) { v -> set { it.copy(depthKeepClock = v) } }
            }
        }
        SwitchRow("Album art background", "While music plays, the background becomes the blurred album art", a.albumArtBackground) { v ->
            set { it.copy(albumArtBackground = v) }
        }
        SwitchRow("Tilt parallax", "The background shifts as you tilt the phone", a.tiltParallax) { v -> set { it.copy(tiltParallax = v) } }
        SwitchRow("Weather effects", "Rain, snow, storms or fog over the background, matching the weather. Needs a weather city.", a.weatherFx) { v ->
            set { it.copy(weatherFx = v) }
        }
        SwitchRow("Adapt to surroundings", "Dimmer and warmer in the dark, clearer in bright light", a.ambient) { v -> set { it.copy(ambient = v) } }
    }
}

// ---------------------------------------------------------------- Health

@Composable
fun HealthSettings(cfg: HealthConfig, update: SettingsUpdate) {
    val ctx = LocalContext.current
    fun set(f: (HealthConfig) -> HealthConfig) = update { it.copy(health = f(it.health)) }
    var refresh by remember { mutableStateOf(0) }
    val granted by produceState(false, refresh) { value = HealthRepo.hasPermission(ctx) }
    val launcher = rememberLauncherForActivityResult(HealthRepo.permissionContract()) { refresh++ }
    SettingsGroup("Steps & activity") {
        if (!HealthRepo.available(ctx)) {
            Note("Health Connect isn't available on this phone. On Android 13 and older, install it from the Play Store.")
            ClickRow("Get Health Connect", icon = Icons.Filled.FavoriteBorder) {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        } else if (!granted) {
            ClickRow("Connect Health Connect", "Read today's steps, active calories and exercise", Icons.Filled.FavoriteBorder) {
                launcher.launch(HealthRepo.permissions)
            }
        } else Note("Connected to Health Connect. Anything that writes there (Fitbit, Google Fit, Samsung Health…) shows up.")
        Text("Design", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
        ChipChoice(HealthStyle.entries, cfg.style, { it.label }) { s -> set { it.copy(style = s) } }
        SliderRow("Step goal", cfg.stepGoal.toFloat(), 2000f..20000f, steps = 17, label = { "%,d".format(it.roundToInt()) }) { v -> set { it.copy(stepGoal = v.roundToInt()) } }
        SliderRow("Active calorie goal", cfg.calorieGoal.toFloat(), 100f..1500f, steps = 13, label = { "${it.roundToInt()}" }) { v -> set { it.copy(calorieGoal = v.roundToInt()) } }
        SliderRow("Exercise goal", cfg.minutesGoal.toFloat(), 10f..120f, steps = 10, label = { "${it.roundToInt()} min" }) { v -> set { it.copy(minutesGoal = v.roundToInt()) } }
    }
}

// ---------------------------------------------------------------- Sports

@Composable
fun SportsSettings(teams: List<SportsTeam>, update: SettingsUpdate) {
    var league by remember { mutableStateOf(SportsRepo.leagues.keys.first()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val all by produceState(emptyList<SportsTeam>(), league) {
        status = "Loading teams…"
        value = runCatching { SportsRepo.teams(league) }.onFailure { status = "Couldn't load teams" }.getOrDefault(emptyList())
        if (value.isNotEmpty()) status = null
    }
    fun setTeams(t: List<SportsTeam>) = update { it.copy(sports = it.sports.copy(teams = t)) }
    SettingsGroup("Sports scores") {
        teams.forEach { t ->
            ListItem(
                headlineContent = { Text(t.name) },
                supportingContent = { Text(SportsRepo.leagues[t.league] ?: t.league) },
                trailingContent = { IconButton(onClick = { setTeams(teams - t) }) { Icon(Icons.Filled.Close, "Remove") } },
                colors = rowColors,
            )
        }
        if (teams.isEmpty()) Note("Follow a few teams to see live scores, finals and the next game.")
        DropdownRow("League", SportsRepo.leagues.keys.toList(), league, { SportsRepo.leagues[it] ?: it }) { league = it; query = "" }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Find a team") },
            shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        status?.let { Note(it) }
        if (query.isNotBlank()) {
            all.filter { it.name.contains(query, true) || it.abbr.equals(query, true) }.take(8).forEach { t ->
                val added = teams.any { it.league == t.league && it.id == t.id }
                ListItem(
                    headlineContent = { Text(t.name) },
                    supportingContent = { Text(t.abbr) },
                    trailingContent = { if (!added) Icon(Icons.Filled.Add, "Follow") },
                    modifier = Modifier.clickable(enabled = !added) { setTeams(teams + t); query = "" },
                    colors = rowColors,
                )
            }
        }
        Note("Scores come from ESPN and refresh every 2 minutes during a game.")
    }
}

// ---------------------------------------------------------------- Packages

@Composable
fun PackagesSettings(cfg: PackagesConfig, update: SettingsUpdate) {
    val ctx = LocalContext.current
    remember { PackageRepo.load(ctx) }
    val list by PackageRepo.packages.collectAsState()
    var number by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    fun set(f: (PackagesConfig) -> PackagesConfig) = update { it.copy(packages = f(it.packages)) }
    SettingsGroup("Packages") {
        SwitchRow("Detect from notifications", "Picks up shipping updates from Amazon, UPS, FedEx, USPS, DHL and others", cfg.autoDetect) { v ->
            set { it.copy(autoDetect = v) }
        }
        SliderRow("Keep delivered for", cfg.keepDeliveredHours.toFloat(), 2f..72f, label = { "${it.roundToInt()} h" }) { v ->
            set { it.copy(keepDeliveredHours = v.roundToInt()) }
        }
        list.sortedByDescending { it.updatedAt }.forEach { p ->
            ListItem(
                headlineContent = { Text(p.label.ifBlank { "${p.carrier} package" }) },
                supportingContent = { Text(listOf(p.carrier, p.status, p.number.takeUnless { it.startsWith("auto:") }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")) },
                trailingContent = { IconButton(onClick = { PackageRepo.remove(ctx, p.number) }) { Icon(Icons.Filled.Close, "Remove") } },
                colors = rowColors,
            )
        }
        Text("Add a tracking number", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
        Field("Tracking number", number) { number = it }
        Field("Name (optional)", label) { label = it }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                PackageRepo.add(ctx, number, label)
                toast(ctx, "Tracking with ${PackageParse.carrierFor(number.trim())}")
                number = ""
                label = ""
            }, enabled = number.isNotBlank()) { Text("Add") }
        }
        Note("Tapping a package opens the carrier's tracking page. Status for numbers you add yourself only changes when a notification mentions them.")
    }
}

// ---------------------------------------------------------------- Memories

@Composable
fun MemoriesSettings() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(Memories.hasPermission(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = Memories.hasPermission(ctx) }
    SettingsGroup("On this day") {
        if (!granted) {
            ClickRow("Allow photo access", "Brig looks for photos taken on today's date in past years. Nothing leaves your phone.", Icons.Filled.PhotoLibrary) {
                launcher.launch(Memories.permission())
            }
        } else Note("Shows up to three photos from this date in past years. Hidden on days with none.")
    }
}

// ---------------------------------------------------------------- Guest mode

@Composable
fun GuestSettings(settings: BrigSettings, update: SettingsUpdate) {
    val ctx = LocalContext.current
    val g = settings.guest
    var picking by remember { mutableStateOf(false) }
    fun set(f: (dev.pridgey.brig.data.GuestConfig) -> dev.pridgey.brig.data.GuestConfig) = update { it.copy(guest = f(it.guest)) }
    SettingsGroup("Guest mode") {
        Note("Hand your phone over without sharing your life. Guest mode hides notifications, media, calendar and other personal widgets, and only shows the apps you allow. Leaving it needs your Brig PIN, or your system lock if there's no PIN.")
        SwitchRow("Guest mode is on", checked = g.active) { v -> set { it.copy(active = v) } }
        SwitchRow("Guest button in quick toggles", checked = g.toggle) { v -> set { it.copy(toggle = v) } }
        g.apps.forEach { pkg ->
            ListItem(
                headlineContent = { Text(AppsRepo.label(ctx, pkg)) },
                leadingContent = { AppIcon(pkg, 32.dp) },
                trailingContent = { IconButton(onClick = { set { it.copy(apps = it.apps - pkg) } }) { Icon(Icons.Filled.Close, "Remove") } },
                colors = rowColors,
            )
        }
        if (g.apps.size < 6) ClickRow("Allow an app", icon = Icons.Filled.Add) { picking = true }
        Note("Brig can't lock the phone down after unlock. With a system PIN or fingerprint set, opening an allowed app still asks for it, so guests stay on the lock screen and camera. Android's app pinning (Settings → Security) can keep a guest inside one app.")
    }
    if (picking) {
        AppPickerDialog(onDismiss = { picking = false }) { app ->
            if (app.pkg !in g.apps) set { it.copy(apps = it.apps + app.pkg) }
            picking = false
        }
    }
}


// ---------------------------------------------------------------- Date line

@Composable
fun DateSettings(a: Appearance, update: SettingsUpdate) {
    val ctx = LocalContext.current
    fun set(f: (Appearance) -> Appearance) = update { it.copy(appearance = f(it.appearance)) }
    SettingsGroup("Date line") {
        SwitchRow("Say what's next", "\"Standup in 25 min\" or \"Leave in 10 min for Dentist\" instead of the date when something's coming up", a.smartDate) { v ->
            set { it.copy(smartDate = v) }
        }
        if (a.smartDate) {
            SliderRow("Travel time", a.travelBuffer.toFloat(), 0f..60f, steps = 11, label = { "${it.roundToInt()} min" }) { v ->
                set { it.copy(travelBuffer = v.roundToInt()) }
            }
            Note("Used for events with a location. Set it to 0 to never show \"Leave in\".")
            if (!dev.pridgey.brig.data.CalendarRepo.hasPermission(ctx)) Note("Needs calendar access (Permissions).")
        }
    }
}

// ---------------------------------------------------------------- Mood

@Composable
fun MoodSettings() {
    val ctx = LocalContext.current
    remember { dev.pridgey.brig.data.MoodStore.load(ctx) }
    val map by dev.pridgey.brig.data.MoodStore.moods.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    SettingsGroup("Mood check-in") {
        val today = java.time.LocalDate.now()
        val month = map.filterKeys { it.startsWith(today.toString().take(7)) }
        Note(
            if (map.isEmpty()) "Tap a face on the lock screen once a day. Your check-ins stay on this phone."
            else "${map.size} check-ins · ${month.size} this month · ${dev.pridgey.brig.data.MoodStore.streak(map, today)}-day streak",
        )
        if (month.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                dev.pridgey.brig.data.MoodStore.faces.forEachIndexed { i, f ->
                    Text("$f ${month.values.count { it == i }}", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (map.isNotEmpty()) {
            ClickRow(if (confirm) "Tap again to delete everything" else "Delete mood history", icon = Icons.Filled.Close) {
                if (confirm) { dev.pridgey.brig.data.MoodStore.clear(ctx); confirm = false } else confirm = true
            }
        }
    }
}

// ---------------------------------------------------------------- Flashcards

@Composable
fun FlashcardSettings(cfg: dev.pridgey.brig.data.FlashcardConfig, update: SettingsUpdate) {
    fun set(f: (dev.pridgey.brig.data.FlashcardConfig) -> dev.pridgey.brig.data.FlashcardConfig) = update { it.copy(flashcards = f(it.flashcards)) }
    var text by remember { mutableStateOf(cfg.cards) }
    SettingsGroup("Word of the day") {
        ChipChoice(dev.pridgey.brig.data.FlashcardSource.entries, cfg.source, { it.label }) { s -> set { it.copy(source = s) } }
        if (cfg.source == dev.pridgey.brig.data.FlashcardSource.MY_CARDS) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, minLines = 5, maxLines = 12,
                label = { Text("One card per line: front | back") },
                placeholder = { Text("hola | hello\ngracias | thank you") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            val count = dev.pridgey.brig.data.Flashcards.parse(text).size
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { set { it.copy(cards = text) } }, enabled = text != cfg.cards) { Text("Save $count cards") }
            }
            SwitchRow("New card every lock", "Off: one card a day", cfg.everyLock) { v -> set { it.copy(everyLock = v) } }
            Note("Paste from a spreadsheet too: a tab between the columns works like |.")
        } else Note("A new word each day, with its meaning on the back. Tap the card to flip it.")
    }
}

// ---------------------------------------------------------------- Bedtime

@Composable
fun BedtimeSettings(cfg: dev.pridgey.brig.data.SleepConfig, update: SettingsUpdate) {
    val ctx = LocalContext.current
    fun set(f: (dev.pridgey.brig.data.SleepConfig) -> dev.pridgey.brig.data.SleepConfig) = update { it.copy(sleep = f(it.sleep)) }
    var pick by remember { mutableStateOf<Boolean?>(null) }
    val suggestion = remember { dev.pridgey.brig.data.Sleep.suggestBedtime(dev.pridgey.brig.data.Sleep.nightlyOffTimes(ctx)) }
    SettingsGroup("Bedtime nudge") {
        SwitchRow("Nudge me after bedtime", "A gentle note on the lock screen, plus a dimmer, warmer screen", cfg.enabled) { v -> set { it.copy(enabled = v) } }
        if (cfg.enabled) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = { pick = true }) { Text("Bedtime ${dev.pridgey.brig.data.ProfileEngine.formatMinute(cfg.bedtime)}") }
                androidx.compose.material3.OutlinedButton(onClick = { pick = false }) { Text("Until ${dev.pridgey.brig.data.ProfileEngine.formatMinute(cfg.wake)}") }
            }
            SwitchRow("Dim and warm the screen", checked = cfg.dimAfter) { v -> set { it.copy(dimAfter = v) } }
        }
        if (suggestion != null && suggestion != cfg.bedtime) {
            ClickRow(
                "Use ${dev.pridgey.brig.data.ProfileEngine.formatMinute(suggestion)}?",
                "That's when you usually put the phone down, based on the last two weeks",
                Icons.Filled.Refresh,
            ) { set { it.copy(bedtime = suggestion, enabled = true) } }
        }
    }
    pick?.let { start ->
        TimePickDialog(if (start) cfg.bedtime else cfg.wake, onDismiss = { pick = null }) { m ->
            set { if (start) it.copy(bedtime = m) else it.copy(wake = m) }
            pick = null
        }
    }
}
