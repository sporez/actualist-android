package com.sporez.actualist.android.automation

import android.content.Context
import com.sporez.actualist.android.AppContainer
import com.sporez.actualist.android.DeepLinks
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.privacy.SampleValues
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.textimport.TextImportDirection
import com.sporez.actualist.core.transactions.TransactionDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** What a caller (Tasker, a Routine, a test) asks LOG_TRANSACTION to record; amounts are plain text like "12.50". */
data class LogRequest(
    val amount: String?,
    val payee: String? = null,
    val account: String? = null,
    val category: String? = null,
    val notes: String? = null,
    val direction: String? = null,
)

/** The outcome of an automation call: a sentence for the user, and for GET_BALANCES the (name, minor units) pairs. */
data class AutomationResult(
    val ok: Boolean,
    val message: String,
    val balances: List<Pair<String, Long>> = emptyList(),
)

/**
 * The Android analogue of Actualist's Shortcuts and Siri access (`ShortcutTransactionCommand`,
 * `ShortcutsBudgetSession`): other apps can log a transaction and read balances in the selected
 * budget, but only while "Allow automation" is on. Wording follows `ShortcutsError`.
 *
 * Writes go through the open session's write path (shared with the UI through the session hub),
 * so they are recorded for undo and wait in the outbox for sync.
 */
object Automation {
    const val ACTION_LOG_TRANSACTION = "com.sporez.actualist.android.automation.LOG_TRANSACTION"
    const val ACTION_GET_BALANCES = "com.sporez.actualist.android.automation.GET_BALANCES"

    /** Broadcast with the outcome, sent to [EXTRA_REPLY_PACKAGE] when a caller names one. */
    const val ACTION_RESULT = "com.sporez.actualist.android.automation.RESULT"

    const val EXTRA_AMOUNT = "amount"
    const val EXTRA_PAYEE = "payee"
    const val EXTRA_ACCOUNT = "account"
    const val EXTRA_CATEGORY = "category"
    const val EXTRA_NOTES = "notes"
    const val EXTRA_DIRECTION = "direction"
    const val EXTRA_REPLY_PACKAGE = "reply_package"

    const val EXTRA_OK = "ok"
    const val EXTRA_MESSAGE = "message"

    /** One "Account name=minor units" entry per account, for GET_BALANCES. */
    const val EXTRA_BALANCES = "balances"

    const val DISABLED = "Automation is turned off in Settings."
    const val NO_BUDGET = "No budget is selected. Open Actualist and choose a budget first."
    const val FILE_MISSING = "The budget file is not on this device. Reopen the budget in Actualist."
    const val ACCOUNT_NOT_FOUND = "That account could not be found in the selected budget."
    const val CATEGORY_NOT_FOUND = "That category could not be found in the selected budget."
    const val AMOUNT_MISSING = "Enter an amount."
    const val AMOUNT_INVALID = "That amount is not valid."
    const val DEFAULT_ACCOUNT_MISSING = "Choose an account or set a default account in Settings."
    const val AMBIGUOUS = "That name matches more than one item. Be more specific."
    const val ACCOUNT_CLOSED = "That account is closed. Choose an open account."
    const val DIRECTION_INVALID = "Direction must be spend or inflow."
    const val WRITE_FAILED = "Actualist could not apply that change."
    const val BALANCES_BLOCKED = "Turn off Sample Values before reading balances."

    private class Refusal(message: String) : Exception(message)

    /** Records one transaction in the selected budget; refused when automation is off or Sample Values is on. */
    suspend fun logTransaction(context: Context, container: AppContainer, request: LogRequest): AutomationResult {
        val app = context.applicationContext
        return try {
            if (!AutomationPrefs.isAllowed(app)) throw Refusal(DISABLED)
            if (SampleValues.active) throw Refusal(SampleValues.PAYEE_WRITE_BLOCKED)
            val direction = if (request.direction.isNullOrBlank()) {
                TextImportDirection.SPEND
            } else {
                DeepLinks.directionOf(request.direction) ?: throw Refusal(DIRECTION_INVALID)
            }
            val amountText = request.amount?.trim().orEmpty()
            if (amountText.isEmpty()) throw Refusal(AMOUNT_MISSING)
            val magnitude = Money.parse(amountText)?.let { kotlin.math.abs(it) } ?: throw Refusal(AMOUNT_INVALID)
            if (magnitude <= 0L) throw Refusal(AMOUNT_INVALID)
            val defaultAccountId = container.settings.settings.value.defaultAccountId

            withBudget(container) { session ->
                val draft = session.read {
                    val accounts = ledger.accounts(includeClosed = true)
                    val account = if (request.account.isNullOrBlank()) {
                        val wanted = defaultAccountId ?: throw Refusal(DEFAULT_ACCOUNT_MISSING)
                        accounts.firstOrNull { it.id == wanted } ?: throw Refusal(ACCOUNT_NOT_FOUND)
                    } else {
                        uniqueMatch(accounts.filter { !it.closed }, { it.id }, { it.name }, request.account, ACCOUNT_NOT_FOUND)
                    }
                    if (account.closed) throw Refusal(ACCOUNT_CLOSED)
                    val category = request.category?.takeIf { it.isNotBlank() }?.let { query ->
                        uniqueMatch(ledger.categories(includeHidden = false), { it.id }, { it.name }, query, CATEGORY_NOT_FOUND)
                    }
                    TransactionDraft(
                        accountId = account.id,
                        date = LocalDate.now(),
                        amount = if (direction == TextImportDirection.INFLOW) magnitude else -magnitude,
                        payeeName = request.payee?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown",
                        // Off-budget accounts carry no category, as in Actualist.
                        categoryId = if (account.offBudget) null else category?.id,
                        notes = request.notes?.trim()?.takeIf { it.isNotEmpty() },
                        cleared = false,
                    ) to account.name
                }
                session.write { transactionWrites.create(draft.first) }
                if (!session.isDemo && container.server.isSignedIn) runCatching { container.server.sync(session) }
                runCatching { com.sporez.actualist.android.widget.WidgetData.refresh(app) }
                val verb = if (direction == TextImportDirection.INFLOW) "received in" else "spent from"
                AutomationResult(true, "Logged ${Money.format(magnitude)} $verb ${draft.second}.")
            }
        } catch (error: Refusal) {
            AutomationResult(false, error.message ?: WRITE_FAILED)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AutomationResult(false, WRITE_FAILED)
        }
    }

    /** The balance of every open account (or the one matching [accountQuery]) in the selected budget. */
    suspend fun getBalances(context: Context, container: AppContainer, accountQuery: String? = null): AutomationResult {
        val app = context.applicationContext
        return try {
            if (!AutomationPrefs.isAllowed(app)) throw Refusal(DISABLED)
            if (SampleValues.active) throw Refusal(BALANCES_BLOCKED)
            withBudget(container) { session ->
                val rows = session.read {
                    val open = ledger.accounts(includeClosed = false)
                    val chosen = if (accountQuery.isNullOrBlank()) open else listOf(uniqueMatch(open, { it.id }, { it.name }, accountQuery, ACCOUNT_NOT_FOUND))
                    chosen.map { it.name to it.balance }
                }
                val message = rows.joinToString("\n") { (name, balance) -> "$name: ${Money.format(balance)}" }
                AutomationResult(true, message, rows)
            }
        } catch (error: Refusal) {
            AutomationResult(false, error.message ?: WRITE_FAILED)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AutomationResult(false, WRITE_FAILED)
        }
    }

    /** Runs [block] on the selected budget's session, shared with the UI through the hub. */
    private suspend fun <T> withBudget(container: AppContainer, block: suspend (BudgetSession) -> T): T {
        val id = container.settings.settings.value.selectedBudgetId ?: throw Refusal(NO_BUDGET)
        val session = withContext(Dispatchers.IO) {
            container.sessions.acquire(id) { container.library.open(id) ?: throw Refusal(FILE_MISSING) }
        }
        try {
            return block(session)
        } finally {
            container.sessions.release(session)
        }
    }

    /** An id, else a unique exact name (ignoring case), else a unique name prefix, as Actualist's `uniqueMatch`. */
    private fun <T> uniqueMatch(items: List<T>, id: (T) -> String, name: (T) -> String, query: String, notFound: String): T {
        val needle = query.trim()
        if (needle.isEmpty()) throw Refusal(notFound)
        items.firstOrNull { id(it) == needle }?.let { return it }
        val exact = items.filter { name(it).equals(needle, ignoreCase = true) }
        if (exact.size == 1) return exact[0]
        if (exact.size > 1) throw Refusal(AMBIGUOUS)
        val prefix = items.filter { name(it).lowercase().startsWith(needle.lowercase()) }
        if (prefix.size == 1) return prefix[0]
        if (prefix.size > 1) throw Refusal(AMBIGUOUS)
        throw Refusal(notFound)
    }
}
