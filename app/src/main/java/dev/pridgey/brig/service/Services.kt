package dev.pridgey.brig.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import dev.pridgey.brig.BrigApp
import dev.pridgey.brig.R
import dev.pridgey.brig.data.AppsRepo
import dev.pridgey.brig.data.Stats
import dev.pridgey.brig.lock.LockActivity
import dev.pridgey.brig.ui.MainActivity
import dev.pridgey.brig.util.Device
import dev.pridgey.brig.util.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The main trigger. Accessibility services are exempt from Android's background-activity-start
 * limits, so this can open the lock screen the moment the display turns off.
 */
class BrigAccessibilityService : AccessibilityService() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF ->
                    if (BrigApp.settings.flow.value.enabled) LockActivity.show(this@BrigAccessibilityService)
                Intent.ACTION_SCREEN_ON -> Stats.recordWake(context)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        running.value = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // The fallback service isn't needed while this one is running.
        stopService(Intent(this, LockService::class.java))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        instance = null
        running.value = false
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: BrigAccessibilityService? = null
        val running = MutableStateFlow(false)

        fun lockScreen(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
    }
}

data class MediaState(
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    val playing: Boolean,
    val appLabel: String,
    val controller: MediaController,
    val playbackState: Int,
    /** elapsedRealtime of the last position update (for paused sessions, roughly when it paused). */
    val updatedAt: Long,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
) {
    fun isVisible(v: dev.pridgey.brig.data.MediaVisibility): Boolean {
        if (title.isBlank()) return false
        return when (v) {
            dev.pridgey.brig.data.MediaVisibility.PLAYING -> playing
            dev.pridgey.brig.data.MediaVisibility.RECENT -> playing ||
                (playbackState == PlaybackState.STATE_PAUSED && android.os.SystemClock.elapsedRealtime() - updatedAt < 10 * 60_000L)
            dev.pridgey.brig.data.MediaVisibility.ALWAYS ->
                playbackState != PlaybackState.STATE_NONE && playbackState != PlaybackState.STATE_ERROR
        }
    }
}

/** Tracks the active media session. Needs notification access to see sessions. */
object MediaRepo {
    val state = MutableStateFlow<MediaState?>(null)
    private var manager: MediaSessionManager? = null
    private var current: MediaController? = null
    private var appContext: Context? = null
    private var component: ComponentName? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = emit()
        // Another player may have started; re-pick so the playing one wins.
        override fun onPlaybackStateChanged(state: PlaybackState?) = repick()
        override fun onSessionDestroyed() {
            current = null
            emit()
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    fun start(ctx: Context) {
        appContext = ctx.applicationContext
        val m = ctx.getSystemService(MediaSessionManager::class.java)
        manager = m
        val component = ComponentName(ctx, BrigNotificationListener::class.java)
        this.component = component
        runCatching {
            m.addOnActiveSessionsChangedListener(sessionsListener, component)
            pick(m.getActiveSessions(component))
        }
    }

    fun stop() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        current?.unregisterCallback(callback)
        current = null
        state.value = null
    }

    private fun repick() {
        val m = manager
        val c = component
        if (m == null || c == null) return emit()
        runCatching { pick(m.getActiveSessions(c)) }.onFailure { emit() }
    }

    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (best?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(callback)
            current = best
            best?.registerCallback(callback)
        }
        emit()
    }

    private fun emit() {
        val c = current
        val md = c?.metadata
        if (c == null || md == null) {
            state.value = null
            return
        }
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val ps = c.playbackState
        state.value = MediaState(
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            art = art?.asImageBitmap(),
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            appLabel = appContext?.let { AppsRepo.label(it, c.packageName) } ?: c.packageName,
            controller = c,
            playbackState = ps?.state ?: PlaybackState.STATE_NONE,
            updatedAt = ps?.lastPositionUpdateTime ?: 0L,
            positionMs = ps?.position ?: 0L,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            speed = ps?.playbackSpeed ?: 1f,
        )
    }
}

/**
 * Fallback trigger when the accessibility service is off: a foreground service that posts a
 * full-screen-intent notification when the screen turns off.
 */
class LockService : Service() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF && BrigApp.settings.flow.value.enabled) showLock()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, Notifications.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_brig)
            .setContentTitle("Brig is running in fallback mode")
            .setContentText("Turn on Brig's accessibility service for faster launching.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(
            this, Notifications.SERVICE_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun showLock() {
        if (!Notifications.canPost(this)) return
        val pi = PendingIntent.getActivity(
            this, 1,
            Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, Notifications.CH_LOCK)
            .setSmallIcon(R.drawable.ic_stat_brig)
            .setContentTitle("Brig")
            .setContentText("Lock screen")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .build()
        NotificationManagerCompat.from(this).notify(Notifications.LOCK_FSI_ID, n)
    }

    companion object {
        fun sync(ctx: Context, enabled: Boolean) {
            val intent = Intent(ctx, LockService::class.java)
            if (enabled && !Device.isAccessibilityEnabled(ctx)) {
                runCatching { ContextCompat.startForegroundService(ctx, intent) }
            } else {
                ctx.stopService(intent)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        BrigApp.instance.appScope.launch {
            try {
                LockService.sync(context, BrigApp.settings.load().enabled)
            } finally {
                pending.finish()
            }
        }
    }
}
