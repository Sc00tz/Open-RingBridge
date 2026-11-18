package io.github.sc00tz.ringbridge.work

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.sc00tz.ringbridge.R
import io.github.sc00tz.ringbridge.RingBridgeApp
import io.github.sc00tz.ringbridge.ring.RingOps
import io.github.sc00tz.ringbridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Keeps a workout recording with the screen off. Started from the Record screen, stopped from it or the notification. */
class WorkoutService : Service() {
    data class State(
        val running: Boolean = false, val kind: String? = null, val startedAt: Long = 0, val message: String = "",
        val update: RingOps.WorkoutUpdate? = null, val error: String? = null, val finishedId: Long? = null,
    )

    companion object {
        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state
        @Volatile private var stopRequested = false

        fun start(context: Context, kind: String) {
            context.startForegroundService(Intent(context, WorkoutService::class.java).putExtra("kind", kind))
        }

        fun stop() { stopRequested = true }
        fun clear() { if (!_state.value.running) _state.value = State() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stop(); return START_NOT_STICKY }
        val kind = intent?.getStringExtra("kind") ?: return START_NOT_STICKY
        if (_state.value.running) return START_NOT_STICKY
        startForeground(1, notification("Starting ${RingOps.WORKOUTS.getValue(kind).first.lowercase()}"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        stopRequested = false
        _state.value = State(true, kind, System.currentTimeMillis(), "Connecting")
        val app = RingBridgeApp.of(this)
        scope.launch {
            try {
                val id = RingOps.workout(this@WorkoutService, app.store, app.settings.ringAddress!!, kind, { u ->
                    _state.value = _state.value.copy(message = u.message, update = u)
                }, { stopRequested })
                _state.value = _state.value.copy(running = false, finishedId = id, message = "Saved")
                if (app.hub.signedIn) runCatching { app.hub.flush(app.store) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(running = false, error = e.message ?: "The workout stopped.")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("tab", "record"), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WorkoutService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, RingBridgeApp.WORKOUT_CHANNEL)
            .setSmallIcon(R.drawable.ic_ring).setContentTitle("Workout recording").setContentText(text)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
