package dev.pridgey.brig

import android.app.Application
import dev.pridgey.brig.data.SettingsRepository
import dev.pridgey.brig.update.UpdateWorker
import dev.pridgey.brig.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BrigApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settingsRepo: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        dev.pridgey.brig.data.CrashLog.install(this)
        // The ML Kit helper process (see SegmentService) needs none of the app's start-up work.
        if (getProcessName().endsWith(":segment")) return
        settingsRepo = SettingsRepository(this, appScope)
        Notifications.createChannels(this)
        dev.pridgey.brig.data.PhotoRotator.restore(this)
        dev.pridgey.brig.data.PackageRepo.load(this)
        dev.pridgey.brig.data.MoodStore.load(this)
        dev.pridgey.brig.data.DiagLog.load(this)
        if (!BuildConfig.DEBUG) UpdateWorker.schedule(this)
    }

    companion object {
        lateinit var instance: BrigApp
            private set

        val settings: SettingsRepository get() = instance.settingsRepo
    }
}
