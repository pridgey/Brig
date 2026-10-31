package dev.pridgey.brig.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import dev.pridgey.brig.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Apps

data class AppInfo(val pkg: String, val label: String)

object AppsRepo {
    private val icons = LruCache<String, ImageBitmap>(96)
    private val labels = ConcurrentHashMap<String, String>()

    suspend fun launchable(ctx: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ctx.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != ctx.packageName }
            .map { AppInfo(it, label(ctx, it)) }
            .sortedBy { it.label.lowercase() }
    }

    fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    fun icon(ctx: Context, pkg: String, px: Int = 144): ImageBitmap? {
        icons.get(pkg)?.let { return it }
        return runCatching {
            ctx.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap()
        }.getOrNull()?.also { icons.put(pkg, it) }
    }
}

// ---------------------------------------------------------------- Calendar

data class CalEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int)

object CalendarRepo {
    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun upcoming(ctx: Context, limit: Int = 3): List<CalEvent> {
        if (!hasPermission(ctx)) return emptyList()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + 36 * 3600_000L)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
        )
        val out = mutableListOf<CalEvent>()
        runCatching {
            ctx.contentResolver.query(
                uri, projection, "${CalendarContract.Instances.END} >= ?", arrayOf(now.toString()),
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += CalEvent(
                        title = c.getString(0) ?: "(No title)",
                        begin = c.getLong(1),
                        end = c.getLong(2),
                        allDay = c.getInt(3) == 1,
                        color = c.getInt(4),
                    )
                }
            }
        }
        return out
    }

    fun whenLabel(e: CalEvent): String {
        // All-day events are stored in UTC.
        val zone = if (e.allDay) ZoneId.of("UTC") else ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(e.begin).atZone(zone)
        val today = LocalDate.now()
        val dayPrefix = when (start.toLocalDate()) {
            today -> ""
            today.plusDays(1) -> "Tmrw "
            else -> start.format(DateTimeFormatter.ofPattern("EEE "))
        }
        if (e.allDay) return if (dayPrefix.isEmpty()) "All day" else dayPrefix.trim()
        if (e.begin <= System.currentTimeMillis()) return "Now"
        return dayPrefix + start.format(DateTimeFormatter.ofPattern("h:mm a"))
    }
}

// ---------------------------------------------------------------- Weather (Open-Meteo, no API key)

@Serializable
data class WeatherNow(
    val temp: Int, val code: Int, val hi: Int, val lo: Int, val isDay: Boolean,
    val fetchedAt: Long, val lat: Double, val lon: Double, val fahrenheit: Boolean,
)

data class GeoResult(val name: String, val region: String, val lat: Double, val lon: Double)

object WeatherRepo {
    private const val TTL = 30 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun current(ctx: Context, cfg: WeatherConfig): WeatherNow? {
        val lat = cfg.lat ?: return null
        val lon = cfg.lon ?: return null
        val prefs = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
        val cached = prefs.getString("now", null)
            ?.let { runCatching { json.decodeFromString(WeatherNow.serializer(), it) }.getOrNull() }
            ?.takeIf { it.lat == lat && it.lon == lon && it.fahrenheit == cfg.fahrenheit }
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < TTL) return cached
        return try {
            val unit = if (cfg.fahrenheit) "&temperature_unit=fahrenheit" else ""
            val body = Http.get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto$unit",
            )
            val o = json.parseToJsonElement(body).jsonObject
            val cur = o.getValue("current").jsonObject
            val daily = o.getValue("daily").jsonObject
            val now = WeatherNow(
                temp = cur.getValue("temperature_2m").jsonPrimitive.double.roundToInt(),
                code = cur.getValue("weather_code").jsonPrimitive.int,
                hi = daily.getValue("temperature_2m_max").jsonArray[0].jsonPrimitive.double.roundToInt(),
                lo = daily.getValue("temperature_2m_min").jsonArray[0].jsonPrimitive.double.roundToInt(),
                isDay = cur["is_day"]?.jsonPrimitive?.intOrNull == 1,
                fetchedAt = System.currentTimeMillis(),
                lat = lat, lon = lon, fahrenheit = cfg.fahrenheit,
            )
            prefs.edit().putString("now", json.encodeToString(WeatherNow.serializer(), now)).apply()
            now
        } catch (e: Exception) {
            cached // stale beats nothing
        }
    }

    suspend fun geocode(query: String): List<GeoResult> {
        if (query.isBlank()) return emptyList()
        val body = Http.get(
            "https://geocoding-api.open-meteo.com/v1/search?count=6&language=en&format=json&name=" +
                URLEncoder.encode(query.trim(), "UTF-8"),
        )
        val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val r = el.jsonObject
            GeoResult(
                name = r["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                region = listOfNotNull(
                    r["admin1"]?.jsonPrimitive?.contentOrNull,
                    r["country"]?.jsonPrimitive?.contentOrNull,
                ).joinToString(", "),
                lat = r["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                lon = r["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
            )
        }
    }

    /** WMO weather code to (emoji, label). */
    fun describe(code: Int, isDay: Boolean): Pair<String, String> = when (code) {
        0 -> (if (isDay) "☀️" else "🌙") to "Clear"
        1, 2 -> (if (isDay) "🌤️" else "☁️") to "Partly cloudy"
        3 -> "☁️" to "Overcast"
        45, 48 -> "🌫️" to "Fog"
        in 51..57 -> "🌦️" to "Drizzle"
        in 61..67 -> "🌧️" to "Rain"
        in 71..77 -> "🌨️" to "Snow"
        in 80..82 -> "🌧️" to "Showers"
        85, 86 -> "🌨️" to "Snow showers"
        in 95..99 -> "⛈️" to "Storms"
        else -> "🌡️" to ""
    }
}

// ---------------------------------------------------------------- Daily stats

object Stats {
    private fun prefs(c: Context): SharedPreferences {
        val p = c.getSharedPreferences("stats", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (p.getString("day", null) != today) {
            p.edit().putString("day", today).putInt("unlocks", 0).putInt("wakes", 0).apply()
        }
        return p
    }

    fun recordUnlock(c: Context) = prefs(c).let { it.edit().putInt("unlocks", it.getInt("unlocks", 0) + 1).apply() }
    fun recordWake(c: Context) = prefs(c).let { it.edit().putInt("wakes", it.getInt("wakes", 0) + 1).apply() }
    fun unlocks(c: Context) = prefs(c).getInt("unlocks", 0)
    fun wakes(c: Context) = prefs(c).getInt("wakes", 0)
}
