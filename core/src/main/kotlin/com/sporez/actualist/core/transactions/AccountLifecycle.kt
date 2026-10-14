package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.budget.TransactionSplitSql
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncValue
import java.time.LocalDate
import java.util.UUID

enum class AccountCloseBlocker(val message: String) {
    ALREADY_CLOSED("This account is already closed."),
    UNSUPPORTED_BANK_LINK("Unlink this account from its bank provider in Actual first."),
    DESTINATION_REQUIRED("Choose an account to move the remaining balance to."),
    DESTINATION_IS_SOURCE("Choose a different account to move the balance to."),
    DESTINATION_UNAVAILABLE("Choose an open account to move the balance to."),
    CATEGORY_REQUIRED("Moving money off budget needs a category."),
    CATEGORY_UNAVAILABLE("Choose an existing category."),
}

sealed class AccountCloseAction {
    /** No live transaction: Actual deletes (tombstones) the account. */
    data object DeleteEmpty : AccountCloseAction()
    data object CloseAtZero : AccountCloseAction()
    data class CloseWithTransfer(val destinationAccountId: String, val sourceAmount: Long, val categoryId: String?) : AccountCloseAction()
}

data class AccountCloseReview(
    val accountId: String,
    val balance: Long,
    val liveTransactionCount: Int,
    val offBudget: Boolean,
    val hasSimpleFinLink: Boolean,
    val action: AccountCloseAction?,
    val blockers: List<AccountCloseBlocker>,
)

/**
 * Account close, reopen, rename and SimpleFIN unlink. Port of Actualist
 * `BudgetDatabase+AccountLifecycleReads`, `+AccountLifecycleClose` and `+AccountLifecycleWrites`
 * (Actual's `closeAccount`): an account with no live transaction is tombstoned; at a zero
 * balance it is closed; otherwise the balance moves to a destination account with a
 * "Closing account" transfer dated today and then it is closed. A SimpleFIN link is cleared
 * first. Rename trims and skips unchanged names (Actualist's intended OP-22).
 */
class AccountLifecycle(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val sql get() = database.sql

    private data class AccountRow(val id: String, val name: String, val offBudget: Boolean, val closed: Boolean, val syncSource: String?, val remoteId: String?)

    private fun account(id: String): AccountRow? = sql.queryOne(
        "SELECT id, name, offbudget, closed, account_sync_source, account_id FROM accounts WHERE id = ? AND (tombstone IS NULL OR tombstone = 0)",
        id,
    )?.let {
        AccountRow(it.string("id")!!, it.string("name") ?: "", it.bool("offbudget"), it.bool("closed"), it.string("account_sync_source"), it.string("account_id"))
    }

    fun review(accountId: String, destinationAccountId: String?, categoryId: String?): AccountCloseReview {
        val source = account(accountId) ?: throw LocalFirstException.InvalidLocalWrite("missing account")
        val split = TransactionSplitSql(database.columnSet("transactions"))
        val balance = sql.queryLong(
            "SELECT SUM(${split.amount}) FROM transactions t ${split.parentJoin()} WHERE ${split.liveInlinePredicate()} AND ${split.account} = ?",
            accountId,
        ) ?: 0L
        val count = (sql.queryLong("SELECT COUNT(*) FROM transactions WHERE acct = ? AND (tombstone IS NULL OR tombstone = 0)", accountId) ?: 0L).toInt()
        val linked = !source.remoteId.isNullOrEmpty()
        val simpleFin = linked && source.syncSource == SIMPLEFIN
        val blockers = mutableListOf<AccountCloseBlocker>()
        if (source.closed) blockers += AccountCloseBlocker.ALREADY_CLOSED
        if (linked && !simpleFin) blockers += AccountCloseBlocker.UNSUPPORTED_BANK_LINK
        var action: AccountCloseAction? = when {
            count == 0 -> AccountCloseAction.DeleteEmpty
            balance == 0L -> AccountCloseAction.CloseAtZero
            else -> null
        }
        if (action == null) {
            val destination = destinationAccountId?.let(::account)?.takeIf { !it.closed && it.id != accountId }
            when {
                destinationAccountId == accountId -> blockers += AccountCloseBlocker.DESTINATION_IS_SOURCE
                destinationAccountId == null -> blockers += AccountCloseBlocker.DESTINATION_REQUIRED
                destination == null -> blockers += AccountCloseBlocker.DESTINATION_UNAVAILABLE
            }
            if (destination != null) {
                val categoryRequired = !source.offBudget && destination.offBudget
                val categoryExists = categoryId != null && database.liveRowExists("categories", categoryId)
                if (categoryRequired && categoryId == null) {
                    blockers += AccountCloseBlocker.CATEGORY_REQUIRED
                } else if (categoryId != null && !categoryExists) {
                    blockers += AccountCloseBlocker.CATEGORY_UNAVAILABLE
                }
                if (blockers.isEmpty()) {
                    action = AccountCloseAction.CloseWithTransfer(destination.id, Math.negateExact(balance), if (categoryRequired) categoryId else null)
                }
            }
        }
        return AccountCloseReview(accountId, balance, count, source.offBudget, simpleFin, if (blockers.isEmpty()) action else null, blockers)
    }

    fun closeDrafts(accountId: String, destinationAccountId: String?, categoryId: String?, today: LocalDate, includeUnlink: Boolean = true): List<CellDraft> {
        val review = review(accountId, destinationAccountId, categoryId)
        val action = review.action ?: throw LocalFirstException.InvalidLocalWrite(review.blockers.firstOrNull()?.message ?: "this account cannot be closed")
        val drafts = mutableListOf<CellDraft>()
        if (review.hasSimpleFinLink && includeUnlink) drafts += unlinkCells(accountId)
        when (action) {
            AccountCloseAction.DeleteEmpty -> drafts += CellDraft("accounts", accountId, "tombstone", SyncValue.bool(true))
            AccountCloseAction.CloseAtZero -> drafts += CellDraft("accounts", accountId, "closed", SyncValue.bool(true))
            is AccountCloseAction.CloseWithTransfer -> {
                drafts += CellDraft("accounts", accountId, "closed", SyncValue.bool(true))
                drafts += closingTransfer(accountId, review.offBudget, action, today)
            }
        }
        return drafts
    }

    private fun closingTransfer(sourceId: String, sourceOffBudget: Boolean, transfer: AccountCloseAction.CloseWithTransfer, today: LocalDate): List<CellDraft> {
        val sourceRow = newId()
        val destinationRow = newId()
        if (sourceRow == destinationRow) throw LocalFirstException.InvalidLocalWrite("duplicate transaction ids")
        val destinationOffBudget = account(transfer.destinationAccountId)?.offBudget ?: sourceOffBudget
        val date = SyncValue.int(Months.packedDate(today))
        val sortOrder = SyncValue.Real(clock.nowMillis().toDouble())
        val notes = SyncValue.text(CLOSING_NOTE)
        val source = mutableListOf(
            "acct" to SyncValue.text(sourceId),
            "date" to date,
            "amount" to SyncValue.int(transfer.sourceAmount),
            "description" to SyncValue.text(transferPayee(transfer.destinationAccountId)),
        )
        // Actual clears the source category only between accounts on the same side of the budget.
        if (transfer.categoryId != null || sourceOffBudget == destinationOffBudget) source += "category" to SyncValue.textOrNull(transfer.categoryId)
        source += listOf("notes" to notes, "cleared" to SyncValue.bool(true), "reconciled" to SyncValue.bool(false), "transferred_id" to SyncValue.text(destinationRow), "sort_order" to sortOrder)
        val destination = listOf(
            "acct" to SyncValue.text(transfer.destinationAccountId),
            "date" to date,
            "amount" to SyncValue.int(-transfer.sourceAmount),
            "description" to SyncValue.text(transferPayee(sourceId)),
            "notes" to notes,
            "cleared" to SyncValue.bool(false),
            "reconciled" to SyncValue.bool(false),
            "transferred_id" to SyncValue.text(sourceRow),
            "sort_order" to sortOrder,
        )
        return source.map { (column, value) -> CellDraft("transactions", sourceRow, column, value) } +
            destination.map { (column, value) -> CellDraft("transactions", destinationRow, column, value) }
    }

    private fun transferPayee(accountId: String): String {
        val ids = sql.query("SELECT id FROM payees WHERE transfer_acct = ? AND (tombstone IS NULL OR tombstone = 0) ORDER BY id", accountId)
            .mapNotNull { it.string("id") }
        return ids.singleOrNull()?.takeIf { it.isNotEmpty() } ?: throw LocalFirstException.InvalidLocalWrite("missing or duplicate transfer payee")
    }

    /** Clears the seven local SimpleFIN link cells; `last_sync` is kept, as Actual does. */
    fun unlinkDrafts(accountId: String): List<CellDraft> {
        val row = account(accountId) ?: throw LocalFirstException.InvalidLocalWrite("missing account")
        if (row.remoteId.isNullOrEmpty() || row.syncSource != SIMPLEFIN) {
            throw LocalFirstException.InvalidLocalWrite("only SimpleFIN-linked accounts can be unlinked here")
        }
        return unlinkCells(accountId)
    }

    private fun unlinkCells(accountId: String): List<CellDraft> {
        val columns = database.columnSet("accounts")
        return listOf("account_id", "bank", "balance_current", "balance_available", "balance_limit", "account_sync_source", "bank_sync_status")
            .filter { it in columns }
            .map { CellDraft("accounts", accountId, it, SyncValue.Null) }
    }

    /** Reopen always writes `closed = 0` for a closed-or-open live account, as the pinned handler does. */
    fun reopenDrafts(accountId: String): List<CellDraft> {
        account(accountId) ?: throw LocalFirstException.InvalidLocalWrite("missing account")
        return listOf(CellDraft("accounts", accountId, "closed", SyncValue.bool(false)))
    }

    fun renameDrafts(accountId: String, name: String): List<CellDraft> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LocalFirstException.InvalidLocalWrite("missing account name")
        val current = account(accountId) ?: throw LocalFirstException.InvalidLocalWrite("missing account")
        if (current.name == trimmed) return emptyList()
        return listOf(CellDraft("accounts", accountId, "name", SyncValue.text(trimmed)))
    }

    /**
     * Closes as Actual does: a SimpleFIN link is cleared first in its own batch, outside the undo
     * history, so undoing the close reopens the account without restoring the bank link.
     */
    fun close(accountId: String, destinationAccountId: String?, categoryId: String?, today: LocalDate): List<SyncMessage> {
        val review = review(accountId, destinationAccountId, categoryId)
        if (review.action == null) throw LocalFirstException.InvalidLocalWrite(review.blockers.firstOrNull()?.message ?: "this account cannot be closed")
        val unlinked = if (review.hasSimpleFinLink) database.commitLocal(undoable = false) { unlinkCells(accountId) to Unit }.messages else emptyList()
        return unlinked + database.commitLocal { closeDrafts(accountId, destinationAccountId, categoryId, today, includeUnlink = false) to Unit }.messages
    }

    fun reopen(accountId: String): List<SyncMessage> = database.commitLocal { reopenDrafts(accountId) to Unit }.messages

    fun rename(accountId: String, name: String): List<SyncMessage> = database.commitLocal { renameDrafts(accountId, name) to Unit }.messages

    fun unlink(accountId: String): List<SyncMessage> = database.commitLocal { unlinkDrafts(accountId) to Unit }.messages

    companion object {
        const val CLOSING_NOTE = "Closing account"
        const val SIMPLEFIN = "simpleFin"
    }
}
