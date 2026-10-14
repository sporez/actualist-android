package com.sporez.actualist.core.commands

import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.SqlRow
import com.sporez.actualist.core.sync.SyncValue
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Full physical state of one `transactions` row, as the duplicate and merge planners see it.
 * Port of Actualist `TransactionBatchTransactionSnapshot` over the fixed Actual column set
 * (`acct`, `description`, `isParent`, `isChild`, `transferred_id`). A null flag is a stored SQL
 * NULL, which several checks treat differently from false. The pinned schema has no
 * `imported_payee` column, so the imported payee is `imported_description` alone.
 */
internal data class TxSnapshot(
    val id: String,
    val accountId: String?,
    val date: Long?,
    val amount: Long?,
    val payeeId: String?,
    val categoryId: String?,
    val notes: String?,
    val cleared: Boolean?,
    val reconciled: Boolean?,
    val tombstone: Boolean?,
    val isParent: Boolean?,
    val isChild: Boolean?,
    val parentId: String?,
    val transferId: String?,
    val sortOrder: Double?,
    val splitError: String?,
    val startingBalance: Boolean?,
    val scheduleId: String?,
    val importedId: String?,
    val importedDescription: String?,
) {
    val effectiveImportedPayee: String? get() = importedDescription

    /** Actualist `TransactionCommandActionValidation.isCompleteSnapshot` for the fixed schema. */
    val isComplete: Boolean
        get() = id.isNotEmpty() && !accountId.isNullOrEmpty() && date != null && amount != null &&
            tombstone != null && isParent != null && isChild != null
}

internal data class TxGraph(val snapshots: Map<String, TxSnapshot>, val invalidReason: String?)

internal object PackedDates {
    /** A packed `YYYYMMDD` that is a real calendar day in years 1 to 9999. */
    fun isValid(packed: Long): Boolean {
        if (packed <= 0) return false
        val year = packed / 10_000
        val month = (packed / 100) % 100
        val day = packed % 100
        if (year !in 1..9_999) return false
        return try {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
            true
        } catch (error: DateTimeException) {
            false
        }
    }

    /** `YYYY-MM` of a valid packed date. */
    fun monthId(packed: Long): String =
        "%04d-%02d".format(java.util.Locale.ROOT, packed / 10_000, (packed / 100) % 100)
}

/** Reads transaction snapshots and complete command graphs. Port of `BudgetDatabase+TransactionBatchSnapshots`. */
internal class TransactionGraphReader(private val database: BudgetDatabase) {
    private val sql get() = database.sql
    private val live: String get() = BudgetDatabase.livePredicate(database.columnSet("transactions"))

    fun snapshot(id: String): TxSnapshot? {
        val row = sql.queryOne("SELECT * FROM transactions WHERE id = ?", id) ?: return null
        val parentId = row.string("parent_id")
        val isChildValue = row.long("isChild")
        return TxSnapshot(
            id = id,
            accountId = row.string("acct"),
            date = row.long("date") ?: row.string("date")?.filter { it.isDigit() }?.takeIf { it.length == 8 }?.toLongOrNull(),
            amount = row.long("amount"),
            payeeId = row.string("description"),
            categoryId = row.string("category"),
            notes = row.string("notes"),
            cleared = flag(row, "cleared"),
            reconciled = flag(row, "reconciled"),
            tombstone = flag(row, "tombstone"),
            isParent = flag(row, "isParent"),
            isChild = if (isChildValue == null) parentId != null else isChildValue != 0L,
            parentId = parentId,
            transferId = row.string("transferred_id"),
            sortOrder = row.double("sort_order"),
            splitError = row.string("error"),
            startingBalance = flag(row, "starting_balance_flag"),
            scheduleId = row.string("schedule"),
            importedId = row.string("financial_id"),
            importedDescription = row.string("imported_description"),
        )
    }

    private fun flag(row: SqlRow, name: String): Boolean? = when (val value = row[name]) {
        null -> null
        is Long -> value != 0L
        is Double -> value != 0.0
        is String -> value.trim().toLongOrNull()?.let { it != 0L } ?: when (value.lowercase()) {
            "true", "yes" -> true
            "false", "no" -> false
            else -> null
        }
        else -> null
    }

    private fun liveChildIds(parentId: String): List<String> =
        sql.query("SELECT id FROM transactions WHERE parent_id = ? AND $live", parentId).mapNotNull { it.string("id") }

    private fun liveIncomingTransferIds(transactionId: String): Set<String> =
        sql.query("SELECT id FROM transactions WHERE transferred_id = ? AND $live", transactionId)
            .mapNotNull { it.string("id") }.toSet()

    /**
     * The complete closure of rows tied to [transactionId] by split family and transfer
     * links, with the reason it cannot be trusted when something is off. A split error on any
     * row invalidates the graph: Actualist refuses to duplicate or merge such a family.
     */
    fun graph(transactionId: String): TxGraph {
        val snapshots = LinkedHashMap<String, TxSnapshot>()
        val pending = mutableListOf(transactionId)
        var invalidReason: String? = null
        while (pending.isNotEmpty()) {
            val id = pending.removeAt(pending.lastIndex)
            if (snapshots.containsKey(id)) continue
            val snapshot = snapshot(id)
            if (snapshot == null) {
                invalidReason = "A transaction in the selected graph is missing."
                continue
            }
            snapshots[id] = snapshot
            if (snapshot.tombstone == true) {
                invalidReason = "A transaction in the selected graph is no longer available."
            }
            if (snapshot.accountId.isNullOrEmpty() || snapshot.date == null || snapshot.amount == null) {
                invalidReason = "A transaction in the selected graph is missing required row data."
            }
            if (snapshot.isChild == true) {
                val parentId = snapshot.parentId
                if (parentId.isNullOrEmpty()) {
                    invalidReason = "The selected split entry has no valid parent."
                    continue
                }
                val parent = snapshot(parentId)
                if (parent == null || parent.isParent != true) {
                    invalidReason = "The selected split entry has no valid parent."
                    continue
                }
                pending.add(parentId)
            } else if (!snapshot.parentId.isNullOrEmpty()) {
                invalidReason = "A transaction has a parent link without a split-entry identity."
            }
            if (snapshot.isParent == true) {
                val childIds = liveChildIds(id)
                if (childIds.isEmpty()) {
                    invalidReason = "The selected split has no live entries."
                    continue
                }
                for (childId in childIds) {
                    val child = snapshot(childId)
                    if (child == null || child.isChild != true || child.parentId != id) {
                        invalidReason = "The selected split contains a malformed entry."
                        continue
                    }
                    pending.add(childId)
                }
            } else if (liveChildIds(id).isNotEmpty()) {
                invalidReason = "The selected transaction has split entries but is not marked as their parent."
            }
            val transferId = snapshot.transferId
            if (transferId != null) {
                val pair = snapshot(transferId)
                if (pair == null || pair.transferId != snapshot.id || pair.tombstone == true) {
                    invalidReason = "The selected transfer pair is incomplete or malformed."
                    continue
                }
                val accountId = snapshot.accountId
                val pairedAccountId = pair.accountId
                val amount = snapshot.amount
                val pairedAmount = pair.amount
                if (transferId == snapshot.id || accountId.isNullOrEmpty() || pairedAccountId.isNullOrEmpty() ||
                    accountId == pairedAccountId || amount == null || amount == Long.MIN_VALUE ||
                    pairedAmount == null || pairedAmount != -amount
                ) {
                    invalidReason = "The selected transfer pair is incomplete or malformed."
                    continue
                }
                pending.add(transferId)
            }
            val incoming = liveIncomingTransferIds(id)
            val expectedIncoming = snapshot.transferId?.let { setOf(it) } ?: emptySet()
            if (incoming != expectedIncoming) {
                invalidReason = "The selected transfer pair has a missing, nonreciprocal, or extra backlink."
            }
            pending.addAll(incoming.sorted())
            val error = snapshot.splitError
            if (!error.isNullOrEmpty() && error != "null") {
                invalidReason = "The split has an error, so it cannot be copied or merged safely."
            }
        }
        return TxGraph(snapshots, invalidReason)
    }

    /** The union of [snapshots] must be every live member of the families and pairs it touches. */
    fun graphMembershipMatches(snapshots: List<TxSnapshot>): Boolean {
        val byId = snapshots.associateBy { it.id }
        val rootIds = snapshots.mapNotNull { if (it.isParent == true) it.id else it.parentId }.toSet()
        for (rootId in rootIds) {
            val liveIds = liveChildIds(rootId).toSet()
            val expectedIds = snapshots.filter { it.parentId == rootId && it.tombstone != true }.map { it.id }.toSet()
            if (liveIds != expectedIds || byId[rootId] == null) return false
        }
        for (snapshot in snapshots) {
            if (snapshot.tombstone == true) continue
            val pairedId = snapshot.transferId ?: continue
            val paired = byId[pairedId] ?: return false
            if (paired.tombstone == true || paired.transferId != snapshot.id) return false
        }
        for (snapshot in snapshots) {
            val liveIncoming = liveIncomingTransferIds(snapshot.id)
            val expectedIncoming = snapshots.filter { it.tombstone != true && it.transferId == snapshot.id }.map { it.id }.toSet()
            if (liveIncoming != expectedIncoming) return false
        }
        return true
    }
}

/** Actualist `transactionBatchRestoreMessages`: every stored cell of a row, in its write order. */
internal fun restoreDrafts(snapshot: TxSnapshot): List<CellDraft> {
    fun cell(column: String, value: SyncValue) = CellDraft("transactions", snapshot.id, column, value)
    fun text(value: String?) = SyncValue.textOrNull(value)
    fun flag(value: Boolean?) = if (value == null) SyncValue.Null else SyncValue.bool(value)
    fun integer(value: Long?) = if (value == null) SyncValue.Null else SyncValue.int(value)
    return listOf(
        cell("acct", text(snapshot.accountId)),
        cell("date", integer(snapshot.date)),
        cell("amount", integer(snapshot.amount)),
        cell("description", text(snapshot.payeeId)),
        cell("category", text(snapshot.categoryId)),
        cell("notes", text(snapshot.notes)),
        cell("cleared", flag(snapshot.cleared)),
        cell("reconciled", flag(snapshot.reconciled)),
        cell("isParent", flag(snapshot.isParent)),
        cell("parent_id", text(snapshot.parentId)),
        cell("isChild", flag(snapshot.isChild)),
        cell("transferred_id", text(snapshot.transferId)),
        cell("sort_order", snapshot.sortOrder?.let { SyncValue.Real(it) } ?: SyncValue.Null),
        cell("error", text(snapshot.splitError)),
        cell("starting_balance_flag", flag(snapshot.startingBalance)),
        cell("schedule", text(snapshot.scheduleId)),
        cell("financial_id", text(snapshot.importedId)),
        cell("imported_description", text(snapshot.importedDescription)),
        cell("tombstone", flag(snapshot.tombstone)),
    )
}
