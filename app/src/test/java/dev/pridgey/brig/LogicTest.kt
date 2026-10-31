package dev.pridgey.brig

import dev.pridgey.brig.data.BrigSettings
import dev.pridgey.brig.data.GestureAction
import dev.pridgey.brig.data.GestureDir
import dev.pridgey.brig.data.Gestures
import dev.pridgey.brig.data.Profile
import dev.pridgey.brig.data.ProfileEngine
import dev.pridgey.brig.data.SettingsRepository
import dev.pridgey.brig.data.TriggerType
import dev.pridgey.brig.update.ReleaseParser
import dev.pridgey.brig.util.MathProblems
import dev.pridgey.brig.util.PinHasher
import dev.pridgey.brig.util.PhotoFx
import dev.pridgey.brig.data.PhotoConfig
import dev.pridgey.brig.data.PhotoEffect
import androidx.compose.ui.graphics.Color
import dev.pridgey.brig.util.WordClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LogicTest {
    @Test
    fun windowsWrapPastMidnight() {
        assertTrue(ProfileEngine.inWindow(23 * 60, 22 * 60, 7 * 60))
        assertTrue(ProfileEngine.inWindow(3 * 60, 22 * 60, 7 * 60))
        assertFalse(ProfileEngine.inWindow(12 * 60, 22 * 60, 7 * 60))
        assertTrue(ProfileEngine.inWindow(10 * 60, 9 * 60, 17 * 60))
        assertFalse(ProfileEngine.inWindow(17 * 60, 9 * 60, 17 * 60))
        assertTrue(ProfileEngine.inWindow(5, 600, 600))
    }

    @Test
    fun profilesMerge() {
        val profiles = listOf(
            Profile("a", "A", trigger = TriggerType.CHARGING, bedside = true, dim = 0.9f),
            Profile("b", "B", trigger = TriggerType.TIME, startMinute = 0, endMinute = 1439, hideContent = true, dim = 0.1f),
            Profile("c", "C", enabled = false, trigger = TriggerType.TIME, startMinute = 0, endMinute = 1439, hideNotifications = true),
        )
        val e = ProfileEngine.resolve(profiles, 600, charging = true, headphones = false)
        assertTrue(e.bedside)
        assertTrue(e.hideContent)
        assertFalse(e.hideNotifications)
        assertEquals(0.9f, e.dim)
        assertEquals(listOf("A", "B"), e.activeNames)
        assertFalse(ProfileEngine.resolve(profiles, 600, charging = false, headphones = false).bedside)
    }

    @Test
    fun wordClock() {
        assertEquals("It's quarter past ten", WordClock.phrase(10, 15))
        assertEquals("It's about half past three", WordClock.phrase(15, 31))
        assertEquals("It's twelve o'clock", WordClock.phrase(0, 0))
        assertEquals("It's about one o'clock", WordClock.phrase(12, 58))
        assertEquals("It's twenty to eight", WordClock.phrase(19, 40))
    }

    @Test
    fun releaseParsing() {
        assertEquals(42, ReleaseParser.versionCodeFromTag("v1.42"))
        assertNull(ReleaseParser.versionCodeFromTag("latest"))
        val json = """
            {"tag_name":"v1.7","name":"Brig 1.7","body":"Fixes","html_url":"https://x",
             "assets":[{"name":"notes.txt","browser_download_url":"https://n","size":1},
                       {"name":"Brig-1.7.apk","browser_download_url":"https://a/Brig.apk","size":1234}]}
        """.trimIndent()
        val r = ReleaseParser.parse(json)!!
        assertEquals(7, r.versionCode)
        assertEquals("https://a/Brig.apk", r.apkUrl)
        assertEquals(1234L, r.size)
        assertNull(ReleaseParser.parse("""{"tag_name":"v1.8","assets":[]}"""))
    }

    @Test
    fun pinHashing() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("4821", salt)
        assertTrue(PinHasher.verify("4821", salt, hash))
        assertFalse(PinHasher.verify("4822", salt, hash))
    }

    @Test
    fun mathProblemsAreCorrect() {
        val rnd = Random(1)
        repeat(200) {
            for (d in 1..3) {
                val p = MathProblems.generate(d, rnd)
                val expr = p.text.replace("×", "*").replace("−", "-")
                assertEquals(p.text, eval(expr), p.answer)
            }
        }
    }

    private fun eval(e: String): Int {
        val t = e.split(" ")
        var acc = t[0].toInt()
        var i = 1
        // Left-to-right is fine here: generated problems put "*" first.
        while (i < t.size) {
            val n = t[i + 1].toInt()
            acc = when (t[i]) { "+" -> acc + n; "-" -> acc - n; else -> acc * n }
            i += 2
        }
        return acc
    }

    @Test
    fun gesturesAlwaysHaveAnUnlock() {
        val none = Gestures(up = GestureAction.CAMERA)
        assertFalse(none.hasUnlock())
        assertEquals(GestureDir.UP, none.unlockDir())
        assertEquals(GestureDir.LEFT, Gestures(up = GestureAction.NONE, left = GestureAction.UNLOCK).unlockDir())
    }

    @Test
    fun settingsRoundTripAndTolerateJunk() {
        val s = BrigSettings(enabled = true, shortcuts = listOf("a.b"))
        val json = SettingsRepository.json.encodeToString(BrigSettings.serializer(), s)
        assertEquals(s, SettingsRepository.decode(json))
        assertEquals(BrigSettings(), SettingsRepository.decode("not json"))
        assertTrue(SettingsRepository.decode("""{"enabled":true,"futureField":1}""").enabled)
    }

    @Test
    fun photoMatrices() {
        val id = PhotoFx.identity()
        val sat = PhotoFx.saturation(0.3f)
        assertTrue(PhotoFx.concat(id, sat).contentEquals(sat))
        assertTrue(PhotoFx.concat(sat, id).contentEquals(sat))

        // Mono makes channels equal.
        val (r, g, b) = PhotoFx.apply(PhotoFx.saturation(0f), 200f, 50f, 10f)
        assertEquals(r, g, 0.01f)
        assertEquals(g, b, 0.01f)

        // Order matters: contrast first, then brightness, so +0.2 brightness adds 51 at the end.
        val m = PhotoFx.concat(PhotoFx.brightness(0.2f), PhotoFx.contrast(2f))
        assertEquals(128f * 2 - 128f + 51f, PhotoFx.apply(m, 128f, 128f, 128f).first, 0.01f)

        // Duotone maps black to the shadow color and white to the highlight color.
        val duo = PhotoFx.duotone(Color(0xFF102030), Color(0xFFF0E0D0))
        assertEquals(0x10.toFloat(), PhotoFx.apply(duo, 0f, 0f, 0f).first, 0.6f)
        assertEquals(0xF0.toFloat(), PhotoFx.apply(duo, 255f, 255f, 255f).first, 0.6f)

        // Default config leaves colors alone.
        val (r2, g2, b2) = PhotoFx.apply(PhotoFx.matrix(PhotoConfig(), Color.Red), 12f, 99f, 230f)
        assertEquals(12f, r2, 0.01f); assertEquals(99f, g2, 0.01f); assertEquals(230f, b2, 0.01f)
        PhotoEffect.entries.forEach { assertEquals(20, PhotoFx.effect(it, Color.Blue).size) }
    }
}
