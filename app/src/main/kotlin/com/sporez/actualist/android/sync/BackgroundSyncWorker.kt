package com.sporez.actualist.android.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sporez.actualist.android.ActualistApplication
import com.sporez.actualist.core.store.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Syncs the selected server budget while the app is in the background. It shares the UI's open
 * session through [SessionHub] (or opens the budget itself when the app is not on screen), so
 * there is only ever one connection to the file. Demo budgets and signed-out servers are skipped.
 */
class BackgroundSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ActualistApplication).container
        val id = container.settings.settings.value.selectedBudgetId ?: return Result.success()
        val metadata = container.library.metadata(id) ?: return Result.success()
        if (metadata.isDemo || metadata.cloudFileId == null || !container.server.isSignedIn) return Result.success()
        val session = withContext(Dispatchers.IO) {
            container.sessions.acquire(id) { container.library.open(id) ?: error("The selected budget is no longer on this device.") }
        }
        return try {
            if (container.server.sync(session) is SyncStatus.Failed) Result.retry() else Result.success()
        } finally {
            withContext(Dispatchers.IO) { container.sessions.release(session) }
        }
    }

    companion object {
        const val NAME = "actualist-background-sync"

        /** Hourly, on any network; the first run waits, since opening the app syncs anyway. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
