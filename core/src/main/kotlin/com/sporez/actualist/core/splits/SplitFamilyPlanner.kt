package com.sporez.actualist.core.splits

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.sync.SyncValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/**
 * Port of `BudgetDatabase+TransactionSplitWrites` and `+SplitDraftIDValidation`: loads a family,
 * runs the pure family ops, and diffs the old and new rows into CRDT cells.
 */
internal class SplitFamilyPlanner(
    private val database: BudgetDatabase,
    private val newId: () -> String,
) {
    private val sql get() = database.sql

    /** The physical `transactions` columns the family path writes. */
    private class Columns(val all: Set<String>) {
        val hasNotes = "notes" in all
        val hasCleared = "cleared" in all
        val hasReconciled = "reconciled" in all
        val hasTombstone = "tombstone" in all
        val hasParentId = "parent_id" in all
        val hasSchedule = "schedule" in all
        val hasError = "error" in all
        val hasStartingBalance = "starting_balance_flag" in all
        val hasIsParent = "isParent" in all
        val hasIsChild = "isChild" in all
        val hasTransferId = "transferred_id" in all
        val hasSortOrder = "sort_order" in all
    }

    private class TransferEffect(
        val drafts: List<CellDraft>,
        val accounts: Set<String>,
        val transactions: Set<String>,
    ) {
        companion object {
            val NONE = TransferEffect(emptyList(), emptySet(), emptySet())
        }
    }

    private fun transactionColumns(): Columns = Columns(database.requiredColumns("transactions", listOf("date", "amount")))

    // region Entry points

    fun createFamily(draft: SplitFamilyDraft, parentId: String, payeeId: String?, nowMillis: Long): SplitWritePlan {
        if (draft.splits.isEmpty()) throw LocalFirstException.InvalidLocalWrite("split requires at least one child")
        val columns = transactionColumns()
        if (database.tableExists("accounts") && !database.liveRowExists("accounts", draft.accountId)) {
            throw LocalFirstException.InvalidLocalWrite("missing account")
        }
        validateSplitCategories(draft.splits)
        validateSplitDraftIds(draft.splits, parentId, emptySet())
        val parent = splitParentRecord(parentId, draft.copy(sortOrder = draft.sortOrder ?: nowMillis.toDouble()), payeeId, null)
        val family = materializeSplitFamily(parent, draft.splits, emptyList(), nullParentPayee = true)
        val persisted = persistFamilyChange(emptyList(), SplitTransactionFamilyOps.ungroupTransaction(family), columns)
        return appendingParentImportMetadata(persisted, parentId, draft, columns)
    }

    fun updateFamily(transactionId: String, draft: SplitFamilyDraft, payeeId: String?): SplitWritePlan {
        val columns = transactionColumns()
        validateSplitCategories(draft.splits)
        val oldRows = loadSplitFamily(transactionId, columns)
        val current = oldRows.firstOrNull { it.id == transactionId } ?: oldRows.firstOrNull()
            ?: throw LocalFirstException.InvalidLocalWrite("missing transaction")

        if (current.isChild && !draft.isSplit) {
            val child = current.copy(
                amount = draft.amount,
                category = draft.categoryId,
                payee = payeeId ?: current.payee,
                notes = draft.notes?.ifEmpty { null },
                cleared = draft.cleared,
                reconciled = draft.reconciled,
            )
            val result = SplitTransactionFamilyOps.updateTransaction(oldRows, child, idGenerator = newId)
            return persistFamilyChange(oldRows, result.data, columns)
        }

        if (!draft.isSplit) {
            val simple = splitParentRecord(if (current.isParent) current.id else transactionId, draft, payeeId, current)
            return persistFamilyChange(oldRows, listOf(simple), columns)
        }

        val converting = !current.isParent && !current.isChild
        val parentId = if (current.isChild) (current.parentId ?: current.id) else current.id
        validateSplitDraftIds(draft.splits, parentId, oldRows.filter { it.isChild }.map { it.id }.toSet())
        var parent = splitParentRecord(parentId, draft, payeeId, oldRows.firstOrNull { it.isParent } ?: current)
        if (payeeId != null) {
            parent = parent.copy(payee = payeeId)
        } else if (converting) {
            parent = parent.copy(payee = null)
        }
        parent = parent.copy(category = if (converting) draft.categoryId else null)
        val family = materializeSplitFamily(parent, draft.splits, oldRows.filter { it.isChild }, nullParentPayee = converting)
        val newRows = if (converting) {
            SplitTransactionFamilyOps.ungroupTransaction(family)
        } else {
            SplitTransactionFamilyOps.updateTransaction(oldRows, family, idGenerator = newId).data
        }
        return persistFamilyChange(oldRows, newRows, columns)
    }

    fun deleteFamily(transactionId: String): SplitWritePlan {
        val columns = transactionColumns()
        val oldRows = loadSplitFamily(transactionId, columns)
        if (oldRows.isEmpty()) throw LocalFirstException.InvalidLocalWrite("missing transaction")
        val result = SplitTransactionFamilyOps.deleteTransaction(oldRows, transactionId)
        return persistFamilyChange(oldRows, result.data, columns)
    }

    fun loadFamily(transactionId: String): List<SplitTransactionRecord> = loadSplitFamily(transactionId, transactionColumns())

    // endregion

    // region Family construction

    fun materializeSplitFamily(
        parent: SplitTransactionRecord,
        drafts: List<SplitChildDraft>,
        existingChildren: List<SplitTransactionRecord>,
        nullParentPayee: Boolean,
    ): SplitTransactionRecord {
        val children = drafts.mapIndexed { index, draft ->
            val child = SplitTransactionFamilyOps.makeChild(parent, childPatch(draft, existingChildren, index), newId)
            // `makeChild` matches Actual's family oracle and always clears transferId.
            // Persistence must keep the existing pair.
            val existing = existingChildren.firstOrNull { it.id == child.id }
            if (existing != null) child.copy(transferId = existing.transferId) else child
        }
        var family = parent.copy(isParent = true, isChild = false, parentId = null, category = null)
        if (nullParentPayee) family = family.copy(payee = null)
        family = family.copy(subtransactions = children)
        return SplitTransactionFamilyOps.recalculateSplit(family)
    }

    fun childPatch(draft: SplitChildDraft, existing: List<SplitTransactionRecord>, index: Int): SplitTransactionPatch {
        val current = draft.id?.let { id -> existing.firstOrNull { it.id == id } }
        var payee: SplitOptionalField<String> = SplitOptionalField.Omitted
        when (val field = draft.payeeId) {
            is SplitOptionalField.Omitted -> if (current != null) payee = SplitOptionalField.Value(current.payee)
            is SplitOptionalField.Value -> payee = SplitOptionalField.Value(field.value)
        }
        var notes: SplitOptionalField<String> = SplitOptionalField.Omitted
        when (val field = draft.notes) {
            is SplitOptionalField.Omitted -> if (current != null) notes = SplitOptionalField.Value(current.notes)
            is SplitOptionalField.Value -> notes = SplitOptionalField.Value(field.value)
        }
        val fallbackOrder = -(index + 1).toDouble()
        val sortOrder: SplitOptionalField<Double> = when (val field = draft.sortOrder) {
            is SplitOptionalField.Omitted ->
                if (current != null) SplitOptionalField.Value(current.sortOrder) else SplitOptionalField.Value(fallbackOrder)
            is SplitOptionalField.Value -> SplitOptionalField.Value(field.value ?: fallbackOrder)
        }
        return SplitTransactionPatch(
            id = draft.id ?: current?.id,
            amount = draft.amount,
            category = SplitOptionalField.Value(draft.categoryId),
            payee = payee,
            notes = notes,
            sortOrder = sortOrder,
        )
    }

    fun splitParentRecord(
        id: String,
        draft: SplitFamilyDraft,
        payeeId: String?,
        inheritFrom: SplitTransactionRecord?,
    ): SplitTransactionRecord = SplitTransactionRecord(
        id = id,
        amount = draft.amount,
        account = draft.accountId,
        date = draft.date.toString(),
        category = draft.categoryId,
        payee = payeeId,
        notes = draft.notes?.ifEmpty { null },
        cleared = draft.cleared,
        reconciled = draft.reconciled,
        startingBalance = inheritFrom?.startingBalance,
        sortOrder = draft.sortOrder ?: inheritFrom?.sortOrder,
        isParent = false,
        isChild = false,
        parentId = null,
        transferId = inheritFrom?.transferId,
        error = null,
        deleted = false,
    )

    // endregion

    // region Validation

    private fun validateSplitCategories(splits: List<SplitChildDraft>) {
        if (!database.tableExists("categories")) return
        for (split in splits) {
            val categoryId = split.categoryId ?: continue
            if (!database.liveRowExists("categories", categoryId)) throw LocalFirstException.InvalidLocalWrite("missing category")
        }
    }

    /**
     * Rejects split drafts whose child ids would collide. Explicit ids must be unique, must not be
     * the parent, and must be either an existing child of this family (update in place) or absent
     * from `transactions`.
     */
    fun validateSplitDraftIds(splits: List<SplitChildDraft>, parentId: String, familyChildIds: Set<String>) {
        val seen = HashSet<String>()
        for (id in splits.mapNotNull { it.id }) {
            if (id == parentId || !seen.add(id)) throw LocalFirstException.InvalidLocalWrite("invalid split child id")
            if (id !in familyChildIds && database.rowExists("transactions", id)) {
                throw LocalFirstException.InvalidLocalWrite("invalid split child id")
            }
        }
    }

    // endregion

    // region Persisting a family change

    /** Import and schedule identity lives on the parent only; family records do not carry it. */
    private fun appendingParentImportMetadata(
        result: SplitWritePlan,
        parentId: String,
        draft: SplitFamilyDraft,
        columns: Columns,
    ): SplitWritePlan {
        val drafts = result.drafts.toMutableList()
        draft.importedPayee?.takeIf { it.isNotEmpty() }?.let { value ->
            val column = listOf("imported_description", "imported_payee").firstOrNull { it in columns.all }
            if (column != null) drafts.add(CellDraft("transactions", parentId, column, SyncValue.text(value)))
        }
        draft.importedId?.takeIf { it.isNotEmpty() }?.let { value ->
            val column = listOf("financial_id", "imported_id").firstOrNull { it in columns.all }
            if (column != null) drafts.add(CellDraft("transactions", parentId, column, SyncValue.text(value)))
        }
        draft.scheduleId?.takeIf { it.isNotEmpty() }?.let { value ->
            if (columns.hasSchedule) drafts.add(CellDraft("transactions", parentId, "schedule", SyncValue.text(value)))
        }
        return SplitWritePlan(drafts, result.affectedAccountIds, result.affectedTransactionIds)
    }

    private fun persistFamilyChange(
        oldRows: List<SplitTransactionRecord>,
        newRows: List<SplitTransactionRecord>,
        columns: Columns,
    ): SplitWritePlan {
        val oldById = oldRows.associateBy { it.id }
        val newById = newRows.associateBy { it.id }
        val deletedIds = HashSet(oldById.keys)
        deletedIds.removeAll(newById.keys)
        val oldParent = oldRows.firstOrNull { it.isParent }
        if (oldParent != null && newById[oldParent.id] == null) deletedIds.addAll(oldRows.map { it.id })
        val addedIds = HashSet(newById.keys)
        addedIds.removeAll(oldById.keys)
        val updatedIds = HashSet(newById.keys)
        updatedIds.retainAll(oldById.keys)

        val drafts = mutableListOf<CellDraft>()
        val affectedAccounts = HashSet<String>()
        oldRows.forEach { row -> row.account?.let { affectedAccounts.add(it) } }
        newRows.forEach { row -> row.account?.let { affectedAccounts.add(it) } }
        val affectedTransactions = HashSet<String>()
        affectedTransactions.addAll(deletedIds)
        affectedTransactions.addAll(addedIds)
        affectedTransactions.addAll(updatedIds)

        for (id in addedIds.sorted()) {
            val row = newById[id] ?: continue
            val prepared = prepareTransferOnInsert(row, columns, affectedAccounts, affectedTransactions)
            drafts.addAll(prepared.second)
            drafts.addAll(messagesForNewRow(prepared.first, columns))
        }

        for (id in updatedIds.sorted()) {
            val old = oldById[id] ?: continue
            val new = newById[id] ?: continue
            drafts.addAll(messagesForChangedFields(old, new, columns))
            val transfer = transferMessagesOnUpdate(old, new, columns)
            drafts.addAll(transfer.drafts)
            affectedAccounts.addAll(transfer.accounts)
            affectedTransactions.addAll(transfer.transactions)
        }

        for (id in deletedIds.sorted()) {
            val old = oldById[id] ?: continue
            if (columns.hasTombstone) drafts.add(tombstone(id))
            val transfer = transferMessagesOnDelete(old, columns)
            drafts.addAll(transfer.drafts)
            affectedAccounts.addAll(transfer.accounts)
            affectedTransactions.addAll(transfer.transactions)
        }

        return SplitWritePlan(drafts, affectedAccounts, affectedTransactions)
    }

    private fun tombstone(rowId: String) = CellDraft("transactions", rowId, "tombstone", SyncValue.bool(true))

    private fun cell(rowId: String, column: String, value: SyncValue) = CellDraft("transactions", rowId, column, value)

    private fun splitErrorValue(error: SplitTransactionError?): SyncValue {
        if (error == null) return SyncValue.Null
        return SyncValue.text("{\"type\":\"${error.type}\",\"version\":${error.version},\"difference\":${error.difference}}")
    }

    /** Actualist `transactionRowMessages` in its `.snapshot` shape: every stored column. */
    private fun snapshotCells(
        rowId: String,
        accountId: String,
        dateValue: Int,
        amount: Long,
        payeeId: String?,
        categoryId: String?,
        notes: String?,
        cleared: Boolean,
        reconciled: Boolean?,
        isParent: Boolean,
        parentId: String?,
        isChild: Boolean,
        transferId: String?,
        sortOrder: Double?,
        error: SplitTransactionError?,
        startingBalance: Boolean?,
        columns: Columns,
    ): List<CellDraft> {
        val cells = mutableListOf(
            cell(rowId, "acct", SyncValue.text(accountId)),
            cell(rowId, "date", SyncValue.int(dateValue)),
            cell(rowId, "amount", SyncValue.int(amount)),
            cell(rowId, "description", SyncValue.textOrNull(payeeId)),
            cell(rowId, "category", SyncValue.textOrNull(categoryId)),
        )
        if (columns.hasNotes) cells.add(cell(rowId, "notes", SyncValue.textOrNull(notes)))
        if (columns.hasCleared) cells.add(cell(rowId, "cleared", SyncValue.bool(cleared)))
        if (columns.hasReconciled && reconciled != null) cells.add(cell(rowId, "reconciled", SyncValue.bool(reconciled)))
        if (columns.hasIsParent) cells.add(cell(rowId, "isParent", SyncValue.bool(isParent)))
        if (columns.hasParentId) cells.add(cell(rowId, "parent_id", SyncValue.textOrNull(parentId)))
        if (columns.hasIsChild) cells.add(cell(rowId, "isChild", SyncValue.bool(isChild)))
        if (transferId != null && columns.hasTransferId) cells.add(cell(rowId, "transferred_id", SyncValue.text(transferId)))
        if (sortOrder != null && columns.hasSortOrder) cells.add(cell(rowId, "sort_order", SyncValue.Real(sortOrder)))
        if (columns.hasTombstone) cells.add(cell(rowId, "tombstone", SyncValue.bool(false)))
        if (columns.hasError) cells.add(cell(rowId, "error", splitErrorValue(error)))
        if (startingBalance != null && columns.hasStartingBalance) {
            cells.add(cell(rowId, "starting_balance_flag", SyncValue.bool(startingBalance)))
        }
        return cells
    }

    private fun messagesForNewRow(row: SplitTransactionRecord, columns: Columns): List<CellDraft> = snapshotCells(
        rowId = row.id,
        accountId = row.account ?: "",
        dateValue = packedDate(row.date),
        amount = row.amount,
        payeeId = row.payee,
        categoryId = if (row.isParent) null else row.category,
        notes = row.notes,
        cleared = row.cleared ?: false,
        reconciled = row.reconciled,
        isParent = row.isParent,
        parentId = row.parentId,
        isChild = row.isChild,
        transferId = row.transferId,
        sortOrder = row.sortOrder,
        error = if (row.isParent) row.error else null,
        startingBalance = row.startingBalance,
        columns = columns,
    )

    private fun sameDouble(a: Double?, b: Double?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a == b
    }

    private fun messagesForChangedFields(old: SplitTransactionRecord, new: SplitTransactionRecord, columns: Columns): List<CellDraft> {
        val fields = mutableListOf<Pair<String, SyncValue>>()
        if (old.account != new.account) fields.add("acct" to SyncValue.textOrNull(new.account))
        if (old.date != new.date) fields.add("date" to SyncValue.int(packedDate(new.date)))
        if (old.amount != new.amount) fields.add("amount" to SyncValue.int(new.amount))
        if (old.payee != new.payee) fields.add("description" to SyncValue.textOrNull(new.payee))
        val newCategory = if (new.isParent) null else new.category
        val oldCategory = if (old.isParent) null else old.category
        if (oldCategory != newCategory) fields.add("category" to SyncValue.textOrNull(newCategory))
        if (columns.hasNotes && old.notes != new.notes) fields.add("notes" to SyncValue.textOrNull(new.notes))
        if (columns.hasCleared && old.cleared != new.cleared) fields.add("cleared" to SyncValue.bool(new.cleared ?: false))
        if (columns.hasReconciled && old.reconciled != new.reconciled) fields.add("reconciled" to SyncValue.bool(new.reconciled ?: false))
        if (columns.hasIsParent && old.isParent != new.isParent) fields.add("isParent" to SyncValue.bool(new.isParent))
        if (columns.hasParentId && old.parentId != new.parentId) fields.add("parent_id" to SyncValue.textOrNull(new.parentId))
        if (columns.hasIsChild && old.isChild != new.isChild) fields.add("isChild" to SyncValue.bool(new.isChild))
        val newSortOrder = new.sortOrder
        if (columns.hasSortOrder && !sameDouble(old.sortOrder, newSortOrder) && newSortOrder != null) {
            fields.add("sort_order" to SyncValue.Real(newSortOrder))
        }
        if (columns.hasError && old.error != new.error) fields.add("error" to splitErrorValue(if (new.isParent) new.error else null))
        if (columns.hasStartingBalance && old.startingBalance != new.startingBalance) {
            fields.add("starting_balance_flag" to SyncValue.bool(new.startingBalance ?: false))
        }
        if (old.deleted != new.deleted && columns.hasTombstone) fields.add("tombstone" to SyncValue.bool(new.deleted))
        return fields.map { (column, value) -> cell(new.id, column, value) }
    }

    // endregion

    // region Transfers

    /** A child whose payee is a transfer payee gets a paired row in the destination account. */
    private fun prepareTransferOnInsert(
        row: SplitTransactionRecord,
        columns: Columns,
        affectedAccounts: MutableSet<String>,
        affectedTransactions: MutableSet<String>,
    ): Pair<SplitTransactionRecord, List<CellDraft>> {
        if (row.isParent || !columns.hasTransferId) return row to emptyList()
        val destination = transferAccountId(row.payee) ?: return row to emptyList()
        val account = row.account ?: return row to emptyList()
        val pairedId = newId()
        val fromPayeeId = transferPayeeId(account)
        val clearCategory = accountOffBudget(account) == accountOffBudget(destination)
        var next = row.copy(transferId = pairedId)
        if (clearCategory) next = next.copy(category = null)
        affectedAccounts.add(destination)
        affectedTransactions.add(pairedId)
        val paired = snapshotCells(
            rowId = pairedId,
            accountId = destination,
            dateValue = packedDate(row.date),
            amount = -row.amount,
            payeeId = fromPayeeId,
            categoryId = null,
            notes = row.notes,
            cleared = false,
            reconciled = null,
            isParent = false,
            parentId = null,
            isChild = false,
            transferId = row.id,
            sortOrder = null,
            error = null,
            startingBalance = null,
            columns = columns,
        )
        return next to paired
    }

    private fun transferMessagesOnUpdate(old: SplitTransactionRecord, new: SplitTransactionRecord, columns: Columns): TransferEffect {
        // An untouched row must not create or unlink a transfer. Amount and notes
        // matter only when they have to reach an existing pair.
        val linkInputsUnchanged = old.account == new.account && old.payee == new.payee &&
            old.transferId == new.transferId && old.isParent == new.isParent
        if (linkInputsUnchanged && (old.transferId == null || (old.amount == new.amount && old.notes == new.notes))) {
            return TransferEffect.NONE
        }
        if (new.isParent) return transferMessagesOnDelete(old, columns)
        val destination = transferAccountId(new.payee)
        if (destination != null && new.transferId == null && old.transferId == null) {
            val accounts = HashSet<String>()
            val transactions = HashSet<String>()
            val prepared = prepareTransferOnInsert(new, columns, accounts, transactions)
            val drafts = prepared.second.toMutableList()
            val preparedTransferId = prepared.first.transferId
            if (columns.hasTransferId && preparedTransferId != null) {
                drafts.add(cell(new.id, "transferred_id", SyncValue.text(preparedTransferId)))
            }
            if (prepared.first.category == null && old.category != null) {
                drafts.add(cell(new.id, "category", SyncValue.Null))
            }
            return TransferEffect(drafts, accounts, transactions)
        }
        if (destination == null && old.transferId != null) return transferMessagesOnDelete(old, columns)
        if (old.account == new.account && old.payee == new.payee && old.amount == new.amount && old.notes == new.notes) {
            return TransferEffect.NONE
        }
        val pairedId = new.transferId ?: old.transferId
        val account = new.account
        if (destination == null || pairedId == null || account == null) return TransferEffect.NONE
        val fromPayeeId = transferPayeeId(account)
        val drafts = mutableListOf<CellDraft>()
        drafts.add(cell(pairedId, "acct", SyncValue.text(destination)))
        drafts.add(cell(pairedId, "description", SyncValue.text(fromPayeeId)))
        if (columns.hasNotes) drafts.add(cell(pairedId, "notes", SyncValue.textOrNull(new.notes)))
        drafts.add(cell(pairedId, "amount", SyncValue.int(-new.amount)))
        if (accountOffBudget(account) == accountOffBudget(destination)) {
            drafts.add(cell(new.id, "category", SyncValue.Null))
            drafts.add(cell(pairedId, "category", SyncValue.Null))
        }
        return TransferEffect(drafts, setOf(destination), setOf(pairedId))
    }

    private fun transferMessagesOnDelete(old: SplitTransactionRecord, columns: Columns): TransferEffect {
        val pairedId = old.transferId
        if (pairedId == null || !columns.hasTransferId) return TransferEffect.NONE
        val drafts = mutableListOf<CellDraft>()
        val accounts = HashSet<String>()
        val paired = loadTransactionRecord(pairedId, columns)
        if (paired != null) {
            paired.account?.let { accounts.add(it) }
            if (paired.isChild) {
                drafts.add(cell(pairedId, "transferred_id", SyncValue.Null))
                drafts.add(cell(pairedId, "description", SyncValue.Null))
            } else if (columns.hasTombstone) {
                drafts.add(tombstone(pairedId))
            }
        }
        if (!old.deleted) drafts.add(cell(old.id, "transferred_id", SyncValue.Null))
        return TransferEffect(drafts, accounts, setOf(pairedId))
    }

    fun transferAccountId(payeeId: String?): String? {
        if (payeeId.isNullOrEmpty() || !database.tableExists("payees")) return null
        if ("transfer_acct" !in database.columnSet("payees")) return null
        return sql.queryString("SELECT transfer_acct FROM payees WHERE id = ?", payeeId)?.takeIf { it.isNotEmpty() }
    }

    private fun transferPayeeId(accountId: String): String {
        if (!database.tableExists("payees")) throw LocalFirstException.SchemaUnavailable("missing payees table")
        val payeeColumns = database.columnSet("payees")
        if ("transfer_acct" !in payeeColumns) throw LocalFirstException.InvalidLocalWrite("missing transfer payee for account")
        return sql.queryString(
            "SELECT id FROM payees WHERE transfer_acct = ? AND ${BudgetDatabase.livePredicate(payeeColumns)} LIMIT 1",
            accountId,
        )?.takeIf { it.isNotEmpty() } ?: throw LocalFirstException.InvalidLocalWrite("missing transfer payee for account")
    }

    fun accountOffBudget(accountId: String): Boolean {
        if (!database.tableExists("accounts") || "offbudget" !in database.columnSet("accounts")) return false
        return (sql.queryLong("SELECT offbudget FROM accounts WHERE id = ?", accountId) ?: 0L) != 0L
    }

    // endregion

    // region Loading

    private fun loadSplitFamily(id: String, columns: Columns): List<SplitTransactionRecord> {
        val current = loadTransactionRecord(id, columns) ?: return emptyList()
        val parentId = if (current.isChild) (current.parentId ?: id) else id
        val parent = loadTransactionRecord(parentId, columns) ?: return listOf(current)
        val children = loadChildRecords(parent.id, columns)
        return listOf(parent.copy(subtransactions = emptyList())) + children
    }

    private fun loadChildRecords(parentId: String, columns: Columns): List<SplitTransactionRecord> {
        if (!columns.hasParentId) return emptyList()
        val order = if (columns.hasSortOrder) "sort_order DESC, id" else "id"
        val ids = sql.query(
            "SELECT id FROM transactions WHERE parent_id = ? AND ${BudgetDatabase.livePredicate(columns.all)} ORDER BY $order",
            parentId,
        ).mapNotNull { it.string("id") }
        return ids.mapNotNull { loadTransactionRecord(it, columns) }
    }

    private fun nonEmpty(value: String?): String? = value?.takeIf { it.isNotEmpty() }

    private fun loadTransactionRecord(id: String, columns: Columns): SplitTransactionRecord? {
        val row = sql.queryOne("SELECT * FROM transactions WHERE id = ?", id) ?: return null
        val parentId = if (columns.hasParentId) nonEmpty(row.string("parent_id")) else null
        val isParent = columns.hasIsParent && row.bool("isParent")
        val isChild = if (!columns.hasIsChild) {
            parentId != null
        } else {
            val flag = row.long("isChild")
            if (flag == null) parentId != null else flag != 0L
        }
        return SplitTransactionRecord(
            id = id,
            amount = row.longOr0("amount"),
            account = nonEmpty(row.string("acct")),
            date = isoDateString(row.long("date") ?: 0L),
            category = nonEmpty(row.string("category")),
            payee = nonEmpty(row.string("description")),
            notes = if (columns.hasNotes) nonEmpty(row.string("notes")) else null,
            cleared = if (columns.hasCleared) row.bool("cleared") else null,
            reconciled = if (columns.hasReconciled) row.bool("reconciled") else null,
            startingBalance = if (columns.hasStartingBalance) row.bool("starting_balance_flag") else null,
            sortOrder = if (columns.hasSortOrder) row.double("sort_order") else null,
            isParent = isParent,
            isChild = isChild,
            parentId = if (isChild) parentId else null,
            transferId = if (columns.hasTransferId) nonEmpty(row.string("transferred_id")) else null,
            error = if (columns.hasError) parseSplitError(row.string("error")) else null,
            deleted = columns.hasTombstone && row.bool("tombstone"),
        )
    }

    private fun parseSplitError(raw: String?): SplitTransactionError? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed == "null") return null
        return try {
            val obj = Json.parseToJsonElement(trimmed).jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
            val version = obj["version"]?.jsonPrimitive?.longOrNull ?: return null
            val difference = obj["difference"]?.jsonPrimitive?.longOrNull ?: return null
            SplitTransactionError(type, version.toInt(), difference)
        } catch (failure: Exception) {
            null
        }
    }

    // endregion

    companion object {
        fun isoDateString(packed: Long): String =
            String.format(Locale.ROOT, "%04d-%02d-%02d", packed / 10_000, (packed / 100) % 100, packed % 100)

        fun packedDate(date: String?): Int = (date ?: "").replace("-", "").toIntOrNull() ?: 0
    }
}
