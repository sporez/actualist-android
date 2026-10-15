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
import com.sporez.actualist.android.notifications.TransactionAlertNotifier
import com.sporez.actualist.android.notifications.TransactionAlertPrefs
import com.sporez.actualist.core.notifications.PendingNewTransactionSource
import com.sporez.actualist.core.notifications.PendingNewTransactions
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.store.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
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
            val pending = PendingNewTransactions(session.database)
            val before = withContext(Dispatchers.IO) { pending.liveTransactionAccounts() }
            val status = container.server.sync(session)
            withContext(Dispatchers.IO) { recordNewTransactions(session, pending, before) }
            if (status !is SyncStatus.Failed) {
                // Actual runs schedule advancement after a successful sync; what it posts is ours, not "new".
                val advanced = ScheduleAdvancer.advance(applicationContext, session)
                val banked = if (BackgroundBankSyncPrefs.isEnabled(applicationContext)) {
                    BackgroundBankSync.run(
                        applicationContext,
                        session,
                        container.settings.settings.value.serverUrl,
                        container.server,
                        container.secrets,
                    )
                } else {
                    0
                }
                // Upload what advancement and the bank posted so other devices do not post the same occurrence again.
                if (ScheduleAdvancer.changedData(advanced) || banked > 0) container.server.sync(session)
            }
            com.sporez.actualist.android.widget.WidgetData.refresh(applicationContext)
            if (status is SyncStatus.Failed) Result.retry() else Result.success()
        } finally {
            withContext(Dispatchers.IO) { container.sessions.release(session) }
        }
    }

    /**
     * Transactions that appeared during this pass are the ones other devices (or the bank) added,
     * since nothing edits locally while the app is in the background. They are recorded as
     * pending-new; one notification is posted when alerts are on, otherwise the delivery is
     * suppressed (the rows still show the "new" marker).
     */
    private fun recordNewTransactions(session: BudgetSession, pending: PendingNewTransactions, before: Map<String, String>) {
        val fresh = pending.newSince(before)
        if (fresh.isEmpty()) return
        val alerts = TransactionAlertPrefs.isEnabled(applicationContext)
        val notificationId = UUID.randomUUID().toString()
        val added = pending.record(fresh, PendingNewTransactionSource.REMOTE_SYNC, notificationId, alreadyAcknowledged = false)
        if (added == 0) return
        if (alerts) {
            val delivery = pending.delivery()
            if (delivery != null && TransactionAlertNotifier.post(applicationContext)) {
                pending.acknowledgeDelivery(delivery.transactionIds)
            }
        } else {
            pending.suppressDeliveries()
        }
        session.changed()
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
