package dev.pridgey.brig.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.pridgey.brig.BuildConfig
import dev.pridgey.brig.service.BrigAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.random.Random

object Http {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Brig/${BuildConfig.VERSION_NAME}")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/** Small wrappers around system state Brig reads or toggles. */
object Device {
    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val expected = ComponentName(ctx, BrigAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    fun isNotificationListenerEnabled(ctx: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)
    private fun am(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    fun hasDndAccess(ctx: Context) = nm(ctx).isNotificationPolicyAccessGranted
    fun isDndOn(ctx: Context) = nm(ctx).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL

    fun toggleDnd(ctx: Context): Boolean {
        if (!hasDndAccess(ctx)) return false
        nm(ctx).setInterruptionFilter(
            if (isDndOn(ctx)) NotificationManager.INTERRUPTION_FILTER_ALL
            else NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        )
        return true
    }

    fun ringerMode(ctx: Context) = am(ctx).ringerMode

    /** Normal → vibrate → silent (silent needs DND access) → normal. */
    fun cycleRinger(ctx: Context): Boolean {
        val am = am(ctx)
        val next = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE ->
                if (hasDndAccess(ctx)) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        return runCatching { am.ringerMode = next }.isSuccess
    }

    @SuppressLint("InlinedApi")
    fun headphonesConnected(ctx: Context): Boolean {
        val types = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        return am(ctx).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    fun nextAlarm(ctx: Context): Long? = ctx.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime

    fun canInstallPackages(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun ignoringBatteryOptimizations(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun hasPermission(ctx: Context, permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
}

object Torch {
    val on = MutableStateFlow(false)
    private var cameraId: String? = null
    private var registered = false

    fun init(ctx: Context) {
        if (registered) return
        val cm = ctx.applicationContext.getSystemService(CameraManager::class.java)
        cameraId = runCatching {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        cm.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == cameraId) on.value = enabled
            }
        }, Handler(Looper.getMainLooper()))
        registered = true
    }

    fun toggle(ctx: Context) {
        init(ctx)
        val id = cameraId ?: return
        val cm = ctx.applicationContext.getSystemService(CameraManager::class.java)
        runCatching { cm.setTorchMode(id, !on.value) }
    }
}

object Notifications {
    const val CH_UPDATES = "updates"
    const val CH_LOCK = "lock"
    const val CH_SERVICE = "service"
    const val UPDATE_ID = 10
    const val LOCK_FSI_ID = 11
    const val SERVICE_ID = 12

    fun createChannels(ctx: Context) {
        NotificationManagerCompat.from(ctx).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CH_UPDATES, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("App updates").build(),
                NotificationChannelCompat.Builder(CH_LOCK, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Lock screen launcher")
                    .setDescription("Used to show Brig when the accessibility service is off")
                    .setSound(null, null).setVibrationEnabled(false).build(),
                NotificationChannelCompat.Builder(CH_SERVICE, NotificationManagerCompat.IMPORTANCE_MIN)
                    .setName("Background service").build(),
            ),
        )
    }

    fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        Device.hasPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
}

object PinHasher {
    private const val ITERATIONS = 120_000

    fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.let(Base64.getEncoder()::encodeToString)

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun verify(pin: String, salt: String, expected: String) =
        MessageDigest.isEqual(hash(pin, salt).toByteArray(), expected.toByteArray())
}

object WordClock {
    private val names = listOf(
        "twelve", "one", "two", "three", "four", "five", "six",
        "seven", "eight", "nine", "ten", "eleven",
    )

    /** "It's quarter past ten", rounded to the nearest five minutes. */
    fun phrase(hour: Int, minute: Int): String {
        var h = hour % 12
        var m = ((minute + 2) / 5) * 5
        if (m == 60) {
            m = 0
            h = (h + 1) % 12
        }
        val cur = names[h]
        val next = names[(h + 1) % 12]
        val body = when (m) {
            0 -> "$cur o'clock"
            5 -> "five past $cur"
            10 -> "ten past $cur"
            15 -> "quarter past $cur"
            20 -> "twenty past $cur"
            25 -> "twenty-five past $cur"
            30 -> "half past $cur"
            35 -> "twenty-five to $next"
            40 -> "twenty to $next"
            45 -> "quarter to $next"
            50 -> "ten to $next"
            else -> "five to $next"
        }
        val about = if (minute % 5 != 0) "about " else ""
        return "It's $about$body"
    }
}

data class MathProblem(val text: String, val answer: Int)

object MathProblems {
    fun generate(difficulty: Int, rnd: Random = Random.Default): MathProblem = when (difficulty.coerceIn(1, 3)) {
        1 -> {
            val a = rnd.nextInt(5, 40)
            val b = rnd.nextInt(2, 30)
            if (rnd.nextBoolean()) MathProblem("$a + $b", a + b)
            else MathProblem("${maxOf(a, b)} − ${minOf(a, b)}", maxOf(a, b) - minOf(a, b))
        }
        2 -> {
            val a = rnd.nextInt(3, 13)
            val b = rnd.nextInt(3, 13)
            MathProblem("$a × $b", a * b)
        }
        else -> {
            val a = rnd.nextInt(3, 13)
            val b = rnd.nextInt(3, 13)
            val c = rnd.nextInt(10, 100)
            MathProblem("$a × $b + $c", a * b + c)
        }
    }
}

object Quotes {
    private val all = listOf(
        "The best way out is always through." to "Robert Frost",
        "Well done is better than well said." to "Benjamin Franklin",
        "Simplicity is the ultimate sophistication." to "Leonardo da Vinci",
        "It always seems impossible until it's done." to "Nelson Mandela",
        "What we think, we become." to "Buddha",
        "Act as if what you do makes a difference. It does." to "William James",
        "The secret of getting ahead is getting started." to "Mark Twain",
        "Stay hungry, stay foolish." to "Stewart Brand",
        "Not all those who wander are lost." to "J.R.R. Tolkien",
        "Turn your wounds into wisdom." to "Oprah Winfrey",
        "Little by little, one travels far." to "J.R.R. Tolkien",
        "Whatever you are, be a good one." to "Abraham Lincoln",
        "Dream big and dare to fail." to "Norman Vaughan",
        "Make each day your masterpiece." to "John Wooden",
        "Do what you can, with what you have, where you are." to "Theodore Roosevelt",
        "Happiness depends upon ourselves." to "Aristotle",
        "Everything you can imagine is real." to "Pablo Picasso",
        "Be so good they can't ignore you." to "Steve Martin",
        "The journey of a thousand miles begins with one step." to "Lao Tzu",
        "Fortune favors the bold." to "Virgil",
        "Nothing will work unless you do." to "Maya Angelou",
        "Done is better than perfect." to "Sheryl Sandberg",
        "Quality is not an act, it is a habit." to "Aristotle",
        "Keep your face always toward the sunshine." to "Walt Whitman",
        "Courage is grace under pressure." to "Ernest Hemingway",
        "If you're going through hell, keep going." to "Winston Churchill",
        "Be curious, not judgmental." to "Walt Whitman",
        "The only way to do great work is to love what you do." to "Steve Jobs",
    )

    fun forDay(dayOfYear: Int) = all[dayOfYear % all.size]
}
