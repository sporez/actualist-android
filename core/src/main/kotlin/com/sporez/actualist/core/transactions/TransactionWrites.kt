package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.splits.SplitChildDraft
import com.sporez.actualist.core.splits.SplitFamilies
import com.sporez.actualist.core.splits.SplitFamilyDraft
import com.sporez.actualist.core.splits.SplitOptionalField
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

/**
 * One split line. A null [payeeId] with a blank [payeeName] leaves the line on the parent's payee;
 * a [payeeName] with no [payeeId] finds or creates that payee, and a transfer payee makes the line a transfer.
 */
data class SplitDraft(
    val categoryId: String?,
    val amount: Long,
    val notes: String? = null,
    val payeeId: String? = null,
    val payeeName: String = "",
)

/** One child of an edited split; a null [id] adds a new child. */
data class SplitEdit(
    val id: String?,
    val categoryId: String?,
    val amount: Long,
    val notes: String? = null,
    /** Null keeps an existing child's payee (a new child inherits the parent's). */
    val payeeId: String? = null,
    val payeeName: String = "",
)

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
    /** Moves the row to another account; a transfer's paired row follows Actual's rules. */
    val accountId: String? = null,
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
     * A new split family through the ported family operations ([SplitFamilies], replayed against
     * Actual's Splits oracle): the parent carries the total and no category, children inherit
     * account, date, payee and cleared state. Children must add up to the parent; the editor
     * enforces this, and a mismatch is refused here rather than written with Actual's error cell.
     */
    fun splitDrafts(draft: TransactionDraft, parentId: String, payeeId: String?): List<CellDraft> {
        validateAccount(draft.accountId)
        val total = draft.splits.sumOf { it.amount }
        if (total != draft.amount) {
            throw LocalFirstException.InvalidLocalWrite("split amounts (${total}) do not add up to the transaction (${draft.amount})")
        }
        draft.splits.forEach { split -> split.categoryId?.let(::validateCategory) }
        val payeeDrafts = mutableListOf<CellDraft>()
        val resolvedPayees = HashMap<String, String>()
        val children = draft.splits.map { split ->
            val childPayee = resolveSplitPayee(split.payeeId, split.payeeName, payeeDrafts, resolvedPayees)
            SplitChildDraft(
                categoryId = split.categoryId,
                amount = split.amount,
                payeeId = if (childPayee != null) SplitOptionalField.Value(childPayee) else SplitOptionalField.Omitted,
                notes = optional(split.notes),
            )
        }
        return payeeDrafts + SplitFamilies(database, clock, newId).createDrafts(
            SplitFamilyDraft(
                accountId = draft.accountId,
                date = draft.date,
                amount = draft.amount,
                payeeId = payeeId,
                notes = draft.notes,
                cleared = draft.cleared,
                sortOrder = draft.sortOrder ?: newRowSortOrder(),
                importedPayee = draft.importedPayee,
                importedId = draft.importedId,
                splits = children,
            ),
            parentId,
        ).drafts
    }

    /** A split line's payee: a live selected id, a found or planned new payee by name, or null to inherit. */
    private fun resolveSplitPayee(
        selected: String?,
        name: String,
        sink: MutableList<CellDraft>,
        resolvedByName: MutableMap<String, String>,
    ): String? {
        if (!selected.isNullOrEmpty()) {
            if (database.tableExists("payees") && !database.liveRowExists("payees", selected)) {
                throw LocalFirstException.InvalidLocalWrite("missing payee")
            }
            return selected
        }
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val key = trimmed.lowercase()
        resolvedByName[key]?.let { return it }
        val (payeeId, drafts) = resolveOrCreatePayeeDrafts(null, trimmed)
        sink += drafts
        resolvedByName[key] = payeeId
        return payeeId
    }

    private fun optional(text: String?): SplitOptionalField<String> = if (text == null) SplitOptionalField.Omitted else SplitOptionalField.Value(text)

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
     * Writes only the changed cells of a simple or transfer row, then applies Actual's
     * `transfer.onUpdate` (as Actualist's `transferMessagesOnUpdate` ports it): choosing a transfer
     * payee creates the paired row, leaving one tombstones it, and any other change to a transfer
     * moves the paired row to the destination account with this account's transfer payee, the
     * notes and the negated amount (never the date). Between accounts on the same side of the
     * budget the category is cleared on both legs. Split rows and reconciled rows are refused here.
     */
    fun updateDrafts(transactionId: String, edit: TransactionEdit): List<CellDraft> {
        val row = loadRow(transactionId)?.takeIf { !it.tombstone } ?: throw LocalFirstException.InvalidLocalWrite("missing transaction")
        if (row.isParent || row.isChild) throw LocalFirstException.InvalidLocalWrite("edit the whole split transaction instead")
        if (row.reconciled) throw LocalFirstException.InvalidLocalWrite("reconciled transactions are locked")
        val drafts = mutableListOf<CellDraft>()
        fun cell(id: String, column: String, value: SyncValue) {
            drafts += CellDraft("transactions", id, column, value)
        }

        val account = edit.accountId?.takeIf { it != row.accountId }?.also(::validateAccount) ?: row.accountId
            ?: throw LocalFirstException.InvalidLocalWrite("missing account")
        val date = edit.date?.let(Months::packedDate)?.toLong() ?: row.date
        val amount = edit.amount ?: row.amount
        val notes = edit.notes?.let { it.ifEmpty { null } } ?: if (edit.notes == null) row.notes else null
        var payee = row.payeeId
        if (edit.payeeId != null || !edit.payeeName.isNullOrBlank()) {
            val (payeeId, payeeDrafts) = resolveOrCreatePayeeDrafts(edit.payeeId, edit.payeeName ?: "")
            if (payeeId != row.payeeId) {
                drafts.addAll(0, payeeDrafts)
                payee = payeeId
            }
        }
        val destination = payee?.let(::transferAccountIdOf)
        if (destination != null && destination == account) throw LocalFirstException.InvalidLocalWrite("transfer to the same account")
        val offBudget = accountOffBudget(account)
        var category = when {
            edit.clearCategory -> null
            edit.categoryId != null && !offBudget -> edit.categoryId.also(::validateCategory)
            else -> row.categoryId
        }
        val cleared = edit.cleared ?: row.cleared

        val transferDrafts = mutableListOf<CellDraft>()
        var transferId = row.transferId
        val linkInputsUnchanged = account == row.accountId && payee == row.payeeId
        if (!(linkInputsUnchanged && (row.transferId == null || (amount == row.amount && notes == row.notes)))) {
            when {
                destination != null && row.transferId == null -> {
                    // Becomes a transfer: create the paired row.
                    val pairedId = newId()
                    transferId = pairedId
                    if (offBudget == accountOffBudget(destination)) category = null
                    transferDrafts += snapshotLeg(pairedId, destination, date ?: Months.packedDate(LocalDate.now()).toLong(), -amount, transferPayeeId(account), notes, transactionId)
                }
                destination == null && row.transferId != null -> {
                    // No longer a transfer: the paired row goes, and the link is cleared.
                    val paired = loadRow(row.transferId)
                    if (paired != null && !paired.tombstone) {
                        if (paired.isChild) {
                            transferDrafts += CellDraft("transactions", paired.id, "transferred_id", SyncValue.Null)
                            transferDrafts += CellDraft("transactions", paired.id, "description", SyncValue.Null)
                        } else {
                            transferDrafts += CellDraft("transactions", paired.id, "tombstone", SyncValue.bool(true))
                        }
                    }
                    transferId = null
                }
                destination != null && row.transferId != null -> {
                    val pairedId = row.transferId
                    transferDrafts += CellDraft("transactions", pairedId, "acct", SyncValue.text(destination))
                    transferDrafts += CellDraft("transactions", pairedId, "description", SyncValue.text(transferPayeeId(account)))
                    transferDrafts += CellDraft("transactions", pairedId, "notes", SyncValue.textOrNull(notes))
                    transferDrafts += CellDraft("transactions", pairedId, "amount", SyncValue.int(-amount))
                    if (offBudget == accountOffBudget(destination)) {
                        category = null
                        transferDrafts += CellDraft("transactions", pairedId, "category", SyncValue.Null)
                    }
                }
            }
        }

        // Across sides only the on-budget leg carries the category: from an off-budget account it goes on the paired row.
        val pairedId = transferId
        val chosenCategory = edit.categoryId
        if (offBudget && pairedId != null && destination != null && !accountOffBudget(destination) && chosenCategory != null) {
            validateCategory(chosenCategory)
            transferDrafts += CellDraft("transactions", pairedId, "category", SyncValue.text(chosenCategory))
        }

        if (account != row.accountId) cell(transactionId, "acct", SyncValue.text(account))
        if (date != row.date && date != null) cell(transactionId, "date", SyncValue.int(date))
        if (amount != row.amount) cell(transactionId, "amount", SyncValue.int(amount))
        if (payee != row.payeeId) cell(transactionId, "description", SyncValue.textOrNull(payee))
        if (category != row.categoryId) cell(transactionId, "category", SyncValue.textOrNull(category))
        if (notes != row.notes) cell(transactionId, "notes", SyncValue.textOrNull(notes))
        if (cleared != row.cleared) cell(transactionId, "cleared", SyncValue.bool(cleared))
        if (transferId != row.transferId) cell(transactionId, "transferred_id", SyncValue.textOrNull(transferId))
        return drafts + transferDrafts
    }

    /** Actualist's `.snapshot` cells for a new paired transfer leg (uncleared, uncategorized). */
    private fun snapshotLeg(id: String, account: String, date: Long, amount: Long, payee: String, notes: String?, transferId: String): List<CellDraft> = listOf(
        "acct" to SyncValue.text(account),
        "date" to SyncValue.int(date),
        "amount" to SyncValue.int(amount),
        "description" to SyncValue.text(payee),
        "category" to SyncValue.Null,
        "notes" to SyncValue.textOrNull(notes),
        "cleared" to SyncValue.bool(false),
        "reconciled" to SyncValue.bool(false),
        "isParent" to SyncValue.bool(false),
        "parent_id" to SyncValue.Null,
        "isChild" to SyncValue.bool(false),
        "transferred_id" to SyncValue.text(transferId),
        "tombstone" to SyncValue.bool(false),
    ).map { (column, value) -> CellDraft("transactions", id, column, value) }

    /**
     * Edits a split family, or turns a simple row into one, through the ported family operations
     * ([SplitFamilies.updateDrafts], Actual's `updateTransaction` on a family). Children not listed
     * are removed. The children must add up to the parent amount.
     */
    fun updateSplitDrafts(transactionId: String, edit: SplitFamilyEdit): List<CellDraft> {
        val parent = loadRow(transactionId)?.takeIf { !it.tombstone } ?: throw LocalFirstException.InvalidLocalWrite("missing transaction")
        if (parent.isChild) throw LocalFirstException.InvalidLocalWrite("edit the whole split transaction instead")
        if (parent.transferId != null) throw LocalFirstException.InvalidLocalWrite("transfers cannot be split")
        if (edit.splits.isEmpty()) throw LocalFirstException.InvalidLocalWrite("a split needs at least one category")
        val total = edit.splits.sumOf { it.amount }
        if (total != edit.amount) {
            throw LocalFirstException.InvalidLocalWrite("split amounts ($total) do not add up to the transaction (${edit.amount})")
        }
        val accountId = parent.accountId ?: throw LocalFirstException.InvalidLocalWrite("missing account")
        edit.splits.forEach { split -> split.categoryId?.let(::validateCategory) }
        val drafts = mutableListOf<CellDraft>()
        var payee = parent.payeeId
        if (edit.payeeId != null || !edit.payeeName.isNullOrBlank()) {
            val (payeeId, payeeDrafts) = resolveOrCreatePayeeDrafts(edit.payeeId, edit.payeeName ?: "")
            if (transferAccountIdOf(payeeId) != null) throw LocalFirstException.InvalidLocalWrite("transfers cannot be split")
            drafts += payeeDrafts
            payee = payeeId
        }
        val date = edit.date ?: parent.date?.let(Months::dateFromPacked) ?: throw LocalFirstException.InvalidLocalWrite("missing date")
        val childPayees = HashMap<String, String>()
        val children = edit.splits.map { split ->
            val childPayee = resolveSplitPayee(split.payeeId, split.payeeName, drafts, childPayees)
            SplitChildDraft(
                id = split.id,
                categoryId = split.categoryId,
                amount = split.amount,
                payeeId = if (childPayee != null) SplitOptionalField.Value(childPayee) else SplitOptionalField.Omitted,
                notes = optional(split.notes),
            )
        }
        drafts += SplitFamilies(database, clock, newId).updateDrafts(
            transactionId,
            SplitFamilyDraft(
                accountId = accountId,
                date = date,
                amount = edit.amount,
                payeeId = payee,
                notes = edit.notes?.ifEmpty { null } ?: if (edit.notes == null) parent.notes else null,
                cleared = edit.cleared ?: parent.cleared,
                reconciled = parent.reconciled,
                splits = children,
            ),
        ).drafts
        return drafts
    }

    fun updateSplit(transactionId: String, edit: SplitFamilyEdit): TransactionWriteResult {
        val result = database.commitLocal { updateSplitDrafts(transactionId, edit) to Unit }
        return TransactionWriteResult(transactionId, result.messages)
    }

    /**
     * Collapses a split parent back to a normal transaction (Actualist: removing split rows down to
     * one): the children are tombstoned, the parent stops being a parent and takes the survivor's
     * [categoryId], and the supplied [edit] fields (amount, date, notes, cleared, payee) are applied.
     */
    fun collapseSplitDrafts(transactionId: String, categoryId: String?, edit: TransactionEdit): List<CellDraft> {
        val parent = loadRow(transactionId)?.takeIf { !it.tombstone } ?: throw LocalFirstException.InvalidLocalWrite("missing transaction")
        if (!parent.isParent) throw LocalFirstException.InvalidLocalWrite("not a split transaction")
        if (parent.reconciled) throw LocalFirstException.InvalidLocalWrite("reconciled transactions are locked")
        categoryId?.let(::validateCategory)
        val drafts = mutableListOf<CellDraft>()
        sql.query("SELECT id FROM transactions WHERE parent_id = ? AND (tombstone IS NULL OR tombstone = 0)", transactionId)
            .mapNotNull { it.string("id") }.sorted()
            .forEach { drafts += CellDraft("transactions", it, "tombstone", SyncValue.bool(true)) }
        fun cell(column: String, value: SyncValue) {
            drafts += CellDraft("transactions", transactionId, column, value)
        }
        cell("isParent", SyncValue.bool(false))
        cell("category", SyncValue.textOrNull(categoryId))
        edit.amount?.let { cell("amount", SyncValue.int(it)) }
        edit.date?.let { cell("date", SyncValue.int(Months.packedDate(it).toLong())) }
        edit.notes?.let { cell("notes", SyncValue.textOrNull(it.ifEmpty { null })) }
        edit.cleared?.let { cell("cleared", SyncValue.bool(it)) }
        edit.payeeId?.let { cell("description", SyncValue.text(it)) }
        return drafts
    }

    fun collapseSplit(transactionId: String, categoryId: String?, edit: TransactionEdit): TransactionWriteResult {
        val result = database.commitLocal { collapseSplitDrafts(transactionId, categoryId, edit) to Unit }
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
