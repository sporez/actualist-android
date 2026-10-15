package com.sporez.actualist.android

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sporez.actualist.core.transactions.ReconciledMutations
import com.sporez.actualist.core.transactions.TransactionDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Wording that follows Actualist: the reconciled-row categorize confirmation, the status filter's
 * empty message and the envelope Income group starting collapsed, on the synthetic demo budget.
 */
@RunWith(AndroidJUnit4::class)
class LocaleAndWordingFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
    }

    @Test
    fun confirmsCategorizingAReconciledRow() {
        openDemo()
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ActualistApplication).container
        val budgetId = container.library.list().first().id
        val session = container.sessions.acquire(budgetId) { container.library.open(budgetId) ?: error("The demo budget is missing.") }
        val finished = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        try {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    session.write {
                        val created = transactionWrites.create(TransactionDraft(accountId = "checking", date = LocalDate.now(), amount = -2_468, payeeName = "Locked Cafe"))
                        ReconciledMutations(database).relock(listOf(created.transactionId))
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    finished.set(true)
                }
            }
            compose.waitUntil(30_000) { finished.get() }
        } finally {
            container.sessions.release(session)
        }
        assertNull(failure.get())

        compose.waitOrCapture("uncategorized-row") {
            runCatching { compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("uncategorized-row")) }.isSuccess
        }
        compose.waitForTag("uncategorized-row")
        compose.onNodeWithTag("uncategorized-row").performClick()
        compose.waitForTag("uncategorized-review")
        compose.waitOrCapture("locked-row") { compose.onAllNodesWithText("Locked Cafe").fetchSemanticsNodes().isNotEmpty() }

        // Every row offers Categorize; the reconciled one asks first after a category is chosen.
        compose.onAllNodesWithTag("uncategorized-categorize")[0].performClick()
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("Dining")
        compose.waitForTag("pick-dining")
        compose.onNodeWithTag("pick-dining").performClick()
        compose.waitForTag("uncategorized-reconciled-warning")
        compose.waitOrCapture("confirm-title") { compose.onAllNodesWithText("Categorize Reconciled Transaction?").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("uncategorized-reconciled-confirm").performClick()
        compose.waitOrCapture("locked-row-categorized") { compose.onAllNodesWithText("Locked Cafe").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun emptyMessageFollowsTheStatusFilter() {
        openDemo()
        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transaction-list")
        compose.waitForTag("status-reconciled")
        compose.onNodeWithTag("status-reconciled").performClick()
        compose.waitOrCapture("reconciled-empty") { compose.onAllNodesWithText("No reconciled transactions").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun incomeGroupStartsCollapsed() {
        openDemo()
        compose.waitOrCapture("income-header") {
            runCatching { compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("group-income")) }.isSuccess
        }
        compose.waitForTag("group-income")
        check(compose.onAllNodesWithText("Paycheck").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("group-income").performClick()
        compose.waitOrCapture("income-expanded") { compose.onAllNodesWithText("Paycheck").fetchSemanticsNodes().isNotEmpty() }
    }
}
