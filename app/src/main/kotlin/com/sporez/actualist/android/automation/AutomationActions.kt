package com.sporez.actualist.android.automation

import android.content.Context
import com.sporez.actualist.android.AppContainer
import com.sporez.actualist.android.DeepLinks
import com.sporez.actualist.core.budget.BudgetMonthCategory
import com.sporez.actualist.core.budget.BudgetTable
import com.sporez.actualist.core.budget.MoveMoneyCommand
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.schedules.ScheduleStatus
import com.sporez.actualist.core.schedules.Schedules
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.templates.BudgetTemplates
import com.sporez.actualist.core.templates.TemplateApplyMode
import com.sporez.actualist.core.templates.UnsupportedTemplateException
import com.sporez.actualist.core.textimport.TextImportDirection
import com.sporez.actualist.core.transactions.AccountWrites
import com.sporez.actualist.core.transactions.CategoryOption
import com.sporez.actualist.core.transactions.PayeeWrites
import com.sporez.actualist.core.transactions.TransactionBatch
import com.sporez.actualist.core.transactions.TransactionDraft
import com.sporez.actualist.core.transactions.TransactionEdit
import com.sporez.actualist.core.transactions.TransactionQuery
import java.time.LocalDate
import kotlin.math.abs

/**
 * The rest of Actualist's Shortcuts operations as broadcast actions on [AutomationReceiver]. Every
 * action is refused unless "Allow Automation" is on, and refused under Sample Values. Every write goes
 * through the session's write path (so it syncs and can be undone), then a server sync when signed in,
 * then a widget refresh. Amounts are plain text such as "12.50"; amounts that come back are integer
 * minor units as text. Names match an id, else a unique exact name (any case), else a unique prefix.
 * `month` is `YYYY-MM` (default: this month); `date` is `YYYY-MM-DD` (default: today).
 *
 * Result: ordered-broadcast result code (OK or CANCELED), result data = the message, and extras `ok`
 * (boolean), `message` (string), plus the action's named values as string extras and, for list reads,
 * `items` (string array). The same extras are sent as an [Automation.ACTION_RESULT] broadcast to
 * `reply_package` when set. Action strings are prefixed with `com.sporez.actualist.android.automation.`.
 *
 * Writes (extras are strings; `*` = required):
 * - LOG_TRANSFER: amount*, to_account*, account (default account), date, notes. Values: none.
 * - UPDATE_TRANSACTION: id*, and any of amount (sign kept unless `direction` is spend or inflow), payee,
 *   category, clear_category (true), notes (empty clears), date, cleared (true/false). Splits and
 *   reconciled rows are refused; a transfer's payee and category cannot change.
 * - CATEGORIZE_TRANSACTION: id*, category*.
 * - SET_CLEARED: id*, cleared* (true/false).
 * - DELETE_TRANSACTION: id*.
 * - ASSIGN_BUDGET: category*, amount*, month. Values: category, month, budgeted.
 * - ADD_TO_BUDGET: category*, amount* (To Budget to category: adds to what is budgeted), month.
 *   Values: category, month, budgeted. Not on tracking budgets.
 * - MOVE_MONEY: from_category and/or to_category (a missing side is To Budget), amount*, month.
 *   Values: month, to_budget. Not on tracking budgets.
 * - APPLY_TEMPLATES: month, overwrite (true replaces budgeted amounts; default fills empty ones).
 *   Values: month, to_budget.
 * - SET_CARRYOVER: category*, carryover* (true/false), month. Values: category, month, carryover.
 * - CREATE_PAYEE: name*. Values: id, name (an existing name is returned, not duplicated).
 * - CREATE_ACCOUNT: name*, off_budget (true/false), opening_balance. Values: id, name.
 *
 * Reads:
 * - GET_BUDGET_SUMMARY: month. Values: month, to_budget, budgeted, spent (negative for outflow, as the
 *   budget sheet stores it), income, balance.
 * - GET_CATEGORY_BALANCE: category*, month. Values: category, month, budgeted, spent, balance, carryover.
 * - GET_OVERSPENT: month. Values: month, count; items: `name=balance` per overspent category.
 * - GET_UNCATEGORIZED_COUNT: Values: count.
 * - GET_NET_WORTH: Values: net_worth.
 * - GET_CASH_FLOW: month. Values: month, income, expenses, net, uncategorized.
 * - GET_RECENT_TRANSACTIONS: limit (1 to 100, default 10), account. Items: `date|payee|amount|category|account|id`.
 * - GET_UPCOMING_SCHEDULES: limit (1 to 100, default 10). Items: `date|name|amount|account|status|id`.
 */
object AutomationActions {
    private const val PREFIX = "com.sporez.actualist.android.automation."

    const val ACTION_LOG_TRANSFER = PREFIX + "LOG_TRANSFER"
    const val ACTION_UPDATE_TRANSACTION = PREFIX + "UPDATE_TRANSACTION"
    const val ACTION_CATEGORIZE_TRANSACTION = PREFIX + "CATEGORIZE_TRANSACTION"
    const val ACTION_SET_CLEARED = PREFIX + "SET_CLEARED"
    const val ACTION_DELETE_TRANSACTION = PREFIX + "DELETE_TRANSACTION"
    const val ACTION_ASSIGN_BUDGET = PREFIX + "ASSIGN_BUDGET"
    const val ACTION_ADD_TO_BUDGET = PREFIX + "ADD_TO_BUDGET"
    const val ACTION_MOVE_MONEY = PREFIX + "MOVE_MONEY"
    const val ACTION_APPLY_TEMPLATES = PREFIX + "APPLY_TEMPLATES"
    const val ACTION_SET_CARRYOVER = PREFIX + "SET_CARRYOVER"
    const val ACTION_CREATE_PAYEE = PREFIX + "CREATE_PAYEE"
    const val ACTION_CREATE_ACCOUNT = PREFIX + "CREATE_ACCOUNT"

    const val ACTION_GET_BUDGET_SUMMARY = PREFIX + "GET_BUDGET_SUMMARY"
    const val ACTION_GET_CATEGORY_BALANCE = PREFIX + "GET_CATEGORY_BALANCE"
    const val ACTION_GET_OVERSPENT = PREFIX + "GET_OVERSPENT"
    const val ACTION_GET_UNCATEGORIZED_COUNT = PREFIX + "GET_UNCATEGORIZED_COUNT"
    const val ACTION_GET_NET_WORTH = PREFIX + "GET_NET_WORTH"
    const val ACTION_GET_CASH_FLOW = PREFIX + "GET_CASH_FLOW"
    const val ACTION_GET_RECENT_TRANSACTIONS = PREFIX + "GET_RECENT_TRANSACTIONS"
    const val ACTION_GET_UPCOMING_SCHEDULES = PREFIX + "GET_UPCOMING_SCHEDULES"

    val ACTIONS: Set<String> = setOf(
        ACTION_LOG_TRANSFER, ACTION_UPDATE_TRANSACTION, ACTION_CATEGORIZE_TRANSACTION, ACTION_SET_CLEARED,
        ACTION_DELETE_TRANSACTION, ACTION_ASSIGN_BUDGET, ACTION_ADD_TO_BUDGET, ACTION_MOVE_MONEY,
        ACTION_APPLY_TEMPLATES, ACTION_SET_CARRYOVER, ACTION_CREATE_PAYEE, ACTION_CREATE_ACCOUNT,
        ACTION_GET_BUDGET_SUMMARY, ACTION_GET_CATEGORY_BALANCE, ACTION_GET_OVERSPENT, ACTION_GET_UNCATEGORIZED_COUNT,
        ACTION_GET_NET_WORTH, ACTION_GET_CASH_FLOW, ACTION_GET_RECENT_TRANSACTIONS, ACTION_GET_UPCOMING_SCHEDULES,
    )

    const val EXTRA_ID = "id"
    const val EXTRA_TO_ACCOUNT = "to_account"
    const val EXTRA_FROM_CATEGORY = "from_category"
    const val EXTRA_TO_CATEGORY = "to_category"
    const val EXTRA_MONTH = "month"
    const val EXTRA_DATE = "date"
    const val EXTRA_CLEARED = "cleared"
    const val EXTRA_CARRYOVER = "carryover"
    const val EXTRA_NAME = "name"
    const val EXTRA_OFF_BUDGET = "off_budget"
    const val EXTRA_OPENING_BALANCE = "opening_balance"
    const val EXTRA_OVERWRITE = "overwrite"
    const val EXTRA_LIMIT = "limit"
    const val EXTRA_CLEAR_CATEGORY = "clear_category"

    /** One `name=minor units` or `date|...` entry per row, for the list reads. */
    const val EXTRA_ITEMS = "items"

    const val UNKNOWN_ACTION = "That automation action is not supported."
    const val PAYEE_NOT_FOUND = "That payee could not be found in the selected budget."
    const val TRANSACTION_NOT_FOUND = "That transaction could not be found in the selected budget."
    const val SPLIT_LOCKED = "Split transactions can only be changed in Actualist. Automation will not change a split."
    const val TRANSFER_LOCKED = "That transfer cannot be changed that way. Open it in Actualist."
    const val RECONCILED = "That transaction is reconciled. Open it in Actualist to change it."
    const val TRANSFER_DESTINATION_MISSING = "Choose a destination account for the transfer."
    const val TRANSFER_SAME_ACCOUNT = "Choose a different destination account."
    const val NAME_MISSING = "Enter a name."
    const val MONTH_INVALID = "That month is not valid. Use YYYY-MM."
    const val DATE_INVALID = "That date is not valid. Use YYYY-MM-DD."
    const val FLAG_INVALID = "Enter true or false."
    const val NOTHING_TO_UPDATE = "Nothing to change."
    const val TRACKING_UNSUPPORTED = "Tracking budgets do not support Move Money or adding from To Budget. Use budget assignment."
    const val MOVE_ENDPOINTS_MISSING = "Choose a category to move from or to."
    const val MOVE_SAME_CATEGORY = "Choose two different categories."
    const val NO_TEMPLATES = "This budget has no templates."
    const val TEMPLATE_UNSUPPORTED = "That budget template cannot be applied by automation. Open Actualist to apply it."
    const val ACCOUNT_EXISTS = "An account with that name already exists."
    const val NOT_CATEGORIZABLE = "That transaction cannot be categorized."

    private val refusal: (String) -> Automation.Refusal = { Automation.Refusal(it) }

    /** Runs [action] with the string extras [params]; never throws except for cancellation. */
    suspend fun perform(context: Context, container: AppContainer, action: String, params: Map<String, String>): AutomationResult = when (action) {
        ACTION_LOG_TRANSFER -> logTransfer(context, container, params)
        ACTION_UPDATE_TRANSACTION -> updateTransaction(context, container, params)
        ACTION_CATEGORIZE_TRANSACTION -> categorizeTransaction(context, container, params)
        ACTION_SET_CLEARED -> setCleared(context, container, params)
        ACTION_DELETE_TRANSACTION -> deleteTransaction(context, container, params)
        ACTION_ASSIGN_BUDGET -> assignBudget(context, container, params)
        ACTION_ADD_TO_BUDGET -> addToBudget(context, container, params)
        ACTION_MOVE_MONEY -> moveMoney(context, container, params)
        ACTION_APPLY_TEMPLATES -> applyTemplates(context, container, params)
        ACTION_SET_CARRYOVER -> setCarryover(context, container, params)
        ACTION_CREATE_PAYEE -> createPayee(context, container, params)
        ACTION_CREATE_ACCOUNT -> createAccount(context, container, params)
        ACTION_GET_BUDGET_SUMMARY -> budgetSummary(context, container, params)
        ACTION_GET_CATEGORY_BALANCE -> categoryBalance(context, container, params)
        ACTION_GET_OVERSPENT -> overspent(context, container, params)
        ACTION_GET_UNCATEGORIZED_COUNT -> uncategorizedCount(context, container)
        ACTION_GET_NET_WORTH -> netWorth(context, container)
        ACTION_GET_CASH_FLOW -> cashFlow(context, container, params)
        ACTION_GET_RECENT_TRANSACTIONS -> recentTransactions(context, container, params)
        ACTION_GET_UPCOMING_SCHEDULES -> upcomingSchedules(context, container, params)
        else -> AutomationResult(false, UNKNOWN_ACTION)
    }

    // region Parsing

    private fun Map<String, String>.text(key: String): String? = this[key]?.trim()?.takeIf { it.isNotEmpty() }

    private fun monthOf(params: Map<String, String>): String {
        val raw = params.text(EXTRA_MONTH) ?: return Months.current()
        return Months.canonical(raw) ?: throw refusal(MONTH_INVALID)
    }

    private fun dateOf(params: Map<String, String>): LocalDate? {
        val raw = params.text(EXTRA_DATE) ?: return null
        return Months.parseDate(raw) ?: throw refusal(DATE_INVALID)
    }

    /** A signed amount from [key]; missing and unparsable amounts are refused. */
    private fun amountOf(params: Map<String, String>, key: String = Automation.EXTRA_AMOUNT): Long {
        val text = params.text(key) ?: throw refusal(Automation.AMOUNT_MISSING)
        return Money.parse(text) ?: throw refusal(Automation.AMOUNT_INVALID)
    }

    private fun magnitudeOf(params: Map<String, String>): Long {
        val value = abs(amountOf(params))
        if (value <= 0L) throw refusal(Automation.AMOUNT_INVALID)
        return value
    }

    /** true/yes/on/1 or false/no/off/0 (any case); null when absent; refused when something else. */
    private fun flagOf(params: Map<String, String>, key: String): Boolean? {
        val raw = params.text(key) ?: return null
        return when (raw.lowercase()) {
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0" -> false
            else -> throw refusal(FLAG_INVALID)
        }
    }

    private fun limitOf(params: Map<String, String>): Int =
        (params.text(EXTRA_LIMIT)?.toIntOrNull() ?: 10).coerceIn(1, 100)

    private fun needed(params: Map<String, String>, key: String, missing: String): String =
        params.text(key) ?: throw refusal(missing)

    private fun BudgetSession.findCategory(query: String, expenseOnly: Boolean): CategoryOption {
        val options = ledger.categories(includeHidden = false).filter { !expenseOnly || !it.isIncome }
        return Automation.uniqueMatch(options, { it.id }, { it.name }, query, Automation.CATEGORY_NOT_FOUND)
    }

    private fun BudgetSession.requireEnvelope() {
        if (budget.budgetTable() == BudgetTable.TRACKING) throw refusal(TRACKING_UNSUPPORTED)
    }

    private fun categoryValues(category: BudgetMonthCategory?, name: String, month: String): Map<String, String> = mapOf(
        "category" to name,
        "month" to month,
        "budgeted" to (category?.budgeted ?: 0L).toString(),
    )

    // endregion

    // region Transaction writes

    private suspend fun logTransfer(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = true) { session ->
            val magnitude = magnitudeOf(p)
            val date = dateOf(p) ?: LocalDate.now()
            val defaultAccountId = container.settings.settings.value.defaultAccountId
            val plan = session.read {
                val accounts = ledger.accounts(includeClosed = true)
                val open = accounts.filter { !it.closed }
                val source = if (p.text(Automation.EXTRA_ACCOUNT) == null) {
                    val wanted = defaultAccountId ?: throw refusal(Automation.DEFAULT_ACCOUNT_MISSING)
                    accounts.firstOrNull { it.id == wanted } ?: throw refusal(Automation.ACCOUNT_NOT_FOUND)
                } else {
                    Automation.uniqueMatch(open, { it.id }, { it.name }, p.getValue(Automation.EXTRA_ACCOUNT), Automation.ACCOUNT_NOT_FOUND)
                }
                if (source.closed) throw refusal(Automation.ACCOUNT_CLOSED)
                val destinationQuery = needed(p, EXTRA_TO_ACCOUNT, TRANSFER_DESTINATION_MISSING)
                val destination = Automation.uniqueMatch(open, { it.id }, { it.name }, destinationQuery, Automation.ACCOUNT_NOT_FOUND)
                if (destination.id == source.id) throw refusal(TRANSFER_SAME_ACCOUNT)
                val transferPayee = ledger.payees().firstOrNull { it.transferAccountId == destination.id } ?: throw refusal(Automation.WRITE_FAILED)
                Triple(
                    TransactionDraft(
                        accountId = source.id,
                        date = date,
                        amount = -magnitude,
                        payeeId = transferPayee.id,
                        notes = p.text(Automation.EXTRA_NOTES),
                        isTransfer = true,
                    ),
                    source.name,
                    destination.name,
                )
            }
            session.write { transactionWrites.create(plan.first) }
            AutomationResult(true, "Transferred ${Money.format(magnitude)} from ${plan.second} to ${plan.third}.")
        }

    private suspend fun updateTransaction(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = true) { session ->
            val id = needed(p, EXTRA_ID, TRANSACTION_NOT_FOUND)
            val direction = p.text(Automation.EXTRA_DIRECTION)?.let { DeepLinks.directionOf(it) ?: throw refusal(Automation.DIRECTION_INVALID) }
            val cleared = flagOf(p, EXTRA_CLEARED)
            val clearCategory = flagOf(p, EXTRA_CLEAR_CATEGORY) == true
            val date = dateOf(p)
            val edit = session.read {
                val row = ledger.transaction(id) ?: throw refusal(TRANSACTION_NOT_FOUND)
                if (row.isParent || row.isChild) throw refusal(SPLIT_LOCKED)
                if (row.reconciled) throw refusal(RECONCILED)
                val payee = p.text(Automation.EXTRA_PAYEE)
                val categoryQuery = p.text(Automation.EXTRA_CATEGORY)
                if (row.isTransfer && (payee != null || categoryQuery != null || clearCategory)) throw refusal(TRANSFER_LOCKED)
                val amount = if (p.text(Automation.EXTRA_AMOUNT) == null) {
                    null
                } else {
                    val size = magnitudeOf(p)
                    val negative = when (direction) {
                        TextImportDirection.SPEND -> true
                        TextImportDirection.INFLOW -> false
                        else -> row.amount < 0
                    }
                    if (negative) -size else size
                }
                val categoryId = categoryQuery?.let { findCategory(it, false).id }
                // A null note leaves the field alone; a present empty one clears it.
                val notes = if (p.containsKey(Automation.EXTRA_NOTES)) p.getValue(Automation.EXTRA_NOTES).trim() else null
                TransactionEdit(
                    date = date,
                    amount = amount,
                    payeeName = payee,
                    categoryId = categoryId,
                    clearCategory = clearCategory,
                    notes = notes,
                    cleared = cleared,
                )
            }
            val changes = listOf(edit.date, edit.amount, edit.payeeName, edit.categoryId, edit.notes, edit.cleared).count { it != null } +
                (if (edit.clearCategory) 1 else 0)
            if (changes == 0) throw refusal(NOTHING_TO_UPDATE)
            session.write { transactionWrites.update(id, edit) }
            AutomationResult(true, "Updated the transaction.", values = mapOf("id" to id))
        }

    private suspend fun categorizeTransaction(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = true) { session ->
            val id = needed(p, EXTRA_ID, TRANSACTION_NOT_FOUND)
            val query = needed(p, Automation.EXTRA_CATEGORY, Automation.CATEGORY_NOT_FOUND)
            val category = session.read {
                val row = ledger.transaction(id) ?: throw refusal(TRANSACTION_NOT_FOUND)
                if (row.isParent || row.isChild) throw refusal(SPLIT_LOCKED)
                if (row.isTransfer) throw refusal(TRANSFER_LOCKED)
                if (row.reconciled) throw refusal(RECONCILED)
                findCategory(query, false)
            }
            val outcome = session.write { TransactionBatch(database, clock).categorize(listOf(id), category.id) }
            if (outcome.changed == 0) throw refusal(NOT_CATEGORIZABLE)
            AutomationResult(true, "Categorized the transaction as ${category.name}.", values = mapOf("id" to id, "category" to category.name))
        }

    private suspend fun setCleared(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = true) { session ->
            val id = needed(p, EXTRA_ID, TRANSACTION_NOT_FOUND)
            val cleared = flagOf(p, EXTRA_CLEARED) ?: throw refusal(FLAG_INVALID)
            session.read {
                val row = ledger.transaction(id) ?: throw refusal(TRANSACTION_NOT_FOUND)
                if (row.isChild) throw refusal(SPLIT_LOCKED)
                if (row.reconciled) throw refusal(RECONCILED)
            }
            session.write { TransactionBatch(database, clock).setCleared(listOf(id), cleared) }
            AutomationResult(true, if (cleared) "Marked the transaction cleared." else "Marked the transaction uncleared.", values = mapOf("id" to id, "cleared" to cleared.toString()))
        }

    private suspend fun deleteTransaction(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = true) { session ->
            val id = needed(p, EXTRA_ID, TRANSACTION_NOT_FOUND)
            session.read {
                val row = ledger.transaction(id) ?: throw refusal(TRANSACTION_NOT_FOUND)
                if (row.isChild) throw refusal(SPLIT_LOCKED)
                if (row.reconciled) throw refusal(RECONCILED)
            }
            session.write { transactionWrites.delete(id) }
            AutomationResult(true, "Deleted the transaction.", values = mapOf("id" to id))
        }

    // endregion

    // region Budget writes

    private suspend fun assignBudget(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = true) { session ->
            val query = needed(p, Automation.EXTRA_CATEGORY, Automation.CATEGORY_NOT_FOUND)
            val month = monthOf(p)
            val amount = amountOf(p)
            val category = session.read { findCategory(query, true) }
            session.write { budgetWrites.assign(month, category.id, amount) }
            val after = session.read { budget.fetchBudgetMonth(month).category(category.id) }
            AutomationResult(true, "Assigned ${Money.format(amount)} to ${category.name}.", values = categoryValues(after, category.name, month))
        }

    private suspend fun addToBudget(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = true) { session ->
            val query = needed(p, Automation.EXTRA_CATEGORY, Automation.CATEGORY_NOT_FOUND)
            val month = monthOf(p)
            val amount = amountOf(p)
            if (amount == 0L) throw refusal(Automation.AMOUNT_INVALID)
            val category = session.read {
                requireEnvelope()
                findCategory(query, true)
            }
            val current = session.read { budget.fetchBudgetMonth(month).category(category.id)?.budgeted ?: 0L }
            session.write { budgetWrites.assign(month, category.id, current + amount) }
            val after = session.read { budget.fetchBudgetMonth(month).category(category.id) }
            AutomationResult(true, "${category.name} is now budgeted ${Money.format(after?.budgeted ?: (current + amount))}.", values = categoryValues(after, category.name, month))
        }

    private suspend fun moveMoney(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = true) { session ->
            val fromQuery = p.text(EXTRA_FROM_CATEGORY)
            val toQuery = p.text(EXTRA_TO_CATEGORY)
            if (fromQuery == null && toQuery == null) throw refusal(MOVE_ENDPOINTS_MISSING)
            val month = monthOf(p)
            val amount = magnitudeOf(p)
            val command = session.read {
                requireEnvelope()
                val from = fromQuery?.let { findCategory(it, true) }
                val to = toQuery?.let { findCategory(it, true) }
                if (from != null && to != null && from.id == to.id) throw refusal(MOVE_SAME_CATEGORY)
                MoveMoneyCommand(
                    from?.id,
                    to?.id,
                    amount,
                    if (from == null) MoveMoneyCommand.Kind.TRANSFER_AVAILABLE else MoveMoneyCommand.Kind.MOVE,
                )
            }
            session.write { budgetWrites.move(month, listOf(command)) }
            val toBudget = session.read { budget.fetchBudgetMonth(month).toBudget }
            AutomationResult(true, "Moved ${Money.format(amount)}.", values = mapOf("month" to month, "to_budget" to toBudget.toString()))
        }

    private suspend fun applyTemplates(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = true) { session ->
            val month = monthOf(p)
            val mode = if (flagOf(p, EXTRA_OVERWRITE) == true) TemplateApplyMode.OVERWRITE else TemplateApplyMode.FILL_EMPTY
            val has = session.read { BudgetTemplates(database, clock).hasTemplates() }
            if (!has) throw refusal(NO_TEMPLATES)
            try {
                session.write { BudgetTemplates(database, clock).apply(month, mode = mode) }
            } catch (error: UnsupportedTemplateException) {
                throw refusal(TEMPLATE_UNSUPPORTED)
            }
            val toBudget = session.read { budget.fetchBudgetMonth(month).toBudget }
            AutomationResult(true, "Applied budget templates. To Budget is ${Money.format(toBudget)}.", values = mapOf("month" to month, "to_budget" to toBudget.toString()))
        }

    private suspend fun setCarryover(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = true) { session ->
            val query = needed(p, Automation.EXTRA_CATEGORY, Automation.CATEGORY_NOT_FOUND)
            val month = monthOf(p)
            val carryover = flagOf(p, EXTRA_CARRYOVER) ?: throw refusal(FLAG_INVALID)
            val category = session.read { findCategory(query, true) }
            session.write { budgetWrites.setCarryover(category.id, carryover, month, month) }
            AutomationResult(
                true,
                if (carryover) "Rollover is on for ${category.name}." else "Rollover is off for ${category.name}.",
                values = mapOf("category" to category.name, "month" to month, "carryover" to carryover.toString()),
            )
        }

    // endregion

    // region Payee and account writes

    private suspend fun createPayee(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = true) { session ->
            val name = needed(p, EXTRA_NAME, NAME_MISSING)
            val existing = session.read { ledger.payees().firstOrNull { !it.isTransfer && it.name.equals(name, ignoreCase = true) } }
            if (existing != null) {
                return@guarded AutomationResult(true, "${existing.name} already exists.", values = mapOf("id" to existing.id, "name" to existing.name))
            }
            val created = session.write { PayeeWrites(database).create(name) }
            AutomationResult(true, "Created $name.", values = mapOf("id" to created.first, "name" to name))
        }

    private suspend fun createAccount(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = true) { session ->
            val name = needed(p, EXTRA_NAME, NAME_MISSING)
            val offBudget = flagOf(p, EXTRA_OFF_BUDGET) ?: false
            val opening = p.text(EXTRA_OPENING_BALANCE)?.let { Money.parse(it) ?: throw refusal(Automation.AMOUNT_INVALID) } ?: 0L
            val taken = session.read { ledger.accounts(includeClosed = true).any { it.name.equals(name, ignoreCase = true) } }
            if (taken) throw refusal(ACCOUNT_EXISTS)
            val created = session.write { AccountWrites(database, clock).create(name, offBudget, opening, LocalDate.now()) }
            AutomationResult(true, "Created $name.", values = mapOf("id" to created.accountId, "name" to name))
        }

    // endregion

    // region Reads

    private suspend fun budgetSummary(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = false) { session ->
            val month = monthOf(p)
            val m = session.read { budget.fetchBudgetMonth(month) }
            AutomationResult(
                true,
                "$month: To Budget ${Money.format(m.toBudget)}, budgeted ${Money.format(m.totalBudgeted)}, spent ${Money.format(abs(m.totalSpent))}.",
                values = mapOf(
                    "month" to month,
                    "to_budget" to m.toBudget.toString(),
                    "budgeted" to m.totalBudgeted.toString(),
                    "spent" to m.totalSpent.toString(),
                    "income" to m.totalIncome.toString(),
                    "balance" to m.totalBalance.toString(),
                ),
            )
        }

    private suspend fun categoryBalance(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = false) { session ->
            val query = needed(p, Automation.EXTRA_CATEGORY, Automation.CATEGORY_NOT_FOUND)
            val month = monthOf(p)
            val found = session.read {
                val option = findCategory(query, false)
                option to (budget.fetchBudgetMonth(month).category(option.id) ?: throw refusal(Automation.CATEGORY_NOT_FOUND))
            }
            val c = found.second
            AutomationResult(
                true,
                "${found.first.name}: ${Money.format(c.balance)} available.",
                values = mapOf(
                    "category" to found.first.name,
                    "month" to month,
                    "budgeted" to c.budgeted.toString(),
                    "spent" to c.spent.toString(),
                    "balance" to c.balance.toString(),
                    "carryover" to c.carryover.toString(),
                ),
            )
        }

    private suspend fun overspent(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = false) { session ->
            val month = monthOf(p)
            val rows = session.read {
                budget.fetchBudgetMonth(month).categories.filter { !it.isIncome && it.balance < 0L }.map { it.name to it.balance }
            }
            val message = if (rows.isEmpty()) "Nothing is overspent." else rows.joinToString("\n") { (name, balance) -> "$name: ${Money.format(balance)}" }
            AutomationResult(true, message, values = mapOf("month" to month, "count" to rows.size.toString()), items = rows.map { (name, balance) -> "$name=$balance" })
        }

    private suspend fun uncategorizedCount(context: Context, container: AppContainer): AutomationResult =
        Automation.guarded(context, container, write = false) { session ->
            val count = session.read { ledger.uncategorizedCount() }
            AutomationResult(true, "$count uncategorized transactions.", values = mapOf("count" to count.toString()))
        }

    private suspend fun netWorth(context: Context, container: AppContainer): AutomationResult =
        Automation.guarded(context, container, write = false) { session ->
            val month = Months.current()
            val balance = session.read { reports.netWorth(month, month).balance }
            AutomationResult(true, "Net worth is ${Money.format(balance)}.", values = mapOf("net_worth" to balance.toString()))
        }

    private suspend fun cashFlow(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = false) { session ->
            val month = monthOf(p)
            val flow = session.read { reports.cashFlow(month) }
            AutomationResult(
                true,
                "$month: income ${Money.format(flow.income)}, expenses ${Money.format(abs(flow.expenses))}, net ${Money.format(flow.net)}.",
                values = mapOf(
                    "month" to month,
                    "income" to flow.income.toString(),
                    "expenses" to flow.expenses.toString(),
                    "net" to flow.net.toString(),
                    "uncategorized" to flow.uncategorized.toString(),
                ),
            )
        }

    private suspend fun recentTransactions(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = false) { session ->
            val limit = limitOf(p)
            val rows = session.read {
                val accountId = p.text(Automation.EXTRA_ACCOUNT)?.let { query ->
                    Automation.uniqueMatch(ledger.accounts(includeClosed = true), { it.id }, { it.name }, query, Automation.ACCOUNT_NOT_FOUND).id
                }
                ledger.transactions(TransactionQuery(accountId = accountId, limit = limit))
            }
            val items = rows.map { "${it.date ?: ""}|${it.displayPayee}|${it.amount}|${it.categoryName ?: ""}|${it.accountName}|${it.id}" }
            val message = if (rows.isEmpty()) "No transactions." else rows.joinToString("\n") { "${it.date ?: ""} ${it.displayPayee}: ${Money.format(it.amount)}" }
            AutomationResult(true, message, values = mapOf("count" to rows.size.toString()), items = items)
        }

    private suspend fun upcomingSchedules(context: Context, container: AppContainer, p: Map<String, String>): AutomationResult =
        Automation.guarded(context, container, write = false) { session ->
            val limit = limitOf(p)
            val today = LocalDate.now().toString()
            val upcoming = session.read {
                Schedules(database, clock).loaded(today).schedules
                    .filter { it.effectiveNextDate != null && it.status != ScheduleStatus.COMPLETED && it.status != ScheduleStatus.PAID }
                    .sortedBy { it.effectiveNextDate }
                    .take(limit)
            }
            val items = upcoming.map {
                "${it.effectiveNextDate ?: ""}|${it.displayName}|${it.amount.postingAmount ?: ""}|${it.account.name ?: ""}|${it.status.raw}|${it.id}"
            }
            val message = if (upcoming.isEmpty()) "No upcoming schedules." else upcoming.joinToString("\n") { schedule ->
                val amount = schedule.amount.postingAmount?.let { Money.format(it) } ?: ""
                "${schedule.effectiveNextDate} ${schedule.displayName} $amount".trim()
            }
            AutomationResult(true, message, values = mapOf("count" to upcoming.size.toString()), items = items)
        }

    // endregion
}
