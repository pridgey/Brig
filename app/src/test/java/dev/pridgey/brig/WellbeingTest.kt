package dev.pridgey.brig

import dev.pridgey.brig.data.CalEvent
import dev.pridgey.brig.data.DiagEntry
import dev.pridgey.brig.data.DiagEvent
import dev.pridgey.brig.data.DiagLog
import dev.pridgey.brig.data.FlashcardConfig
import dev.pridgey.brig.data.FlashcardSource
import dev.pridgey.brig.data.Flashcards
import dev.pridgey.brig.data.MoodStore
import dev.pridgey.brig.data.NextUp
import dev.pridgey.brig.data.OtpParse
import dev.pridgey.brig.data.Sleep
import dev.pridgey.brig.data.SleepConfig
import dev.pridgey.brig.util.PhotoColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WellbeingTest {
    @Test
    fun otpCodes() {
        assertEquals("482913", OtpParse.extract("Your verification code is 482913. Don't share it."))
        assertEquals("123-456", OtpParse.extract("G-123-456 is your Google verification code"))
        assertEquals("123456", OtpParse.digits("123-456"))
        assertEquals("7781", OtpParse.extract("Use 7781 to sign in to Bank"))
        // No code-ish words: not a code.
        assertNull(OtpParse.extract("Meet at 1530 by the fountain"))
        // Years, prices and times are skipped.
        assertNull(OtpParse.extract("Your 2026 login summary"))
        assertNull(OtpParse.extract("Confirm your payment of $1234.50"))
        assertEquals("90210", OtpParse.extract("Security code: 90210"))
    }

    @Test
    fun whatsNext() {
        val now = 1_000_000_000_000L
        val m = 60_000L
        fun ev(title: String, start: Long, mins: Long = 30, loc: String = "") = CalEvent(title, now + start * m, now + (start + mins) * m, false, 0, loc)
        assertEquals("Standup in 25 min", NextUp.label(listOf(ev("Standup", 25)), now, 15))
        assertEquals("Lunch · 20 min left", NextUp.label(listOf(ev("Lunch", -10)), now, 15))
        assertEquals("Leave in 10 min for Dentist", NextUp.label(listOf(ev("Dentist", 25, loc = "Main St")), now, 15))
        assertEquals("Leave now for Dentist", NextUp.label(listOf(ev("Dentist", 10, loc = "Main St")), now, 15))
        assertEquals("Review in 1h 30m", NextUp.label(listOf(ev("Review", 90)), now, 15))
        assertNull(NextUp.label(listOf(ev("Later", 300)), now, 15))
        assertNull(NextUp.label(emptyList(), now, 15))
        // All-day events don't count.
        assertNull(NextUp.label(listOf(CalEvent("Holiday", now, now + 86_400_000L, true, 0)), now, 15))
    }

    @Test
    fun bedtime() {
        val cfg = SleepConfig(enabled = true, bedtime = 23 * 60, wake = 6 * 60)
        assertTrue(Sleep.isLate(23 * 60 + 30, cfg))
        assertTrue(Sleep.isLate(2 * 60, cfg))
        assertFalse(Sleep.isLate(12 * 60, cfg))
        assertFalse(Sleep.isLate(23 * 60 + 30, cfg.copy(enabled = false)))
        assertEquals(90, Sleep.minutesPast(30, 23 * 60))
        // Median of 11:20 PM, 11:40 PM, 12:30 AM, rounded to 15 minutes.
        assertEquals(23 * 60 + 45, Sleep.suggestBedtime(listOf(23 * 60 + 20, 30, 23 * 60 + 40)))
        assertNull(Sleep.suggestBedtime(listOf(23 * 60)))
    }

    @Test
    fun moodStreak() {
        val today = LocalDate.of(2026, 11, 5)
        val map = (0..3).associate { today.minusDays(it.toLong()).toString() to 3 } + (today.minusDays(6).toString() to 1)
        assertEquals(4, MoodStore.streak(map, today))
        // Not checked in yet today: the streak up to yesterday still counts.
        assertEquals(3, MoodStore.streak(map - today.toString(), today))
        assertEquals(0, MoodStore.streak(emptyMap(), today))
    }

    @Test
    fun flashcards() {
        val cards = Flashcards.parse("hola | hello\n\ngracias\tthank you\nbroken line\n | empty front")
        assertEquals(listOf("hola" to "hello", "gracias" to "thank you"), cards.map { it.front to it.back })
        val mine = FlashcardConfig(FlashcardSource.MY_CARDS, "a | 1\nb | 2\nc | 3", everyLock = true)
        assertEquals("b", Flashcards.today(mine, LocalDate.of(2026, 1, 1), 1).front)
        assertEquals("a", Flashcards.today(mine, LocalDate.of(2026, 1, 1), 3).front)
        val words = FlashcardConfig()
        assertNotEquals(Flashcards.today(words, LocalDate.of(2026, 1, 1), 0), Flashcards.today(words, LocalDate.of(2026, 1, 2), 0))
        // An empty deck falls back to words.
        assertTrue(Flashcards.today(FlashcardConfig(FlashcardSource.MY_CARDS, ""), LocalDate.now(), 0).back.isNotBlank())
    }

    @Test
    fun accentFromPixels() {
        val orange = 0xFFFF8800.toInt()
        val grey = 0xFF808080.toInt()
        val px = IntArray(400) { if (it < 120) orange else grey }
        val accent = PhotoColor.accent(px)!!
        val hsv = PhotoColor.hsv(accent.toInt())
        assertEquals(32f, hsv[0], 6f)
        // Greyscale has no accent.
        assertNull(PhotoColor.accent(IntArray(400) { grey }))
        assertEquals(0xFFFF0000, PhotoColor.fromHsv(0f, 1f, 1f))
    }

    @Test
    fun missedLocks() {
        val log = listOf(
            DiagEntry(DiagEvent.SCREEN_OFF, 1000),
            DiagEntry(DiagEvent.LOCK_SHOWN, 1500),
            DiagEntry(DiagEvent.SCREEN_OFF, 10_000),
            DiagEntry(DiagEvent.SCREEN_OFF, 20_000),
            DiagEntry(DiagEvent.LOCK_SHOWN, 30_000),
        )
        assertEquals(listOf(10_000L, 20_000L), DiagLog.missed(log).map { it.at })
    }
}
