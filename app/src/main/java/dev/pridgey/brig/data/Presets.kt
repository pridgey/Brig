package dev.pridgey.brig.data

/** Starter layouts offered by the setup assistant. Each one only touches looks and widgets. */
enum class Preset(val label: String, val description: String) {
    CLEAN("Clean", "A big clock, the essentials, nothing else"),
    PRODUCTIVE("Productive", "Agenda, hourly weather, alarm and smart notifications"),
    PHOTO("Photo", "A fresh photo each day with the depth effect"),
    NIGHT("Night owl", "Starfield, minimal clock, glanceable notifications");

    fun apply(s: BrigSettings): BrigSettings = when (this) {
        CLEAN -> s.copy(
            appearance = s.appearance.copy(clockStyle = ClockStyle.DIGITAL, background = BackgroundType.AURORA, depth = false),
            widgets = layout(WidgetType.DATE, WidgetType.ALARM, WidgetType.BATTERY, WidgetType.LIVE, WidgetType.MEDIA, WidgetType.NOTIFICATIONS, WidgetType.TOGGLES),
            notifications = s.notifications.copy(smartPriority = true, glanceMode = false),
        )
        PRODUCTIVE -> s.copy(
            appearance = s.appearance.copy(clockStyle = ClockStyle.STACKED, background = BackgroundType.TIME_OF_DAY, depth = false),
            widgets = layout(
                WidgetType.DATE, WidgetType.WEATHER, WidgetType.ALARM, WidgetType.BATTERY, WidgetType.HOURLY,
                WidgetType.CALENDAR, WidgetType.LIVE, WidgetType.NOTIFICATIONS, WidgetType.SHORTCUTS, WidgetType.TOGGLES,
            ),
            calendar = s.calendar.copy(style = CalendarStyle.TIMELINE),
            notifications = s.notifications.copy(smartPriority = true, conversationView = true, glanceMode = false),
        )
        PHOTO -> s.copy(
            appearance = s.appearance.copy(clockStyle = ClockStyle.GRADIENT, background = BackgroundType.IMAGE, depth = true, dim = 0.15f),
            photos = s.photos.copy(source = if (s.photos.source == PhotoSource.SINGLE && s.appearance.imagePath == null) PhotoSource.BING else s.photos.source),
            widgets = layout(WidgetType.DATE, WidgetType.MEDIA, WidgetType.NOTIFICATIONS, WidgetType.TOGGLES),
            notifications = s.notifications.copy(glanceMode = true),
        )
        NIGHT -> s.copy(
            appearance = s.appearance.copy(clockStyle = ClockStyle.MINIMAL, background = BackgroundType.PARTICLES, depth = false, ambient = true),
            widgets = layout(WidgetType.DATE, WidgetType.ALARM, WidgetType.WEATHER, WidgetType.MEDIA, WidgetType.NOTIFICATIONS),
            notifications = s.notifications.copy(glanceMode = true, smartPriority = true),
        )
    }

    companion object {
        /** The given widgets on, in that order (each in its default zone), then everything else off. */
        fun layout(vararg on: WidgetType): List<WidgetConfig> =
            on.map { WidgetConfig(it, true) } + WidgetType.entries.filter { it !in on }.map { WidgetConfig(it, false) }
    }
}
