package com.sporez.actualist.android

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sporez.actualist.android.ui.budget.monthTitle
import com.sporez.actualist.core.budget.Months
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
 * Budget month navigation and attention flows on the demo budget: the month chooser and Today,
 * the guided cover review for an overspent category, and categorizing an uncategorized
 * transaction from the review list.
 */
@RunWith(AndroidJUnit4::class)
class BudgetNavigationFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose)

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.onNodeWithTag("open-demo").performClick()
        compose.waitForTag("to-budget-banner")
    }

    @Test
    fun monthChooserJumpsToAMonthAndTodayComesBack() {
        openDemo()
        val current = Months.current()
        compose.onNodeWithTag("month-title").assertTextEquals(monthTitle(current))

        compose.onNodeWithTag("month-title").performClick()
        compose.waitForTag("month-chooser")
        compose.onNodeWithTag("month-chooser-previous-year").performClick()
        val target = Months.id(current.take(4).toInt() - 1, current.takeLast(2).toInt())
        compose.onNodeWithTag("month-pick-$target").performClick()
        compose.waitForGone("month-chooser")
        compose.waitOrCapture("chooser-month") { compose.onAllNodesWithTag("month-today").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("month-title").assertTextEquals(monthTitle(target))

        compose.onNodeWithTag("month-today").performClick()
        compose.waitOrCapture("today-month") { compose.onAllNodesWithTag("month-today").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("month-title").assertTextEquals(monthTitle(current))

        // The chooser's own Today action returns from another month too.
        compose.onNodeWithTag("month-previous").performClick()
        compose.waitForTag("month-today")
        compose.onNodeWithTag("month-title").performClick()
        compose.waitForTag("month-chooser")
        compose.onNodeWithTag("month-chooser-today").performClick()
        compose.waitForGone("month-chooser")
        compose.waitOrCapture("chooser-today") { compose.onAllNodesWithTag("month-today").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("month-title").assertTextEquals(monthTitle(current))
    }

    @Test
    fun coverReviewCoversAnOverspentCategoryFromToBudget() {
        openDemo()
        // Assigning nothing to Rent leaves it overspent by its rent payment.
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("category-rent"))
        compose.onNodeWithTag("category-rent").performClick()
        compose.waitForTag("category-sheet")
        compose.onNodeWithTag("assign-field").performTextClearance()
        compose.onNodeWithTag("assign-field").performTextInput("0")
        compose.onNodeWithTag("assign-save").performClick()
        compose.waitForGone("category-sheet")

        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("overspent-row"))
        compose.waitForTag("overspent-row")
        compose.onNodeWithTag("overspent-row").performClick()
        compose.waitForTag("cover-review")
        compose.waitForTag("cover-item-rent")
        compose.onNodeWithTag("cover-apply-rent").performClick()
        compose.waitOrCapture("rent-covered") { compose.onAllNodesWithTag("cover-item-rent").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun categorizesAnUncategorizedTransactionFromTheReview() {
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
                        transactionWrites.create(TransactionDraft(accountId = "checking", date = LocalDate.now(), amount = -4_321, payeeName = "Review Cafe"))
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

        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("uncategorized-row"))
        compose.waitForTag("uncategorized-row")
        compose.onNodeWithTag("uncategorized-row").performClick()
        compose.waitForTag("uncategorized-review")
        compose.waitOrCapture("review-row") { compose.onAllNodesWithText("Review Cafe").fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodesWithTag("uncategorized-categorize")[0].performClick()
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("Dining")
        compose.waitForTag("pick-dining")
        compose.onNodeWithTag("pick-dining").performClick()
        compose.waitOrCapture("review-categorized") { compose.onAllNodesWithText("Review Cafe").fetchSemanticsNodes().isEmpty() }
    }
}
