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
        settingsRepo = SettingsRepository(this, appScope)
        Notifications.createChannels(this)
        if (!BuildConfig.DEBUG) UpdateWorker.schedule(this)
    }

    companion object {
        lateinit var instance: BrigApp
            private set

        val settings: SettingsRepository get() = instance.settingsRepo
    }
}
