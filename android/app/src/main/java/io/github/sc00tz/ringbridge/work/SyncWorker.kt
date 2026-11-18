package io.github.sc00tz.ringbridge.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.sc00tz.ringbridge.RingBridgeApp
import io.github.sc00tz.ringbridge.hub.HubError
import io.github.sc00tz.ringbridge.ring.RingError
import io.github.sc00tz.ringbridge.ring.RingOps
import java.util.concurrent.TimeUnit

/**
 * Syncs in the background. The ring only keeps about a day of heart and vitals records, so this runs
 * every 6 hours; missing one is fine, missing a whole day loses data.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = RingBridgeApp.of(applicationContext)
        val address = app.settings.ringAddress ?: return Result.success()
        if (applicationContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return Result.success()
        return try {
            RingOps.sync(applicationContext, app.store, address)
            if (app.hub.signedIn) runCatching { app.hub.flush(app.store) }
            Result.success()
        } catch (e: RingError) {
            // Usually just out of range. Upload anything queued, and try again at the next run.
            if (app.hub.signedIn) runCatching { app.hub.flush(app.store) }
            if (runAttemptCount < 2) Result.retry() else Result.success()
        } catch (e: HubError) {
            Result.success()
        }
    }

    companion object {
        private const val NAME = "ring-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
