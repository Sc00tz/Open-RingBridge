package dev.ringbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts RingService automatically after the device boots.
 *
 * Registered in AndroidManifest with RECEIVE_BOOT_COMPLETED.
 * Only starts the service if the server has been configured — no point
 * starting BLE scanning if the app was never set up.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings.isConfigured(context)) {
            Log.d(TAG, "Boot: server not configured, skipping auto-start")
            return
        }
        Log.i(TAG, "Boot complete — starting RingService")
        RingService.start(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
