package dev.pridgey.brig.lock

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import dev.pridgey.brig.data.PhotoRotator
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.pridgey.brig.BrigApp
import dev.pridgey.brig.data.BrigSettings
import dev.pridgey.brig.data.DiagEvent
import dev.pridgey.brig.data.DiagLog
import dev.pridgey.brig.data.GestureAction
import dev.pridgey.brig.data.Stats
import dev.pridgey.brig.data.UnlockMethod
import dev.pridgey.brig.service.BrigAccessibilityService
import dev.pridgey.brig.service.BrigNotification
import dev.pridgey.brig.service.BrigNotificationListener
import dev.pridgey.brig.ui.BrigTheme
import dev.pridgey.brig.util.Device
import dev.pridgey.brig.util.Notifications
import dev.pridgey.brig.util.Torch

/**
 * The lock screen itself. It shows over the system keyguard, which stays underneath and keeps
 * providing real security. Unlocking asks the system to dismiss the keyguard.
 */
class LockActivity : ComponentActivity() {
    private lateinit var controller: LockController

    /** Set when opened from a photo editor to preview one photo; back returns to the editor. */
    private val previewPhoto = mutableStateOf<String?>(null)

    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Unlocked with fingerprint/face/PIN from the system: get out of the way.
            val km = getSystemService(KeyguardManager::class.java)
            if (km.isKeyguardSecure && BrigApp.settings.flow.value.unlock.autoHideOnSystemUnlock) {
                controller.finishLock()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(false)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.navigationBars())

        NotificationManagerCompat.from(this).cancel(Notifications.LOCK_FSI_ID)
        controller = LockController(this)
        previewPhoto.value = intent.getStringExtra(EXTRA_PREVIEW)
        if (previewPhoto.value == null) rotatePhoto()
        onBackPressedDispatcher.addCallback(this) {
            if (previewPhoto.value != null) finish() else controller.gateCancelled()
        }
        ContextCompat.registerReceiver(
            this, userPresent, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        watchIncomingCalls()

        setContent {
            val settings by BrigApp.settings.flow.collectAsStateWithLifecycle()
            controller.settings = settings
            LaunchedEffect(settings.appearance.hideStatusBar) {
                if (settings.appearance.hideStatusBar) insets.hide(WindowInsetsCompat.Type.statusBars())
                else insets.show(WindowInsetsCompat.Type.statusBars())
            }
            BrigTheme(accent = settings.appearance.accent, dark = true) {
                LockScreen(settings, controller, previewPhoto.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller.reset()
        previewPhoto.value = intent.getStringExtra(EXTRA_PREVIEW)
        if (previewPhoto.value == null) rotatePhoto()
    }

    /**
     * With the display on, Android sees Brig as an app in use and only shows a heads-up for a
     * ringing call instead of the dialer's call screen. Open that call screen ourselves, once per call.
     */
    private fun watchIncomingCalls() {
        val handled = mutableSetOf<String>()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                BrigNotificationListener.notifications.collect { list ->
                    val ringing = list.filter { it.isIncomingCall }
                    handled.retainAll(ringing.map { it.key }.toSet())
                    ringing.firstOrNull { it.key !in handled }?.let {
                        handled += it.key
                        controller.openCall(it)
                    }
                }
            }
        }
    }

    private fun rotatePhoto() {
        val ctx = applicationContext
        dev.pridgey.brig.data.DiagLog.record(ctx, dev.pridgey.brig.data.DiagEvent.LOCK_SHOWN)
        dev.pridgey.brig.data.Flashcards.nextLock(ctx)
        BrigApp.instance.appScope.launch { PhotoRotator.maybeRotate(ctx, BrigApp.settings.load().photos) }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(userPresent) }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PREVIEW = "preview_photo"

        /** Shows the real lock screen with [path] as the photo, to check exactly how it looks. */
        fun preview(ctx: Context, path: String) {
            ctx.startActivity(
                Intent(ctx, LockActivity::class.java)
                    .putExtra(EXTRA_PREVIEW, path)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        fun show(ctx: Context) {
            ctx.startActivity(
                Intent(ctx, LockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }
}

class LockController(private val activity: ComponentActivity) {
    var settings: BrigSettings = BrigSettings()

    /** Set while the PIN / math gate is showing; holds what to do once it passes. */
    var gate by mutableStateOf<(() -> Unit)?>(null)
        private set

    private var finished = false
    private val km = activity.getSystemService(KeyguardManager::class.java)
    val systemLockSecure: Boolean get() = km.isKeyguardSecure

    fun requestUnlock(then: (() -> Unit)? = null) {
        val proceed = { dismissKeyguard(then) }
        val u = settings.unlock
        val needsGate = when (u.method) {
            UnlockMethod.SWIPE -> false
            UnlockMethod.PIN -> u.pinHash != null && u.pinSalt != null
            UnlockMethod.MATH -> true
        }
        if (needsGate) gate = proceed else proceed()
    }

    fun gatePassed() {
        val g = gate
        gate = null
        g?.invoke()
    }

    fun gateCancelled() {
        gate = null
    }

    private fun dismissKeyguard(then: (() -> Unit)?) {
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finishLock(then)
            })
        } else {
            finishLock(then)
        }
    }

    fun finishLock(then: (() -> Unit)? = null) {
        if (finished) return
        finished = true
        Stats.recordUnlock(activity)
        runCatching { then?.invoke() }
        activity.finish()
    }

    fun reset() {
        finished = false
        gate = null
    }

    fun perform(action: GestureAction, appPkg: String?) {
        when (action) {
            GestureAction.NONE -> Unit
            GestureAction.UNLOCK -> requestUnlock()
            GestureAction.CAMERA -> openCamera()
            GestureAction.FLASHLIGHT -> Torch.toggle(activity)
            GestureAction.SLEEP -> sleep()
            GestureAction.TOGGLE_DND -> toggleDnd()
            GestureAction.APP -> appPkg?.let(::launchApp) ?: toast("Pick an app for this gesture in Brig")
        }
    }

    fun launchApp(pkg: String) = requestUnlock {
        activity.packageManager.getLaunchIntentForPackage(pkg)?.let {
            activity.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun diag(event: DiagEvent) = DiagLog.record(activity, event)

    /**
     * Points [action] at one camera app. With several cameras installed and no default, Android
     * would show its app chooser instead, and that chooser can't appear over the lock screen.
     */
    private fun cameraIntent(action: String): Intent {
        val pm = activity.packageManager
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val handlers = runCatching { pm.queryIntentActivities(Intent(action), 0) }.getOrDefault(emptyList())
        if (handlers.size <= 1) return intent
        val preferred = Camera.preferredPackage(
            handlers.map { it.activityInfo.packageName },
            default = pm.resolveActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName,
            isSystem = { pkg -> handlers.first { it.activityInfo.packageName == pkg }.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 },
        )
        val pick = handlers.first { it.activityInfo.packageName == preferred }.activityInfo
        return intent.setClassName(pick.packageName, pick.name)
    }

    fun openCamera() {
        val plain = {
            diag(DiagEvent.CAMERA_PLAIN)
            requestUnlock {
                try {
                    activity.startActivity(cameraIntent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                } catch (e: Exception) {
                    diag(DiagEvent.CAMERA_FAILED)
                    toast("No camera app found")
                }
            }
        }
        // The secure camera opens over the keyguard without unlocking, but only while it's locked;
        // otherwise there's nothing to open over, so go straight to the normal camera.
        if (!km.isKeyguardLocked) return plain()
        diag(DiagEvent.CAMERA_SECURE)
        try {
            activity.startActivity(cameraIntent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE))
        } catch (e: Exception) {
            return plain()
        }
        // Some camera apps accept the secure request and then quietly do nothing. If Brig is still
        // in front a moment later, the camera never came up: fall back to unlocking first.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!activity.isFinishing && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                diag(DiagEvent.CAMERA_FAILED)
                plain()
            }
        }, 1500)
    }

    fun sleep() {
        if (!BrigAccessibilityService.lockScreen()) toast("Turn on Brig's accessibility service to use this")
    }

    fun toggleDnd() {
        if (!Device.toggleDnd(activity)) toast("Grant Do Not Disturb access in Brig first")
    }

    fun cycleRinger() {
        Device.cycleRinger(activity)
    }

    fun openNotification(n: BrigNotification) {
        if (n.isCall) openCall(n) else requestUnlock { n.contentIntent?.let(::send) }
    }

    /** The dialer's call screen shows over the keyguard, so calls never need an unlock. */
    fun openCall(n: BrigNotification) {
        (n.fullScreenIntent ?: n.contentIntent)?.let(::send)
    }

    private fun send(pi: PendingIntent) {
        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
        runCatching { pi.send(activity, 0, null, null, null, null, options) }
    }

    fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    private fun start(intent: Intent) {
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast("No app can open that")
        }
    }

    // ---------------------------------------------------------------- Widgets

    /** Asks the clock app to dismiss the next alarm. Google Clock skips upcoming alarms this way. */
    fun skipNextAlarm() = requestUnlock {
        start(
            Intent(AlarmClock.ACTION_DISMISS_ALARM)
                .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT),
        )
    }

    fun openAlarm(show: PendingIntent?) = requestUnlock {
        if (show != null) send(show) else start(Intent(AlarmClock.ACTION_SHOW_ALARMS))
    }

    /** Long-press on the Packages widget: Brig's package list, where packages can be removed. */
    fun openPackageSettings() = requestUnlock {
        start(
            Intent(activity, dev.pridgey.brig.ui.MainActivity::class.java)
                .putExtra(dev.pridgey.brig.ui.MainActivity.EXTRA_SCREEN, dev.pridgey.brig.ui.Screen.PACKAGES.name),
        )
    }

    /** Long-press on the Flight tracker widget: the flight list. */
    fun openFlightSettings() = requestUnlock {
        start(
            Intent(activity, dev.pridgey.brig.ui.MainActivity::class.java)
                .putExtra(dev.pridgey.brig.ui.MainActivity.EXTRA_SCREEN, dev.pridgey.brig.ui.Screen.FLIGHTS.name),
        )
    }

    fun openUri(uri: Uri, type: String? = null) = requestUnlock {
        val i = Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (type != null) i.setDataAndType(uri, type) else i.data = uri
        start(i)
    }

    /** Copies only once the phone is unlocked, so codes never sit on the clipboard while locked. */
    fun copyAfterUnlock(text: String) = requestUnlock {
        val cm = activity.getSystemService(android.content.ClipboardManager::class.java)
        val clip = android.content.ClipData.newPlainText("Code", text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        cm.setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < 33) toast("Code copied")
    }

    // ---------------------------------------------------------------- Guest mode

    private fun setGuest(on: Boolean) {
        BrigApp.instance.appScope.launch { BrigApp.settings.update { it.copy(guest = it.guest.copy(active = on)) } }
    }

    fun enterGuest() {
        setGuest(true)
        toast("Guest mode on")
    }

    /** Leaving guest mode needs the Brig PIN, or the system lock if there's no PIN. */
    fun exitGuest() {
        val u = settings.unlock
        when {
            u.method == UnlockMethod.PIN && u.pinHash != null && u.pinSalt != null -> gate = { setGuest(false) }
            km.isKeyguardSecure -> km.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    setGuest(false)
                    finishLock()
                }
            })
            else -> {
                setGuest(false)
                toast("Set a Brig PIN or a system lock to protect guest mode")
            }
        }
    }

    /** Opens an allowed app without Brig's own PIN or math gate. A secure system lock still applies. */
    fun launchGuestApp(pkg: String) = dismissKeyguard {
        activity.packageManager.getLaunchIntentForPackage(pkg)?.let { start(it) }
    }
}

/** Which camera app the lock screen opens. Pure, for tests. */
object Camera {
    /** The user's default camera if it's among [candidates], else a built-in one, else the first. */
    fun preferredPackage(candidates: List<String>, default: String?, isSystem: (String) -> Boolean): String =
        default?.takeIf { it in candidates }
            ?: candidates.firstOrNull(isSystem)
            ?: candidates.first()
}
