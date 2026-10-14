package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.sync.SyncValue

enum class ReconciledIntent { UPDATE, DELETE, CATEGORIZE, UNLOCK }

/** The warning Actualist shows before a change touches reconciled data (`ReconciledTransactionMutationPresentation`). */
data class ReconciledMutationPresentation(val title: String, val message: String, val confirmationTitle: String) {
    companion object {
        fun make(intent: ReconciledIntent, targetReconciledCount: Int, includesPairedTransfer: Boolean, targetRequiresUnlock: Boolean = true): ReconciledMutationPresentation {
            val title = when (intent) {
                ReconciledIntent.UPDATE -> "Edit Reconciled Transaction?"
                ReconciledIntent.DELETE -> "Delete Reconciled Transaction?"
                ReconciledIntent.CATEGORIZE -> "Categorize Reconciled Transaction?"
                ReconciledIntent.UNLOCK -> "Unlock Reconciled Transaction?"
            }
            val confirmation = when (intent) {
                ReconciledIntent.UPDATE -> "Edit Transaction"
                ReconciledIntent.DELETE -> "Delete Transaction"
                ReconciledIntent.CATEGORIZE -> "Categorize Transaction"
                ReconciledIntent.UNLOCK -> "Unlock Transaction"
            }
            if (intent == ReconciledIntent.UNLOCK) {
                return ReconciledMutationPresentation(
                    title,
                    "This keeps the transaction cleared and removes its reconciliation lock. You can then edit it normally.",
                    confirmation,
                )
            }
            val action = when (intent) {
                ReconciledIntent.UPDATE -> "Editing"
                ReconciledIntent.CATEGORIZE -> "Categorizing"
                else -> "Deleting"
            }
            val message = when {
                targetRequiresUnlock && includesPairedTransfer ->
                    "This transaction and the other side of its transfer include reconciled data. $action it can change previously balanced accounts."
                includesPairedTransfer ->
                    "The other side of this transfer is reconciled. $action this transaction can change a previously balanced account."
                targetReconciledCount > 1 ->
                    "This split transaction includes reconciled entries. $action it can change a previously balanced account."
                else -> "This transaction has been reconciled. $action it can change a previously balanced account."
            }
            return ReconciledMutationPresentation(title, message, confirmation)
        }
    }
}

/** Which rows of one transaction are reconciled: the row and its split entries, and the other side of a transfer. */
data class ReconciledScope(val targetIds: List<String>, val pairedIds: List<String>) {
    val isEmpty: Boolean get() = targetIds.isEmpty() && pairedIds.isEmpty()
}

/**
 * Edits to reconciled transactions after the user confirmed Actualist's warning: the lock is lifted
 * for the change and put back after it (a delete leaves nothing to lock again). The writers refuse
 * locked rows, so every confirmed edit goes unlock, change, relock.
 */
class ReconciledMutations(private val database: BudgetDatabase) {
    private fun live() = "(tombstone IS NULL OR tombstone = 0)"

    /** Live reconciled rows among [ids], their split entries and the other side of their transfers. */
    fun reconciledFamily(ids: Collection<String>): List<String> {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return emptyList()
        val marks = distinct.joinToString(",") { "?" }
        val args = (distinct + distinct + distinct).toTypedArray()
        return database.sql.query(
            "SELECT id FROM transactions WHERE (id IN ($marks) OR parent_id IN ($marks) OR id IN (SELECT transferred_id FROM transactions WHERE id IN ($marks))) " +
                "AND reconciled = 1 AND ${live()} ORDER BY id",
            *args,
        ).mapNotNull { it.string("id") }
    }

    fun hasReconciled(ids: Collection<String>): Boolean = reconciledFamily(ids).isNotEmpty()

    fun scope(id: String): ReconciledScope {
        val family = reconciledFamily(listOf(id))
        val peer = database.sql.queryOne("SELECT transferred_id FROM transactions WHERE id = ?", id)?.string("transferred_id")
        val paired = if (peer == null) emptyList() else family.filter { it == peer }
        return ReconciledScope(family.filter { it !in paired }, paired)
    }

    /** Lifts the lock; returns the rows to pass to [relock] once the change is done. */
    fun unlock(ids: Collection<String>): List<String> {
        val locked = reconciledFamily(ids)
        if (locked.isNotEmpty()) {
            database.commitLocal { locked.map { CellDraft("transactions", it, "reconciled", SyncValue.bool(false)) } to Unit }
        }
        return locked
    }

    /** Locks again the rows that survived the change. */
    fun relock(locked: Collection<String>) {
        val live = locked.filter { database.liveRowExists("transactions", it) }
        if (live.isNotEmpty()) {
            database.commitLocal { live.map { CellDraft("transactions", it, "reconciled", SyncValue.bool(true)) } to Unit }
        }
    }
}
