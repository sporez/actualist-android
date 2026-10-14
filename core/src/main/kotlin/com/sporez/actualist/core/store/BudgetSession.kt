package com.sporez.actualist.core.store

import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.budget.BudgetWrites
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.reports.ReportReads
import com.sporez.actualist.core.transactions.LedgerReads
import com.sporez.actualist.core.transactions.TransactionWrites
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * What the app knows about the server copy of this budget. Local state and confirmed
 * server state are kept apart: [Synced] is only reported after the server confirmed every
 * uploaded change and the merkle trees matched.
 */
sealed class SyncStatus {
    /** The demo budget: every change stays on this device. */
    data object LocalOnly : SyncStatus()
    data class Idle(val pendingChanges: Int, val lastSyncedAtMillis: Long?) : SyncStatus()
    data object Syncing : SyncStatus()
    data class Synced(val atMillis: Long, val applied: Int, val uploaded: Int) : SyncStatus()
    data class Failed(val message: String, val pendingChanges: Int) : SyncStatus()
}

data class UndoState(val canUndo: Boolean, val canRedo: Boolean)

/**
 * One open budget. Reads run on [io]; writes are serialized and bump [revision] so
 * screens reload from the database, the single source of truth.
 */
class BudgetSession(
    val budgetId: String,
    val name: String,
    val database: BudgetDatabase,
    val isDemo: Boolean,
    val clock: Clock = Clock.SYSTEM,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
    val budget = BudgetReads(database)
    val ledger = LedgerReads(database)
    val reports = ReportReads(database)
    val budgetWrites = BudgetWrites(database)
    val transactionWrites = TransactionWrites(database, clock)

    /** Undo and redo of this session's writes (in memory, as in Actual). */
    val history = UndoHistory(database)
    private val _undoState = MutableStateFlow(UndoState(false, false))
    val undoState: StateFlow<UndoState> = _undoState.asStateFlow()

    private val writeLock = Mutex()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus>(
        if (isDemo) SyncStatus.LocalOnly else SyncStatus.Idle(database.pendingLocalMessageCount(), null),
    )
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    suspend fun <T> read(block: BudgetSession.() -> T): T = withContext(io) { block() }

    /**
     * Runs a local write. In demo mode the outbox is drained afterwards so nothing can
     * ever be uploaded; otherwise the change waits in the outbox for the next sync.
     */
    suspend fun <T> write(block: BudgetSession.() -> T): T = writeLock.withLock {
        withContext(io) {
            val result = history.record { block() }
            if (isDemo) database.drainAllPendingLocalMessages()
            result
        }.also { changed() }
    }

    /** Undoes the latest write; false when there was nothing to undo. Throws when it was edited since. */
    suspend fun undo(): Boolean = historyStep { history.undo() != null }

    /** Redoes the write undone last; false when there was nothing to redo. */
    suspend fun redo(): Boolean = historyStep { history.redo() != null }

    private suspend fun historyStep(step: () -> Boolean): Boolean = writeLock.withLock {
        withContext(io) {
            val stepped = step()
            if (isDemo) database.drainAllPendingLocalMessages()
            stepped
        }.also { changed() }
    }

    /** Runs a sync pass under the write lock, so no local write interleaves with it. */
    suspend fun <T> syncing(block: BudgetSession.() -> T): T = writeLock.withLock {
        withContext(io) { block() }.also { changed() }
    }

    fun publishSyncStatus(status: SyncStatus) {
        _syncStatus.value = status
    }

    fun changed() {
        _undoState.value = UndoState(history.canUndo, history.canRedo)
        _revision.value = _revision.value + 1
    }

    override fun close() = database.close()
}
