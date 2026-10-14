package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncValue
import java.time.LocalDate
import java.util.UUID

/** A transaction to create, as the editor collects it. Amounts are integer minor units. */
data class TransactionDraft(
    val accountId: String,
    val date: LocalDate,
    val amount: Long,
    /** An existing payee, or a transfer payee when [isTransfer]. */
    val payeeId: String? = null,
    /** A payee name to find or create when no [payeeId] is selected. */
    val payeeName: String = "",
    val categoryId: String? = null,
    val notes: String? = null,
    val cleared: Boolean = false,
    val isTransfer: Boolean = false,
    val importedPayee: String? = null,
    val importedId: String? = null,
    val sortOrder: Double? = null,
    val splits: List<SplitDraft> = emptyList(),
)

data class SplitDraft(val categoryId: String?, val amount: Long, val notes: String? = null)

/** One child of an edited split; a null [id] adds a new child. */
data class SplitEdit(val id: String?, val categoryId: String?, val amount: Long, val notes: String? = null)

/** The whole family after an edit; children not listed are removed. */
data class SplitFamilyEdit(
    val amount: Long,
    val splits: List<SplitEdit>,
    val date: LocalDate? = null,
    val payeeId: String? = null,
    val payeeName: String? = null,
    val notes: String? = null,
    val cleared: Boolean? = null,
)

/** Field changes to an existing simple or transfer row. Null leaves a field unchanged. */
data class TransactionEdit(
    val date: LocalDate? = null,
    val amount: Long? = null,
    val payeeId: String? = null,
    val payeeName: String? = null,
    val categoryId: String? = null,
    val clearCategory: Boolean = false,
    val notes: String? = null,
    val cleared: Boolean? = null,
)

data class TransactionWriteResult(val transactionId: String, val messages: List<SyncMessage>)

/**
 * Transaction writes as Actual CRDT cells. Port of Actualist
 * `BudgetDatabase+TransactionCreation` (simple, transfer, opening balance), the
 * split-create path of `+TransactionSplitWrites`, and simple field updates and deletes.
 *
 * Every `*Drafts` function must run inside [BudgetDatabase.commitLocal] so validation and
 * planning read the latest committed rows.
 */
class TransactionWrites(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val sql get() = database.sql

    // region Commit entry points

    fun create(draft: TransactionDraft): TransactionWriteResult {
        val id = newId()
        val result = database.commitLocal {
            val payee = resolvePayeeForDraft(draft)
            val rows = when {
                draft.isTransfer -> transferDrafts(draft, id, payee.first ?: throw LocalFirstException.InvalidLocalWrite("missing payee")).drafts
                draft.splits.isNotEmpty() -> splitDrafts(draft, id, payee.first)
                else -> simpleDrafts(withoutOffBudgetCategory(draft), id, payee.first)
            }
            (payee.second + rows) to Unit
        }
        return TransactionWriteResult(id, result.messages)
    }

    fun update(transactionId: String, edit: TransactionEdit): TransactionWriteResult {
        val result = database.commitLocal { updateDrafts(transactionId, edit) to Unit }
        return TransactionWriteResult(transactionId, result.messages)
    }

    fun delete(transactionId: String): TransactionWriteResult {
        val result = database.commitLocal { deleteDrafts(transactionId) to Unit }
        return TransactionWriteResult(transactionId, result.messages)
    }

    /** Creates an opening-balance row for an account, as Actual's account creation does. */
    fun createOpeningBalance(accountId: String, amount: Long, date: LocalDate, onBudget: Boolean): TransactionWriteResult {
        val id = newId()
        val result = database.commitLocal { openingBalanceDrafts(id, accountId, amount, date, onBudget, null) to Unit }
        return TransactionWriteResult(id, result.messages)
    }

    // endregion

    // region Payees

    /** Actualist `resolvePayeeIfNeeded`: a selected payee must be live; an empty name means no payee. */
    fun resolvePayeeForDraft(draft: TransactionDraft): Pair<String?, List<CellDraft>> {
        val selected = draft.payeeId
        if (!selected.isNullOrEmpty()) {
            if (database.tableExists("payees") && !database.liveRowExists("payees", selected)) {
                throw LocalFirstException.InvalidLocalWrite("missing payee")
            }
        } else if (draft.payeeName.isBlank() && !draft.isTransfer) {
            return null to emptyList()
        }
        return resolveOrCreatePayeeDrafts(selected, draft.payeeName)
    }

    /** Finds a non-transfer payee by case-insensitive name, or plans `payees` and `payee_mapping` cells. */
    fun resolveOrCreatePayeeDrafts(selectedPayeeId: String?, payeeName: String): Pair<String, List<CellDraft>> {
        if (!selectedPayeeId.isNullOrEmpty()) return selectedPayeeId to emptyList()
        val trimmed = payeeName.trim()
        if (trimmed.isEmpty()) throw LocalFirstException.InvalidLocalWrite("missing payee name")
        val existing = sql.query(
            "SELECT id, name FROM payees WHERE (transfer_acct IS NULL OR transfer_acct = '') AND " +
                BudgetDatabase.livePredicate(database.columnSet("payees")),
        ).firstOrNull { it.string("name")?.equals(trimmed, ignoreCase = true) == true }?.string("id")
        if (!existing.isNullOrEmpty()) return existing to emptyList()
        val payeeId = newId()
        database.requiredColumns("payees", listOf("name"))
        val drafts = mutableListOf(CellDraft("payees", payeeId, "name", SyncValue.text(trimmed)))
        if (database.tableExists("payee_mapping")) {
            database.requiredColumns("payee_mapping", listOf("targetId"))
            drafts += CellDraft("payee_mapping", payeeId, "targetId", SyncValue.text(payeeId))
        }
        return payeeId to drafts
    }

    // endregion

    // region Create

    private fun withoutOffBudgetCategory(draft: TransactionDraft): TransactionDraft =
        if (accountOffBudget(draft.accountId)) draft.copy(categoryId = null, splits = draft.splits.map { it.copy(categoryId = null) }) else draft

    /**
     * Actual's insert sends only the cells it sets: an absent payee, category or notes stays
     * unwritten and structural columns keep their defaults.
     */
    fun simpleDrafts(draft: TransactionDraft, transactionId: String, payeeId: String?, checkAccount: Boolean = true): List<CellDraft> {
        if (checkAccount) validateAccount(draft.accountId)
        draft.categoryId?.let(::validateCategory)
        val columns = database.requiredColumns("transactions", listOf("date", "amount"))
        val cells = mutableListOf(
            "acct" to SyncValue.text(draft.accountId),
            "date" to SyncValue.int(Months.packedDate(draft.date)),
            "amount" to SyncValue.int(draft.amount),
        )
        payeeId?.let { cells += "description" to SyncValue.text(it) }
        draft.categoryId?.let { cells += "category" to SyncValue.text(it) }
        if ("notes" in columns) draft.notes?.let { cells += "notes" to SyncValue.text(it) }
        if ("cleared" in columns) cells += "cleared" to SyncValue.bool(draft.cleared)
        draft.importedPayee?.let { cells += "imported_description" to SyncValue.text(it) }
        draft.importedId?.takeIf { it.isNotEmpty() }?.let { cells += "financial_id" to SyncValue.text(it) }
        if ("sort_order" in columns) cells += "sort_order" to SyncValue.Real(draft.sortOrder ?: newRowSortOrder())
        if ("reconciled" in columns) cells += "reconciled" to SyncValue.bool(false)
        return cells.map { (column, value) -> CellDraft("transactions", transactionId, column, value) }
    }

    data class TransferPlan(val drafts: List<CellDraft>, val destinationAccountId: String, val pairedTransactionId: String)

    /**
     * A transfer is two rows linked by `transferred_id`. Actual clears the source category
     * of a transfer between accounts on the same side of the budget; across sides only the
     * budget side may carry a category (Actualist's OP-86 choice: on the on-budget leg).
     */
    fun transferDrafts(draft: TransactionDraft, sourceId: String, payeeId: String): TransferPlan {
        if (draft.accountId.isEmpty()) throw LocalFirstException.InvalidLocalWrite("missing account")
        if (draft.amount == 0L) throw LocalFirstException.InvalidLocalWrite("missing amount")
        validateAccount(draft.accountId)
        val destination = transferDestinationAccountId(payeeId)
        if (destination == draft.accountId) throw LocalFirstException.InvalidLocalWrite("transfer to the same account")
        val fromPayee = transferPayeeId(draft.accountId)
        val pairedId = newId()
        val sortOrder = draft.sortOrder ?: newRowSortOrder()
        val sourceOff = accountOffBudget(draft.accountId)
        val destinationOff = accountOffBudget(destination)
        val sameSide = sourceOff == destinationOff
        val categories: Pair<String?, String?> = if (sameSide || draft.categoryId == null) {
            null to null
        } else {
            validateCategory(draft.categoryId)
            if (sourceOff) null to draft.categoryId else draft.categoryId to null
        }
        val date = Months.packedDate(draft.date)
        val source = rowCells(
            sourceId, draft.accountId, date, draft.amount, payeeId, categories.first, draft.notes, draft.cleared,
            transferId = pairedId, sortOrder = sortOrder, clearsCategory = sameSide,
        )
        val paired = rowCells(
            pairedId, destination, date, -draft.amount, fromPayee, categories.second, draft.notes, false,
            transferId = sourceId, sortOrder = sortOrder, clearsCategory = false,
        )
        return TransferPlan(source + paired, destination, pairedId)
    }

    /** Actualist `transactionRowMessages` in its `.inserted` shape (cell order kept). */
    private fun rowCells(
        rowId: String,
        accountId: String,
        date: Int,
        amount: Long,
        payeeId: String?,
        categoryId: String?,
        notes: String?,
        cleared: Boolean,
        transferId: String?,
        sortOrder: Double?,
        clearsCategory: Boolean,
    ): List<CellDraft> {
        val cells = mutableListOf(
            "acct" to SyncValue.text(accountId),
            "date" to SyncValue.int(date),
            "amount" to SyncValue.int(amount),
        )
        payeeId?.let { cells += "description" to SyncValue.text(it) }
        if (categoryId != null || clearsCategory) cells += "category" to SyncValue.textOrNull(categoryId)
        notes?.let { cells += "notes" to SyncValue.text(it) }
        cells += "cleared" to SyncValue.bool(cleared)
        cells += "reconciled" to SyncValue.bool(false)
        transferId?.let { cells += "transferred_id" to SyncValue.text(it) }
        sortOrder?.let { cells += "sort_order" to SyncValue.Real(it) }
        return cells.map { (column, value) -> CellDraft("transactions", rowId, column, value) }
    }

    /**
     * A split family: the parent carries the total and no category; each child carries its
     * own category and amount and inherits account, date, payee and cleared from the parent.
     * Children must add up to the parent; the editor enforces this before saving.
     */
    fun splitDrafts(draft: TransactionDraft, parentId: String, payeeId: String?): List<CellDraft> {
        validateAccount(draft.accountId)
        val offBudget = accountOffBudget(draft.accountId)
        val splits = if (offBudget) draft.splits.map { it.copy(categoryId = null) } else draft.splits
        splits.forEach { split -> split.categoryId?.let(::validateCategory) }
        val total = splits.sumOf { it.amount }
        if (total != draft.amount) {
            throw LocalFirstException.InvalidLocalWrite("split amounts (${total}) do not add up to the transaction (${draft.amount})")
        }
        val date = Months.packedDate(draft.date)
        val sortOrder = draft.sortOrder ?: newRowSortOrder()
        val drafts = mutableListOf<CellDraft>()
        drafts += snapshotCells(parentId, draft.accountId, date, draft.amount, payeeId, null, draft.notes, draft.cleared, isParent = true, parentId = null, sortOrder = sortOrder)
        draft.importedPayee?.takeIf { it.isNotEmpty() }?.let { drafts += CellDraft("transactions", parentId, "imported_description", SyncValue.text(it)) }
        draft.importedId?.takeIf { it.isNotEmpty() }?.let { drafts += CellDraft("transactions", parentId, "financial_id", SyncValue.text(it)) }
        splits.forEachIndexed { index, split ->
            drafts += snapshotCells(
                newId(), draft.accountId, date, split.amount, payeeId, split.categoryId, split.notes, draft.cleared,
                isParent = false, parentId = parentId, sortOrder = -(index + 1).toDouble(),
            )
        }
        return drafts
    }

    /** Actualist's `.snapshot` row shape: every stored column, so a new family row is complete. */
    private fun snapshotCells(
        rowId: String,
        accountId: String,
        date: Int,
        amount: Long,
        payeeId: String?,
        categoryId: String?,
        notes: String?,
        cleared: Boolean,
        isParent: Boolean,
        parentId: String?,
        sortOrder: Double,
    ): List<CellDraft> = listOf(
        "acct" to SyncValue.text(accountId),
        "date" to SyncValue.int(date),
        "amount" to SyncValue.int(amount),
        "description" to SyncValue.textOrNull(payeeId),
        "category" to SyncValue.textOrNull(if (isParent) null else categoryId),
        "notes" to SyncValue.textOrNull(notes),
        "cleared" to SyncValue.bool(cleared),
        "reconciled" to SyncValue.bool(false),
        "isParent" to SyncValue.bool(isParent),
        "parent_id" to SyncValue.textOrNull(parentId),
        "isChild" to SyncValue.bool(parentId != null),
        "sort_order" to SyncValue.Real(sortOrder),
        "tombstone" to SyncValue.bool(false),
        "error" to SyncValue.Null,
    ).map { (column, value) -> CellDraft("transactions", rowId, column, value) }

    /** Opening-balance row: Starting Balance payee, an income category only on budget, flagged. */
    fun openingBalanceDrafts(
        transactionId: String,
        accountId: String,
        amount: Long,
        date: LocalDate,
        onBudget: Boolean,
        sortOrder: Double?,
        checkAccount: Boolean = true,
    ): List<CellDraft> {
        val (payeeId, payeeDrafts) = resolveOrCreatePayeeDrafts(null, "Starting Balance")
        val draft = TransactionDraft(
            accountId = accountId,
            date = date,
            amount = amount,
            payeeId = payeeId,
            payeeName = "Starting Balance",
            categoryId = if (onBudget) startingBalanceCategoryId() else null,
            cleared = true,
            sortOrder = sortOrder,
        )
        val rows = simpleDrafts(draft, transactionId, payeeId, checkAccount)
        val flag = if ("starting_balance_flag" in database.columnSet("transactions")) {
            listOf(CellDraft("transactions", transactionId, "starting_balance_flag", SyncValue.int(1)))
        } else {
            emptyList()
        }
        return payeeDrafts + rows + flag
    }

    /** For an account created in the same commit, whose row is not in the database yet. */
    fun openingBalanceDraftsForNewAccount(transactionId: String, accountId: String, amount: Long, date: LocalDate, onBudget: Boolean) =
        openingBalanceDrafts(transactionId, accountId, amount, date, onBudget, null, checkAccount = false)

    fun startingBalanceCategoryId(): String? {
        val columns = database.columnSet("categories")
        return sql.queryString(
            "SELECT id FROM categories WHERE is_income = 1 AND ${BudgetDatabase.livePredicate(columns)} " +
                "ORDER BY lower(name) = 'starting balances' DESC, sort_order LIMIT 1",
        )
    }

    // endregion

    // region Update and delete

    private data class StoredRow(
        val id: String,
        val accountId: String?,
        val date: Long?,
        val amount: Long,
        val payeeId: String?,
        val categoryId: String?,
        val notes: String?,
        val cleared: Boolean,
        val reconciled: Boolean,
        val isParent: Boolean,
        val isChild: Boolean,
        val parentId: String?,
        val transferId: String?,
        val tombstone: Boolean,
    )

    private fun loadRow(id: String): StoredRow? = sql.queryOne(
        "SELECT id, acct, date, amount, description, category, notes, cleared, reconciled, isParent, isChild, parent_id, " +
            "transferred_id, tombstone FROM transactions WHERE id = ?",
        id,
    )?.let {
        StoredRow(
            id = it.string("id")!!,
            accountId = it.string("acct"),
            date = it.long("date"),
            amount = it.longOr0("amount"),
            payeeId = it.string("description"),
            categoryId = it.string("category"),
            notes = it.string("notes"),
            cleared = it.bool("cleared"),
            reconciled = it.bool("reconciled"),
            isParent = it.bool("isParent"),
            isChild = it.bool("isChild"),
            parentId = it.string("parent_id"),
            transferId = it.string("transferred_id"),
            tombstone = it.bool("tombstone"),
        )
    }

    /**
     * Writes only the changed cells of a simple or transfer row. A transfer's paired row
     * follows its amount (negated), date and notes, as Actual's `transfer.onUpdate` does.
     * Split families and reconciled rows are refused here: they need Actualist's family
     * and reconciliation guards, which this port does not have yet.
     */
    fun updateDrafts(transactionId: String, edit: TransactionEdit): List<CellDraft> {
        val row = loadRow(transactionId)?.takeIf { !it.tombstone } ?: throw LocalFirstException.InvalidLocalWrite("missing transaction")
        if (row.isParent || row.isChild) throw LocalFirstException.InvalidLocalWrite("editing split transactions is not supported yet")
        if (row.reconciled) throw LocalFirstException.InvalidLocalWrite("reconciled transactions are locked")
        val drafts = mutableListOf<CellDraft>()
        fun cell(column: String, value: SyncValue) {
            drafts += CellDraft("transactions", transactionId, column, value)
        }
        val newDate = edit.date?.let(Months::packedDate)?.toLong()
        if (newDate != null && newDate != row.date) cell("date", SyncValue.int(newDate))
        if (edit.amount != null && edit.amount != row.amount) cell("amount", SyncValue.int(edit.amount))
        var newPayee: String? = null
        if (edit.payeeId != null || !edit.payeeName.isNullOrBlank()) {
            val (payeeId, payeeDrafts) = resolveOrCreatePayeeDrafts(edit.payeeId, edit.payeeName ?: "")
            if (payeeId != row.payeeId) {
                if (row.transferId != null || transferAccountIdOf(payeeId) != null) {
                    throw LocalFirstException.InvalidLocalWrite("changing a transfer's payee is not supported yet")
                }
                drafts.addAll(0, payeeDrafts)
                newPayee = payeeId
                cell("description", SyncValue.text(payeeId))
            }
        }
        val offBudget = row.accountId?.let(::accountOffBudget) ?: false
        if (edit.clearCategory && row.categoryId != null) {
            cell("category", SyncValue.Null)
        } else if (edit.categoryId != null && edit.categoryId != row.categoryId && !offBudget) {
            validateCategory(edit.categoryId)
            cell("category", SyncValue.text(edit.categoryId))
        }
        if (edit.notes != null && edit.notes != (row.notes ?: "")) cell("notes", if (edit.notes.isEmpty()) SyncValue.Null else SyncValue.text(edit.notes))
        if (edit.cleared != null && edit.cleared != row.cleared) cell("cleared", SyncValue.bool(edit.cleared))
        check(newPayee == null || row.transferId == null)
        val paired = row.transferId?.let(::loadRow)?.takeIf { !it.tombstone }
        if (paired != null) {
            val pairedDrafts = mutableListOf<CellDraft>()
            if (edit.amount != null && -edit.amount != paired.amount) {
                pairedDrafts += CellDraft("transactions", paired.id, "amount", SyncValue.int(-edit.amount))
            }
            if (newDate != null && newDate != paired.date) pairedDrafts += CellDraft("transactions", paired.id, "date", SyncValue.int(newDate))
            if (edit.notes != null && edit.notes != (paired.notes ?: "")) {
                pairedDrafts += CellDraft("transactions", paired.id, "notes", if (edit.notes.isEmpty()) SyncValue.Null else SyncValue.text(edit.notes))
            }
            drafts += pairedDrafts
        }
        return drafts
    }

    /**
     * Edits a split family, or turns a simple row into one. Follows Actual's
     * `updateTransaction` for families: children take the parent's date, account and
     * cleared state, a child whose payee matched the old parent payee follows the new one,
     * removed children are tombstoned and new ones are written whole. The children must add
     * up to the parent amount.
     */
    fun updateSplitDrafts(transactionId: String, edit: SplitFamilyEdit): List<CellDraft> {
        val parent = loadRow(transactionId)?.takeIf { !it.tombstone } ?: throw LocalFirstException.InvalidLocalWrite("missing transaction")
        if (parent.isChild) throw LocalFirstException.InvalidLocalWrite("edit the whole split transaction instead")
        if (parent.transferId != null) throw LocalFirstException.InvalidLocalWrite("transfers cannot be split")
        if (parent.reconciled) throw LocalFirstException.InvalidLocalWrite("reconciled transactions are locked")
        if (edit.splits.isEmpty()) throw LocalFirstException.InvalidLocalWrite("a split needs at least one category")
        val total = edit.splits.sumOf { it.amount }
        if (total != edit.amount) {
            throw LocalFirstException.InvalidLocalWrite("split amounts ($total) do not add up to the transaction (${edit.amount})")
        }
        val accountId = parent.accountId ?: throw LocalFirstException.InvalidLocalWrite("missing account")
        val offBudget = accountOffBudget(accountId)
        edit.splits.forEach { split -> if (!offBudget) split.categoryId?.let(::validateCategory) }
        val children = if (parent.isParent) {
            sql.query("SELECT id FROM transactions WHERE parent_id = ? AND (tombstone IS NULL OR tombstone = 0)", transactionId)
                .mapNotNull { it.string("id") }.mapNotNull(::loadRow)
        } else {
            emptyList()
        }
        if (children.any { it.reconciled }) throw LocalFirstException.InvalidLocalWrite("reconciled transactions are locked")

        val drafts = mutableListOf<CellDraft>()
        fun cell(id: String, column: String, value: SyncValue) {
            drafts += CellDraft("transactions", id, column, value)
        }
        val date = edit.date?.let(Months::packedDate)?.toLong() ?: parent.date ?: throw LocalFirstException.InvalidLocalWrite("missing date")
        val cleared = edit.cleared ?: parent.cleared
        var payee = parent.payeeId
        if (edit.payeeId != null || !edit.payeeName.isNullOrBlank()) {
            val (payeeId, payeeDrafts) = resolveOrCreatePayeeDrafts(edit.payeeId, edit.payeeName ?: "")
            if (transferAccountIdOf(payeeId) != null) throw LocalFirstException.InvalidLocalWrite("transfers cannot be split")
            drafts += payeeDrafts
            payee = payeeId
        }
        // Parent row.
        if (!parent.isParent) cell(transactionId, "isParent", SyncValue.bool(true))
        if (parent.categoryId != null) cell(transactionId, "category", SyncValue.Null)
        if (date != parent.date) cell(transactionId, "date", SyncValue.int(date))
        if (edit.amount != parent.amount) cell(transactionId, "amount", SyncValue.int(edit.amount))
        if (payee != parent.payeeId) cell(transactionId, "description", SyncValue.textOrNull(payee))
        if (edit.notes != null && edit.notes != (parent.notes ?: "")) cell(transactionId, "notes", if (edit.notes.isEmpty()) SyncValue.Null else SyncValue.text(edit.notes))
        if (cleared != parent.cleared) cell(transactionId, "cleared", SyncValue.bool(cleared))

        val existing = children.associateBy { it.id }
        val keptIds = edit.splits.mapNotNull { it.id }.toSet()
        edit.splits.forEachIndexed { index, split ->
            val category = if (offBudget) null else split.categoryId
            val child = split.id?.let { existing[it] }
            if (child == null) {
                drafts += snapshotCells(
                    newId(), accountId, date.toInt(), split.amount, payee, category, split.notes, cleared,
                    isParent = false, parentId = transactionId, sortOrder = -(index + 1).toDouble(),
                )
            } else {
                val childPayee = if (child.payeeId == parent.payeeId) payee else child.payeeId
                if (child.date != date) cell(child.id, "date", SyncValue.int(date))
                if (child.accountId != accountId) cell(child.id, "acct", SyncValue.text(accountId))
                if (child.cleared != cleared) cell(child.id, "cleared", SyncValue.bool(cleared))
                if (childPayee != child.payeeId) cell(child.id, "description", SyncValue.textOrNull(childPayee))
                if (category != child.categoryId) cell(child.id, "category", SyncValue.textOrNull(category))
                if (split.amount != child.amount) cell(child.id, "amount", SyncValue.int(split.amount))
                if ((split.notes ?: "") != (child.notes ?: "")) cell(child.id, "notes", SyncValue.textOrNull(split.notes?.ifEmpty { null }))
            }
        }
        for (child in children) {
            if (child.id !in keptIds) cell(child.id, "tombstone", SyncValue.bool(true))
        }
        return drafts
    }

    fun updateSplit(transactionId: String, edit: SplitFamilyEdit): TransactionWriteResult {
        val result = database.commitLocal { updateSplitDrafts(transactionId, edit) to Unit }
        return TransactionWriteResult(transactionId, result.messages)
    }

    /** Tombstones a row; a transfer's paired row and a split parent's children go with it. */
    fun deleteDrafts(transactionId: String): List<CellDraft> {
        val row = loadRow(transactionId)?.takeIf { !it.tombstone } ?: throw LocalFirstException.InvalidLocalWrite("missing transaction")
        if (row.reconciled) throw LocalFirstException.InvalidLocalWrite("reconciled transactions are locked")
        if (row.isChild) throw LocalFirstException.InvalidLocalWrite("delete the whole split transaction instead")
        val ids = mutableListOf(transactionId)
        if (row.isParent) {
            ids += sql.query("SELECT id FROM transactions WHERE parent_id = ? AND (tombstone IS NULL OR tombstone = 0)", transactionId)
                .mapNotNull { it.string("id") }.sorted()
        }
        row.transferId?.let { paired -> if (loadRow(paired)?.tombstone == false) ids += paired }
        return ids.map { CellDraft("transactions", it, "tombstone", SyncValue.bool(true)) }
    }

    // endregion

    // region Lookups

    private fun validateAccount(accountId: String) {
        if (database.tableExists("accounts") && !database.liveRowExists("accounts", accountId)) {
            throw LocalFirstException.InvalidLocalWrite("missing account")
        }
    }

    private fun validateCategory(categoryId: String) {
        if (database.tableExists("categories") && !database.liveRowExists("categories", categoryId)) {
            throw LocalFirstException.InvalidLocalWrite("missing category")
        }
    }

    fun accountOffBudget(accountId: String): Boolean =
        (sql.queryLong("SELECT offbudget FROM accounts WHERE id = ?", accountId) ?: 0L) != 0L

    private fun transferDestinationAccountId(payeeId: String): String =
        transferAccountIdOf(payeeId)?.takeIf { it.isNotEmpty() }
            ?: throw LocalFirstException.InvalidLocalWrite("selected payee is not a transfer payee")

    fun transferAccountIdOf(payeeId: String): String? =
        sql.queryString("SELECT transfer_acct FROM payees WHERE id = ?", payeeId)?.takeIf { it.isNotEmpty() }

    private fun transferPayeeId(accountId: String): String =
        sql.queryString(
            "SELECT id FROM payees WHERE transfer_acct = ? AND ${BudgetDatabase.livePredicate(database.columnSet("payees"))} LIMIT 1",
            accountId,
        ) ?: throw LocalFirstException.InvalidLocalWrite("missing transfer payee for account")

    /** Actual stamps a new row's `sort_order` with the clock in milliseconds. */
    private fun newRowSortOrder(): Double = clock.nowMillis().toDouble()

    // endregion
}
