package com.sporez.actualist.core.store

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.UndoBatch
import com.sporez.actualist.core.sync.SyncMessage

/**
 * Undo and redo for local edits, ported from Actual's `server/undo.ts`: each recorded gesture
 * (one [record] call, Actual's undo marker) keeps the batches it committed; undo writes their
 * [UndoBatch.undoCells] in reverse order with fresh timestamps, redo writes the batches again
 * and resurrects the rows they created. Unlike Actual, the history is
 * kept in the budget database's local-only
 * `actualist_history` table ([HistoryStore]) and loaded again when a session opens, so it
 * survives a restart; it is bounded by [limit] gestures and [maxAgeMillis].
 *
 * Unlike Actual, a gesture whose rows changed since (a later local edit or a synced change to
 * any row it touched) cannot be undone or redone: Actual would silently overwrite the newer
 * values. This is Actualist's intended OP-76, which also compares whole rows.
 */
class UndoHistory(
    private val database: BudgetDatabase,
    private val limit: Int = 100,
    private val maxAgeMillis: Long = THIRTY_DAYS_MILLIS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    persist: Boolean = true,
) {
    private class Entry(val batches: List<UndoBatch>, rows: HistoryRows? = null) {
        var id: Long = 0L

        /** The touched rows as they must still be for the next undo or redo. */
        var rows: HistoryRows = rows ?: merged(batches)
    }

    private val entries = ArrayList<Entry>()
    private var cursor = 0
    private var recording: MutableList<UndoBatch>? = null

    private val store: HistoryStore? = if (persist) HistoryStore(database) else null

    init {
        database.undoListener = { batch -> recording?.add(batch) }
        if (store != null) {
            val loaded = try {
                store.load(limit, maxAgeMillis, nowMillis())
            } catch (error: Exception) {
                emptyList()
            }
            for (stored in loaded) {
                entries += Entry(stored.batches, stored.rows).also { it.id = stored.id }
            }
            // Undone gestures are always the newest ones: the cursor sits before the first.
            cursor = loaded.indexOfFirst { it.undone }.let { if (it < 0) loaded.size else it }
        }
    }

    val canUndo: Boolean @Synchronized get() = cursor > 0
    val canRedo: Boolean @Synchronized get() = cursor < entries.size

    data class Item(val summary: String, val undone: Boolean)

    /** Recorded gestures, newest first, for a History screen. */
    @Synchronized
    fun items(): List<Item> = entries.mapIndexed { index, entry -> Item(describe(entry.batches), index >= cursor) }.reversed()

    /** A recorded gesture with its batches, for [HistoryPresenter]. */
    class Detail(val batches: List<UndoBatch>, val undone: Boolean)

    /** Recorded gestures, newest first, with their batches. */
    @Synchronized
    fun details(): List<Detail> = entries.mapIndexed { index, entry -> Detail(entry.batches, index >= cursor) }.reversed()

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
            val entry = Entry(batches)
            entries += entry
            if (entries.size > limit) entries.removeAt(0)
            cursor = entries.size
            if (store != null) {
                // A failed save leaves the in-memory history working; it only will not survive a restart.
                try {
                    database.sql.transaction { entry.id = store.append(batches, entry.rows, limit, maxAgeMillis, nowMillis()) }
                } catch (error: Exception) {
                    entry.id = 0L
                }
            }
        }
        return result
    }

    /** Reverses the latest gesture and returns the cells written; null when there is nothing to undo. */
    @Synchronized
    fun undo(): List<SyncMessage>? {
        if (!canUndo) return null
        val entry = entries[cursor - 1]
        requireUnchanged(entry.rows, "This change was edited again since, so it can no longer be undone.")
        val written = commitCapturing(entry, entry.batches.flatMap { it.undoCells }.reversed(), undone = true)
        cursor -= 1
        return written
    }

    /** Re-applies the gesture undone last and returns the cells written; null when there is nothing to redo. */
    @Synchronized
    fun redo(): List<SyncMessage>? {
        if (!canRedo) return null
        val entry = entries[cursor]
        requireUnchanged(entry.rows, "This budget changed since the undo, so it can no longer be redone.")
        val written = commitCapturing(entry, entry.batches.flatMap { it.redoCells }, undone = false)
        cursor += 1
        return written
    }

    /** Commits [cells] and records the rows they leave as the entry's next expected state. */
    private fun commitCapturing(entry: Entry, cells: List<com.sporez.actualist.core.db.CellDraft>, undone: Boolean): List<SyncMessage> {
        val captured = mutableListOf<UndoBatch>()
        recording = captured
        var rows = entry.rows
        // The cells and the history row commit together, so a crash cannot leave them disagreeing.
        val written = try {
            database.sql.transaction {
                val committed = database.commitLocal(cells)
                rows = entry.rows + merged(captured)
                if (store != null && entry.id != 0L) store.update(entry.id, undone, rows)
                committed
            }
        } finally {
            recording = null
        }
        entry.rows = rows
        return written
    }

    @Synchronized
    fun clear() {
        entries.clear()
        cursor = 0
        try {
            store?.clear()
        } catch (error: Exception) {
            // The in-memory history is already empty.
        }
    }

    private fun requireUnchanged(rows: Map<Pair<String, String>, Map<String, String>?>, reason: String) {
        for ((key, expected) in rows) {
            if (database.rowSnapshot(key.first, key.second) != expected) throw LocalFirstException.InvalidLocalWrite(reason)
        }
    }

    private companion object {
        const val THIRTY_DAYS_MILLIS = 30L * 24 * 60 * 60 * 1000

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
                // In the label table's order, so the summary does not depend on write order.
                return verb + " " + labels.keys.filter { it in counts }.joinToString(", ") { dataset ->
                    val n = counts.getValue(dataset)
                    val (one, many) = labels.getValue(dataset)
                    "$n ${if (n == 1) one else many}"
                }
            }
            val parts = listOfNotNull(phrase("Added", created), phrase("Deleted", deleted), phrase("Changed", changed))
            return if (parts.isEmpty()) "Changed the budget" else parts.joinToString("; ").let { first ->
                first.substring(0, 1) + first.substring(1).replace("; Deleted", "; deleted").replace("; Changed", "; changed")
            }
        }

        fun merged(batches: List<UndoBatch>): HistoryRows {
            val rows = LinkedHashMap<Pair<String, String>, Map<String, String>?>()
            for (batch in batches) rows.putAll(batch.rowsAfter)
            return rows
        }
    }
}
