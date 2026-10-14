package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.sync.SyncValue
import java.util.UUID

/** How many selected transactions a batch changed and how many it left alone (locked or not applicable). */
data class BatchResult(val changed: Int, val skipped: Int)

/**
 * Actions on several selected transactions at once, as Actualist's multi-select offers: clear or
 * unclear, categorize, and delete. Every change is planned first and committed in one batch, so
 * the action is atomic and undoes as one gesture. Reconciled rows are locked and skipped, as are
 * rows an action does not apply to (categorizing a split, a transfer or an off-budget row).
 */
class TransactionBatch(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val writes get() = TransactionWrites(database, clock, newId)

    private data class Row(val id: String, val isParent: Boolean, val isChild: Boolean, val reconciled: Boolean, val cleared: Boolean, val isTransfer: Boolean)

    private fun rows(ids: Collection<String>): List<Row> = ids.distinct().mapNotNull { id ->
        database.sql.queryOne(
            "SELECT id, isParent, isChild, reconciled, cleared, transferred_id FROM transactions WHERE id = ? AND (tombstone IS NULL OR tombstone = 0)",
            id,
        )?.let { Row(it.string("id")!!, it.bool("isParent"), it.bool("isChild"), it.bool("reconciled"), it.bool("cleared"), it.string("transferred_id") != null) }
    }

    /** [plan] returns the row's cells, an empty list when an earlier row already changed it, or null to skip it. */
    private fun commit(ids: Collection<String>, plan: (Row) -> List<CellDraft>?): BatchResult = database.commitLocal {
        val drafts = mutableListOf<CellDraft>()
        var changed = 0
        val found = rows(ids)
        for (row in found) {
            val cells = (if (row.reconciled || row.isChild) null else plan(row)) ?: continue
            drafts.addAll(cells)
            changed += 1
        }
        drafts to BatchResult(changed, ids.distinct().size - changed)
    }.outcome

    /** A split parent's children follow its cleared state, as Actual's family update does. */
    fun setCleared(ids: Collection<String>, cleared: Boolean): BatchResult = commit(ids) { row ->
        if (row.cleared == cleared) return@commit null
        val family = if (row.isParent) {
            database.sql.query("SELECT id FROM transactions WHERE parent_id = ? AND (tombstone IS NULL OR tombstone = 0)", row.id).mapNotNull { it.string("id") }
        } else {
            emptyList()
        }
        (listOf(row.id) + family).map { CellDraft("transactions", it, "cleared", SyncValue.bool(cleared)) }
    }

    fun categorize(ids: Collection<String>, categoryId: String): BatchResult {
        if (!database.liveRowExists("categories", categoryId)) throw LocalFirstException.InvalidLocalWrite("missing category")
        return commit(ids) { row ->
            if (row.isParent || row.isTransfer) return@commit null
            try {
                writes.updateDrafts(row.id, TransactionEdit(categoryId = categoryId)).takeIf { drafts ->
                    // An off-budget row keeps no category: skip it rather than count it.
                    drafts.any { it.column == "category" }
                }
            } catch (error: LocalFirstException.InvalidLocalWrite) {
                null
            }
        }
    }

    fun delete(ids: Collection<String>): BatchResult {
        val tombstoned = HashSet<String>()
        return commit(ids) { row ->
            if (row.id in tombstoned) return@commit emptyList()
            // A transfer's paired leg may also be selected: tombstone each row once.
            writes.deleteDrafts(row.id).filter { tombstoned.add(it.row) }
        }
    }
}
