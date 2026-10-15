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
 * The last few background passes (start, finish, outcome and a short category), kept in
 * SharedPreferences for the diagnostic report. Messages are fixed categories, never server text.
 */
object BackgroundSyncRecord {
    private const val PREFS = "actualist_background_sync_record"
    private const val MAX = 5

    class Entry(val raw: String, val startedAt: Long, val completedAt: Long, val outcome: String, val message: String)

    fun record(context: Context, startedAt: Long, outcome: String, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val line = "$startedAt|${System.currentTimeMillis()}|$outcome|${message.replace("|", "/").replace("\n", " ")}"
        val kept = (listOf(line) + history(context).map { it.raw }).take(MAX)
        prefs.edit().putString("runs", kept.joinToString("\n")).putInt("total", prefs.getInt("total", 0) + 1).apply()
    }

    fun history(context: Context): List<Entry> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("runs", null) ?: return emptyList()
        return text.lines().mapNotNull { raw ->
            val parts = raw.split("|", limit = 4)
            if (parts.size < 4) return@mapNotNull null
            val started = parts[0].toLongOrNull() ?: return@mapNotNull null
            val completed = parts[1].toLongOrNull() ?: return@mapNotNull null
            Entry(raw, started, completed, parts[2], parts[3])
        }
    }

    fun total(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("total", 0)
}

/**
 * Syncs the selected server budget while the app is in the background. It shares the UI's open
 * session through [SessionHub] (or opens the budget itself when the app is not on screen), so
 * there is only ever one connection to the file. Demo budgets and signed-out servers are skipped.
 */
class BackgroundSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val startedAt = System.currentTimeMillis()
        return try {
            runPass(startedAt)
        } catch (error: Exception) {
            BackgroundSyncRecord.record(applicationContext, startedAt, "failed", error.javaClass.simpleName)
            throw error
        }
    }

    private suspend fun runPass(startedAt: Long): Result {
        val container = (applicationContext as ActualistApplication).container
        fun skipped(reason: String): Result {
            BackgroundSyncRecord.record(applicationContext, startedAt, "skipped", reason)
            return Result.success()
        }
        val id = container.settings.settings.value.selectedBudgetId ?: return skipped("no budget selected")
        val metadata = container.library.metadata(id) ?: return skipped("budget not on this device")
        if (metadata.isDemo || metadata.cloudFileId == null) return skipped("budget is not a server budget")
        if (!container.server.isSignedIn) return skipped("credentials unavailable on this device")
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
            val failed = status is SyncStatus.Failed
            BackgroundSyncRecord.record(applicationContext, startedAt, if (failed) "failed" else "completed", if (failed) "sync failed" else "ok")
            if (failed) Result.retry() else Result.success()
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
