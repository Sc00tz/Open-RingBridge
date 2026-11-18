package io.github.sc00tz.ringbridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.github.sc00tz.ringbridge.data.Store
import io.github.sc00tz.ringbridge.hub.HubClient
import io.github.sc00tz.ringbridge.work.SyncWorker

class RingBridgeApp : Application() {
    lateinit var store: Store
    lateinit var hub: HubClient
    lateinit var settings: Settings

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        hub = HubClient(this)
        settings = Settings(this)
        store.closeInterruptedWorkouts()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(WORKOUT_CHANNEL, "Workouts", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while a workout is recording" })
        if (settings.ringAddress != null && settings.backgroundSync) SyncWorker.schedule(this)
    }

    companion object {
        const val WORKOUT_CHANNEL = "workout"
        fun of(context: Context) = context.applicationContext as RingBridgeApp
    }
}

/** Which ring, and the app's own preferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var ringAddress: String?
        get() = prefs.getString("ring_address", null)
        set(v) = prefs.edit().putString("ring_address", v).apply()
    var ringName: String?
        get() = prefs.getString("ring_name", null)
        set(v) = prefs.edit().putString("ring_name", v).apply()
    var backgroundSync: Boolean
        get() = prefs.getBoolean("background_sync", true)
        set(v) = prefs.edit().putBoolean("background_sync", v).apply()
}
