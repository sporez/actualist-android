package com.sporez.actualist.core.db

import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncValue

/**
 * One committed local batch with the cells that reverse and replay it. Port of Actual's
 * `server/undo.ts` (`undoMessage`, `redoResurrections`): undo writes each touched cell's value
 * from before the batch; a row the batch created is tombstoned instead (budget amounts go to 0,
 * notes to null, the mapping tables are left alone). Redo writes the batch again and clears the
 * tombstone of every row it had created.
 */
class UndoBatch(
    val messages: List<SyncMessage>,
    /** In forward order; history concatenates the batches of a gesture and reverses them. */
    val undoCells: List<CellDraft>,
    val redoCells: List<CellDraft>,
) {
    companion object {
        private val budgetTables = setOf("zero_budget_months", "zero_budgets", "reflect_budgets")
        private val reversibleBudgetColumns = setOf("buffered", "amount", "carryover")
        private val mappingTables = setOf("category_mapping", "payee_mapping")

        fun of(messages: List<SyncMessage>, drafts: List<CellDraft>, oldRows: Map<Pair<String, String>, SqlRow?>): UndoBatch {
            val undo = drafts.mapNotNull { draft -> undoCell(draft, oldRows[draft.dataset to draft.row]) }
            val resurrect = LinkedHashSet<Pair<String, String>>()
            for (draft in drafts) {
                val key = draft.dataset to draft.row
                if (oldRows[key] == null && draft.dataset !in budgetTables && draft.dataset != "notes" && draft.dataset !in mappingTables) {
                    resurrect += key
                }
            }
            val redo = drafts + resurrect.map { (dataset, row) -> CellDraft(dataset, row, "tombstone", SyncValue.int(0)) }
            return UndoBatch(messages, undo, redo)
        }

        fun undoCell(draft: CellDraft, oldRow: SqlRow?): CellDraft? {
            if (oldRow != null) return draft.copy(value = sqlToSyncValue(oldRow[draft.column]))
            return when {
                draft.dataset in mappingTables -> null
                draft.dataset in budgetTables -> if (draft.column in reversibleBudgetColumns) draft.copy(value = SyncValue.int(0)) else null
                draft.dataset == "notes" -> draft.copy(value = SyncValue.Null)
                else -> CellDraft(draft.dataset, draft.row, "tombstone", SyncValue.int(1))
            }
        }

        fun sqlToSyncValue(value: Any?): SyncValue = when (value) {
            null -> SyncValue.Null
            is Long -> SyncValue.Integer(value)
            is Int -> SyncValue.Integer(value.toLong())
            is Double -> SyncValue.Real(value)
            is Boolean -> SyncValue.bool(value)
            is ByteArray -> SyncValue.Text(value.toString(Charsets.UTF_8))
            else -> SyncValue.Text(value.toString())
        }
    }
}
