package com.sporez.actualist.core.notes

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncValue

/** Whose note it is; Actual keys `notes` rows by these ids (Actualist `ActualNoteTarget.noteID`). */
sealed class NoteTarget(val noteId: String, val table: String?, val entityId: String) {
    class Category(id: String) : NoteTarget(id, "categories", id)
    class CategoryGroup(id: String) : NoteTarget(id, "category_groups", id)
    class Account(id: String) : NoteTarget("account-$id", "accounts", id)
    class BudgetMonth(month: String) : NoteTarget("budget-$month", null, month)
}

/**
 * A stored note split into the user's text and the reserved directive lines (`#template`, `#goal`,
 * `#cleanup`) that budget templates read. Port of Actualist `ActualNoteBody`: editing the user's
 * text never drops or reorders the directives.
 */
class NoteBody(storedNote: String?) {
    val userBody: String
    val reservedLines: List<String>

    init {
        val lines = (storedNote ?: "").replace("\r\n", "\n").replace("\r", "\n").split("\n")
        reservedLines = lines.filter(::isReservedDirective)
        userBody = lines.filterNot(::isReservedDirective).joinToString("\n")
    }

    val displayText: String? get() = userBody.trim().ifEmpty { null }

    fun persistedNote(newUserBody: String): String? {
        val hasUserBody = newUserBody.isNotBlank()
        if (!hasUserBody && reservedLines.isEmpty()) return null
        if (!hasUserBody) return reservedLines.joinToString("\n")
        if (reservedLines.isEmpty()) return newUserBody
        return if (newUserBody.endsWith("\n")) newUserBody + reservedLines.joinToString("\n")
        else newUserBody + "\n" + reservedLines.joinToString("\n")
    }

    private companion object {
        fun isReservedDirective(line: String): Boolean {
            val normalized = line.trim().lowercase()
            return normalized.startsWith("#template") || normalized.startsWith("#goal") || normalized.startsWith("#cleanup")
        }
    }
}

/** Reads and writes entity notes as Actualist's `fetchEntityNote` / `setEntityNoteMessages` do. */
class EntityNotes(private val database: BudgetDatabase) {
    fun read(target: NoteTarget): String? = NoteBody(stored(target)).displayText

    private fun stored(target: NoteTarget): String? =
        if (!database.tableExists("notes")) null else database.sql.queryString("SELECT note FROM notes WHERE id = ? LIMIT 1", target.noteId)

    fun drafts(target: NoteTarget, userBody: String): List<CellDraft> {
        database.requiredColumns("notes", listOf("id", "note"))
        target.table?.let { table ->
            if (!database.liveRowExists(table, target.entityId)) throw LocalFirstException.InvalidLocalWrite("this ${table.removeSuffix("s")} no longer exists")
        }
        val existing = stored(target)
        val persisted = NoteBody(existing).persistedNote(userBody)
        if (persisted == existing) return emptyList()
        return listOf(CellDraft("notes", target.noteId, "note", SyncValue.textOrNull(persisted)))
    }

    fun write(target: NoteTarget, userBody: String): List<SyncMessage> = database.commitLocal { drafts(target, userBody) to Unit }.messages
}
