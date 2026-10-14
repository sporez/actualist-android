package com.sporez.actualist.core.importer

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.importer.BankSyncReconciliation.Candidate
import com.sporez.actualist.core.importer.BankSyncReconciliation.Existing
import com.sporez.actualist.core.importer.BankSyncReconciliation.MatchedUpdate
import com.sporez.actualist.core.sync.SyncValue
import com.sporez.actualist.core.transactions.SplitDraft
import com.sporez.actualist.core.transactions.TransactionDraft
import com.sporez.actualist.core.transactions.TransactionWrites

/** The cell writes and counters for a plan's matched updates and inserts. */
data class ImportWrites(
    val drafts: List<CellDraft>,
    /** Local transaction ids created per account, in insertion order; a transfer insert also lists its paired leg. */
    val insertedIdsByAccount: Map<String, List<String>>,
    /** The importing account plus every transfer counterpart. */
    val affectedAccountIds: Set<String>,
    val insertedCount: Int,
    val updatedCount: Int,
)

/**
 * Turns a plan into CRDT cell drafts. Port of Actualist `LocalFirstActualStore+ImportReconcile`
 * (`importReconcileWrites`) with `BudgetDatabase+BankSync` message builders (`makeBankSyncMatchUpdateMessages`,
 * `makeBankSyncTransferConversionMessages`). Every function must run inside [BudgetDatabase.commitLocal] so
 * planning reads the latest committed rows.
 */
class ImportReconcileWriter(
    private val database: BudgetDatabase,
    clock: Clock = Clock.SYSTEM,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val sql get() = database.sql
    private val transactions = TransactionWrites(database, clock, newId)

    /** Resolves a payee name to a payee once per name for the whole import, planning its creation when new. */
    private inner class PayeeResolver {
        private val resolved = HashMap<String, String>()

        fun resolve(name: String): Pair<String?, List<CellDraft>> {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return null to emptyList()
            val key = trimmed.lowercase()
            val cached = resolved[key]
            if (cached != null) return cached to emptyList()
            val (payeeId, drafts) = transactions.resolveOrCreatePayeeDrafts(null, name)
            resolved[key] = payeeId
            return payeeId to drafts
        }
    }

    /**
     * Matched updates first, then inserts, each insert preceded by the cells that create its payee.
     *
     * @param sortOrder the `sort_order` of the insert at an index.
     */
    fun writes(
        accountId: String,
        accountIsOffBudget: Boolean,
        updates: List<Pair<MatchedUpdate, Existing>>,
        inserts: List<Candidate>,
        options: ImportReconcileOptions,
        sortOrder: (Int) -> Double,
    ): ImportWrites {
        val drafts = ArrayList<CellDraft>()
        val insertedIds = LinkedHashMap<String, MutableList<String>>()
        val affected = LinkedHashSet<String>()
        affected.add(accountId)
        val payees = PayeeResolver()
        var updatedCount = 0
        var insertedCount = 0

        for ((planned, existing) in updates) {
            var update = planned
            val name = planned.newPayeeName
            if (name != null) {
                val (payeeId, payeeDrafts) = payees.resolve(name)
                update = update.copy(payeeId = payeeId)
                drafts.addAll(payeeDrafts)
            }
            drafts.addAll(matchUpdateDrafts(update, existing, accountIsOffBudget, !options.isBankSyncAccount))
            val transfer = transferConversionDrafts(update, existing)
            if (transfer != null) {
                drafts.addAll(transfer.drafts)
                affected.add(transfer.destinationAccountId)
                insertedIds.getOrPut(transfer.destinationAccountId) { ArrayList() }.add(transfer.pairedTransactionId)
            }
            updatedCount++
        }

        for ((index, candidate) in inserts.withIndex()) {
            val transactionId = newId()
            val payeeId: String?
            val payeeDrafts: List<CellDraft>
            val selected = candidate.payeeId
            if (!selected.isNullOrEmpty()) {
                payeeId = selected
                payeeDrafts = emptyList()
            } else {
                // Splits carry no payee of their own; the parent name drives creation. A row with no payee text
                // stays payee-less (CSV allows it).
                val resolution = payees.resolve(candidate.payeeName ?: "")
                payeeId = resolution.first
                payeeDrafts = resolution.second
            }
            val transferDestinationId = if (candidate.isSplit || payeeId == null) null else transactions.transferAccountIdOf(payeeId)
            val draft = insertDraft(candidate, accountId, payeeId, sortOrder(index), accountIsOffBudget, options)
            val transactionDrafts: List<CellDraft>
            if (candidate.isSplit) {
                transactionDrafts = transactions.splitDrafts(draft, transactionId, payeeId) + scheduleDrafts(transactionId, candidate.scheduleId)
            } else if (transferDestinationId != null && payeeId != null) {
                val plan = transactions.transferDrafts(draft, transactionId, payeeId)
                transactionDrafts = plan.drafts +
                    importedIdentityDrafts(transactionId, candidate.financialId, candidate.importedPayee) +
                    scheduleDrafts(transactionId, candidate.scheduleId)
                affected.add(transferDestinationId)
                insertedIds.getOrPut(transferDestinationId) { ArrayList() }.add(plan.pairedTransactionId)
            } else {
                transactionDrafts = transactions.simpleDrafts(draft, transactionId, payeeId) + scheduleDrafts(transactionId, candidate.scheduleId)
            }
            drafts.addAll(payeeDrafts)
            drafts.addAll(transactionDrafts)
            insertedIds.getOrPut(accountId) { ArrayList() }.add(transactionId)
            insertedCount++
        }
        return ImportWrites(drafts, insertedIds, affected, insertedCount, updatedCount)
    }

    /** The Starting Balance row of a first sync: Starting Balance payee, income category only on budget, rules skipped. */
    fun openingBalanceDrafts(
        accountId: String,
        openingBalance: BankSyncReconciliation.OpeningBalance,
        onBudget: Boolean,
        sortOrder: Double?,
    ): List<CellDraft> {
        val date = ImportDays.date(openingBalance.dayId)
            ?: throw LocalFirstException.InvalidLocalWrite("invalid opening balance date")
        return transactions.openingBalanceDrafts(newId(), accountId, openingBalance.amount, date, onBudget, sortOrder)
    }

    private fun insertDraft(
        candidate: Candidate,
        accountId: String,
        payeeId: String?,
        sortOrder: Double,
        accountIsOffBudget: Boolean,
        options: ImportReconcileOptions,
    ): TransactionDraft {
        val date = ImportDays.date(candidate.dayId)
            ?: throw LocalFirstException.InvalidLocalWrite("missing bank sync download")
        return TransactionDraft(
            accountId = accountId,
            date = date,
            amount = candidate.amount,
            payeeId = payeeId,
            payeeName = candidate.payeeName ?: "",
            categoryId = if (accountIsOffBudget) null else candidate.categoryId,
            notes = candidate.notes,
            // `trans.cleared ?? defaultCleared` (sync.ts).
            cleared = if (candidate.clearedIsExplicit) candidate.cleared else options.defaultCleared,
            isTransfer = false,
            importedPayee = candidate.importedPayee,
            importedId = candidate.financialId,
            sortOrder = sortOrder,
            // A rule split's own payee and sort order are not carried: children inherit the parent's payee and
            // take Actual's descending order.
            splits = candidate.splits.map {
                SplitDraft(if (accountIsOffBudget) null else it.categoryId, it.amount, (it.notes as? SplitField.Value<String>)?.value)
            },
        )
    }

    private fun scheduleDrafts(transactionId: String, scheduleId: String?): List<CellDraft> {
        if (scheduleId.isNullOrEmpty() || "schedule" !in database.columnSet("transactions")) return emptyList()
        return listOf(CellDraft("transactions", transactionId, "schedule", SyncValue.text(scheduleId)))
    }

    /** Imported identity of a transfer's source row (`makeImportedIdentityMessages`). */
    private fun importedIdentityDrafts(transactionId: String, importedId: String?, importedPayee: String?): List<CellDraft> {
        val columns = database.columnSet("transactions")
        val out = ArrayList<CellDraft>()
        if (!importedPayee.isNullOrEmpty()) {
            listOf("imported_description", "imported_payee").firstOrNull { it in columns }?.let {
                out.add(CellDraft("transactions", transactionId, it, SyncValue.text(importedPayee)))
            }
        }
        if (!importedId.isNullOrEmpty()) {
            listOf("financial_id", "imported_id").firstOrNull { it in columns }?.let {
                out.add(CellDraft("transactions", transactionId, it, SyncValue.text(importedId)))
            }
        }
        return out
    }

    /**
     * One matched row's update cells. Only fields that actually change from the current row are written; the
     * parent's planned cleared value cascades onto its live children in the same commit.
     *
     * @param clearsMissingImportIdentity write null over a stored `imported_id` or `imported_payee` the update
     * does not carry (CSV, as upstream's `x || null`). Bank Sync never clears either.
     */
    fun matchUpdateDrafts(
        update: MatchedUpdate,
        existing: Existing,
        accountIsOffBudget: Boolean,
        clearsMissingImportIdentity: Boolean,
    ): List<CellDraft> {
        val columns = database.columnSet("transactions")
        val out = ArrayList<CellDraft>()
        fun put(rowId: String, column: String, value: SyncValue) {
            out.add(CellDraft("transactions", rowId, column, value))
        }

        fun putIfChanged(column: String, value: SyncValue, changed: Boolean) {
            if (changed) put(update.existingId, column, value)
        }

        val financialIdColumn = listOf("financial_id", "imported_id").firstOrNull { it in columns }
        if (financialIdColumn != null) {
            val financialId = update.financialId
            if (financialId != null) {
                putIfChanged(financialIdColumn, SyncValue.text(financialId), financialId != existing.financialId)
            } else if (clearsMissingImportIdentity) {
                putIfChanged(financialIdColumn, SyncValue.Null, existing.financialId != null)
            }
        }
        val payeeColumn = listOf("description", "payee").firstOrNull { it in columns }
            ?: throw LocalFirstException.SchemaUnavailable("missing column transactions.description")
        val payeeId = update.payeeId
        if (payeeId != null) putIfChanged(payeeColumn, SyncValue.text(payeeId), payeeId != existing.payeeId)
        // Bank Sync never manufactures a category on a transfer or off-budget row.
        val categoryId = update.categoryId
        if ("category" in columns && categoryId != null && !existing.isTransfer && !accountIsOffBudget) {
            putIfChanged("category", SyncValue.text(categoryId), categoryId != existing.categoryId)
        }
        val importedPayeeColumn = listOf("imported_description", "imported_payee").firstOrNull { it in columns }
        if (importedPayeeColumn != null) {
            val importedPayee = update.importedPayee
            if (importedPayee != null) {
                putIfChanged(importedPayeeColumn, SyncValue.text(importedPayee), importedPayee != existing.importedPayee)
            } else if (clearsMissingImportIdentity) {
                putIfChanged(importedPayeeColumn, SyncValue.Null, existing.importedPayee != null)
            }
        }
        val notes = update.notes
        if ("notes" in columns && notes != null) {
            putIfChanged("notes", SyncValue.text(notes), notes != existing.notes)
        }
        if ("cleared" in columns) {
            putIfChanged("cleared", SyncValue.bool(update.cleared), update.cleared != existing.cleared)
        }
        val dayId = update.dayId
        val packed = dayId?.toLongOrNull()
        if (dayId != null && packed != null) {
            putIfChanged("date", SyncValue.int(packed), dayId != existing.dayId)
            for (childId in update.dateChildIds) put(childId, "date", SyncValue.int(packed))
        }
        if ("cleared" in columns) {
            for (childId in update.childIds) put(childId, "cleared", SyncValue.bool(update.cleared))
        }
        return out
    }

    class TransferConversion(val drafts: List<CellDraft>, val destinationAccountId: String, val pairedTransactionId: String)

    /**
     * A matched plain row that took a transfer payee becomes a transfer: the paired leg and the link
     * (`transfer.ts` `onUpdate`). Null when the payee is not a transfer payee or the row is already a transfer
     * or a split parent.
     */
    fun transferConversionDrafts(update: MatchedUpdate, existing: Existing): TransferConversion? {
        val payeeId = update.payeeId ?: return null
        if (existing.isTransfer || existing.isParent || payeeId == existing.payeeId) return null
        val destination = transactions.transferAccountIdOf(payeeId) ?: return null
        val row = sql.queryOne(
            "SELECT acct, category, notes, date, amount FROM transactions WHERE id = ?",
            update.existingId,
        ) ?: return null
        val accountId = row.string("acct") ?: return null
        val columns = database.columnSet("transactions")
        // The pair mirrors the row as updated: the update's category, notes and date.
        val category = update.categoryId ?: row.string("category")
        val notes = update.notes ?: row.string("notes")
        val date = update.dayId?.toLongOrNull() ?: row.long("date") ?: return null
        val amount = row.longOr0("amount")
        val fromPayeeId = sql.queryString(
            "SELECT id FROM payees WHERE transfer_acct = ? AND ${BudgetDatabase.livePredicate(database.columnSet("payees"))} LIMIT 1",
            accountId,
        ) ?: throw LocalFirstException.InvalidLocalWrite("missing transfer payee for account")
        val clearCategory = transactions.accountOffBudget(accountId) == transactions.accountOffBudget(destination)
        val pairedId = newId()

        val out = ArrayList<CellDraft>()
        fun put(rowId: String, column: String, value: SyncValue) {
            if (column in columns) out.add(CellDraft("transactions", rowId, column, value))
        }
        // The paired leg in Actualist's `.snapshot` shape.
        put(pairedId, "acct", SyncValue.text(destination))
        put(pairedId, "date", SyncValue.int(date))
        put(pairedId, "amount", SyncValue.int(-amount))
        put(pairedId, "description", SyncValue.text(fromPayeeId))
        put(pairedId, "category", SyncValue.Null)
        put(pairedId, "notes", SyncValue.textOrNull(notes))
        put(pairedId, "cleared", SyncValue.bool(false))
        put(pairedId, "isParent", SyncValue.bool(false))
        put(pairedId, "parent_id", SyncValue.Null)
        put(pairedId, "isChild", SyncValue.bool(false))
        put(pairedId, "transferred_id", SyncValue.text(update.existingId))
        put(pairedId, "tombstone", SyncValue.bool(false))
        put(pairedId, "error", SyncValue.Null)
        put(update.existingId, "transferred_id", SyncValue.text(pairedId))
        if (clearCategory && category != null) put(update.existingId, "category", SyncValue.Null)
        return TransferConversion(out, destination, pairedId)
    }
}
