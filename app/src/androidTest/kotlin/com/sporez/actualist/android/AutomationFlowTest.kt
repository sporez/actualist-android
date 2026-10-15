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
import com.sporez.actualist.android.automation.AutomationPrefs
import com.sporez.actualist.android.automation.LogRequest
import com.sporez.actualist.core.privacy.SampleValues
import com.sporez.actualist.core.transactions.TransactionQuery
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
        assertEquals("spending", DeepLinks.routeForSegments(listOf("uncategorized")))
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
