package dev.pridgey.brig.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import dev.pridgey.brig.data.AppNotificationMode
import dev.pridgey.brig.data.AppsRepo
import dev.pridgey.brig.data.PackageRepo
import kotlinx.coroutines.flow.MutableStateFlow

/** One message in a conversation notification. */
data class ChatMessage(val sender: String, val text: String, val time: Long, val fromMe: Boolean)

/** Progress or a running timer, for ongoing "live" notifications. */
data class LiveInfo(
    /** 0..1, or null when there is no progress bar. */
    val progress: Float?,
    val indeterminate: Boolean,
    /** Epoch ms the chronometer counts from (or down to). Null when there is no timer. */
    val chronometerBase: Long?,
    val countDown: Boolean,
)

enum class NotifTier { PEOPLE, NORMAL, QUIET }

data class BrigNotification(
    val key: String,
    val pkg: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val appIcon: ImageBitmap?,
    val largeIcon: ImageBitmap?,
    val contentIntent: PendingIntent?,
    val replyAction: Notification.Action?,
    val silent: Boolean,
    val clearable: Boolean,
    val category: String? = null,
    val isConversation: Boolean = false,
    val hasPeople: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val conversationTitle: String? = null,
    val isGroupChat: Boolean = false,
    val live: LiveInfo? = null,
    val ongoing: Boolean = false,
    val fullScreenIntent: PendingIntent? = null,
) {
    /** A ringing call: the dialer attaches a full-screen intent only while the phone is ringing. */
    val isIncomingCall get() = category == Notification.CATEGORY_CALL && fullScreenIntent != null
    val isCall get() = category == Notification.CATEGORY_CALL
}

/** Notifications from one app, newest first. */
data class NotificationGroup(val pkg: String, val items: List<BrigNotification>) {
    val key get() = "group:$pkg"
    val latest get() = items.first()
    val clearable get() = items.any { it.clearable }
}

/** Groups by app, keeping each app at the position of its newest notification. */
fun groupNotifications(list: List<BrigNotification>, byApp: Boolean): List<NotificationGroup> =
    if (!byApp) list.sortedByDescending { it.postTime }.map { NotificationGroup(it.pkg, listOf(it)) }
    else list.sortedByDescending { it.postTime }
        .groupBy { it.pkg }
        .map { (pkg, items) -> NotificationGroup(pkg, items) }
        .sortedByDescending { it.latest.postTime }

object NotifPriority {
    private val quietCategories = setOf(
        Notification.CATEGORY_PROMO, Notification.CATEGORY_RECOMMENDATION, Notification.CATEGORY_STATUS,
        Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM,
    )
    private val peopleCategories = setOf(Notification.CATEGORY_MESSAGE, Notification.CATEGORY_CALL, Notification.CATEGORY_MISSED_CALL)

    fun tier(n: BrigNotification, mode: AppNotificationMode?): NotifTier = when {
        mode == AppNotificationMode.PRIORITY -> NotifTier.PEOPLE
        mode == AppNotificationMode.QUIET -> NotifTier.QUIET
        n.isConversation || n.messages.isNotEmpty() || n.category in peopleCategories -> NotifTier.PEOPLE
        n.silent || n.category in quietCategories -> NotifTier.QUIET
        else -> NotifTier.NORMAL
    }

    /**
     * Splits into (main cards, quiet cards). Main cards put people first, then everything else,
     * each newest first.
     */
    fun arrange(
        list: List<BrigNotification>,
        modes: Map<String, AppNotificationMode>,
        byApp: Boolean,
        smart: Boolean,
    ): Pair<List<NotificationGroup>, List<NotificationGroup>> {
        if (!smart) return groupNotifications(list, byApp) to emptyList()
        val tiers = list.groupBy { tier(it, modes[it.pkg]) }
        val people = groupNotifications(tiers[NotifTier.PEOPLE].orEmpty(), byApp)
        val normal = groupNotifications(tiers[NotifTier.NORMAL].orEmpty(), byApp)
        val quiet = groupNotifications(tiers[NotifTier.QUIET].orEmpty(), byApp)
        return (people + normal) to quiet
    }
}

class BrigNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        connected.value = true
        dev.pridgey.brig.data.DiagLog.record(this, dev.pridgey.brig.data.DiagEvent.LISTENER_ON)
        refresh()
        MediaRepo.start(this)
    }

    override fun onListenerDisconnected() {
        instance = null
        connected.value = false
        dev.pridgey.brig.data.DiagLog.record(this, dev.pridgey.brig.data.DiagEvent.LISTENER_OFF)
        MediaRepo.stop()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { runCatching { PackageRepo.observe(this, it.packageName, text(it.notification)) } }
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = refresh()

    private fun text(n: Notification): String {
        val e = n.extras
        return listOfNotNull(
            e.getCharSequence(Notification.EXTRA_TITLE),
            e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT),
        ).joinToString(" · ")
    }

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val ranking = currentRanking
        notifications.value = active.asSequence()
            .filter { it.packageName != packageName }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter { !it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }
            .mapNotNull { toModel(it, ranking) }
            // Ongoing notifications only show when they carry something live (progress, timer, call).
            .filter { !it.ongoing || it.live != null || it.category == Notification.CATEGORY_CALL }
            .sortedByDescending { it.postTime }
            .toList()
    }

    private fun liveInfo(n: Notification): LiveInfo? {
        val e = n.extras
        val max = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val chrono = e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val countDown = e.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
        val liveCategory = n.category in setOf(Notification.CATEGORY_PROGRESS, Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_STOPWATCH)
        if (max <= 0 && !indeterminate && !chrono && !liveCategory) return null
        return LiveInfo(
            progress = if (max > 0) (e.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max).coerceIn(0f, 1f) else null,
            indeterminate = indeterminate,
            chronometerBase = if (chrono && n.`when` > 0) n.`when` else null,
            countDown = countDown,
        )
    }

    private fun messages(n: Notification): Triple<List<ChatMessage>, String?, Boolean> {
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
            ?: return Triple(emptyList(), null, false)
        val me = style.user.name?.toString()
        val msgs = style.messages.takeLast(8).map { m ->
            val sender = m.person?.name?.toString()
            ChatMessage(
                sender = sender ?: me ?: "You",
                text = m.text?.toString().orEmpty(),
                time = m.timestamp,
                fromMe = m.person == null || (sender != null && sender == me),
            )
        }.filter { it.text.isNotBlank() }
        return Triple(msgs, style.conversationTitle?.toString(), style.isGroupConversation)
    }

    private fun toModel(sbn: StatusBarNotification, ranking: RankingMap?): BrigNotification? {
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val live = liveInfo(n)
        if (title.isBlank() && text.isBlank() && live == null) return null
        val r = Ranking()
        val ranked = ranking?.getRanking(sbn.key, r) == true
        val silent = ranked && r.importance <= NotificationManager.IMPORTANCE_LOW
        val conversation = ranked && Build.VERSION.SDK_INT >= 30 && r.isConversation
        val (msgs, convTitle, group) = messages(n)
        val people = runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelableArrayList<android.os.Parcelable>(Notification.EXTRA_PEOPLE_LIST)?.isNotEmpty() == true
        }.getOrDefault(false)
        return BrigNotification(
            key = sbn.key,
            pkg = sbn.packageName,
            appLabel = AppsRepo.label(this, sbn.packageName),
            title = title,
            text = text,
            postTime = sbn.postTime,
            appIcon = AppsRepo.icon(this, sbn.packageName, 96),
            largeIcon = runCatching { n.getLargeIcon()?.loadDrawable(this)?.toBitmap(128, 128)?.asImageBitmap() }.getOrNull(),
            contentIntent = n.contentIntent,
            replyAction = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true },
            silent = silent,
            clearable = sbn.isClearable,
            category = n.category,
            isConversation = conversation,
            hasPeople = people,
            messages = msgs,
            conversationTitle = convTitle,
            isGroupChat = group,
            live = live,
            ongoing = sbn.isOngoing,
            fullScreenIntent = n.fullScreenIntent,
        )
    }

    companion object {
        val notifications = MutableStateFlow<List<BrigNotification>>(emptyList())
        val connected = MutableStateFlow(false)

        @Volatile
        var instance: BrigNotificationListener? = null

        fun dismiss(key: String) {
            instance?.cancelNotification(key)
        }

        fun reply(ctx: Context, action: Notification.Action, text: String): Boolean {
            val inputs = action.remoteInputs ?: return false
            val intent = Intent()
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            return runCatching { action.actionIntent.send(ctx, 0, intent) }.isSuccess
        }
    }
}
