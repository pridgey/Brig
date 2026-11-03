package dev.pridgey.brig.data

import kotlinx.serialization.Serializable

/** Everything the user can configure. Stored as one JSON blob so export/import is trivial. */
@Serializable
data class BrigSettings(
    val enabled: Boolean = false,
    val appearance: Appearance = Appearance(),
    val widgets: List<WidgetConfig> = WidgetType.entries.map { WidgetConfig(it, it.defaultOn) },
    val notifications: NotificationRules = NotificationRules(),
    val unlock: UnlockConfig = UnlockConfig(),
    val gestures: Gestures = Gestures(),
    val shortcuts: List<String> = emptyList(),
    val profiles: List<Profile> = Profile.defaults(),
    val weather: WeatherConfig = WeatherConfig(),
    val updates: UpdateConfig = UpdateConfig(),
    val media: MediaConfig = MediaConfig(),
    val calendar: CalendarConfig = CalendarConfig(),
    val health: HealthConfig = HealthConfig(),
    val sports: SportsConfig = SportsConfig(),
    val packages: PackagesConfig = PackagesConfig(),
    val photos: PhotoSourceConfig = PhotoSourceConfig(),
    val guest: GuestConfig = GuestConfig(),
    /** 0 until the first-run assistant finishes. */
    val setupVersion: Int = 0,
) {
    /** Saved widget order, plus any widget types added in newer versions. */
    fun widgetOrder(): List<WidgetConfig> {
        val known = widgets.map { it.type }.toSet()
        return widgets + WidgetType.entries.filter { it !in known }.map { WidgetConfig(it, false) }
    }

    fun isWidgetOn(type: WidgetType) = widgetOrder().any { it.type == type && it.enabled }
}

enum class ClockStyle(val label: String, val centered: Boolean) {
    STACKED("Stacked", false),
    DIGITAL("Digital", true),
    FLIP("Flip", true),
    BUBBLE("Bubbles", false),
    ORBIT("Orbit", true),
    GRADIENT("Gradient", false),
    OUTLINE("Outline", false),
    DOT_MATRIX("Dot matrix", true),
    SEGMENT("Retro LED", true),
    BINARY("Binary", true),
    ANALOG("Analog", true),
    WORDS("Words", false),
    MINIMAL("Minimal", false),
    CUSTOM("Custom", false),
}

enum class ClockFont(val label: String) {
    DEFAULT("Google Sans"),
    CONDENSED("Condensed"),
    SERIF("Serif"),
    MONO("Mono"),
    SCRIPT("Script"),
}

enum class ClockWeight(val label: String) {
    THIN("Thin"),
    LIGHT("Light"),
    REGULAR("Regular"),
    BOLD("Bold"),
    BLACK("Black"),
}

enum class ClockAlign(val label: String) {
    AUTO("Auto"),
    START("Left"),
    CENTER("Center"),
    END("Right"),
}

enum class BackgroundType(val label: String) {
    AURORA("Aurora"),
    TIME_OF_DAY("Sky"),
    PARTICLES("Starfield"),
    SOLID("Solid"),
    IMAGE("Photo"),
    VIDEO("Video loop"),
}

@Serializable
data class Appearance(
    val clockStyle: ClockStyle = ClockStyle.STACKED,
    val use24h: Boolean = false,
    val showSeconds: Boolean = false,
    val secondaryZone: String? = null,
    val clockScale: Float = 1f,
    /** Main clock color; null means white. The accent is used for the second element. */
    val clockColor: Long? = null,
    val clockFont: ClockFont = ClockFont.DEFAULT,
    /** Null keeps each style's own weight. */
    val clockWeight: ClockWeight? = null,
    val leadingZero: Boolean = false,
    val showAmPm: Boolean = true,
    /** Background opacity of glance chips (0 = invisible, 1 = solid white). */
    val chipOpacity: Float = 0.12f,
    val clockAlign: ClockAlign = ClockAlign.AUTO,
    val layout: LayoutConfig = LayoutConfig(),
    val photo: PhotoConfig = PhotoConfig(),
    val background: BackgroundType = BackgroundType.AURORA,
    val solidColor: Long = 0xFF101318,
    val accent: Long? = null,
    val imagePath: String? = null,
    val dim: Float = 0.25f,
    val blur: Float = 0f,
    val hideStatusBar: Boolean = false,
    /** Cut the photo's subject out and draw it over the clock. */
    val depth: Boolean = false,
    /** Background shifts slightly as the phone tilts. */
    val tiltParallax: Boolean = false,
    /** Rain, snow or fog drawn over the background to match the current weather. */
    val weatherFx: Boolean = false,
    /** Dimmer and warmer in the dark, brighter in sunlight. */
    val ambient: Boolean = false,
    val videoPath: String? = null,
    val customClock: CustomClock = CustomClock.starter(),
)

// ---------------------------------------------------------------- Custom clock

enum class LayerKind(val label: String) {
    TEXT("Text"),
    SHAPE("Shape"),
}

enum class LayerColor(val label: String) {
    WHITE("White"),
    ACCENT("Accent"),
    ACCENT_SOFT("Soft accent"),
    CUSTOM("Custom"),
}

enum class LayerShape(val label: String) {
    CIRCLE("Circle"),
    COOKIE4("Clover"),
    COOKIE6("Flower"),
    COOKIE9("Cookie"),
    COOKIE12("Sun"),
    PILL("Pill"),
    SQUARE("Square"),
    RING("Ring"),
}

/**
 * One layer of a custom clock. Text layers use a java.time pattern like "HH" or "EEEE".
 * Position is relative to the clock box: x and y are 0..1, size is in sp (text) or dp (shapes).
 */
@Serializable
data class ClockLayer(
    val kind: LayerKind = LayerKind.TEXT,
    val pattern: String = "h:mm",
    val font: ClockFont = ClockFont.DEFAULT,
    val weight: ClockWeight = ClockWeight.BOLD,
    val shape: LayerShape = LayerShape.COOKIE9,
    val x: Float = 0f,
    val y: Float = 0f,
    val size: Float = 72f,
    val color: LayerColor = LayerColor.WHITE,
    val customColor: Long = 0xFFFFFFFF,
    val alpha: Float = 1f,
    val rotation: Float = 0f,
    /** Shapes only: turn slowly, one revolution per minute. */
    val spin: Boolean = false,
)

@Serializable
data class CustomClock(val name: String = "My clock", val height: Int = 200, val layers: List<ClockLayer> = emptyList()) {
    companion object {
        fun starter() = CustomClock(
            name = "Cookie time",
            height = 210,
            layers = listOf(
                ClockLayer(kind = LayerKind.SHAPE, shape = LayerShape.COOKIE9, x = 0.02f, y = 0.02f, size = 190f, color = LayerColor.ACCENT, alpha = 0.35f, spin = true),
                ClockLayer(pattern = "HH", x = 0.08f, y = 0.04f, size = 96f, weight = ClockWeight.BLACK),
                ClockLayer(pattern = "mm", x = 0.08f, y = 0.46f, size = 96f, weight = ClockWeight.BLACK, color = LayerColor.ACCENT),
                ClockLayer(pattern = "EEE d", x = 0.52f, y = 0.42f, size = 22f, weight = ClockWeight.REGULAR, alpha = 0.8f),
            ),
        )
    }
}

// ---------------------------------------------------------------- Photo sources

enum class PhotoSource(val label: String, val description: String) {
    SINGLE("One photo", "The photo you picked"),
    ALBUM("My picks", "Rotate through photos you choose"),
    ON_THIS_DAY("On this day", "Your own photos taken on this date in past years"),
    IMMICH("Immich", "An album from your Immich server"),
    APOD("NASA picture of the day", "Astronomy Picture of the Day"),
    BING("Bing daily", "Bing's photo of the day"),
    UNSPLASH("Unsplash", "Random photos for a search term (needs a free access key)"),
    REDDIT("Reddit", "Top image posts from a subreddit"),
}

enum class Rotation(val label: String, val minutes: Int) {
    EVERY_LOCK("Every time the screen locks", 0),
    HOURLY("Every hour", 60),
    SIX_HOURS("Every 6 hours", 360),
    DAILY("Once a day", 1440),
}

@Serializable
data class PhotoSourceConfig(
    val source: PhotoSource = PhotoSource.SINGLE,
    val rotation: Rotation = Rotation.DAILY,
    val albumPaths: List<String> = emptyList(),
    val immichUrl: String = "",
    val immichKey: String = "",
    val immichAlbumId: String = "",
    val immichAlbumName: String = "",
    val unsplashKey: String = "",
    val unsplashQuery: String = "nature",
    val subreddit: String = "EarthPorn",
)

// ---------------------------------------------------------------- Feeds

enum class HealthStyle(val label: String) {
    RINGS("Rings"),
    BARS("Bars"),
    CHIP("Chip"),
}

@Serializable
data class HealthConfig(
    val style: HealthStyle = HealthStyle.RINGS,
    val stepGoal: Int = 8000,
    val calorieGoal: Int = 500,
    val minutesGoal: Int = 30,
)

@Serializable
data class SportsTeam(val league: String, val id: String, val abbr: String, val name: String, val color: Long? = null)

@Serializable
data class SportsConfig(val teams: List<SportsTeam> = emptyList())

@Serializable
data class TrackedPackage(
    val number: String,
    val carrier: String,
    val label: String = "",
    val status: String = "",
    val updatedAt: Long = 0,
    val delivered: Boolean = false,
    val auto: Boolean = false,
)

@Serializable
data class PackagesConfig(
    val autoDetect: Boolean = true,
    /** Hide delivered packages after this many hours. */
    val keepDeliveredHours: Int = 24,
)

@Serializable
data class GuestConfig(
    val active: Boolean = false,
    /** Apps a guest may open. Empty means the camera only. */
    val apps: List<String> = emptyList(),
    /** Show a guest button in the lock screen's quick toggles. */
    val toggle: Boolean = true,
)

/** Lock screen spacing, in dp. */
@Serializable
data class LayoutConfig(
    val top: Int = 28,
    val side: Int = 22,
    val spacing: Int = 14,
    val bottom: Int = 10,
)

enum class PhotoEffect(val label: String) {
    NONE("Original"),
    MONO("Mono"),
    NOIR("Noir"),
    SEPIA("Sepia"),
    VINTAGE("Vintage"),
    DUOTONE("Duotone"),
    WARM("Warm"),
    COOL("Cool"),
    POP("Pop"),
    INVERT("Invert"),
}

@Serializable
data class PhotoConfig(
    val effect: PhotoEffect = PhotoEffect.NONE,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    /** Dark fade at the top and bottom so text stays readable. */
    val scrim: Float = 0.3f,
    val kenBurns: Boolean = false,
)

enum class MediaStyle(val label: String) {
    CARD("Card"),
    ARTWORK("Artwork"),
    COMPACT("Compact"),
    MINIMAL("Minimal"),
}

enum class MediaVisibility(val label: String) {
    PLAYING("Only while playing"),
    RECENT("Playing, or paused in the last 10 min"),
    ALWAYS("Whenever a player has a track"),
}

@Serializable
data class MediaConfig(
    val style: MediaStyle = MediaStyle.CARD,
    val visibility: MediaVisibility = MediaVisibility.RECENT,
    val tintFromArt: Boolean = true,
    val showProgress: Boolean = true,
)

enum class CalendarStyle(val label: String) {
    LIST("List"),
    TIMELINE("Timeline"),
    NEXT_UP("Next up"),
    CHIPS("Chips"),
}

@Serializable
data class CalendarConfig(
    val style: CalendarStyle = CalendarStyle.LIST,
    val count: Int = 3,
    val showAllDay: Boolean = true,
    val lookaheadHours: Int = 36,
    /** Off: everything uses the accent color. On: each event keeps its calendar's color. */
    val useEventColors: Boolean = false,
)

enum class WidgetZone(val label: String) {
    TOP("Top"),
    BOTTOM("Bottom"),
}

enum class WidgetType(val label: String, val defaultOn: Boolean, val defaultZone: WidgetZone = WidgetZone.TOP) {
    DATE("Date", true),
    ALARM("Next alarm", true),
    BATTERY("Battery", true),
    WEATHER("Weather", false),
    STATS("Daily stats", false),
    CALENDAR("Calendar", false),
    MEDIA("Media controls", true),
    NOTIFICATIONS("Notifications", true),
    QUOTE("Quote of the day", false),
    SHORTCUTS("App shortcuts", true, WidgetZone.BOTTOM),
    TOGGLES("Quick toggles", true, WidgetZone.BOTTOM),
    LIVE("Live activities", true),
    ALARM_CARD("Alarm card", false),
    HOURLY("Hourly weather", false),
    AIR("Air quality", false),
    UV("UV index", false),
    POLLEN("Pollen", false),
    HEALTH("Steps & activity", false),
    SPORTS("Sports scores", false),
    PACKAGES("Packages", false),
    MEMORIES("On this day", false),
}

@Serializable
data class WidgetConfig(
    val type: WidgetType,
    val enabled: Boolean,
    /** Null means the widget's default zone (older saved settings have no zone). */
    val zone: WidgetZone? = null,
) {
    fun zoneOrDefault() = zone ?: type.defaultZone
}

enum class AppNotificationMode(val label: String) {
    SHOW("Show"),
    PRIORITY("Always on top"),
    QUIET("Quiet"),
    HIDE_CONTENT("Hide content"),
    BLOCK("Block"),
}

@Serializable
data class NotificationRules(
    val hideAllContent: Boolean = false,
    val showSilent: Boolean = false,
    val maxShown: Int = 4,
    val allowReply: Boolean = true,
    val groupByApp: Boolean = true,
    /** People first, promos and silent ones collapsed into a Quiet stack. */
    val smartPriority: Boolean = true,
    /** Message notifications expand into chat bubbles. */
    val conversationView: Boolean = true,
    /** Only app icons until tapped. */
    val glanceMode: Boolean = false,
    val appModes: Map<String, AppNotificationMode> = emptyMap(),
)

enum class UnlockMethod(val label: String, val description: String) {
    SWIPE("Swipe", "Just swipe. Your system PIN/fingerprint still protects the phone."),
    PIN("Brig PIN", "An extra PIN on top. Useful if you don't use a system lock."),
    MATH("Math challenge", "Solve a quick problem to unlock. Good for waking your brain up."),
}

@Serializable
data class UnlockConfig(
    val method: UnlockMethod = UnlockMethod.SWIPE,
    val pinHash: String? = null,
    val pinSalt: String? = null,
    val mathDifficulty: Int = 1,
    val autoHideOnSystemUnlock: Boolean = true,
)

enum class GestureAction(val label: String) {
    NONE("Nothing"),
    UNLOCK("Unlock"),
    CAMERA("Camera"),
    FLASHLIGHT("Flashlight"),
    SLEEP("Turn screen off"),
    TOGGLE_DND("Toggle Do Not Disturb"),
    APP("Open an app"),
}

enum class GestureDir(val label: String) {
    UP("Swipe up"),
    DOWN("Swipe down"),
    LEFT("Swipe left"),
    RIGHT("Swipe right"),
    DOUBLE_TAP("Double-tap"),
}

@Serializable
data class Gestures(
    val up: GestureAction = GestureAction.UNLOCK,
    val down: GestureAction = GestureAction.NONE,
    val left: GestureAction = GestureAction.CAMERA,
    val right: GestureAction = GestureAction.FLASHLIGHT,
    val doubleTap: GestureAction = GestureAction.SLEEP,
    /** Package to open for gestures set to APP, keyed by [GestureDir.name]. */
    val apps: Map<String, String> = emptyMap(),
) {
    fun action(dir: GestureDir) = when (dir) {
        GestureDir.UP -> up
        GestureDir.DOWN -> down
        GestureDir.LEFT -> left
        GestureDir.RIGHT -> right
        GestureDir.DOUBLE_TAP -> doubleTap
    }

    fun with(dir: GestureDir, action: GestureAction) = when (dir) {
        GestureDir.UP -> copy(up = action)
        GestureDir.DOWN -> copy(down = action)
        GestureDir.LEFT -> copy(left = action)
        GestureDir.RIGHT -> copy(right = action)
        GestureDir.DOUBLE_TAP -> copy(doubleTap = action)
    }

    fun hasUnlock() = GestureDir.entries.any { action(it) == GestureAction.UNLOCK }

    /** The direction shown in the unlock hint. Swipe up always unlocks if nothing else does. */
    fun unlockDir() = GestureDir.entries.firstOrNull { action(it) == GestureAction.UNLOCK } ?: GestureDir.UP
}

enum class TriggerType(val label: String, val usesTime: Boolean) {
    TIME("Time of day", true),
    CHARGING("While charging", false),
    CHARGING_AND_TIME("Charging during a time window", true),
    HEADPHONES("Headphones connected", false),
    DAYTIME("Daytime (sunrise to sunset)", false),
    NIGHTTIME("Night (sunset to sunrise)", false),
}

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: TriggerType = TriggerType.TIME,
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 7 * 60,
    val bedside: Boolean = false,
    val hideNotifications: Boolean = false,
    val hideContent: Boolean = false,
    val clockStyle: ClockStyle? = null,
    val accent: Long? = null,
    val dim: Float? = null,
    val background: BackgroundType? = null,
    val imagePath: String? = null,
    /** Shifts sunrise/sunset triggers, in minutes (negative = earlier). */
    val sunOffset: Int = 0,
) {
    companion object {
        fun defaults() = listOf(
            Profile(
                id = "bedside", name = "Bedside", enabled = true,
                trigger = TriggerType.CHARGING_AND_TIME, startMinute = 22 * 60, endMinute = 7 * 60,
                bedside = true,
            ),
            Profile(
                id = "work", name = "Work privacy", enabled = false,
                trigger = TriggerType.TIME, startMinute = 9 * 60, endMinute = 17 * 60,
                hideContent = true,
            ),
            Profile(
                id = "listening", name = "Listening", enabled = false,
                trigger = TriggerType.HEADPHONES, clockStyle = ClockStyle.MINIMAL,
            ),
        )
    }
}

@Serializable
data class WeatherConfig(
    val city: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val fahrenheit: Boolean = true,
    /** "Rain in 15 min" when rain is about to start. */
    val rainAlert: Boolean = true,
)

@Serializable
data class UpdateConfig(val autoCheck: Boolean = true)
