package dev.pridgey.brig

import dev.pridgey.brig.data.Airport
import dev.pridgey.brig.data.FlightMath
import dev.pridgey.brig.data.FlightParse
import dev.pridgey.brig.data.FlightPhase
import dev.pridgey.brig.data.FlightRoute
import dev.pridgey.brig.data.PlanePos
import dev.pridgey.brig.util.MapShot
import dev.pridgey.brig.util.Mercator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FlightsTest {
    private val sfo = Airport("SFO", "", "San Francisco", 37.619, -122.375)
    private val ewr = Airport("EWR", "", "Newark", 40.692, -74.169)
    private val route = FlightRoute("UAL1321", "UA1321", "United Airlines", sfo, ewr)
    private fun pos(lat: Double, lon: Double, ground: Boolean = false, at: Long = 0, kt: Double? = 450.0) =
        PlanePos("abc", "UAL1321", lat = lat, lon = lon, onGround = ground, at = at, speedKt = kt)

    @Test
    fun normalizesFlightNumbers() {
        assertEquals("UA1321", FlightParse.normalize("ua 1321"))
        assertEquals("UAL1321", FlightParse.normalize("UAL-1321"))
        assertNull(FlightParse.normalize("hello"))
        assertNull(FlightParse.normalize("1234"))
    }

    @Test
    fun guessesCallsigns() {
        assertEquals("UAL123", FlightParse.guessCallsign("UA0123"))
        assertEquals("JBU5", FlightParse.guessCallsign("B6005"))
        assertEquals("UAL1321", FlightParse.guessCallsign("UAL1321"))
        assertEquals("N12345", FlightParse.guessCallsign("N12345"))
        assertEquals("UA 1321", FlightParse.pretty("UA1321"))
        assertEquals("UAL 1321", FlightParse.pretty("UAL1321"))
    }

    @Test
    fun findsFlightsInCalendarEvents() {
        assertEquals("UA1321", FlightParse.fromEvent("Flight to Denver (UA 1321)"))
        assertEquals("DL42", FlightParse.fromEvent("DL42 to Atlanta"))
        assertEquals("B6123", FlightParse.fromEvent("✈ B6 123 JFK-BOS"))
        assertNull(FlightParse.fromEvent("Standup at 10 AM 2026"))
        assertNull(FlightParse.fromEvent("Q4 2026 planning"))
        assertNull(FlightParse.fromEvent("Room B2 100"))
    }

    @Test
    fun parsesAdsbAndRoutes() {
        val ac = Json.parseToJsonElement(
            """{"hex":"a11fa3","flight":"UAL1321 ","r":"N17128","t":"B752","alt_baro":16350,"gs":361.6,"track":266.8,"baro_rate":1984,"lat":40.8,"lon":-74.6,"seen_pos":2.0}""",
        )
        val p = FlightParse.plane(ac, 10_000)!!
        assertEquals("UAL1321", p.callsign)
        assertEquals(16350, p.altFt)
        assertEquals(8_000L, p.at)
        val ground = FlightParse.plane(Json.parseToJsonElement("""{"hex":"x","alt_baro":"ground","lat":1,"lon":2}"""), 0)!!
        assertTrue(ground.onGround)
        assertNull(ground.altFt)

        val r = FlightParse.route(
            Json.parseToJsonElement(
                """{"response":{"flightroute":{"callsign":"UA1321","callsign_icao":"UAL1321","callsign_iata":"UA1321","airline":{"name":"United Airlines"},
                "origin":{"iata_code":"MIA","latitude":25.79,"longitude":-80.29,"municipality":"Miami","name":"Miami Intl"},
                "destination":{"iata_code":"EWR","latitude":40.69,"longitude":-74.17,"municipality":"Newark","name":"Newark"}}}}""",
            ),
        )!!
        assertEquals("UAL1321", r.callsign)
        assertEquals("MIA", r.origin?.iata)
        assertNull(FlightParse.route(Json.parseToJsonElement("""{"response":"unknown callsign"}""")))
    }

    @Test
    fun progressAndTimeLeft() {
        assertEquals(0f, FlightMath.progress(route, pos(sfo.lat, sfo.lon))!!, 0.01f)
        val mid = FlightMath.greatCircle(sfo.lat, sfo.lon, ewr.lat, ewr.lon, 2)[1]
        assertEquals(0.5f, FlightMath.progress(route, pos(mid.first, mid.second))!!, 0.01f)
        val left = FlightMath.minutesLeft(route, pos(mid.first, mid.second))!!
        // About 1,100 nmi to go at 450 kt.
        assertTrue(left in 140..170)
        assertNull(FlightMath.minutesLeft(route, pos(mid.first, mid.second, kt = 20.0)))
    }

    @Test
    fun phases() {
        val now = 10 * 3600_000L
        assertEquals(FlightPhase.SCHEDULED, FlightMath.phase(null, null, ewr, now))
        assertEquals(FlightPhase.AIRBORNE, FlightMath.phase(pos(39.0, -100.0), null, ewr, now))
        assertEquals(FlightPhase.TAXIING, FlightMath.phase(pos(sfo.lat, sfo.lon, ground = true), null, ewr, now))
        assertEquals(FlightPhase.LANDED, FlightMath.phase(pos(ewr.lat, ewr.lon, ground = true), null, ewr, now))
        // Vanished near the destination: landed. Vanished mid-flight recently: out of range.
        assertEquals(FlightPhase.LANDED, FlightMath.phase(null, pos(40.75, -74.3, at = now - 10 * 60_000L), ewr, now))
        assertEquals(FlightPhase.OUT_OF_RANGE, FlightMath.phase(null, pos(39.0, -100.0, at = now - 10 * 60_000L), ewr, now))
        assertEquals(FlightPhase.SCHEDULED, FlightMath.phase(null, pos(39.0, -100.0, at = now - 5 * 3600_000L), ewr, now))
    }

    @Test
    fun mapFitsTheRoute() {
        val (z, _, _) = Mercator.fit(listOf(sfo.lat to sfo.lon, ewr.lat to ewr.lon), 1024, 440, 2, 9)
        assertTrue(z in 3..4)
        val shot = MapShot.blank(listOf(sfo.lat to sfo.lon, ewr.lat to ewr.lon))
        listOf(sfo, ewr).forEach { a ->
            val (x, y) = shot.project(a.lat, a.lon)
            assertTrue("${a.iata} x=$x", x in 0f..1024f)
            assertTrue("${a.iata} y=$y", y in 0f..440f)
        }
    }

    @Test
    fun mapHandlesTheDateLine() {
        // Tokyo to Honolulu crosses 180°.
        val shot = MapShot.blank(listOf(35.55 to 139.78, 21.32 to -157.92))
        val (x1, _) = shot.project(35.55, 139.78)
        val (x2, _) = shot.project(21.32, -157.92)
        assertTrue(x1 in 0f..1024f && x2 in 0f..1024f)
        assertTrue(x2 > x1)
    }

    @Test
    fun greatCircleEndsAtBothAirports() {
        val pts = FlightMath.greatCircle(sfo.lat, sfo.lon, ewr.lat, ewr.lon)
        assertTrue(abs(pts.first().first - sfo.lat) < 1e-6 && abs(pts.last().second - ewr.lon) < 1e-6)
        // The great circle bows north of the straight line.
        assertTrue(pts[pts.size / 2].first > (sfo.lat + ewr.lat) / 2)
        assertNotNull(FlightMath.compass(350.0))
        assertEquals("N", FlightMath.compass(350.0))
        assertEquals("SW", FlightMath.compass(225.0))
    }
}
