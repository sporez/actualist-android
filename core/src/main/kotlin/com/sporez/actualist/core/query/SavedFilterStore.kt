package com.sporez.actualist.core.query

import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.rules.RuleCondition
import com.sporez.actualist.core.rules.RuleConditionJoin
import com.sporez.actualist.core.sync.SyncValue

/** Saved transaction filters in the `transaction_filters` table, ported from Actualist `+TransactionFilters`. */
internal class SavedFilterStore(private val database: BudgetDatabase) {
    private val sql get() = database.sql

    /** Actualist `fetchSavedTransactionFilters`: every stored row, tombstoned ones included. */
    fun read(): SavedFilterReadResult = sql.locked {
        if (!database.tableExists(TABLE)) {
            return@locked SavedFilterReadResult.Unavailable("Saved filters are not available in this budget")
        }
        val columns = database.columnSet(TABLE)
        if (!columns.containsAll(listOf("id", "name", "conditions"))) {
            return@locked SavedFilterReadResult.Unavailable("Saved-filter data columns are unavailable")
        }
        val joinColumn = if ("conditions_op" in columns) "conditions_op" else "NULL"
        val tombstoneColumn = if ("tombstone" in columns) "tombstone" else "0"
        val rows = sql.query(
            """
            SELECT id, name, conditions, $joinColumn AS conditions_op, $tombstoneColumn AS tombstone
            FROM transaction_filters
            ORDER BY name, id
            """.trimIndent(),
        )
        if (rows.any { it.string("id") == null }) {
            return@locked SavedFilterReadResult.Unavailable("Saved-filter rows are missing stable identifiers")
        }
        val filters = rows.map { row ->
            SavedFilter.project(
                id = row.string("id") ?: "",
                name = row.string("name"),
                rawConditionsJson = row.string("conditions"),
                conditionsOperation = row.string("conditions_op"),
                tombstone = row.longOr0("tombstone") != 0L,
            )
        }.sortedWith(Comparator<SavedFilter> { left, right ->
            val leftName = left.name.trim()
            val rightName = right.name.trim()
            if (leftName == rightName) left.id.compareTo(right.id) else StandardCompare.compare(leftName, rightName)
        })
        SavedFilterReadResult.Available(filters)
    }

    /** Creates a filter and returns its id. Throws `InvalidLocalWrite` on a duplicate name or duplicate conditions. */
    fun create(id: String, name: String, conditions: List<RuleCondition>, join: RuleConditionJoin): String {
        database.commitLocal {
            val columns = writableColumns()
            val normalizedName = validatedName(name)
            val peers = liveFilters()
            SavedFilterComparator.duplicateName(normalizedName, null, peers)?.let {
                throw SavedFilterMutationError.DuplicateName(it.name).exception()
            }
            if (conditions.isEmpty()) throw SavedFilterMutationError.MissingConditions.exception()
            SavedFilterComparator.duplicateConditions(conditions, join, null, peers)?.let {
                throw SavedFilterMutationError.DuplicateConditions(it.name).exception()
            }
            val raw = encodedSupportedConditions(conditions, join)
            messages(id, normalizedName, raw, join, false, columns) to id
        }
        return id
    }

    /**
     * Renames and/or replaces the conditions of a live, supported filter (Actualist
     * `updateSavedTransactionFilter`). Null [name] or null [conditions] leave that part alone.
     * Throws `InvalidLocalWrite` for a missing or unsupported filter, a blank or duplicate name,
     * or empty or duplicate conditions.
     */
    fun update(id: String, name: String?, conditions: List<RuleCondition>?, join: RuleConditionJoin?) {
        database.commitLocal {
            val columns = writableColumns()
            val peers = liveFilters()
            val existing = peers.firstOrNull { it.id == id } ?: throw SavedFilterMutationError.MissingFilter.exception()
            if (!existing.isSupported) throw SavedFilterMutationError.UnsupportedFilter.exception()
            val newName = name?.let { validatedName(it) }
            if (newName != null) {
                SavedFilterComparator.duplicateName(newName, id, peers)?.let {
                    throw SavedFilterMutationError.DuplicateName(it.name).exception()
                }
            }
            var raw: String? = null
            var newJoin: RuleConditionJoin? = null
            if (conditions != null) {
                if (conditions.isEmpty()) throw SavedFilterMutationError.MissingConditions.exception()
                newJoin = join ?: RuleConditionJoin.fromRaw(existing.conditionsOperation ?: "and") ?: RuleConditionJoin.AND
                SavedFilterComparator.duplicateConditions(conditions, newJoin, id, peers)?.let {
                    throw SavedFilterMutationError.DuplicateConditions(it.name).exception()
                }
                raw = encodedSupportedConditions(conditions, newJoin)
            }
            messages(id, newName, raw, newJoin, null, columns) to Unit
        }
    }

    /** Marks the filter deleted. Throws `InvalidLocalWrite` when no live filter has the id. */
    fun delete(id: String) {
        database.commitLocal {
            writableColumns()
            if (liveFilters().none { it.id == id }) throw SavedFilterMutationError.MissingFilter.exception()
            listOf(CellDraft(TABLE, id, "tombstone", SyncValue.bool(true))) to Unit
        }
    }

    private fun writableColumns(): Set<String> {
        if (!database.tableExists(TABLE)) throw SavedFilterMutationError.Unavailable.exception()
        val columns = database.columnSet(TABLE)
        if (!columns.containsAll(listOf("id", "name", "conditions", "conditions_op", "tombstone"))) {
            throw SavedFilterMutationError.Unavailable.exception()
        }
        return columns
    }

    private fun liveFilters(): List<SavedFilter> {
        val columns = database.columnSet(TABLE)
        return sql.query(
            """
            SELECT id, name, conditions, conditions_op, tombstone
            FROM transaction_filters
            WHERE ${BudgetDatabase.livePredicate(columns)}
            ORDER BY id
            """.trimIndent(),
        ).mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            SavedFilter.project(id, row.string("name"), row.string("conditions"), row.string("conditions_op"), false)
        }
    }

    private fun validatedName(name: String): String {
        val normalized = name.trim()
        if (normalized.isEmpty()) throw SavedFilterMutationError.InvalidName.exception()
        return normalized
    }

    private fun encodedSupportedConditions(conditions: List<RuleCondition>, join: RuleConditionJoin): String {
        val raw = SavedFilterJson.encodeConditions(conditions)
        val projection = SavedFilter.project("validation", "validation", raw, join.raw, false)
        if (!projection.isSupported) throw SavedFilterMutationError.UnsupportedFilter.exception()
        return raw
    }

    private fun messages(
        id: String,
        name: String?,
        conditions: String?,
        join: RuleConditionJoin?,
        tombstone: Boolean?,
        columns: Set<String>,
    ): List<CellDraft> {
        val drafts = mutableListOf<CellDraft>()
        if (name != null) drafts.add(CellDraft(TABLE, id, "name", SyncValue.text(name)))
        if (conditions != null) {
            drafts.add(CellDraft(TABLE, id, "conditions", SyncValue.text(conditions)))
            if (join != null && "conditions_op" in columns) {
                drafts.add(CellDraft(TABLE, id, "conditions_op", SyncValue.text(join.raw)))
            }
        }
        if (tombstone != null && "tombstone" in columns) {
            drafts.add(CellDraft(TABLE, id, "tombstone", SyncValue.bool(tombstone)))
        }
        return drafts
    }

    private companion object {
        const val TABLE = "transaction_filters"
    }
}

/**
 * An approximation of Foundation's `localizedStandardCompare` for filter names: case-insensitive,
 * runs of digits compare by numeric value ("Filter 2" before "Filter 10").
 */
internal object StandardCompare {
    fun compare(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val a = left[i]
            val b = right[j]
            if (a.isDigit() && b.isDigit()) {
                var iEnd = i
                while (iEnd < left.length && left[iEnd].isDigit()) iEnd++
                var jEnd = j
                while (jEnd < right.length && right[jEnd].isDigit()) jEnd++
                val leftDigits = left.substring(i, iEnd).trimStart('0')
                val rightDigits = right.substring(j, jEnd).trimStart('0')
                if (leftDigits.length != rightDigits.length) return leftDigits.length.compareTo(rightDigits.length)
                val byValue = leftDigits.compareTo(rightDigits)
                if (byValue != 0) return byValue
                i = iEnd
                j = jEnd
            } else {
                val byChar = a.lowercaseChar().compareTo(b.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        val remaining = (left.length - i).compareTo(right.length - j)
        if (remaining != 0) return remaining
        return left.compareTo(right)
    }
}
