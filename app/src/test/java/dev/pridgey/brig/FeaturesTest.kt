package dev.pridgey.brig

import dev.pridgey.brig.data.AirRepo
import dev.pridgey.brig.data.AppNotificationMode
import dev.pridgey.brig.data.ClockShare
import dev.pridgey.brig.data.CustomClock
import dev.pridgey.brig.data.GameState
import dev.pridgey.brig.data.Memories
import dev.pridgey.brig.data.PackageParse
import dev.pridgey.brig.data.PackageRepo
import dev.pridgey.brig.data.PackagesConfig
import dev.pridgey.brig.data.Preset
import dev.pridgey.brig.data.Profile
import dev.pridgey.brig.data.ProfileEngine
import dev.pridgey.brig.data.SettingsRepository
import dev.pridgey.brig.data.SportsRepo
import dev.pridgey.brig.data.Sun
import dev.pridgey.brig.data.SunTimes
import dev.pridgey.brig.data.TrackedPackage
import dev.pridgey.brig.data.TriggerType
import dev.pridgey.brig.data.WeatherDetailRepo
import dev.pridgey.brig.data.WidgetType
import dev.pridgey.brig.lock.Ambient
import dev.pridgey.brig.lock.WeatherFx
import dev.pridgey.brig.lock.clockSpan
import dev.pridgey.brig.lock.formatLayer
import dev.pridgey.brig.lock.untilLabel
import dev.pridgey.brig.lock.weatherFxFor
import dev.pridgey.brig.service.BrigNotification
import dev.pridgey.brig.service.ChatMessage
import dev.pridgey.brig.service.NotifPriority
import dev.pridgey.brig.service.NotifTier
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class FeaturesTest {
    private fun n(key: String, pkg: String, t: Long, silent: Boolean = false, category: String? = null, messages: Boolean = false) = BrigNotification(
        key, pkg, pkg, "t", "x", t, null, null, null, null, silent = silent, clearable = true, category = category,
        messages = if (messages) listOf(ChatMessage("Sam", "hi", t, false)) else emptyList(),
    )

    // ---------------------------------------------------------------- Notifications

    @Test
    fun smartPriorityPutsPeopleFirstAndPromosInQuiet() {
        val list = listOf(
            n("promo", "shop", 50, category = "promo"),
            n("news", "news", 40),
            n("chat", "chat", 10, messages = true),
            n("low", "weather", 30, silent = true),
            n("call", "phone", 5, category = "missed_call"),
        )
        assertEquals(NotifTier.PEOPLE, NotifPriority.tier(list[2], null))
        assertEquals(NotifTier.QUIET, NotifPriority.tier(list[0], null))
        assertEquals(NotifTier.NORMAL, NotifPriority.tier(list[1], null))
        // Per-app overrides win.
        assertEquals(NotifTier.PEOPLE, NotifPriority.tier(list[1], AppNotificationMode.PRIORITY))
        assertEquals(NotifTier.QUIET, NotifPriority.tier(list[2], AppNotificationMode.QUIET))

        val (main, quiet) = NotifPriority.arrange(list, emptyMap(), byApp = true, smart = true)
        assertEquals(listOf("chat", "phone", "news"), main.map { it.pkg })
        assertEquals(listOf("shop", "weather"), quiet.map { it.pkg })

        val (plain, none) = NotifPriority.arrange(list, emptyMap(), byApp = true, smart = false)
        assertEquals("shop", plain.first().pkg)
        assertTrue(none.isEmpty())
    }

    @Test
    fun liveTimersFormat() {
        assertEquals("0:05", clockSpan(5_000))
        assertEquals("12:04", clockSpan(724_000))
        assertEquals("1:00:00", clockSpan(3_600_000))
        assertEquals("0:00", clockSpan(-3_000))
    }

    // ---------------------------------------------------------------- Sun

    @Test
    fun sunriseAndSunsetInNewYork() {
        val ny = ZoneId.of("America/New_York")
        val t = Sun.times(LocalDate.of(2024, 6, 21), 40.7128, -74.0060, ny)
        assertEquals(5 * 60 + 25.0, t.sunrise!!.toDouble(), 5.0)
        assertEquals(20 * 60 + 31.0, t.sunset!!.toDouble(), 5.0)
        val w = Sun.times(LocalDate.of(2024, 12, 21), 40.7128, -74.0060, ny)
        assertEquals(7 * 60 + 17.0, w.sunrise!!.toDouble(), 5.0)
        assertEquals(16 * 60 + 32.0, w.sunset!!.toDouble(), 5.0)
    }

    @Test
    fun polarDayAndNight() {
        val oslo = ZoneId.of("Europe/Oslo")
        val summer = Sun.times(LocalDate.of(2024, 6, 21), 69.65, 18.96, oslo)
        assertNull(summer.sunrise)
        assertTrue(summer.isDay(0))
        val winter = Sun.times(LocalDate.of(2024, 12, 21), 69.65, 18.96, oslo)
        assertFalse(winter.isDay(12 * 60))
    }

    @Test
    fun sunTriggers() {
        val sun = SunTimes(6 * 60, 20 * 60)
        val day = Profile("d", "Day", trigger = TriggerType.DAYTIME)
        val night = Profile("n", "Night", trigger = TriggerType.NIGHTTIME, sunOffset = -30)
        assertTrue(ProfileEngine.matches(day, 12 * 60, false, false, sun))
        assertFalse(ProfileEngine.matches(day, 21 * 60, false, false, sun))
        // Night starts 30 minutes before sunset.
        assertTrue(ProfileEngine.matches(night, 19 * 60 + 45, false, false, sun))
        assertTrue(ProfileEngine.matches(night, 2 * 60, false, false, sun))
        // No location, no match.
        assertFalse(ProfileEngine.matches(day, 12 * 60, false, false, null))
    }

    // ---------------------------------------------------------------- Weather

    @Test
    fun rainAlerts() {
        val start = 1_000_000_000L
        val q = 15 * 60_000L
        assertEquals("Rain in 30 min", WeatherDetailRepo.rainAlert(listOf(0.0, 0.0, 0.4, 0.6), start, start))
        assertEquals("Rain stopping in 15 min", WeatherDetailRepo.rainAlert(listOf(0.5, 0.0, 0.0), start, start))
        assertEquals("Snow in 15 min", WeatherDetailRepo.rainAlert(listOf(0.0, 0.3), start, start, snow = true))
        assertNull(WeatherDetailRepo.rainAlert(listOf(0.0, 0.0, 0.05), start, start))
        // Past buckets are ignored.
        assertNull(WeatherDetailRepo.rainAlert(listOf(1.0, 0.0), start, start + q + 1))
    }

    @Test
    fun airLabels() {
        assertEquals("Good", AirRepo.aqiLabel(30))
        assertEquals("Unhealthy", AirRepo.aqiLabel(180))
        assertEquals("High", AirRepo.uvLabel(7.0))
        assertEquals("Grass" to "Moderate", AirRepo.pollenSummary(mapOf("grass" to 40.0, "birch" to 2.0)))
        assertNull(AirRepo.pollenSummary(emptyMap()))
    }

    @Test
    fun weatherEffects() {
        assertEquals(WeatherFx.RAIN, weatherFxFor(63))
        assertEquals(WeatherFx.SNOW, weatherFxFor(73))
        assertEquals(WeatherFx.STORM, weatherFxFor(95))
        assertEquals(WeatherFx.FOG, weatherFxFor(45))
        assertEquals(WeatherFx.NONE, weatherFxFor(0))
    }

    @Test
    fun ambientTuning() {
        assertEquals(0.25f, Ambient.tune(0.25f, null).dim)
        val dark = Ambient.tune(0.25f, 1f)
        assertTrue(dark.dim > 0.25f && dark.warmth > 0f)
        val sunny = Ambient.tune(0.25f, 20_000f)
        assertTrue(sunny.dim < 0.25f && sunny.chipBoost > 0f)
    }

    // ---------------------------------------------------------------- Alarm

    @Test
    fun alarmCountdown() {
        val now = 0L
        assertEquals("in 25 min", untilLabel(25 * 60_000L, now))
        assertEquals("in 7h 12m", untilLabel((7 * 60 + 12) * 60_000L, now))
        assertEquals("in 3h", untilLabel(3 * 3600_000L, now))
    }

    // ---------------------------------------------------------------- Packages

    @Test
    fun packageDetection() {
        assertEquals("Out for delivery", PackageParse.status("Your package is out for delivery today"))
        assertEquals("Delivered", PackageParse.status("Delivered: 1 item"))
        assertNull(PackageParse.status("Your package will be delivered Tuesday").takeIf { it == "Delivered" })
        assertNull(PackageParse.status("Lunch at noon?"))

        val ups = PackageParse.detect("com.whatever", "UPS: 1Z999AA10123456784 arriving today")
        assertEquals("UPS", ups?.carrier)
        assertEquals("1Z999AA10123456784", ups?.number)
        assertEquals("USPS", PackageParse.detect("x", "Tracking 9400111899223817579425 shipped")?.carrier)
        assertEquals("FedEx", PackageParse.detect("x", "FedEx shipment 123456789012 delivered")?.carrier)
        assertEquals("Amazon", PackageParse.detect("com.amazon.mShop.android.shopping", "Arriving today")?.carrier)
        assertNull(PackageParse.detect("x", "Dinner tonight"))
        assertEquals("UPS", PackageParse.carrierFor("1Z999AA10123456784"))
        assertTrue(PackageParse.trackingUrl(TrackedPackage("1Z999AA10123456784", "UPS"))!!.startsWith("https://www.ups.com"))
    }

    @Test
    fun deliveredPackagesExpire() {
        val now = 100L * 3600_000L
        val list = listOf(
            TrackedPackage("a", "UPS", delivered = true, updatedAt = now - 2 * 3600_000L),
            TrackedPackage("b", "UPS", delivered = true, updatedAt = now - 30 * 3600_000L),
            TrackedPackage("c", "UPS", status = "Shipped", updatedAt = now - 3600_000L),
        )
        assertEquals(listOf("c", "a"), PackageRepo.visible(list, PackagesConfig(keepDeliveredHours = 24), now).map { it.number })
    }

    // ---------------------------------------------------------------- Sports

    @Test
    fun espnEventParsing() {
        val event = Json.parseToJsonElement(
            """
            {"date":"2026-11-02T18:00Z","competitions":[{
              "status":{"type":{"state":"in","shortDetail":"Q3 5:12"}},
              "competitors":[
                {"homeAway":"home","score":"21","team":{"id":"26","abbreviation":"SEA","color":"002244"}},
                {"homeAway":"away","score":"17","team":{"id":"25","abbreviation":"SF","color":"aa0000"}}
              ]}]}
            """,
        )
        val g = SportsRepo.parseEvent(event, "26")!!
        assertEquals("SEA", g.teamAbbr)
        assertEquals("SF", g.oppAbbr)
        assertEquals("21", g.teamScore)
        assertEquals(GameState.LIVE, g.state)
        assertEquals("Q3 5:12", g.detail)
        assertTrue(g.home)
        assertEquals(0xFF002244, g.teamColor)
        // From the other side, and with object scores like the team schedule uses.
        val other = SportsRepo.parseEvent(
            Json.parseToJsonElement(event.toString().replace("\"score\":\"17\"", "\"score\":{\"displayValue\":\"17\"}")), "25",
        )!!
        assertEquals("17", other.teamScore)
        assertFalse(other.home)
        assertNull(SportsRepo.parseEvent(event, "99"))
    }

    // ---------------------------------------------------------------- Memories

    @Test
    fun onThisDayMatching() {
        val today = LocalDate.of(2026, 11, 2)
        assertTrue(Memories.matchesDay(LocalDate.of(2023, 11, 2), today))
        assertFalse(Memories.matchesDay(LocalDate.of(2026, 11, 2), today))
        assertFalse(Memories.matchesDay(LocalDate.of(2023, 11, 4), today))
        assertTrue(Memories.matchesDay(LocalDate.of(2023, 11, 4), today, days = 3))
        assertFalse(Memories.matchesDay(LocalDate.of(2024, 2, 29), LocalDate.of(2026, 3, 1)))
    }

    // ---------------------------------------------------------------- Custom clock

    @Test
    fun clockCodesRoundTrip() {
        ClockShare.templates.forEach { t ->
            val code = ClockShare.encode(t)
            assertTrue(code.startsWith("brig-clock:"))
            assertEquals(t, ClockShare.decode("Look at this: $code !"))
        }
        assertNull(ClockShare.decode("brig-clock:notreal"))
        assertNull(ClockShare.decode("hello"))
    }

    @Test
    fun layerPatterns() {
        val t = LocalDateTime.of(2026, 11, 2, 9, 5)
        assertEquals("09:05", formatLayer("HH:mm", t))
        assertEquals("Monday", formatLayer("EEEE", t))
        assertEquals("It is 9", formatLayer("'It is' h", t))
        // Bad patterns show as typed instead of crashing.
        assertEquals("{bad", formatLayer("{bad", t))
    }

    // ---------------------------------------------------------------- Settings

    @Test
    fun presetsKeepEveryWidget() {
        Preset.entries.forEach { p ->
            val s = p.apply(dev.pridgey.brig.data.BrigSettings())
            assertEquals(WidgetType.entries.size, s.widgetOrder().size)
            assertEquals(WidgetType.entries.toSet(), s.widgets.map { it.type }.toSet())
        }
        assertTrue(Preset.PRODUCTIVE.apply(dev.pridgey.brig.data.BrigSettings()).isWidgetOn(WidgetType.HOURLY))
    }

    @Test
    fun oldSettingsGainNewWidgetsSwitchedOff() {
        val old = """{"enabled":true,"widgets":[{"type":"DATE","enabled":true}]}"""
        val s = SettingsRepository.decode(old)
        assertTrue(s.isWidgetOn(WidgetType.DATE))
        assertFalse(s.isWidgetOn(WidgetType.SPORTS))
        assertEquals(0, s.setupVersion)
        assertNotNull(s.appearance.customClock)
        assertFalse(s.guest.active)
    }

    @Test
    fun customClockSurvivesSettingsJson() {
        val s = dev.pridgey.brig.data.BrigSettings(appearance = dev.pridgey.brig.data.Appearance(customClock = ClockShare.templates[2]))
        val back = SettingsRepository.decode(SettingsRepository.json.encodeToString(dev.pridgey.brig.data.BrigSettings.serializer(), s))
        assertEquals(ClockShare.templates[2], back.appearance.customClock)
        assertEquals(CustomClock.starter().layers.size, dev.pridgey.brig.data.BrigSettings().appearance.customClock.layers.size)
    }

    @Test
    fun onThisDayFillsFromNearbyDaysAndSkipsBursts() {
        val zone = java.time.ZoneOffset.UTC
        val today = LocalDate.of(2026, 11, 3)
        fun at(y: Int, m: Int, d: Int, minute: Int = 0) =
            java.time.LocalDateTime.of(y, m, d, 12, minute).toInstant(zone).toEpochMilli()
        val exact = at(2023, 11, 3)
        val burst = at(2023, 11, 3, minute = 1)
        val near = at(2022, 11, 4)
        val far = at(2021, 11, 6)
        val picked = Memories.pick(listOf(far, near, burst, exact), { it }, today, zone, 3)
        assertEquals(listOf(exact, near, far), picked)
    }
}
