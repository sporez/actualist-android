package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncValue
import java.time.LocalDate
import java.util.UUID

/**
 * Account lifecycle writes, following Actual's `createAccount`: the account row, its
 * transfer payee (empty name, `transfer_acct`), the payee mapping, and an opening balance
 * row when a balance is given. Rename, close and reopen live in [AccountLifecycle].
 */
class AccountWrites(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val sql get() = database.sql

    data class Created(val accountId: String, val messages: List<SyncMessage>)

    fun create(name: String, offBudget: Boolean, openingBalance: Long, date: LocalDate): Created {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LocalFirstException.InvalidLocalWrite("missing account name")
        val accountId = newId()
        val payeeId = newId()
        val result = database.commitLocal {
            val maxSort = sql.queryOne("SELECT MAX(sort_order) AS m FROM accounts")?.double("m") ?: 0.0
            val drafts = mutableListOf(
                CellDraft("accounts", accountId, "name", SyncValue.text(trimmed)),
                CellDraft("accounts", accountId, "offbudget", SyncValue.bool(offBudget)),
                CellDraft("accounts", accountId, "closed", SyncValue.bool(false)),
                CellDraft("accounts", accountId, "sort_order", SyncValue.Real(maxSort + 16_384.0)),
                CellDraft("payees", payeeId, "name", SyncValue.text("")),
                CellDraft("payees", payeeId, "transfer_acct", SyncValue.text(accountId)),
                CellDraft("payee_mapping", payeeId, "targetId", SyncValue.text(payeeId)),
            )
            if (openingBalance != 0L) {
                // Rows planned in this commit are not in the database yet, so validate after applying.
                drafts += TransactionWrites(database, clock, newId).openingBalanceDraftsForNewAccount(
                    newId(), accountId, openingBalance, date, onBudget = !offBudget,
                )
            }
            drafts to Unit
        }
        return Created(accountId, result.messages)
    }
}
