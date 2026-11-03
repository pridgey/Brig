package dev.pridgey.brig.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Custom clocks as short text codes ("brig-clock:…") that can be pasted anywhere. */
object ClockShare {
    private const val PREFIX = "brig-clock:"

    fun encode(clock: CustomClock): String {
        val raw = SettingsRepository.json.encodeToString(CustomClock.serializer(), clock).toByteArray()
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(zipped)
    }

    /** Finds a code anywhere in [text] (so a whole pasted message works). Null if there isn't a valid one. */
    fun decode(text: String): CustomClock? {
        val code = Regex("""brig-clock:([A-Za-z0-9_\-]+)""").find(text)?.groupValues?.get(1) ?: return null
        return runCatching {
            val bytes = GZIPInputStream(ByteArrayInputStream(Base64.getUrlDecoder().decode(code))).use { it.readBytes() }
            SettingsRepository.json.decodeFromString(CustomClock.serializer(), String(bytes))
        }.getOrNull()?.takeIf { it.layers.size <= 24 }
    }

    val templates: List<CustomClock> = listOf(
        CustomClock.starter(),
        CustomClock(
            name = "Editorial",
            height = 230,
            layers = listOf(
                ClockLayer(pattern = "EEEE", x = 0.0f, y = 0.0f, size = 22f, font = ClockFont.SERIF, weight = ClockWeight.REGULAR, color = LayerColor.ACCENT),
                ClockLayer(pattern = "h:mm", x = 0.0f, y = 0.12f, size = 120f, font = ClockFont.SERIF, weight = ClockWeight.LIGHT),
                ClockLayer(pattern = "MMMM d", x = 0.01f, y = 0.8f, size = 20f, font = ClockFont.SERIF, weight = ClockWeight.REGULAR, alpha = 0.7f),
            ),
        ),
        CustomClock(
            name = "Pill stack",
            height = 220,
            layers = listOf(
                ClockLayer(kind = LayerKind.SHAPE, shape = LayerShape.PILL, x = 0f, y = 0.02f, size = 260f, color = LayerColor.ACCENT, alpha = 0.9f),
                ClockLayer(pattern = "HH", x = 0.08f, y = 0.0f, size = 92f, weight = ClockWeight.BLACK, color = LayerColor.CUSTOM, customColor = 0xFF101318),
                ClockLayer(kind = LayerKind.SHAPE, shape = LayerShape.PILL, x = 0f, y = 0.52f, size = 260f, color = LayerColor.WHITE, alpha = 0.16f),
                ClockLayer(pattern = "mm", x = 0.08f, y = 0.5f, size = 92f, weight = ClockWeight.BLACK),
            ),
        ),
        CustomClock(
            name = "Mono terminal",
            height = 170,
            layers = listOf(
                ClockLayer(pattern = "'>' HH:mm", x = 0f, y = 0f, size = 64f, font = ClockFont.MONO, weight = ClockWeight.REGULAR, color = LayerColor.ACCENT),
                ClockLayer(pattern = "'  'yyyy-MM-dd EEE", x = 0f, y = 0.55f, size = 18f, font = ClockFont.MONO, weight = ClockWeight.REGULAR, alpha = 0.7f),
            ),
        ),
        CustomClock(
            name = "Ring",
            height = 260,
            layers = listOf(
                ClockLayer(kind = LayerKind.SHAPE, shape = LayerShape.RING, x = 0.05f, y = 0f, size = 250f, color = LayerColor.ACCENT, alpha = 0.8f),
                ClockLayer(kind = LayerKind.SHAPE, shape = LayerShape.CIRCLE, x = 0.42f, y = 0.0f, size = 22f, color = LayerColor.ACCENT, spin = false),
                ClockLayer(pattern = "HH:mm", x = 0.15f, y = 0.33f, size = 64f, weight = ClockWeight.BOLD),
            ),
        ),
    )
}
