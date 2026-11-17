package dev.pridgey.brig.data

import android.content.Context
import dev.pridgey.brig.util.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val flightJson = Json { ignoreUnknownKeys = true }

// ---------------------------------------------------------------- Model

/** A flight the user follows. [code] is normalized, like "UA1321" or "UAL1321". */
@Serializable
data class TrackedFlight(
    val code: String,
    val label: String = "",
    val addedAt: Long = 0,
    /** When Brig first saw it landed; it's dropped a few hours later. */
    val landedAt: Long = 0,
    /** Airport codes the user typed (IATA or ICAO). They override any looked-up route. */
    val from: String = "",
    val to: String = "",
)

@Serializable
data class Airport(val iata: String, val name: String, val city: String, val lat: Double, val lon: Double)

@Serializable
data class FlightRoute(
    /** ICAO callsign, which is what aircraft broadcast ("UAL1321"). */
    val callsign: String,
    /** Airline-style flight number ("UA1321"), if known. */
    val iata: String,
    val airline: String,
    val origin: Airport?,
    val dest: Airport?,
)

/** One position report. Altitude in feet, speed in knots, vertical rate in feet per minute. */
@Serializable
data class PlanePos(
    val hex: String,
    val callsign: String,
    val reg: String = "",
    val type: String = "",
    val lat: Double,
    val lon: Double,
    val altFt: Int? = null,
    val onGround: Boolean = false,
    val speedKt: Double? = null,
    val track: Double? = null,
    val vRate: Int? = null,
    val at: Long = 0,
    /** Point queries only: distance (nautical miles) and bearing from the query point. */
    val distNm: Double? = null,
    val dirDeg: Double? = null,
)

enum class FlightPhase(val label: String) {
    SCHEDULED("Not departed"),
    TAXIING("On the ground"),
    AIRBORNE("In the air"),
    OUT_OF_RANGE("Out of range"),
    LANDED("Landed"),
    OVERHEAD("Overhead"),
}

/** Everything the widget draws for one flight. */
data class FlightView(
    val code: String,
    val label: String,
    val route: FlightRoute?,
    val pos: PlanePos?,
    val phase: FlightPhase,
    val aircraft: String? = null,
    /** Scheduled departure and arrival, from a calendar event, if the flight came from one. */
    val departs: Long? = null,
    val arrives: Long? = null,
    /** When the plane actually took off and landed, as tracked by OpenSky. */
    val tookOff: Long? = null,
    val landedTime: Long? = null,
    /** Overhead mode: where the user is. */
    val home: Pair<Double, Double>? = null,
) {
    val callsign get() = pos?.callsign?.takeIf { it.isNotBlank() } ?: route?.callsign ?: code
    val title get() = route?.iata?.takeIf { it.isNotBlank() }?.let(FlightParse::pretty) ?: FlightParse.pretty(callsign)
}

enum class FlightTracker(val label: String) {
    FLIGHTAWARE("FlightAware"),
    FLIGHTRADAR24("Flightradar24"),
    ADSB_LOL("adsb.lol live map"),
}

/** OpenStreetMap tiles, recolored on the phone (OSM only publishes the one colorful style). */
enum class FlightMapStyle(val label: String, val background: Long) {
    DARK("Dark", 0xFF1B1E22),
    LIGHT("Light", 0xFFF2F2F0),
    COLOR("Color", 0xFFF2EFE9),
}

enum class FlightMapView(val label: String) {
    ROUTE("Whole route"),
    CLOSE("Close-up"),
}

@Serializable
data class FlightsConfig(
    val tracker: FlightTracker = FlightTracker.FLIGHTAWARE,
    val showMap: Boolean = true,
    val mapStyle: FlightMapStyle = FlightMapStyle.DARK,
    val mapView: FlightMapView = FlightMapView.ROUTE,
    /** Follow flights found in calendar events ("Flight to Denver (UA 1321)"). */
    val fromCalendar: Boolean = true,
    /** With no flight to follow, show the plane closest overhead. */
    val overhead: Boolean = false,
    /** Overhead search radius in nautical miles. */
    val overheadRadius: Int = 15,
)

// ---------------------------------------------------------------- Parsing (pure, tested)

object FlightParse {
    /** Common airline codes, for when the route lookup doesn't know a flight. IATA to ICAO. */
    private val airlines = mapOf(
        "AA" to "AAL", "UA" to "UAL", "DL" to "DAL", "WN" to "SWA", "B6" to "JBU", "AS" to "ASA", "NK" to "NKS",
        "F9" to "FFT", "G4" to "AAY", "HA" to "HAL", "SY" to "SCX", "MX" to "MXY", "MQ" to "ENY", "OO" to "SKW",
        "YX" to "RPA", "9E" to "EDV", "QX" to "QXE", "AC" to "ACA", "WS" to "WJA", "AM" to "AMX", "BA" to "BAW",
        "LH" to "DLH", "AF" to "AFR", "KL" to "KLM", "IB" to "IBE", "VS" to "VIR", "EI" to "EIN", "FR" to "RYR",
        "U2" to "EZY", "TK" to "THY", "LX" to "SWR", "OS" to "AUA", "SK" to "SAS", "AY" to "FIN", "TP" to "TAP",
        "EK" to "UAE", "QR" to "QTR", "EY" to "ETD", "SQ" to "SIA", "CX" to "CPA", "QF" to "QFA", "NZ" to "ANZ",
        "JL" to "JAL", "NH" to "ANA", "KE" to "KAL", "OZ" to "AAR", "LA" to "LAN", "AV" to "AVA", "CM" to "CMP",
    )

    /** "ua 1321" → "UA1321". Null when it can't be a flight number or callsign. */
    fun normalize(raw: String): String? {
        val s = raw.uppercase().replace(Regex("""[\s\-.]"""), "")
        if (!Regex("""^[A-Z0-9]{3,8}$""").matches(s) || s.none { it.isDigit() } || s.none { it.isLetter() }) return null
        return s
    }

    /** Best guess at the broadcast callsign: "UA0123" → "UAL123". Anything else is used as is. */
    fun guessCallsign(code: String): String {
        val m = Regex("""^([A-Z][A-Z0-9]|[0-9][A-Z])(\d{1,4}[A-Z]?)$""").matchEntire(code) ?: return code
        val icao = airlines[m.groupValues[1]] ?: return code
        return icao + m.groupValues[2].trimStart('0').ifEmpty { "0" }
    }

    /** "UA1321" → "UA 1321", "UAL1321" → "UAL 1321". */
    fun pretty(code: String): String =
        Regex("""^([A-Z]{3}|[A-Z][A-Z0-9]|[0-9][A-Z])(\d{1,4}[A-Z]?)$""").matchEntire(code)
            ?.let { "${it.groupValues[1]} ${it.groupValues[2]}" } ?: code

    private val inText = Regex("""\b([A-Z][A-Z0-9]|[0-9][A-Z])\s?(\d{1,4})\b""")

    /**
     * A flight number from a calendar event, like Google's "Flight to Denver (UA 1321)". Only
     * trusted when the event mentions a flight or the code is a known airline written without a space.
     */
    fun fromEvent(title: String, location: String = ""): String? {
        val text = "$title $location"
        val flighty = Regex("""(?i)\bflight\b|✈""").containsMatchIn(text)
        return inText.findAll(text).firstNotNullOfOrNull { m ->
            val prefix = m.groupValues[1]
            val spaced = m.value.contains(' ')
            when {
                prefix in setOf("AM", "PM") && spaced -> null
                flighty -> prefix + m.groupValues[2]
                !spaced && prefix in airlines -> prefix + m.groupValues[2]
                else -> null
            }
        }
    }

    /** A plane from an adsb.lol (readsb) "ac" entry. */
    fun plane(ac: JsonElement, now: Long): PlanePos? {
        val lat = ac.num("lat") ?: return null
        val lon = ac.num("lon") ?: return null
        val altRaw = runCatching { ((ac as? kotlinx.serialization.json.JsonObject)?.get("alt_baro") as? JsonPrimitive)?.content }.getOrNull()
        val seen = ac.num("seen_pos") ?: 0.0
        return PlanePos(
            hex = ac.str("hex").orEmpty(),
            callsign = ac.str("flight").orEmpty().trim(),
            reg = ac.str("r").orEmpty(),
            type = ac.str("t").orEmpty(),
            lat = lat, lon = lon,
            altFt = altRaw?.toDoubleOrNull()?.roundToInt(),
            onGround = altRaw == "ground",
            speedKt = ac.num("gs"),
            track = ac.num("track") ?: ac.num("true_heading"),
            vRate = (ac.num("baro_rate") ?: ac.num("geom_rate"))?.roundToInt(),
            at = now - (seen * 1000).toLong(),
            distNm = ac.num("dst"),
            dirDeg = ac.num("dir"),
        )
    }

    private fun airport(o: JsonElement?): Airport? {
        val lat = o.num("latitude") ?: return null
        val lon = o.num("longitude") ?: return null
        return Airport(
            iata = o.str("iata_code") ?: o.str("icao_code") ?: "",
            name = o.str("name").orEmpty(),
            city = o.str("municipality").orEmpty(),
            lat = lat, lon = lon,
        )
    }

    /** "LAX DEN", "lax-den", "KLAX → KDEN" → ("LAX", "DEN"). Null unless it's two airport codes. */
    fun routeCodes(raw: String): Pair<String, String>? {
        val parts = raw.uppercase().split(Regex("""[^A-Z0-9]+""")).filter { it.isNotEmpty() }
        if (parts.size != 2 || parts.any { it.length !in 3..4 || it.none(Char::isLetter) }) return null
        return parts[0] to parts[1]
    }

    /** An airport from hexdb's /api/v1/airport response. */
    fun hexdbAirport(o: JsonElement): Airport? {
        val lat = o.num("latitude") ?: return null
        val lon = o.num("longitude") ?: return null
        val name = o.str("airport").orEmpty()
        return Airport(
            iata = o.str("iata")?.takeIf { it.isNotBlank() } ?: o.str("icao").orEmpty(),
            name = name,
            city = name.replace(Regex("""(?i)\s*(international|intl\.?|regional|municipal)?\s*airport\s*$"""), "").trim(),
            lat = lat, lon = lon,
        )
    }

    /** One trip as OpenSky tracked it: airport ICAO codes and times (ms). */
    data class OpenSkyLeg(val dep: String?, val arr: String?, val firstSeen: Long?, val lastSeen: Long?)

    /**
     * Where [callsign]'s current trip really left from, and when (and where and when it landed,
     * once it has), from OpenSky's flight list. Times are only kept when the matching airport is
     * known; otherwise the plane was first or last seen mid-air, out of receiver range.
     */
    fun openSkyLeg(root: JsonElement, callsign: String): OpenSkyLeg? {
        val legs = (root as? kotlinx.serialization.json.JsonArray).orEmpty()
            .filter { it.str("callsign")?.trim().equals(callsign, ignoreCase = true) }
        val leg = legs.maxByOrNull { it.num("firstSeen") ?: 0.0 } ?: return null
        val dep = leg.str("estDepartureAirport")
        val arr = leg.str("estArrivalAirport")
        return OpenSkyLeg(
            dep, arr,
            firstSeen = leg.num("firstSeen")?.takeIf { dep != null }?.let { (it * 1000).toLong() },
            lastSeen = leg.num("lastSeen")?.takeIf { arr != null }?.let { (it * 1000).toLong() },
        )
    }

    /** A route from adsbdb's /v0/callsign response, or null for "unknown callsign". */
    fun route(root: JsonElement): FlightRoute? {
        val r = root.obj("response").obj("flightroute") ?: return null
        return FlightRoute(
            callsign = r.str("callsign_icao") ?: r.str("callsign") ?: return null,
            iata = r.str("callsign_iata").orEmpty(),
            airline = r.obj("airline").str("name").orEmpty(),
            origin = airport(r.obj("origin")),
            dest = airport(r.obj("destination")),
        )
    }
}

// ---------------------------------------------------------------- Geometry (pure, tested)

object FlightMath {
    private const val EARTH_NM = 3440.065
    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI

    fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = rad(lat2 - lat1)
        val dLon = rad(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } + cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_NM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** [n] points along the great circle from A to B, inclusive. */
    fun greatCircle(lat1: Double, lon1: Double, lat2: Double, lon2: Double, n: Int = 48): List<Pair<Double, Double>> {
        val p1 = rad(lat1); val l1 = rad(lon1); val p2 = rad(lat2); val l2 = rad(lon2)
        val d = 2 * asin(sqrt((sin((p2 - p1) / 2).let { it * it } + cos(p1) * cos(p2) * sin((l2 - l1) / 2).let { it * it }).coerceIn(0.0, 1.0)))
        if (d < 1e-9) return listOf(lat1 to lon1, lat2 to lon2)
        return (0..n).map { i ->
            val f = i.toDouble() / n
            val a = sin((1 - f) * d) / sin(d)
            val b = sin(f * d) / sin(d)
            val x = a * cos(p1) * cos(l1) + b * cos(p2) * cos(l2)
            val y = a * cos(p1) * sin(l1) + b * cos(p2) * sin(l2)
            val z = a * sin(p1) + b * sin(p2)
            deg(atan2(z, sqrt(x * x + y * y))) to deg(atan2(y, x))
        }
    }

    /** Initial great-circle bearing from A to B, degrees from north. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1); val p2 = rad(lat2); val dl = rad(lon2 - lon1)
        return (deg(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl))) + 360) % 360
    }

    private fun angleBetween(a: Double, b: Double) = ((a - b) % 360 + 540) % 360 - 180

    /**
     * Whether [p] could be flying from [o] to [d]: not far off the direct path, and (in cruise)
     * heading roughly toward [d]. Route databases keep one route per flight number, but airlines
     * reuse numbers for several legs, so the stored route is often a different leg.
     */
    fun plausible(o: Airport, d: Airport, p: PlanePos): Boolean {
        val total = distanceNm(o.lat, o.lon, d.lat, d.lon)
        val done = distanceNm(o.lat, o.lon, p.lat, p.lon)
        val left = distanceNm(p.lat, p.lon, d.lat, d.lon)
        if (done + left > total * 1.15 + 50) return false
        val track = p.track
        if (!p.onGround && track != null && done > 40 && left > 40) {
            if (kotlin.math.abs(angleBetween(track, bearing(p.lat, p.lon, d.lat, d.lon))) > 70) return false
        }
        return true
    }

    private fun same(a: Airport?, b: Airport?) = a != null && b != null &&
        (a.iata.equals(b.iata, true) || distanceNm(a.lat, a.lon, b.lat, b.lon) < 3)

    /**
     * The route to show. [origin] (and [arrived], after landing) are where the plane really went,
     * from its tracked path; the stored route only supplies the destination when it agrees.
     */
    fun resolveRoute(stored: FlightRoute?, origin: Airport?, arrived: Airport?, p: PlanePos?): FlightRoute? {
        if (origin == null) return checkRoute(stored, p)
        val base = stored ?: FlightRoute(p?.callsign.orEmpty(), "", "", null, null)
        val dest = arrived ?: when {
            same(stored?.origin, origin) -> stored?.dest
            same(stored?.dest, origin) -> stored?.origin
            else -> null
        }?.takeIf { d -> p == null || plausible(origin, d, p) }
        return base.copy(origin = origin, dest = dest)
    }

    /** [r] as stored, reversed (the return leg), or without airports when neither fits [p]. */
    fun checkRoute(r: FlightRoute?, p: PlanePos?): FlightRoute? {
        val o = r?.origin ?: return r
        val d = r.dest ?: return r
        if (p == null || plausible(o, d, p)) return r
        if (plausible(d, o, p)) return r.copy(origin = d, dest = o)
        return r.copy(origin = null, dest = null)
    }

    /** How far along the trip the plane is, 0..1, or null without both airports. */
    fun progress(route: FlightRoute?, pos: PlanePos?): Float? {
        val o = route?.origin ?: return null
        val d = route.dest ?: return null
        val p = pos ?: return null
        val done = distanceNm(o.lat, o.lon, p.lat, p.lon)
        val left = distanceNm(p.lat, p.lon, d.lat, d.lon)
        if (done + left < 1) return null
        return (done / (done + left)).toFloat().coerceIn(0f, 1f)
    }

    /** Rough minutes until landing from distance and ground speed, plus a little for the approach. */
    fun minutesLeft(route: FlightRoute?, pos: PlanePos?): Int? {
        val d = route?.dest ?: return null
        val p = pos ?: return null
        val kt = p.speedKt?.takeIf { it > 100 } ?: return null
        val nm = distanceNm(p.lat, p.lon, d.lat, d.lon)
        return (nm / kt * 60 + if (nm > 40) 8 else 3).roundToInt()
    }

    /** Where a flight is, from the latest report and the one before it. */
    fun phase(pos: PlanePos?, last: PlanePos?, dest: Airport?, now: Long): FlightPhase {
        fun nearDest(p: PlanePos) = dest != null && distanceNm(p.lat, p.lon, dest.lat, dest.lon) < 25
        if (pos != null) {
            return when {
                !pos.onGround -> FlightPhase.AIRBORNE
                nearDest(pos) -> FlightPhase.LANDED
                else -> FlightPhase.TAXIING
            }
        }
        if (last == null) return FlightPhase.SCHEDULED
        return when {
            nearDest(last) && now - last.at < 6 * 3600_000L -> FlightPhase.LANDED
            !last.onGround && now - last.at < 90 * 60_000L -> FlightPhase.OUT_OF_RANGE
            else -> FlightPhase.SCHEDULED
        }
    }

    fun altitude(ft: Int, imperial: Boolean): String =
        if (imperial) "%,d ft".format(ft) else "%,d m".format((ft * 0.3048).roundToInt())

    fun speed(kt: Double, imperial: Boolean): String =
        if (imperial) "${(kt * 1.15078).roundToInt()} mph" else "${(kt * 1.852).roundToInt()} km/h"

    fun distance(nm: Double, imperial: Boolean): String =
        if (imperial) "${(nm * 1.15078).roundToInt()} mi" else "${(nm * 1.852).roundToInt()} km"

    fun compass(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]

    fun duration(mins: Int): String = if (mins < 60) "$mins min" else "${mins / 60}h ${mins % 60}m"
}

// ---------------------------------------------------------------- Repository

/**
 * Flights the user follows, plus live data: positions from adsb.lol, routes and aircraft from
 * adsbdb. Both are free, community-run and need no key.
 */
object FlightRepo {
    val flights = MutableStateFlow<List<TrackedFlight>>(emptyList())
    /** The last thing the widget showed, so it appears straight away next time. */
    val lastViews = MutableStateFlow<List<FlightView>>(emptyList())
    private var loaded = false
    private val listSer = ListSerializer(TrackedFlight.serializer())
    private val lastSer = MapSerializer(String.serializer(), PlanePos.serializer())

    private data class Timed<T>(val at: Long, val value: T)
    private val routes = HashMap<String, Timed<FlightRoute?>>()
    private val positions = HashMap<String, Timed<PlanePos?>>()
    private val aircraft = HashMap<String, String?>()
    private var overheadCache: Timed<FlightView?>? = null
    private val airports = HashMap<String, Airport?>()
    private val legs = HashMap<String, Timed<FlightParse.OpenSkyLeg?>>()

    /** A trip as flown: real airports and times, any of which may still be unknown. */
    private data class Leg(val dep: Airport?, val arr: Airport?, val tookOff: Long?, val landed: Long?)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("flights", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        flights.value = prefs(ctx).getString("list", null)
            ?.let { runCatching { flightJson.decodeFromString(listSer, it) }.getOrNull() }.orEmpty()
    }

    private fun save(ctx: Context, list: List<TrackedFlight>) {
        flights.value = list
        prefs(ctx).edit().putString("list", flightJson.encodeToString(listSer, list)).apply()
    }

    /** Returns the normalized code, or null if [raw] isn't a flight number. */
    fun add(ctx: Context, raw: String, label: String, routeText: String = ""): String? {
        load(ctx)
        val code = FlightParse.normalize(raw) ?: return null
        val (from, to) = FlightParse.routeCodes(routeText) ?: ("" to "")
        save(ctx, flights.value.filterNot { it.code == code } + TrackedFlight(code, label.trim(), System.currentTimeMillis(), from = from, to = to))
        return code
    }

    fun remove(ctx: Context, code: String) {
        load(ctx)
        save(ctx, flights.value.filterNot { it.code == code })
    }

    /** Hand-added flights still worth showing: landed ones go after 3 hours, forgotten ones after 4 days. */
    fun visible(list: List<TrackedFlight>, now: Long = System.currentTimeMillis()): List<TrackedFlight> =
        list.filter { (it.landedAt == 0L || now - it.landedAt < 3 * 3600_000L) && now - it.addedAt < 4 * 24 * 3600_000L }

    private fun lastSeen(ctx: Context): Map<String, PlanePos> =
        prefs(ctx).getString("last", null)?.let { runCatching { flightJson.decodeFromString(lastSer, it) }.getOrNull() }.orEmpty()

    private fun saveLastSeen(ctx: Context, map: Map<String, PlanePos>) {
        val fresh = map.filterValues { System.currentTimeMillis() - it.at < 12 * 3600_000L }
        prefs(ctx).edit().putString("last", flightJson.encodeToString(lastSer, fresh)).apply()
    }

    suspend fun route(code: String): FlightRoute? {
        val now = System.currentTimeMillis()
        routes[code]?.takeIf { now - it.at < (if (it.value == null) 30 else 12 * 60) * 60_000L }?.let { return it.value }
        val r = runCatching {
            FlightParse.route(flightJson.parseToJsonElement(Http.get("https://api.adsbdb.com/v0/callsign/${enc(code)}")))
        }
        // Don't remember network failures, only real answers.
        r.onSuccess { routes[code] = Timed(now, it) }
        return r.getOrNull() ?: routes[code]?.value
    }

    private suspend fun position(callsign: String): PlanePos? {
        val now = System.currentTimeMillis()
        positions[callsign]?.takeIf { now - it.at < 90_000L }?.let { return it.value }
        val r = runCatching {
            val root = flightJson.parseToJsonElement(Http.get("https://api.adsb.lol/v2/callsign/${enc(callsign)}"))
            root.arr("ac").orEmpty().mapNotNull { FlightParse.plane(it, now) }.maxByOrNull { it.at }
        }
        r.onSuccess { positions[callsign] = Timed(now, it) }
        return r.getOrElse { positions[callsign]?.value }
    }

    /** "Boeing 737-800"-ish, from the aircraft's transponder address. */
    private suspend fun aircraftName(hex: String): String? {
        if (hex.isBlank()) return null
        if (hex in aircraft) return aircraft[hex]
        val r = runCatching {
            val a = flightJson.parseToJsonElement(Http.get("https://api.adsbdb.com/v0/aircraft/${enc(hex)}")).obj("response").obj("aircraft")
            listOfNotNull(a.str("manufacturer"), a.str("type")).joinToString(" ").ifBlank { null }
        }
        r.onSuccess { aircraft[hex] = it }
        return r.getOrNull()
    }

    /** An airport by IATA (3 letters) or ICAO (4) code. */
    suspend fun airport(code: String): Airport? {
        val c = code.uppercase().trim()
        if (c.isEmpty()) return null
        if (c in airports) return airports[c]
        val kind = if (c.length == 4) "icao" else "iata"
        val r = runCatching { FlightParse.hexdbAirport(flightJson.parseToJsonElement(Http.get("https://hexdb.io/api/v1/airport/$kind/${enc(c)}"))) }
        r.onSuccess { airports[c] = it }
        return r.getOrNull()
    }

    /**
     * Where this aircraft's current trip took off (and, once down, landed), from OpenSky. Route
     * databases often hold a different leg of the same flight number, so this is what's trusted.
     */
    private suspend fun leg(hex: String, callsign: String): Leg? {
        if (hex.isBlank()) return null
        val key = "$hex/$callsign"
        val now = System.currentTimeMillis()
        val cached = legs[key]?.takeIf { e ->
            // Once the departure is known it doesn't change; re-ask now and then for the arrival.
            now - e.at < if (e.value?.dep == null) 10 * 60_000L else if (e.value.arr == null) 20 * 60_000L else 12 * 3600_000L
        }
        val l = cached?.value ?: run {
            val end = now / 1000
            val r = runCatching {
                FlightParse.openSkyLeg(
                    flightJson.parseToJsonElement(Http.get("https://opensky-network.org/api/flights/aircraft?icao24=${enc(hex.lowercase())}&begin=${end - 18 * 3600}&end=$end")),
                    callsign,
                )
            }
            r.onSuccess { legs[key] = Timed(now, it) }
            r.getOrNull() ?: legs[key]?.value
        } ?: return null
        return Leg(l.dep?.let { airport(it) }, l.arr?.let { airport(it) }, l.firstSeen, l.lastSeen)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** A flight to show, from the user's list or a calendar event. */
    data class Active(
        val code: String, val label: String, val departs: Long? = null, val arrives: Long? = null,
        val manual: Boolean = true, val from: String = "", val to: String = "",
    )

    fun active(ctx: Context, cfg: FlightsConfig, now: Long = System.currentTimeMillis()): List<Active> {
        load(ctx)
        val manual = visible(flights.value, now).sortedByDescending { it.addedAt }.map { Active(it.code, it.label, from = it.from, to = it.to) }
        val fromCal = if (!cfg.fromCalendar) emptyList() else CalendarRepo.upcoming(ctx, limit = 30, lookaheadHours = 24, includeAllDay = false)
            // From three hours before departure until the event ends.
            .filter { it.begin - 3 * 3600_000L <= now && now <= it.end + 30 * 60_000L }
            .mapNotNull { e -> FlightParse.fromEvent(e.title, e.location)?.let { Active(it, e.title, e.begin, e.end, manual = false) } }
        return (manual + fromCal).distinctBy { FlightParse.guessCallsign(it.code) }
    }

    suspend fun view(ctx: Context, a: Active): FlightView {
        val stored = route(a.code)
        val callsign = stored?.callsign ?: FlightParse.guessCallsign(a.code)
        val pos = position(callsign)
        val seen = lastSeen(ctx)
        val last = seen[a.code]
        val p = pos ?: last
        val l = p?.let { leg(it.hex, it.callsign.ifBlank { callsign }) }
        val route = if (a.from.isNotBlank() && a.to.isNotBlank()) {
            (stored ?: FlightRoute(callsign, a.code, "", null, null)).copy(origin = airport(a.from), dest = airport(a.to))
        } else {
            FlightMath.resolveRoute(stored, l?.dep, l?.arr, p)
        }
        val phase = FlightMath.phase(pos, last, route?.dest, System.currentTimeMillis())
        if (pos != null) saveLastSeen(ctx, seen + (a.code to pos))
        if (phase == FlightPhase.LANDED && a.manual) {
            flights.value.firstOrNull { it.code == a.code && it.landedAt == 0L }?.let { f ->
                save(ctx, flights.value.map { if (it.code == f.code) it.copy(landedAt = System.currentTimeMillis()) else it })
            }
        }
        // Out of range: keep showing where it was last seen.
        val shown = pos ?: last?.takeIf { phase == FlightPhase.OUT_OF_RANGE || phase == FlightPhase.LANDED }
        return FlightView(
            a.code, a.label, route, shown, phase, shown?.hex?.let { aircraftName(it) }, a.departs, a.arrives,
            tookOff = l?.tookOff, landedTime = l?.landed?.takeIf { phase == FlightPhase.LANDED },
        )
    }

    /** The airborne plane closest to [lat], [lon], with its route if known. */
    suspend fun overhead(lat: Double, lon: Double, radiusNm: Int): FlightView? {
        val now = System.currentTimeMillis()
        overheadCache?.takeIf { now - it.at < 90_000L }?.let { return it.value }
        // Rounded so the request doesn't carry a precise location.
        val la = "%.2f".format(java.util.Locale.US, lat)
        val lo = "%.2f".format(java.util.Locale.US, lon)
        val r = runCatching {
            val root = flightJson.parseToJsonElement(Http.get("https://api.adsb.lol/v2/point/$la/$lo/${radiusNm.coerceIn(5, 100)}"))
            val pos = root.arr("ac").orEmpty().mapNotNull { FlightParse.plane(it, now) }
                .filter { !it.onGround && it.callsign.isNotBlank() }
                .minByOrNull { it.distNm ?: FlightMath.distanceNm(lat, lon, it.lat, it.lon) } ?: return@runCatching null
            val l = leg(pos.hex, pos.callsign)
            val route = FlightMath.resolveRoute(route(pos.callsign), l?.dep, l?.arr, pos)
            FlightView(pos.callsign, "", route, pos, FlightPhase.OVERHEAD, aircraftName(pos.hex), home = lat to lon, tookOff = l?.tookOff)
        }
        r.onSuccess { overheadCache = Timed(now, it) }
        return r.getOrElse { overheadCache?.value }
    }

    /** What the widget shows right now: followed flights, or the plane overhead. */
    suspend fun snapshot(ctx: Context, settings: BrigSettings): List<FlightView> {
        val cfg = settings.flights
        val active = active(ctx, cfg)
        val views = if (active.isNotEmpty()) {
            // The flight in the air gets the map; the rest keep their order (newest first).
            active.map { view(ctx, it) }.sortedBy { phaseRank(it.phase) }
        } else if (cfg.overhead && settings.weather.lat != null && settings.weather.lon != null) {
            listOfNotNull(overhead(settings.weather.lat, settings.weather.lon, cfg.overheadRadius))
        } else emptyList()
        lastViews.value = views
        return views
    }

    private fun phaseRank(p: FlightPhase) = when (p) {
        FlightPhase.AIRBORNE, FlightPhase.OUT_OF_RANGE -> 0
        FlightPhase.TAXIING -> 1
        FlightPhase.SCHEDULED -> 2
        else -> 3
    }

    /** The page tapping a flight opens. */
    fun trackerUrl(v: FlightView, tracker: FlightTracker): String {
        val cs = enc(v.callsign)
        val hex = v.pos?.hex?.takeIf { it.isNotBlank() }
        return when {
            tracker == FlightTracker.ADSB_LOL && hex != null -> "https://adsb.lol/?icao=${enc(hex)}"
            tracker == FlightTracker.FLIGHTRADAR24 -> v.route?.iata?.takeIf { it.isNotBlank() }
                ?.let { "https://www.flightradar24.com/data/flights/${enc(it.lowercase())}" }
                ?: "https://www.flightradar24.com/$cs"
            else -> "https://www.flightaware.com/live/flight/$cs"
        }
    }
}
