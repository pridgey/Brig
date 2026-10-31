package dev.pridgey.brig.data

/** The combined result of every profile that is active right now. */
data class ActiveEffects(
    val bedside: Boolean = false,
    val hideNotifications: Boolean = false,
    val hideContent: Boolean = false,
    val clockStyle: ClockStyle? = null,
    val accent: Long? = null,
    val dim: Float? = null,
    val activeNames: List<String> = emptyList(),
)

object ProfileEngine {
    /** True if [minute] (0..1439) falls in [start, end). Windows may wrap past midnight. */
    fun inWindow(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> true
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    fun matches(p: Profile, minute: Int, charging: Boolean, headphones: Boolean): Boolean = when (p.trigger) {
        TriggerType.TIME -> inWindow(minute, p.startMinute, p.endMinute)
        TriggerType.CHARGING -> charging
        TriggerType.CHARGING_AND_TIME -> charging && inWindow(minute, p.startMinute, p.endMinute)
        TriggerType.HEADPHONES -> headphones
    }

    /** On/off effects combine across profiles; for overrides, the first profile in the list wins. */
    fun resolve(profiles: List<Profile>, minute: Int, charging: Boolean, headphones: Boolean): ActiveEffects {
        val active = profiles.filter { it.enabled && matches(it, minute, charging, headphones) }
        return ActiveEffects(
            bedside = active.any { it.bedside },
            hideNotifications = active.any { it.hideNotifications },
            hideContent = active.any { it.hideContent },
            clockStyle = active.firstNotNullOfOrNull { it.clockStyle },
            accent = active.firstNotNullOfOrNull { it.accent },
            dim = active.firstNotNullOfOrNull { it.dim },
            activeNames = active.map { it.name },
        )
    }

    fun formatMinute(minute: Int, use24h: Boolean = false): String {
        val h = minute / 60
        val m = minute % 60
        return if (use24h) "%02d:%02d".format(h, m)
        else "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
    }
}
