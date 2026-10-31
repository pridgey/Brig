package dev.pridgey.brig.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.pridgey.brig.BrigApp
import dev.pridgey.brig.BuildConfig
import dev.pridgey.brig.R
import dev.pridgey.brig.ui.MainActivity
import dev.pridgey.brig.util.Http
import dev.pridgey.brig.util.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Pulls signed APKs from GitHub Releases and installs them with PackageInstaller. */
object Updater {
    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private const val LATEST = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"

    suspend fun fetchLatest(): ReleaseInfo? =
        ReleaseParser.parse(Http.get(LATEST, mapOf("Accept" to "application/vnd.github+json")))

    suspend fun check(): ReleaseInfo? {
        state.value = UpdateState.Checking
        return try {
            val r = fetchLatest()
            if (r != null && r.versionCode > BuildConfig.VERSION_CODE) {
                state.value = UpdateState.Available(r)
                r
            } else {
                state.value = UpdateState.UpToDate
                null
            }
        } catch (e: Exception) {
            state.value = UpdateState.Error("Couldn't reach GitHub: ${e.message}")
            null
        }
    }

    suspend fun downloadAndInstall(ctx: Context, release: ReleaseInfo) {
        try {
            state.value = UpdateState.Downloading(0f)
            val apk = download(ctx, release)
            state.value = UpdateState.Installing
            withContext(Dispatchers.IO) { install(ctx, apk) }
        } catch (e: Exception) {
            state.value = UpdateState.Error("Update failed: ${e.message}")
        }
    }

    private suspend fun download(ctx: Context, release: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val out = File(dir, "brig-${release.versionCode}.apk")
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastReported = 0f
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val p = read.toFloat() / total
                            if (p - lastReported > 0.01f) {
                                lastReported = p
                                state.value = UpdateState.Downloading(p.coerceAtMost(1f))
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        out
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("brig.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val pending = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { Updater.state.value = UpdateState.Error("Open Brig to finish installing.") }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.state.value = UpdateState.Idle
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Updater.state.value = UpdateState.Error("Install failed: $msg")
            }
        }
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        if (!BrigApp.settings.load().updates.autoCheck) return Result.success()
        val release = runCatching { Updater.fetchLatest() }.getOrElse { return Result.retry() }
            ?: return Result.success()
        if (release.versionCode <= BuildConfig.VERSION_CODE) return Result.success()

        val prefs = applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (prefs.getInt("notified", 0) == release.versionCode) return Result.success()
        Updater.state.value = UpdateState.Available(release)

        if (Notifications.canPost(applicationContext)) {
            val open = PendingIntent.getActivity(
                applicationContext, 2,
                Intent(applicationContext, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SCREEN, "UPDATES")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(applicationContext, Notifications.CH_UPDATES)
                .setSmallIcon(R.drawable.ic_stat_brig)
                .setContentTitle("Brig ${release.tag} is available")
                .setContentText(release.notes.lineSequence().firstOrNull { it.isNotBlank() } ?: "Tap to update")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(applicationContext).notify(Notifications.UPDATE_ID, n)
        }
        prefs.edit().putInt("notified", release.versionCode).apply()
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("brig-update-check", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
