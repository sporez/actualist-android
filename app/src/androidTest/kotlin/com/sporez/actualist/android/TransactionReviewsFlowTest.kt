package com.sporez.actualist.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sporez.actualist.android.ui.transactions.FeedPagingConfig
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The transaction screens' review steps on the demo budget: the payee picker (recent payees, "No payee",
 * create), the make-a-schedule review, and "Load older transactions". The CSV import review needs a
 * file chooser, which the tests cannot drive; its logic is covered by the core JVM tests
 * (CsvImportReviewTest).
 */
@RunWith(AndroidJUnit4::class)
class TransactionReviewsFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.onNodeWithTag("open-demo").performClick()
        compose.waitForTag("to-budget-banner")
    }

    private fun hasNode(matcher: SemanticsMatcher): Boolean = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private val rowMatcher = SemanticsMatcher("a transaction row") { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        tag != null && tag.startsWith("transaction-") && tag !in setOf("transaction-list", "transaction-search", "transaction-editor")
    }

    @Test
    fun payeePickerListsRecentsNoPayeeAndCreates() {
        openDemo()
        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transaction-list")
        compose.onNodeWithTag("add-transaction").performClick()
        compose.waitForTag("transaction-editor")
        compose.onNodeWithTag("editor-payee").performClick()
        compose.waitForTag("picker-search")
        compose.waitOrCapture("recent-header") { hasNode(hasTestTag("header-recent")) }
        compose.onNodeWithTag("pick-none").assertExists()
        compose.onNodeWithTag("picker-search").performTextInput("Review Test Payee")
        compose.onNodeWithTag("picker-create").performClick()
        compose.waitOrCapture("created-payee") { hasNode(hasText("Review Test Payee")) }

        compose.onNodeWithTag("editor-payee").performClick()
        compose.waitForTag("pick-none")
        compose.onNodeWithTag("pick-none").performClick()
        compose.waitOrCapture("no-payee") { hasNode(hasText("None")) && !hasNode(hasText("Review Test Payee")) }
    }

    @Test
    fun makeScheduleShowsAReviewBeforeCreating() {
        openDemo()
        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitForTag("budget-accounts")
        compose.onNodeWithTag("account-checking").performClick()
        compose.waitForTag("transaction-list")
        compose.onAllNodes(hasText("Payroll Inc", substring = true))[0].performClick()
        compose.waitForTag("editor-make-schedule")
        compose.onNodeWithTag("editor-make-schedule").performClick()
        compose.waitForTag("schedule-review")
        compose.onNodeWithTag("schedule-review-name").assertExists()
        compose.onNodeWithTag("schedule-review-amount").assertExists()
        assertTrue(hasNode(hasText("(approximate)", substring = true)))
        compose.onNodeWithTag("schedule-review-account").assertExists()
        compose.onNodeWithTag("schedule-review-start").assertExists()

        // Cancelling creates nothing and leaves the editor open.
        compose.onNodeWithTag("schedule-review-cancel").performClick()
        compose.waitForGone("schedule-review")
        compose.onNodeWithTag("editor-make-schedule").assertExists()

        compose.onNodeWithTag("editor-make-schedule").performClick()
        compose.waitForTag("schedule-review")
        compose.onNodeWithTag("schedule-review-freq-WEEKLY").performScrollTo()
        compose.onNodeWithTag("schedule-review-freq-WEEKLY").performClick()
        compose.onNodeWithTag("schedule-review-create").performClick()
        // The editor closes once the schedule is written.
        compose.waitForTag("transaction-list")
        compose.waitForGone("transaction-editor")
    }

    @Test
    fun loadOlderTransactionsAddsAPage() {
        val original = FeedPagingConfig.pageSize
        FeedPagingConfig.pageSize = 5
        try {
            openDemo()
            compose.onNodeWithTag("tab-spending").performClick()
            compose.waitForTag("transaction-list")
            compose.waitOrCapture("load-older") { hasNode(hasTestTag("load-older")) }
            val before = compose.onAllNodes(rowMatcher).fetchSemanticsNodes().size
            assertTrue("a page holds at most 5 rows, saw $before", before in 1..5)
            compose.onNodeWithTag("transaction-list").performScrollToNode(hasTestTag("load-older"))
            compose.onNodeWithTag("load-older").performClick()
            compose.waitOrCapture("more-rows") {
                compose.onAllNodes(rowMatcher).fetchSemanticsNodes().size > before || !hasNode(hasTestTag("load-older"))
            }
            // Searching starts again from the first page and still works.
            compose.onNodeWithTag("transaction-search").performTextInput("Fresh Market")
            compose.waitOrCapture("search-results") { hasNode(hasText("Fresh Market", substring = true)) }
        } finally {
            FeedPagingConfig.pageSize = original
        }
    }
}
