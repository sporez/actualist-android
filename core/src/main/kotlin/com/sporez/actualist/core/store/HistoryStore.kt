package com.sporez.actualist.core.store

import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Sql
import com.sporez.actualist.core.db.UndoBatch
import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The touched rows as an entry expects them for its next undo or redo. */
typealias HistoryRows = Map<Pair<String, String>, Map<String, String>?>

/** One persisted gesture. */
class StoredHistoryEntry(
    val id: Long,
    val createdAtMillis: Long,
    val undone: Boolean,
    val batches: List<UndoBatch>,
    val rows: HistoryRows,
    val automatic: Boolean = false,
)

/**
 * The local-only table behind [UndoHistory]: `actualist_history` in the budget database. The
 * `actualist_` prefix keeps it out of sync ([BudgetDatabase.isReservedDataset]) and out of
 * exports and imports (`BudgetImporter.sanitize` drops every `actualist_` table), and deleting
 * or resetting a budget deletes its database file, history included.
 */
class HistoryStore(private val database: BudgetDatabase) {
    private val sql: Sql get() = database.sql

    private fun tableExists(): Boolean =
        sql.queryOne("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", TABLE) != null

    private fun ensureTable() {
        sql.execute(
            "CREATE TABLE IF NOT EXISTS $TABLE (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, " +
                "undone INTEGER NOT NULL DEFAULT 0, batches TEXT NOT NULL, rows TEXT NOT NULL, " +
                "automatic INTEGER NOT NULL DEFAULT 0)",
        )
        addAutomaticColumn()
    }

    /** Additive: a table written before the flag existed gains the column. */
    private fun addAutomaticColumn() {
        val has = sql.query("PRAGMA table_info($TABLE)").any { it.string("name") == "automatic" }
        if (!has) sql.execute("ALTER TABLE $TABLE ADD COLUMN automatic INTEGER NOT NULL DEFAULT 0")
    }

    /** Entries oldest first, after dropping those older than [maxAgeMillis] and beyond [limit]. */
    fun load(limit: Int, maxAgeMillis: Long, nowMillis: Long): List<StoredHistoryEntry> {
        if (!tableExists()) return emptyList()
        sql.transaction {
            addAutomaticColumn()
            prune(limit, maxAgeMillis, nowMillis)
        }
        val loaded = ArrayList<StoredHistoryEntry>()
        for (row in sql.query("SELECT id, created_at, undone, batches, rows, automatic FROM $TABLE ORDER BY id")) {
            val entry = try {
                StoredHistoryEntry(
                    id = row.longOr0("id"),
                    createdAtMillis = row.longOr0("created_at"),
                    undone = row.longOr0("undone") != 0L,
                    batches = decodeBatches(row.string("batches") ?: ""),
                    rows = decodeRows(row.string("rows") ?: ""),
                    automatic = row.longOr0("automatic") != 0L,
                )
            } catch (error: Exception) {
                null
            }
            if (entry != null) loaded += entry
        }
        return loaded
    }

    /** Stores a new gesture, dropping the redo tail it replaces, and returns its id. Call inside a transaction. */
    fun append(batches: List<UndoBatch>, rows: HistoryRows, limit: Int, maxAgeMillis: Long, nowMillis: Long, automatic: Boolean = false): Long {
        ensureTable()
        sql.execute("DELETE FROM $TABLE WHERE undone = 1")
        sql.execute(
            "INSERT INTO $TABLE (created_at, undone, batches, rows, automatic) VALUES (?, 0, ?, ?, ?)",
            nowMillis,
            encodeBatches(batches),
            encodeRows(rows),
            if (automatic) 1 else 0,
        )
        val id = sql.queryLong("SELECT MAX(id) FROM $TABLE") ?: 0L
        prune(limit, maxAgeMillis, nowMillis)
        return id
    }

    /** Records an undo or redo of [id]. Call inside the transaction that wrote the cells. */
    fun update(id: Long, undone: Boolean, rows: HistoryRows) {
        ensureTable()
        sql.execute("UPDATE $TABLE SET undone = ?, rows = ? WHERE id = ?", if (undone) 1 else 0, encodeRows(rows), id)
    }

    fun clear() {
        if (tableExists()) sql.execute("DELETE FROM $TABLE")
    }

    fun count(): Int = if (tableExists()) (sql.queryLong("SELECT COUNT(*) FROM $TABLE") ?: 0L).toInt() else 0

    private fun prune(limit: Int, maxAgeMillis: Long, nowMillis: Long) {
        sql.execute("DELETE FROM $TABLE WHERE created_at < ?", nowMillis - maxAgeMillis)
        sql.execute("DELETE FROM $TABLE WHERE id NOT IN (SELECT id FROM $TABLE ORDER BY id DESC LIMIT ?)", limit)
    }

    companion object {
        const val TABLE = "actualist_history"

        internal fun encodeBatches(batches: List<UndoBatch>): String = JsonArray(
            batches.map { batch ->
                buildJsonObject {
                    put("messages", JsonArray(batch.messages.map { m -> strings(m.timestamp, m.dataset, m.row, m.column, m.serializedValue) }))
                    put("undo", JsonArray(batch.undoCells.map { cell(it) }))
                    put("redo", JsonArray(batch.redoCells.map { cell(it) }))
                    put("rows", encodeRowsElement(batch.rowsAfter))
                    put("created", JsonArray(batch.createdRows.map { strings(it.first, it.second) }))
                }
            },
        ).toString()

        internal fun decodeBatches(text: String): List<UndoBatch> = Json.parseToJsonElement(text).jsonArray.map { element ->
            val obj = element.jsonObject
            UndoBatch(
                messages = obj.getValue("messages").jsonArray.map { m ->
                    val s = m.jsonArray.map { it.jsonPrimitive.content }
                    SyncMessage(s[0], s[1], s[2], s[3], s[4])
                },
                undoCells = obj.getValue("undo").jsonArray.map { decodeCell(it) },
                redoCells = obj.getValue("redo").jsonArray.map { decodeCell(it) },
                rowsAfter = decodeRowsElement(obj.getValue("rows")),
                createdRows = obj.getValue("created").jsonArray.map {
                    val s = it.jsonArray.map { part -> part.jsonPrimitive.content }
                    s[0] to s[1]
                }.toSet(),
            )
        }

        internal fun encodeRows(rows: HistoryRows): String = encodeRowsElement(rows).toString()

        internal fun decodeRows(text: String): HistoryRows = decodeRowsElement(Json.parseToJsonElement(text))

        private fun strings(vararg values: String): JsonElement = JsonArray(values.map { JsonPrimitive(it) })

        private fun cell(draft: CellDraft): JsonElement = strings(draft.dataset, draft.row, draft.column, draft.value.serialized)

        private fun decodeCell(element: JsonElement): CellDraft {
            val s = element.jsonArray.map { it.jsonPrimitive.content }
            return CellDraft(s[0], s[1], s[2], SyncValue.deserialize(s[3]))
        }

        private fun encodeRowsElement(rows: HistoryRows): JsonElement = JsonArray(
            rows.map { (key, snapshot) ->
                buildJsonObject {
                    put("d", JsonPrimitive(key.first))
                    put("r", JsonPrimitive(key.second))
                    put(
                        "s",
                        if (snapshot == null) {
                            JsonNull
                        } else {
                            JsonObject(snapshot.mapValues { JsonPrimitive(it.value) })
                        },
                    )
                }
            },
        )

        private fun decodeRowsElement(element: JsonElement): HistoryRows {
            val rows = LinkedHashMap<Pair<String, String>, Map<String, String>?>()
            for (item in element.jsonArray) {
                val obj = item.jsonObject
                val key = obj.getValue("d").jsonPrimitive.content to obj.getValue("r").jsonPrimitive.content
                val snapshot = obj.getValue("s")
                rows[key] = if (snapshot is JsonNull) {
                    null
                } else {
                    snapshot.jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" }
                }
            }
            return rows
        }
    }
}
