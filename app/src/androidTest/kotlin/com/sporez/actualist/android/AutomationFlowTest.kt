package com.sporez.actualist.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sporez.actualist.android.automation.Automation
import com.sporez.actualist.android.automation.AutomationActions
import com.sporez.actualist.android.automation.AutomationPrefs
import com.sporez.actualist.android.automation.LogRequest
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.privacy.SampleValues
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.transactions.TransactionQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Deep links with a prefill, and the automation entry points (called directly, as the receiver does). */
@RunWith(AndroidJUnit4::class)
class AutomationFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(FreshAppState()).around(compose).around(FailureScreenshot())

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container: AppContainer get() = (context.applicationContext as ActualistApplication).container

    @After
    fun resetAutomation() {
        AutomationPrefs.setAllowed(context, false)
        SampleValues.active = false
    }

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
    }

    private fun payeesInBudget(): List<String> {
        val id = container.settings.settings.value.selectedBudgetId ?: return emptyList()
        val session = container.sessions.acquire(id) { container.library.open(id) ?: error("The budget is not on this device.") }
        try {
            return runBlocking { session.read { ledger.transactions(TransactionQuery(limit = 500)).map { it.displayPayee } } }
        } finally {
            container.sessions.release(session)
        }
    }

    private fun <T> readBudget(block: BudgetSession.() -> T): T {
        val id = container.settings.settings.value.selectedBudgetId ?: error("No budget is selected.")
        val session = container.sessions.acquire(id) { container.library.open(id) ?: error("The budget is not on this device.") }
        try {
            return runBlocking(Dispatchers.IO) { session.read(block) }
        } finally {
            container.sessions.release(session)
        }
    }

    private fun act(action: String, vararg extras: Pair<String, String>) =
        runBlocking(Dispatchers.IO) { AutomationActions.perform(context, container, action, extras.toMap()) }

    private fun budgeted(categoryId: String, month: String): Long =
        readBudget { budget.fetchBudgetMonth(month).category(categoryId)?.budgeted ?: 0L }

    @Test
    fun assignAndMoveMoneyChangeTheBudgetedAmounts() {
        openDemo()
        AutomationPrefs.setAllowed(context, true)
        val month = Months.current()
        val categories = readBudget { ledger.categories(includeHidden = false).filter { !it.isIncome }.take(2) }
        val from = categories[0]
        val to = categories[1]

        val assigned = act(AutomationActions.ACTION_ASSIGN_BUDGET, "category" to from.id, "amount" to "50.00", "month" to month)
        assertTrue(assigned.message, assigned.ok)
        assertEquals(Money.parse("50.00")!!, budgeted(from.id, month))
        assertEquals(Money.parse("50.00").toString(), assigned.values["budgeted"])

        val toBefore = budgeted(to.id, month)
        val moved = act(
            AutomationActions.ACTION_MOVE_MONEY,
            "from_category" to from.id,
            "to_category" to to.id,
            "amount" to "10.00",
            "month" to month,
        )
        assertTrue(moved.message, moved.ok)
        assertEquals(Money.parse("40.00")!!, budgeted(from.id, month))
        assertEquals(toBefore + Money.parse("10.00")!!, budgeted(to.id, month))

        val same = act(AutomationActions.ACTION_MOVE_MONEY, "from_category" to from.id, "to_category" to from.id, "amount" to "1.00")
        assertFalse(same.ok)
        assertEquals(AutomationActions.MOVE_SAME_CATEGORY, same.message)

        AutomationPrefs.setAllowed(context, false)
        val refused = act(AutomationActions.ACTION_ASSIGN_BUDGET, "category" to from.id, "amount" to "1.00", "month" to month)
        assertEquals(Automation.DISABLED, refused.message)
        assertEquals(Money.parse("40.00")!!, budgeted(from.id, month))
    }

    @Test
    fun categorizeAndSetClearedChangeATransaction() {
        openDemo()
        AutomationPrefs.setAllowed(context, true)
        val account = readBudget { ledger.accounts(includeClosed = false).first { !it.offBudget }.name }
        val category = readBudget { ledger.categories(includeHidden = false).first { !it.isIncome } }
        val logged = runBlocking(Dispatchers.IO) {
            Automation.logTransaction(context, container, LogRequest(amount = "3.21", payee = "Categorize Cafe", account = account))
        }
        assertTrue(logged.message, logged.ok)
        val id = readBudget { ledger.transactions(TransactionQuery(limit = 500)).first { it.displayPayee == "Categorize Cafe" }.id }

        val categorized = act(AutomationActions.ACTION_CATEGORIZE_TRANSACTION, "id" to id, "category" to category.id)
        assertTrue(categorized.message, categorized.ok)
        assertEquals(category.id, readBudget { ledger.transaction(id)?.categoryId })

        val cleared = act(AutomationActions.ACTION_SET_CLEARED, "id" to id, "cleared" to "true")
        assertTrue(cleared.message, cleared.ok)
        assertEquals(true, readBudget { ledger.transaction(id)?.cleared })

        val bad = act(AutomationActions.ACTION_SET_CLEARED, "id" to id, "cleared" to "maybe")
        assertEquals(AutomationActions.FLAG_INVALID, bad.message)

        val missing = act(AutomationActions.ACTION_CATEGORIZE_TRANSACTION, "id" to "no-such-id", "category" to category.id)
        assertEquals(AutomationActions.TRANSACTION_NOT_FOUND, missing.message)
    }

    @Test
    fun budgetSummaryReadReturnsToBudgetAndIsGated() {
        openDemo()
        AutomationPrefs.setAllowed(context, true)
        val month = Months.current()
        val summary = act(AutomationActions.ACTION_GET_BUDGET_SUMMARY, "month" to month)
        assertTrue(summary.message, summary.ok)
        val expected = readBudget { budget.fetchBudgetMonth(month).toBudget }
        assertEquals(expected.toString(), summary.values["to_budget"])
        assertEquals(month, summary.values["month"])

        val invalid = act(AutomationActions.ACTION_GET_BUDGET_SUMMARY, "month" to "March")
        assertEquals(AutomationActions.MONTH_INVALID, invalid.message)

        SampleValues.active = true
        assertFalse(act(AutomationActions.ACTION_GET_BUDGET_SUMMARY).ok)
        SampleValues.active = false
        AutomationPrefs.setAllowed(context, false)
        assertEquals(Automation.DISABLED, act(AutomationActions.ACTION_GET_BUDGET_SUMMARY).message)
    }

    @Test
    fun aLinkWithPrefillOpensTheEditorWithTheAmount() {
        openDemo()
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("actualist://open/new-transaction?amount=12.50&payee=Link%20Cafe&direction=inflow"))
        assertTrue(DeepLinks.handle(link))
        compose.waitForTag("editor-amount")
        compose.waitUntil(30_000) {
            runCatching { compose.onNodeWithTag("editor-amount").assertTextContains("12.50") }.isSuccess
        }
    }

    @Test
    fun routesCarryMonthsAndTheUncategorizedFeed() {
        assertEquals("spending-uncategorized", DeepLinks.routeForSegments(listOf("uncategorized")))
        val route = DeepLinks.routeForSegments(listOf("category", "abc")) { if (it == "month") "2026-03" else null }
        assertEquals("category-month/abc?name=Category&month=2026-03", route)
        assertTrue(DeepLinks.accepts(route!!))
        assertEquals("category/abc", DeepLinks.routeForSegments(listOf("category", "abc")) { null })
    }

    @Test
    fun logTransactionAddsARowWhenAllowedAndIsRefusedWhenNot() {
        openDemo()
        AutomationPrefs.setAllowed(context, true)
        val balances = runBlocking { Automation.getBalances(context, container) }
        assertTrue(balances.message, balances.ok)
        val account = balances.balances.first().first

        val logged = runBlocking {
            Automation.logTransaction(context, container, LogRequest(amount = "7.77", payee = "Automation Cafe", account = account, direction = "spend"))
        }
        assertTrue(logged.message, logged.ok)
        assertTrue(payeesInBudget().contains("Automation Cafe"))

        AutomationPrefs.setAllowed(context, false)
        val refused = runBlocking {
            Automation.logTransaction(context, container, LogRequest(amount = "1.00", payee = "Refused Cafe", account = account))
        }
        assertFalse(refused.ok)
        assertEquals(Automation.DISABLED, refused.message)
        assertFalse(payeesInBudget().contains("Refused Cafe"))
        assertFalse(runBlocking { Automation.getBalances(context, container) }.ok)
    }

    @Test
    fun logTransactionValidatesAndRefusesUnderSampleValues() {
        openDemo()
        AutomationPrefs.setAllowed(context, true)
        val missing = runBlocking { Automation.logTransaction(context, container, LogRequest(amount = null)) }
        assertEquals(Automation.AMOUNT_MISSING, missing.message)
        val invalid = runBlocking { Automation.logTransaction(context, container, LogRequest(amount = "twelve")) }
        assertEquals(Automation.AMOUNT_INVALID, invalid.message)
        val unknownAccount = runBlocking { Automation.logTransaction(context, container, LogRequest(amount = "1", account = "No Such Account")) }
        assertEquals(Automation.ACCOUNT_NOT_FOUND, unknownAccount.message)

        SampleValues.active = true
        val blocked = runBlocking { Automation.logTransaction(context, container, LogRequest(amount = "1", payee = "Sample Cafe")) }
        assertFalse(blocked.ok)
        assertEquals(SampleValues.PAYEE_WRITE_BLOCKED, blocked.message)
    }
}
