package com.sporez.actualist.core.importer

import com.sporez.actualist.core.budget.TransactionSplitSql
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.importer.BankSyncReconciliation.Existing

/** The names an import resolves a row's payee and category against: first live row per lowercased name. */
data class ImportLookup(
    val payeeIdByName: Map<String, String>,
    val categoryIdByName: Map<String, String>,
)

/**
 * Database reads of the import reconcile. Port of Actualist `BudgetDatabase+BankSync` reads
 * (`bankSyncExistingRows`, `bankSyncSuppressedFinancialIDs`, `bankSyncOldestLiveTransactionDayID`,
 * `bankSyncUpdatesDates`) and the name lookups `prepareTransactionCSVImport` builds.
 */
class ImportReconcileReads(private val database: BudgetDatabase) {
    private val sql get() = database.sql

    fun preference(key: String): String? {
        if (!database.tableExists("preferences")) return null
        return sql.queryString("SELECT value FROM preferences WHERE id = ?", key)
    }

    /** `sync-update-dates-<account>`; upstream defaults a missing preference to off. */
    fun updatesDates(accountId: String): Boolean = preference("sync-update-dates-$accountId") == "true"

    fun accountIsOffBudget(accountId: String): Boolean {
        if (!database.tableExists("accounts") || "offbudget" !in database.columnSet("accounts")) return false
        return (sql.queryLong("SELECT offbudget FROM accounts WHERE id = ?", accountId) ?: 0L) != 0L
    }

    /** Lowercased name to id for live payees (transfer payees included) and live categories. */
    fun lookup(): ImportLookup {
        val payees = LinkedHashMap<String, String>()
        if (database.tableExists("payees")) {
            val columns = database.columnSet("payees")
            for (row in sql.query("SELECT id, name FROM payees WHERE ${BudgetDatabase.livePredicate(columns)}")) {
                val id = row.string("id")
                if (id.isNullOrEmpty()) continue
                val key = (row.string("name") ?: "").lowercase()
                if (key !in payees) payees[key] = id
            }
        }
        val categories = LinkedHashMap<String, String>()
        if (database.tableExists("categories")) {
            val columns = database.columnSet("categories")
            val order = if ("sort_order" in columns) "sort_order, lower(name)" else "lower(name)"
            for (row in sql.query("SELECT id, name FROM categories WHERE ${BudgetDatabase.livePredicate(columns)} ORDER BY $order")) {
                val id = row.string("id")
                if (id.isNullOrEmpty()) continue
                val key = (row.string("name") ?: "").lowercase()
                if (key !in categories) categories[key] = id
            }
        }
        return ImportLookup(payees, categories)
    }

    /**
     * Deleted ids are account-wide dedupe keys, never mutable match rows. A missing
     * `sync-reimport-deleted-<id>` preference counts as true (deleted rows re-import, nothing is suppressed).
     * `ignoringPreference` is the explicit `reimportDeleted: false` option.
     */
    fun suppressedFinancialIds(accountId: String, ignoringPreference: Boolean = false): Set<String> {
        val preference = preference("sync-reimport-deleted-$accountId")
        val reimportDeleted = if (ignoringPreference) "false" else (preference ?: "true")
        if (reimportDeleted == "true" || !database.tableExists("transactions")) return emptySet()
        val columns = database.columnSet("transactions")
        val tombstone = listOf("tombstone", "deleted").firstOrNull { it in columns } ?: return emptySet()
        val financialId = listOf("financial_id", "imported_id").firstOrNull { it in columns } ?: return emptySet()
        val split = TransactionSplitSql(columns)
        val ids = sql.query(
            """
            SELECT DISTINCT t.$financialId AS financial_id FROM transactions t
            WHERE ${split.account} = ?
              AND ${split.date} IS NOT NULL AND ${split.account} IS NOT NULL
              AND ((${split.isChild}) = 0 OR ${split.parentId} IS NOT NULL)
              AND NOT (t.$tombstone IS NULL OR t.$tombstone = 0)
              AND t.$financialId IS NOT NULL AND t.$financialId != ''
            """.trimIndent(),
            accountId,
        )
        return ids.mapNotNull { it.string("financial_id") }.toSet()
    }

    /**
     * Oldest live transaction day for the account (`YYYYMMDD`), or null when it has no live rows. Rows dated
     * after [todayDayId] are ignored (`getAccountOldestTransaction`, sync.ts).
     */
    fun oldestLiveTransactionDayId(accountId: String, todayDayId: String): String? {
        if (!database.tableExists("transactions")) return null
        val columns = database.columnSet("transactions")
        val split = TransactionSplitSql(columns)
        val oldest = sql.queryLong(
            "SELECT MIN(t.date) FROM transactions t WHERE ${split.account} = ? AND ${BudgetDatabase.livePredicate(columns, "t")} AND t.date <= ?",
            accountId,
            todayDayId.toLong(),
        )
        return oldest?.toString()
    }

    /**
     * Live rows in the match window with `v_transactions` semantics: valid split children included, tombstones
     * and invalid `is_child` rows without a parent excluded. The window bound is month-widened by the caller;
     * the reconciler applies the exact 7 day filter. Rows carrying one of [orImportedIds] join the window,
     * because the exact `imported_id` tier is not date-bound. Chunked to stay under SQLite's bound-variable
     * limit, then de-duplicated in first-seen order.
     */
    fun existingRows(accountId: String, window: LongRange, orImportedIds: Set<String> = emptySet()): List<Existing> {
        if (!database.tableExists("transactions")) return emptyList()
        if (orImportedIds.isEmpty()) return queryExisting(accountId, window, emptyList())
        val sortedIds = orImportedIds.sorted()
        val seen = HashSet<String>()
        val rows = ArrayList<Existing>()
        for (chunk in sortedIds.chunked(CHUNK)) {
            for (row in queryExisting(accountId, window, chunk)) {
                if (seen.add(row.id)) rows.add(row)
            }
        }
        return rows
    }

    private fun queryExisting(accountId: String, window: LongRange, importedIdChunk: List<String>): List<Existing> {
        val columns = database.columnSet("transactions")
        val split = TransactionSplitSql(columns)
        val financialIdColumn = listOf("financial_id", "imported_id").firstOrNull { it in columns }
        val importedPayeeColumn = listOf("imported_description", "imported_payee").firstOrNull { it in columns }
        val transferIdColumn = listOf("transferred_id", "transfer_id").firstOrNull { it in columns }
        val financialIdSelect = financialIdColumn?.let { "t.$it" } ?: "NULL"
        val importedPayeeSelect = importedPayeeColumn?.let { "t.$it" } ?: "NULL"
        val transferIdSelect = transferIdColumn?.let { "t.$it" } ?: "NULL"
        val importedIdScope: String? = if (financialIdColumn != null && importedIdChunk.isNotEmpty()) {
            "t.$financialIdColumn IN (${importedIdChunk.joinToString(",") { "?" }})"
        } else {
            null
        }
        val dateScope = if (importedIdScope != null) {
            "(${split.date} BETWEEN ? AND ? OR $importedIdScope)"
        } else {
            "${split.date} BETWEEN ? AND ?"
        }
        val query = """
            SELECT t.id AS id,
                   $financialIdSelect AS financial_id,
                   ${split.date} AS date,
                   ${split.amount} AS amount,
                   ${split.payee} AS payee,
                   ${split.category} AS category,
                   ${split.notes} AS notes,
                   ${split.cleared} AS cleared,
                   ${split.reconciled} AS reconciled,
                   $importedPayeeSelect AS imported_payee,
                   ${split.isParent} AS is_parent,
                   ${split.isChild} AS is_child,
                   ${split.effectiveParentId} AS parent_id,
                   $transferIdSelect AS transfer_id
            FROM transactions t
            ${split.parentJoin()}
            WHERE ${split.account} = ?
              AND $dateScope
              AND ${split.liveEffectivePredicate()}
        """.trimIndent()
        val args = ArrayList<Any?>()
        args.add(accountId)
        args.add(window.first)
        args.add(window.last)
        if (importedIdScope != null) args.addAll(importedIdChunk)
        return sql.query(query, *args.toTypedArray()).mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            val day = row.long("date") ?: return@mapNotNull null
            val isParent = row.bool("is_parent")
            val isChild = row.bool("is_child")
            Existing(
                id = id,
                financialId = row.string("financial_id"),
                dayId = day.toString(),
                amount = row.longOr0("amount"),
                payeeId = row.string("payee"),
                categoryId = if (isParent) null else row.string("category"),
                notes = row.string("notes"),
                cleared = row.bool("cleared"),
                reconciled = row.bool("reconciled"),
                importedPayee = row.string("imported_payee"),
                isParent = isParent,
                isChild = isChild,
                parentId = if (isChild) row.string("parent_id") else null,
                transferId = row.string("transfer_id")?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private companion object {
        const val CHUNK = 500
    }
}
