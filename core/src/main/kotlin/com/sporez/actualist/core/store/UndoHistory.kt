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
 * Unlike Actual, a gesture whose cells were written again since (by a later local edit or a
 * synced change) cannot be undone or redone: Actual would silently overwrite the newer value.
 * This is Actualist's intended OP-76.
 */
class UndoHistory(private val database: BudgetDatabase, private val limit: Int = 100) {
    private class Entry(val batches: List<UndoBatch>) {
        /** The messages whose values must still be in place for the next undo or redo. */
        var current: List<SyncMessage> = batches.flatMap { it.messages }
    }

    private val entries = ArrayList<Entry>()
    private var cursor = 0
    private var recording: MutableList<UndoBatch>? = null

    init {
        database.undoListener = { batch -> recording?.add(batch) }
    }

    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < entries.size

    /** Runs [block], recording everything it commits as one gesture. Nested calls join the outer one. */
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
    fun undo(): List<SyncMessage>? {
        if (!canUndo) return null
        val entry = entries[cursor - 1]
        requireUnchanged(entry.current, "This change was edited again since, so it can no longer be undone.")
        val cells = entry.batches.flatMap { it.undoCells }.reversed()
        val written = database.commitLocal(cells)
        entry.current = written
        cursor -= 1
        return written
    }

    /** Re-applies the gesture undone last and returns the cells written; null when there is nothing to redo. */
    fun redo(): List<SyncMessage>? {
        if (!canRedo) return null
        val entry = entries[cursor]
        requireUnchanged(entry.current, "This budget changed since the undo, so it can no longer be redone.")
        val cells = entry.batches.flatMap { it.redoCells }
        val written = database.commitLocal(cells)
        entry.current = written
        cursor += 1
        return written
    }

    fun clear() {
        entries.clear()
        cursor = 0
    }

    /**
     * Every cell must still hold the value [messages] left in it (the last write per cell). Values,
     * not timestamps: undoing a later gesture legitimately rewrites cells an earlier one set.
     */
    private fun requireUnchanged(messages: List<SyncMessage>, reason: String) {
        val latest = LinkedHashMap<Triple<String, String, String>, String>()
        for (message in messages) latest[Triple(message.dataset, message.row, message.column)] = message.serializedValue
        for ((cell, serialized) in latest) {
            if (!sameValue(database.cellValue(cell.first, cell.second, cell.third).serialized, serialized)) {
                throw LocalFirstException.InvalidLocalWrite(reason)
            }
        }
    }

    /** SQLite may store a number written as text in a numeric column; compare the payloads. */
    private fun sameValue(stored: String, written: String): Boolean {
        if (stored == written) return true
        val a = stored.substringAfter(':')
        val b = written.substringAfter(':')
        if (stored.startsWith("0:") || written.startsWith("0:")) return false
        val x = a.toDoubleOrNull()
        val y = b.toDoubleOrNull()
        return a == b || (x != null && y != null && x == y)
    }
}
