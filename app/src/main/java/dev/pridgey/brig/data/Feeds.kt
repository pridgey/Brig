package dev.pridgey.brig.data

import android.content.Context
import dev.pridgey.brig.util.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val feedJson = Json { ignoreUnknownKeys = true }

// Small helpers for walking untyped JSON without a pile of null checks.
internal fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
internal fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
internal fun JsonElement?.str(key: String): String? = runCatching { (this as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()
internal fun JsonElement?.num(key: String): Double? = runCatching { (this as? JsonObject)?.get(key)?.jsonPrimitive?.doubleOrNull }.getOrNull()

/** A tiny JSON-in-SharedPreferences cache with a time-to-live. */
private class Cache<T>(private val name: String, private val serializer: kotlinx.serialization.KSerializer<T>) {
    @Serializable
    private data class Entry(val at: Long, val key: String, val body: String)

    fun get(ctx: Context, key: String, ttlMs: Long, allowStale: Boolean = false): T? {
        val raw = ctx.getSharedPreferences("feeds", Context.MODE_PRIVATE).getString(name, null) ?: return null
        val e = runCatching { feedJson.decodeFromString(Entry.serializer(), raw) }.getOrNull() ?: return null
        if (e.key != key) return null
        if (!allowStale && System.currentTimeMillis() - e.at > ttlMs) return null
        return runCatching { feedJson.decodeFromString(serializer, e.body) }.getOrNull()
    }

    fun put(ctx: Context, key: String, value: T) {
        val e = Entry(System.currentTimeMillis(), key, feedJson.encodeToString(serializer, value))
        ctx.getSharedPreferences("feeds", Context.MODE_PRIVATE).edit()
            .putString(name, feedJson.encodeToString(Entry.serializer(), e)).apply()
    }

    suspend fun fetch(ctx: Context, key: String, ttlMs: Long, load: suspend () -> T): T? =
        get(ctx, key, ttlMs) ?: runCatching { load() }.getOrNull()?.also { put(ctx, key, it) } ?: get(ctx, key, ttlMs, allowStale = true)
}

// ---------------------------------------------------------------- Hourly weather & rain

@Serializable
data class HourWeather(val time: Long, val temp: Int, val code: Int, val rainChance: Int, val isDay: Boolean)

@Serializable
data class WeatherDetail(
    val hours: List<HourWeather>,
    /** Precipitation per 15 minutes for the next few hours, in mm. */
    val precip15: List<Double>,
    val precipStart: Long,
)

object WeatherDetailRepo {
    private val cache = Cache("weather_detail", WeatherDetail.serializer())

    suspend fun load(ctx: Context, cfg: WeatherConfig): WeatherDetail? {
        val lat = cfg.lat ?: return null
        val lon = cfg.lon ?: return null
        return cache.fetch(ctx, "$lat,$lon,${cfg.fahrenheit}", 20 * 60_000L) {
            val unit = if (cfg.fahrenheit) "&temperature_unit=fahrenheit" else ""
            val o = feedJson.parseToJsonElement(
                Http.get(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timeformat=unixtime&timezone=auto$unit" +
                        "&hourly=temperature_2m,weather_code,precipitation_probability,is_day&forecast_hours=13" +
                        "&minutely_15=precipitation&forecast_minutely_15=12",
                ),
            )
            val h = o.obj("hourly")
            val times = h.arr("time").orEmpty()
            val hours = times.indices.mapNotNull { i ->
                HourWeather(
                    time = (times[i].jsonPrimitive.longOrNull ?: return@mapNotNull null) * 1000,
                    temp = h.arr("temperature_2m")?.getOrNull(i)?.jsonPrimitive?.doubleOrNull?.roundToInt() ?: return@mapNotNull null,
                    code = h.arr("weather_code")?.getOrNull(i)?.jsonPrimitive?.intOrNull ?: 0,
                    rainChance = h.arr("precipitation_probability")?.getOrNull(i)?.jsonPrimitive?.intOrNull ?: 0,
                    isDay = h.arr("is_day")?.getOrNull(i)?.jsonPrimitive?.intOrNull == 1,
                )
            }
            val m = o.obj("minutely_15")
            WeatherDetail(
                hours = hours,
                precip15 = m.arr("precipitation").orEmpty().map { it.jsonPrimitive.doubleOrNull ?: 0.0 },
                precipStart = (m.arr("time")?.firstOrNull()?.jsonPrimitive?.longOrNull ?: 0L) * 1000,
            )
        }
    }

    /**
     * "Rain in 15 min", "Rain stopping in 30 min", or null. Pure so it can be tested.
     * [precip] holds 15-minute buckets starting at [start].
     */
    fun rainAlert(precip: List<Double>, start: Long, now: Long, snow: Boolean = false): String? {
        val threshold = 0.1
        val word = if (snow) "Snow" else "Rain"
        val buckets = precip.mapIndexed { i, v -> (start + i * 15 * 60_000L) to v }.filter { it.first + 15 * 60_000L > now }
        if (buckets.isEmpty()) return null
        val wetNow = buckets.first().second >= threshold
        fun mins(t: Long) = (((t - now).coerceAtLeast(0) + 59_999) / 60_000).toInt()
        return if (wetNow) {
            val dry = buckets.firstOrNull { it.second < threshold } ?: return "$word for the next few hours"
            "$word stopping in ${mins(dry.first).coerceAtLeast(5)} min"
        } else {
            val wet = buckets.firstOrNull { it.second >= threshold } ?: return null
            "$word in ${mins(wet.first).coerceAtLeast(5)} min"
        }
    }
}

// ---------------------------------------------------------------- Air quality, UV, pollen

@Serializable
data class AirNow(val usAqi: Int?, val uv: Double?, val pollen: Map<String, Double>)

object AirRepo {
    private val cache = Cache("air", AirNow.serializer())
    private val pollenKeys = listOf("alder", "birch", "grass", "mugwort", "olive", "ragweed")

    suspend fun load(ctx: Context, cfg: WeatherConfig): AirNow? {
        val lat = cfg.lat ?: return null
        val lon = cfg.lon ?: return null
        return cache.fetch(ctx, "$lat,$lon", 60 * 60_000L) {
            val cur = feedJson.parseToJsonElement(
                Http.get(
                    "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon" +
                        "&current=us_aqi,uv_index," + pollenKeys.joinToString(",") { "${it}_pollen" },
                ),
            ).obj("current")
            AirNow(
                usAqi = cur.num("us_aqi")?.roundToInt(),
                uv = cur.num("uv_index"),
                pollen = pollenKeys.mapNotNull { k -> cur.num("${k}_pollen")?.let { k to it } }.toMap(),
            )
        }
    }

    fun aqiLabel(aqi: Int) = when {
        aqi <= 50 -> "Good"
        aqi <= 100 -> "Moderate"
        aqi <= 150 -> "Unhealthy for some"
        aqi <= 200 -> "Unhealthy"
        aqi <= 300 -> "Very unhealthy"
        else -> "Hazardous"
    }

    fun uvLabel(uv: Double) = when {
        uv < 3 -> "Low"
        uv < 6 -> "Moderate"
        uv < 8 -> "High"
        uv < 11 -> "Very high"
        else -> "Extreme"
    }

    /** The strongest pollen type and a rough level, or null outside Europe (no data). */
    fun pollenSummary(p: Map<String, Double>): Pair<String, String>? {
        val (name, v) = p.maxByOrNull { it.value } ?: return null
        val level = when {
            v < 1 -> "None"
            v < 20 -> "Low"
            v < 100 -> "Moderate"
            else -> "High"
        }
        return name.replaceFirstChar { it.uppercase() } to level
    }
}

// ---------------------------------------------------------------- Sports (ESPN's public site API)

enum class GameState { PRE, LIVE, FINAL }

@Serializable
data class Game(
    val teamAbbr: String,
    val oppAbbr: String,
    val home: Boolean,
    val teamScore: String,
    val oppScore: String,
    val state: GameState,
    val detail: String,
    val start: Long,
    val teamColor: Long?,
    val oppColor: Long?,
)

object SportsRepo {
    val leagues = linkedMapOf(
        "football/nfl" to "NFL",
        "basketball/nba" to "NBA",
        "baseball/mlb" to "MLB",
        "hockey/nhl" to "NHL",
        "basketball/wnba" to "WNBA",
        "soccer/usa.1" to "MLS",
        "soccer/eng.1" to "Premier League",
        "soccer/esp.1" to "La Liga",
        "soccer/ger.1" to "Bundesliga",
        "soccer/ita.1" to "Serie A",
        "soccer/fra.1" to "Ligue 1",
        "soccer/uefa.champions" to "Champions League",
        "football/college-football" to "College football",
        "basketball/mens-college-basketball" to "Men's college basketball",
    )

    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports"
    private val cache = Cache("sports", ListSerializer(Game.serializer()))

    private fun color(hex: String?): Long? = hex?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it }

    suspend fun teams(league: String): List<SportsTeam> {
        val root = feedJson.parseToJsonElement(Http.get("$BASE/$league/teams?limit=1000"))
        val teams = root.arr("sports")?.firstOrNull().arr("leagues")?.firstOrNull().arr("teams").orEmpty()
        return teams.mapNotNull { el ->
            val t = el.obj("team") ?: return@mapNotNull null
            SportsTeam(
                league = league,
                id = t.str("id") ?: return@mapNotNull null,
                abbr = t.str("abbreviation") ?: "",
                name = t.str("displayName") ?: return@mapNotNull null,
                color = color(t.str("color")),
            )
        }.sortedBy { it.name }
    }

    /** Parses one ESPN event from [team]'s point of view. Internal for tests. */
    internal fun parseEvent(event: JsonElement, teamId: String): Game? {
        val comp = event.arr("competitions")?.firstOrNull() ?: return null
        val competitors = comp.arr("competitors").orEmpty()
        val us = competitors.firstOrNull { it.obj("team").str("id") == teamId || it.str("id") == teamId } ?: return null
        val them = competitors.firstOrNull { it !== us } ?: return null
        val status = comp.obj("status") ?: event.obj("status")
        val type = status.obj("type")
        val state = when (type.str("state")) {
            "in" -> GameState.LIVE
            "post" -> GameState.FINAL
            else -> GameState.PRE
        }
        // Scores are plain strings on the scoreboard and objects on the team schedule.
        fun score(c: JsonElement): String = runCatching { c.str("score") }.getOrNull()
            ?: c.obj("score").str("displayValue") ?: ""
        val start = runCatching { Instant.parse((event.str("date") ?: "").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }).toEpochMilli() }.getOrDefault(0L)
        val detail = when (state) {
            GameState.PRE -> Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE h:mm a"))
            else -> type.str("shortDetail") ?: type.str("detail") ?: ""
        }
        return Game(
            teamAbbr = us.obj("team").str("abbreviation") ?: "",
            oppAbbr = them.obj("team").str("abbreviation") ?: "",
            home = us.str("homeAway") == "home",
            teamScore = score(us),
            oppScore = score(them),
            state = state,
            detail = detail,
            start = start,
            teamColor = color(us.obj("team").str("color")),
            oppColor = color(them.obj("team").str("color")),
        )
    }

    private suspend fun gameFor(team: SportsTeam): Game? {
        // Today's scoreboard has live scores; fall back to the team's next or most recent event.
        val board = feedJson.parseToJsonElement(Http.get("$BASE/${team.league}/scoreboard"))
        board.arr("events").orEmpty().firstNotNullOfOrNull { parseEvent(it, team.id) }?.let { return it }
        val info = feedJson.parseToJsonElement(Http.get("$BASE/${team.league}/teams/${team.id}"))
        return info.obj("team").arr("nextEvent")?.firstOrNull()?.let { parseEvent(it, team.id) }
    }

    suspend fun games(ctx: Context, cfg: SportsConfig): List<Game> {
        if (cfg.teams.isEmpty()) return emptyList()
        val key = cfg.teams.joinToString { it.league + it.id }
        val cached = cache.get(ctx, key, 15 * 60_000L)
        // Refresh every 2 minutes while anything is live.
        if (cached != null && (cached.none { it.state == GameState.LIVE } || cache.get(ctx, key, 2 * 60_000L) != null)) return cached
        return cache.fetch(ctx, key, 0L) { cfg.teams.mapNotNull { runCatching { gameFor(it) }.getOrNull() } } ?: emptyList()
    }
}

// ---------------------------------------------------------------- Packages

object PackageParse {
    data class Hit(val carrier: String, val number: String?)

    private val patterns = listOf(
        "UPS" to Regex("""\b1Z[0-9A-Z]{16}\b"""),
        "Amazon" to Regex("""\bTBA\d{12}\b"""),
        "USPS" to Regex("""\b(9[2-5]\d{20}|9[2-5]\d{18}|[A-Z]{2}\d{9}US)\b"""),
    )
    private val carrierWords = mapOf(
        "ups" to "UPS", "fedex" to "FedEx", "usps" to "USPS", "dhl" to "DHL", "amazon" to "Amazon",
        "ontrac" to "OnTrac", "lasership" to "LaserShip", "royal mail" to "Royal Mail", "canada post" to "Canada Post",
    )
    private val shippingPackages = mapOf(
        "com.amazon.mShop.android.shopping" to "Amazon",
        "com.ups.mobile.android" to "UPS",
        "com.fedex.ida.android" to "FedEx",
        "com.usps.mobile.android" to "USPS",
        "com.dhl.exp.dhlmobile" to "DHL",
        "com.shopify.arrive" to "Shop",
        "com.parcelapp.parcel" to "Parcel",
        "com.aftership.AfterShip" to "AfterShip",
    )

    /** A short status like "Out for delivery", or null if the text isn't about a delivery. */
    fun status(text: String): String? {
        val t = text.lowercase()
        return when {
            "out for delivery" in t -> "Out for delivery"
            "delivered" in t && "not delivered" !in t && "will be delivered" !in t -> "Delivered"
            "arriving today" in t || "arrives today" in t -> "Arriving today"
            "arriving tomorrow" in t || "arrives tomorrow" in t -> "Arriving tomorrow"
            "delay" in t -> "Delayed"
            "shipped" in t || "on the way" in t || "on its way" in t -> "Shipped"
            "in transit" in t -> "In transit"
            "label created" in t -> "Label created"
            else -> null
        }
    }

    fun detect(pkg: String, text: String): Hit? {
        patterns.forEach { (carrier, re) -> re.find(text)?.let { return Hit(carrier, it.value) } }
        val t = text.lowercase()
        if ("fedex" in t) Regex("""\b(\d{12}|\d{15})\b""").find(text)?.let { return Hit("FedEx", it.value) }
        if ("dhl" in t) Regex("""\b\d{10}\b""").find(text)?.let { return Hit("DHL", it.value) }
        shippingPackages[pkg]?.let { return Hit(it, null) }
        carrierWords.entries.firstOrNull { Regex("""\b${it.key}\b""").containsMatchIn(t) }?.let { return Hit(it.value, null) }
        return null
    }

    fun carrierFor(number: String): String = patterns.firstOrNull { it.second.matches(number) }?.first
        ?: if (number.length == 12 || number.length == 15) "FedEx" else if (number.length == 10) "DHL" else "Other"

    fun trackingUrl(p: TrackedPackage): String? {
        val n = URLEncoder.encode(p.number, "UTF-8")
        if (p.number.startsWith("auto:")) return null
        return when (p.carrier) {
            "UPS" -> "https://www.ups.com/track?tracknum=$n"
            "FedEx" -> "https://www.fedex.com/fedextrack/?trknbr=$n"
            "USPS" -> "https://tools.usps.com/go/TrackConfirmAction?tLabels=$n"
            "DHL" -> "https://www.dhl.com/global-en/home/tracking/tracking-express.html?tracking-id=$n"
            else -> "https://parcelsapp.com/en/tracking/$n"
        }
    }
}

/** Packages from shipping notifications plus ones added by hand. Stored on the device only. */
object PackageRepo {
    val packages = MutableStateFlow<List<TrackedPackage>>(emptyList())
    private var loaded = false
    private val serializer = ListSerializer(TrackedPackage.serializer())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("packages", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        packages.value = prefs(ctx).getString("list", null)
            ?.let { runCatching { feedJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    }

    private fun save(ctx: Context, list: List<TrackedPackage>) {
        packages.value = list
        prefs(ctx).edit().putString("list", feedJson.encodeToString(serializer, list)).apply()
    }

    fun add(ctx: Context, number: String, label: String) {
        load(ctx)
        val clean = number.trim().replace(" ", "")
        if (clean.isEmpty()) return
        val p = TrackedPackage(clean, PackageParse.carrierFor(clean), label.trim(), "Added", System.currentTimeMillis())
        save(ctx, packages.value.filterNot { it.number == clean } + p)
    }

    fun remove(ctx: Context, number: String) {
        load(ctx)
        save(ctx, packages.value.filterNot { it.number == number })
    }

    /** Called for every posted notification; records anything that looks like a delivery update. */
    fun observe(ctx: Context, pkg: String, text: String) {
        val settings = runCatching { dev.pridgey.brig.BrigApp.settings.flow.value }.getOrNull() ?: return
        if (!settings.packages.autoDetect) return
        val status = PackageParse.status(text) ?: return
        val hit = PackageParse.detect(pkg, text) ?: return
        load(ctx)
        val number = hit.number ?: "auto:${hit.carrier}:${text.take(40).hashCode()}"
        val existing = packages.value.firstOrNull { it.number == number }
            // Without a number, update the newest undelivered package from the same carrier.
            ?: if (hit.number == null) packages.value.lastOrNull { it.auto && it.carrier == hit.carrier && !it.delivered && it.number.startsWith("auto:") } else null
        val label = existing?.label?.takeIf { it.isNotBlank() } ?: text.substringAfter(" · ", text).take(60)
        val updated = TrackedPackage(
            number = existing?.number ?: number,
            carrier = hit.carrier,
            label = label,
            status = status,
            updatedAt = System.currentTimeMillis(),
            delivered = status == "Delivered",
            auto = existing?.auto ?: true,
        )
        save(ctx, packages.value.filterNot { it.number == updated.number } + updated)
    }

    fun visible(list: List<TrackedPackage>, cfg: PackagesConfig, now: Long = System.currentTimeMillis()): List<TrackedPackage> =
        list.filter { !it.delivered || now - it.updatedAt < cfg.keepDeliveredHours * 3600_000L }
            // Auto-detected packages with no update for two weeks are probably done.
            .filter { !it.auto || now - it.updatedAt < 14 * 24 * 3600_000L }
            .sortedByDescending { it.updatedAt }
}
