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
import android.provider.MediaStore
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pridgey.brig.BrigApp
import dev.pridgey.brig.data.BrigSettings
import dev.pridgey.brig.data.GestureAction
import dev.pridgey.brig.data.Stats
import dev.pridgey.brig.data.UnlockMethod
import dev.pridgey.brig.service.BrigAccessibilityService
import dev.pridgey.brig.service.BrigNotification
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
        onBackPressedDispatcher.addCallback(this) { controller.gateCancelled() }
        ContextCompat.registerReceiver(
            this, userPresent, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            val settings by BrigApp.settings.flow.collectAsStateWithLifecycle()
            controller.settings = settings
            LaunchedEffect(settings.appearance.hideStatusBar) {
                if (settings.appearance.hideStatusBar) insets.hide(WindowInsetsCompat.Type.statusBars())
                else insets.show(WindowInsetsCompat.Type.statusBars())
            }
            BrigTheme(accent = settings.appearance.accent, dark = true) {
                LockScreen(settings, controller)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        controller.reset()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(userPresent) }
        super.onDestroy()
    }

    companion object {
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

    fun openCamera() {
        try {
            // The secure camera opens over the keyguard without unlocking.
            activity.startActivity(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            requestUnlock {
                activity.startActivity(
                    Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
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

    fun openNotification(n: BrigNotification) = requestUnlock { n.contentIntent?.let(::send) }

    private fun send(pi: PendingIntent) {
        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
        runCatching { pi.send(activity, 0, null, null, null, null, options) }
    }

    fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}
