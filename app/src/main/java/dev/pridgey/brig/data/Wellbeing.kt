package dev.pridgey.brig.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

// ---------------------------------------------------------------- One-time codes

object OtpParse {
    private val keywords = Regex(
        """\b(code|otp|one[- ]time|verification|verify|passcode|password|pin|2fa|two[- ]factor|security|login|log in|sign[- ]in|confirm|authenticat\w*)\b""",
        RegexOption.IGNORE_CASE,
    )
    // A sentence-ending period is fine; a decimal point (".5") or thousands separator is not.
    private val candidate = Regex("""(?<![\d$€£.,:/])(?:G-)?(\d{3}[- ]\d{3}|\d{4,8})(?![\d:/%]|[.,]\d)""")

    /** The code in [text], formatted as shown (e.g. "123-456"), or null if there isn't a clear one. */
    fun extract(text: String): String? {
        if (!keywords.containsMatchIn(text)) return null
        val matches = candidate.findAll(text).map { it.groupValues[1] }
            // Years and times look like codes; skip the obvious ones.
            .filterNot { it.length == 4 && it.toIntOrNull() in 1990..2099 }
            .toList()
        return matches.firstOrNull()
    }

    /** What actually goes on the clipboard. */
    fun digits(code: String) = code.filter(Char::isDigit)

    const val LIFETIME_MS = 5 * 60_000L
}

// ---------------------------------------------------------------- What's next

object NextUp {
    private fun span(ms: Long): String {
        val mins = ((ms + 59_999) / 60_000).coerceAtLeast(1)
        return if (mins < 60) "$mins min" else "${mins / 60}h" + if (mins % 60 != 0L) " ${mins % 60}m" else ""
    }

    /**
     * "Standup in 25 min", "Leave in 10 min for Dentist", "Lunch · 20 min left", or null when
     * nothing timed starts in the next 3 hours.
     */
    fun label(events: List<CalEvent>, now: Long, travelBuffer: Int): String? {
        val e = events.filter { !it.allDay && it.end > now }.minByOrNull { it.begin } ?: return null
        val title = e.title.take(28)
        if (e.begin <= now) return "$title · ${span(e.end - now)} left"
        if (e.begin - now > 3 * 3600_000L) return null
        if (e.location.isNotBlank() && travelBuffer > 0) {
            val leave = e.begin - travelBuffer * 60_000L
            if (leave - now <= 60 * 60_000L) {
                return if (leave <= now) "Leave now for $title" else "Leave in ${span(leave - now)} for $title"
            }
        }
        return "$title in ${span(e.begin - now)}"
    }
}

// ---------------------------------------------------------------- Sleep

object Sleep {
    fun isLate(minute: Int, cfg: SleepConfig) = cfg.enabled && ProfileEngine.inWindow(minute, cfg.bedtime, cfg.wake)

    fun minutesPast(minute: Int, bedtime: Int) = Math.floorMod(minute - bedtime, 1440)

    fun message(minutesPast: Int): String = when {
        minutesPast < 15 -> "It's bedtime. Wind down?"
        minutesPast < 45 -> "Past your bedtime. Tomorrow-you says thanks for sleeping."
        minutesPast < 90 -> "Getting late. Put the phone down soon?"
        else -> "It's really late. Sleep well."
    }

    /** Remembers when the screen goes off late at night, to suggest a realistic bedtime. */
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sleep", Context.MODE_PRIVATE)

    fun recordScreenOff(ctx: Context, minute: Int, date: LocalDate = LocalDate.now()) {
        // 8 PM to 4 AM counts toward the night that started that evening.
        if (minute < 20 * 60 && minute >= 4 * 60) return
        val night = if (minute < 4 * 60) date.minusDays(1) else date
        prefs(ctx).edit().putInt("off_$night", minute).apply()
        // Keep two weeks.
        val keep = (0..13).map { "off_${LocalDate.now().minusDays(it.toLong())}" }.toSet()
        prefs(ctx).all.keys.filter { it.startsWith("off_") && it !in keep }.forEach { prefs(ctx).edit().remove(it).apply() }
    }

    fun nightlyOffTimes(ctx: Context): List<Int> = prefs(ctx).all.filterKeys { it.startsWith("off_") }.values.mapNotNull { it as? Int }

    /** Median of the last screen-off each night, rounded to 15 minutes. Needs at least 3 nights. Pure for tests. */
    fun suggestBedtime(offTimes: List<Int>): Int? {
        if (offTimes.size < 3) return null
        // Shift so times after midnight sort after the evening ones.
        val shifted = offTimes.map { if (it < 12 * 60) it + 1440 else it }.sorted()
        val median = shifted[shifted.size / 2]
        return Math.floorMod(Math.round(median / 15f) * 15, 1440)
    }
}

// ---------------------------------------------------------------- Mood

object MoodStore {
    val faces = listOf("😞", "🙁", "😐", "🙂", "😄")
    val colors = listOf(0xFF7A6FF0, 0xFF5B9BD5, 0xFF9AA5B1, 0xFF7BD88F, 0xFFFFC857)

    val moods = MutableStateFlow<Map<String, Int>>(emptyMap())
    private var loaded = false
    private val serializer = MapSerializer(String.serializer(), Int.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mood", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        moods.value = prefs(ctx).getString("map", null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    }

    /** [mood] is 0 (low) to 4 (great). */
    fun set(ctx: Context, date: LocalDate, mood: Int) {
        load(ctx)
        val next = moods.value + (date.toString() to mood.coerceIn(0, 4))
        moods.value = next
        prefs(ctx).edit().putString("map", json.encodeToString(serializer, next)).apply()
    }

    fun clear(ctx: Context) {
        moods.value = emptyMap()
        prefs(ctx).edit().remove("map").apply()
    }

    /** Current streak of days with a check-in, ending today or yesterday. Pure for tests. */
    fun streak(map: Map<String, Int>, today: LocalDate): Int {
        var d = if (map.containsKey(today.toString())) today else today.minusDays(1)
        var n = 0
        while (map.containsKey(d.toString())) { n++; d = d.minusDays(1) }
        return n
    }
}

// ---------------------------------------------------------------- Flashcards

data class Flashcard(val front: String, val back: String, val hint: String = "")

object Flashcards {
    private val words = listOf(
        Flashcard("Sonder", "The realization that each passerby has a life as vivid and complex as your own.", "noun"),
        Flashcard("Petrichor", "The pleasant earthy smell after rain.", "noun"),
        Flashcard("Apricity", "The warmth of the sun in winter.", "noun"),
        Flashcard("Sobremesa", "Lingering at the table to talk after a meal. (Spanish)", "noun"),
        Flashcard("Eunoia", "Beautiful thinking; a well mind.", "noun"),
        Flashcard("Limerence", "The state of being infatuated with another person.", "noun"),
        Flashcard("Komorebi", "Sunlight filtering through the leaves of trees. (Japanese)", "noun"),
        Flashcard("Ephemeral", "Lasting for a very short time.", "adjective"),
        Flashcard("Serendipity", "Finding something good without looking for it.", "noun"),
        Flashcard("Hiraeth", "A homesickness for a home you can't return to. (Welsh)", "noun"),
        Flashcard("Liminal", "Relating to a transitional, in-between stage.", "adjective"),
        Flashcard("Mellifluous", "Sweet or musical; pleasant to hear.", "adjective"),
        Flashcard("Quiescent", "In a state of inactivity or dormancy.", "adjective"),
        Flashcard("Halcyon", "Idyllically calm and peaceful, often of a happy time in the past.", "adjective"),
        Flashcard("Gezellig", "Cozy, convivial, warmly together. (Dutch)", "adjective"),
        Flashcard("Susurrus", "A whispering or rustling sound.", "noun"),
        Flashcard("Vellichor", "The wistfulness of used bookshops.", "noun"),
        Flashcard("Wabi-sabi", "Finding beauty in imperfection and impermanence. (Japanese)", "noun"),
        Flashcard("Ineffable", "Too great or extreme to be expressed in words.", "adjective"),
        Flashcard("Defenestrate", "To throw someone or something out of a window.", "verb"),
        Flashcard("Lagom", "Just the right amount; not too much, not too little. (Swedish)", "adverb"),
        Flashcard("Effervescent", "Bubbly; vivacious and enthusiastic.", "adjective"),
        Flashcard("Psithurism", "The sound of wind in the trees.", "noun"),
        Flashcard("Ubuntu", "I am because we are; humanity toward others. (Nguni)", "noun"),
        Flashcard("Crepuscular", "Relating to twilight; active at dawn and dusk.", "adjective"),
        Flashcard("Meraki", "Putting soul and creativity into your work. (Greek)", "noun"),
        Flashcard("Numinous", "Having a strong spiritual or mysterious quality.", "adjective"),
        Flashcard("Fernweh", "An ache for distant places. (German)", "noun"),
        Flashcard("Penumbra", "The partially shaded outer region of a shadow.", "noun"),
        Flashcard("Sempiternal", "Eternal and unchanging; everlasting.", "adjective"),
        Flashcard("Resplendent", "Attractive and impressive through being richly colorful.", "adjective"),
        Flashcard("Tsundoku", "Buying books and letting them pile up unread. (Japanese)", "noun"),
        Flashcard("Ataraxia", "A state of serene calmness.", "noun"),
        Flashcard("Querencia", "A place where you feel at home and draw your strength. (Spanish)", "noun"),
        Flashcard("Lucubration", "Laborious study, especially at night.", "noun"),
        Flashcard("Mamihlapinatapai", "A look shared by two people, each wishing the other would start something. (Yaghan)", "noun"),
        Flashcard("Inglenook", "A cozy corner by a fireplace.", "noun"),
        Flashcard("Gossamer", "Something very light, thin and delicate.", "noun"),
        Flashcard("Saudade", "A deep longing for something absent. (Portuguese)", "noun"),
        Flashcard("Felicity", "Intense happiness; a well-chosen phrase.", "noun"),
        Flashcard("Bumbershoot", "An umbrella.", "noun"),
        Flashcard("Yūgen", "A profound awareness of the universe's beauty. (Japanese)", "noun"),
    )

    /** Parses "front | back" lines (a tab also works). Blank or one-sided lines are skipped. Pure for tests. */
    fun parse(text: String): List<Flashcard> = text.lines().mapNotNull { line ->
        val parts = line.split('|', '\t').map { it.trim() }
        if (parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) Flashcard(parts[0], parts.drop(1).joinToString(" | ")) else null
    }

    fun deck(cfg: FlashcardConfig): List<Flashcard> = when (cfg.source) {
        FlashcardSource.WORDS -> words
        FlashcardSource.MY_CARDS -> parse(cfg.cards).ifEmpty { words }
    }

    fun today(cfg: FlashcardConfig, date: LocalDate, lockCount: Int): Flashcard {
        val deck = deck(cfg)
        val i = if (cfg.source == FlashcardSource.MY_CARDS && cfg.everyLock) lockCount else (date.toEpochDay() % 100_000).toInt()
        return deck[Math.floorMod(i, deck.size)]
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("flashcards", Context.MODE_PRIVATE)
    fun lockCount(ctx: Context) = prefs(ctx).getInt("locks", 0)
    fun nextLock(ctx: Context) = prefs(ctx).edit().putInt("locks", lockCount(ctx) + 1).apply()
}
