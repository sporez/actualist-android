package com.sporez.actualist.core.store

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.UndoBatch
import com.sporez.actualist.core.sync.SyncMessage

/**
 * Undo and redo for local edits, ported from Actual's `server/undo.ts`: each recorded gesture
 * (one [record] call, Actual's undo marker) keeps the batches it committed; undo writes their
 * [UndoBatch.undoCells] in reverse order with fresh timestamps, redo writes the batches again
 * and resurrects the rows they created. Like Actual, the history lives in memory only and is
 * lost when the budget closes.
 *
 * Unlike Actual, a gesture whose rows changed since (a later local edit or a synced change to
 * any row it touched) cannot be undone or redone: Actual would silently overwrite the newer
 * values. This is Actualist's intended OP-76, which also compares whole rows.
 */
class UndoHistory(private val database: BudgetDatabase, private val limit: Int = 100) {
    private class Entry(val batches: List<UndoBatch>) {
        /** The touched rows as they must still be for the next undo or redo. */
        var rows: Map<Pair<String, String>, Map<String, String>?> = merged(batches)
    }

    private val entries = ArrayList<Entry>()
    private var cursor = 0
    private var recording: MutableList<UndoBatch>? = null

    init {
        database.undoListener = { batch -> recording?.add(batch) }
    }

    val canUndo: Boolean @Synchronized get() = cursor > 0
    val canRedo: Boolean @Synchronized get() = cursor < entries.size

    data class Item(val summary: String, val undone: Boolean)

    /** Recorded gestures, newest first, for a History screen. */
    @Synchronized
    fun items(): List<Item> = entries.mapIndexed { index, entry -> Item(describe(entry.batches), index >= cursor) }.reversed()

    /** Runs [block], recording everything it commits as one gesture. Nested calls join the outer one. */
    @Synchronized
    fun <T> record(block: () -> T): T {
        if (recording != null) return block()
        val batches = mutableListOf<UndoBatch>()
        recording = batches
        val result = try {
            block()
        } finally {
            recording = null
        }
        if (batches.isNotEmpty()) {
            while (entries.size > cursor) entries.removeAt(entries.size - 1)
            entries += Entry(batches)
            if (entries.size > limit) entries.removeAt(0)
            cursor = entries.size
        }
        return result
    }

    /** Reverses the latest gesture and returns the cells written; null when there is nothing to undo. */
    @Synchronized
    fun undo(): List<SyncMessage>? {
        if (!canUndo) return null
        val entry = entries[cursor - 1]
        requireUnchanged(entry.rows, "This change was edited again since, so it can no longer be undone.")
        val written = commitCapturing(entry, entry.batches.flatMap { it.undoCells }.reversed())
        cursor -= 1
        return written
    }

    /** Re-applies the gesture undone last and returns the cells written; null when there is nothing to redo. */
    @Synchronized
    fun redo(): List<SyncMessage>? {
        if (!canRedo) return null
        val entry = entries[cursor]
        requireUnchanged(entry.rows, "This budget changed since the undo, so it can no longer be redone.")
        val written = commitCapturing(entry, entry.batches.flatMap { it.redoCells })
        cursor += 1
        return written
    }

    /** Commits [cells] and records the rows they leave as the entry's next expected state. */
    private fun commitCapturing(entry: Entry, cells: List<com.sporez.actualist.core.db.CellDraft>): List<SyncMessage> {
        val captured = mutableListOf<UndoBatch>()
        recording = captured
        val written = try {
            database.commitLocal(cells)
        } finally {
            recording = null
        }
        entry.rows = entry.rows + merged(captured)
        return written
    }

    @Synchronized
    fun clear() {
        entries.clear()
        cursor = 0
    }

    private fun requireUnchanged(rows: Map<Pair<String, String>, Map<String, String>?>, reason: String) {
        for ((key, expected) in rows) {
            if (database.rowSnapshot(key.first, key.second) != expected) throw LocalFirstException.InvalidLocalWrite(reason)
        }
    }

    private companion object {
        private val alwaysChanged = setOf("zero_budgets", "reflect_budgets", "zero_budget_months", "notes")

        private val labels = mapOf(
            "transactions" to ("transaction" to "transactions"),
            "zero_budgets" to ("budget amount" to "budget amounts"),
            "reflect_budgets" to ("budget amount" to "budget amounts"),
            "zero_budget_months" to ("month" to "months"),
            "categories" to ("category" to "categories"),
            "category_groups" to ("category group" to "category groups"),
            "payees" to ("payee" to "payees"),
            "accounts" to ("account" to "accounts"),
            "rules" to ("rule" to "rules"),
            "schedules" to ("schedule" to "schedules"),
            "notes" to ("note" to "notes"),
            "transaction_filters" to ("saved filter" to "saved filters"),
        )

        /** "Added 1 transaction, 1 payee; changed 1 budget amount", from the rows the gesture touched. */
        fun describe(batches: List<UndoBatch>): String {
            // A budget cell or note row appearing for the first time reads as a change, not an addition.
            val created = batches.flatMap { it.createdRows }.filter { it.first !in alwaysChanged }.toSet()
            val deleted = batches.flatMap { it.deletedRows }.toSet() - created
            val touched = batches.flatMap { batch -> batch.messages.map { it.dataset to it.row } }.toSet()
            val changed = touched - created - deleted
            fun phrase(verb: String, rows: Set<Pair<String, String>>): String? {
                val counts = rows.filter { it.first in labels }.groupingBy { it.first }.eachCount()
                if (counts.isEmpty()) return null
                return verb + " " + counts.entries.joinToString(", ") { (dataset, n) ->
                    val (one, many) = labels.getValue(dataset)
                    "$n ${if (n == 1) one else many}"
                }
            }
            val parts = listOfNotNull(phrase("Added", created), phrase("Deleted", deleted), phrase("Changed", changed))
            return if (parts.isEmpty()) "Changed the budget" else parts.joinToString("; ").let { first ->
                first.substring(0, 1) + first.substring(1).replace("; Deleted", "; deleted").replace("; Changed", "; changed")
            }
        }

        fun merged(batches: List<UndoBatch>): Map<Pair<String, String>, Map<String, String>?> {
            val rows = LinkedHashMap<Pair<String, String>, Map<String, String>?>()
            for (batch in batches) rows.putAll(batch.rowsAfter)
            return rows
        }
    }
}
