package com.sporez.actualist.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sporez.actualist.android.ui.budget.monthTitle
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * End-to-end UI flows on the demo budget: launch without credentials, open the demo, visit
 * every main screen (with screenshots), assign money, add a transaction through the editor,
 * and survive activity recreation.
 */
@RunWith(AndroidJUnit4::class)
class DemoBudgetFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.screenshot("00-onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
    }

    private fun toBudgetText(): String =
        compose.onNodeWithTag("to-budget-amount", useUnmergedTree = true).fetchSemanticsNode().config
            .let { config -> config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text } }

    @Test
    fun launchesWithoutCredentialsAndShowsEveryMainScreen() {
        openDemo()
        compose.onNodeWithTag("budget-screen").assertIsDisplayed()
        compose.onNodeWithText("Essentials").assertExists()
        compose.screenshot("01-budget")

        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transaction-list")
        compose.onAllNodesWithText("Fresh Market", substring = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.screenshot("02-spending")

        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitForTag("budget-accounts")
        compose.onNodeWithText("Everyday Checking").assertExists()
        compose.onNodeWithText("Car Loan").assertExists()
        compose.screenshot("03-accounts")

        compose.onNodeWithTag("account-checking").performClick()
        compose.waitForTag("account-balance")
        compose.screenshot("04-account-checking")
        compose.onNodeWithTag("nav-back").performClick()

        compose.onNodeWithTag("tab-reports").performClick()
        compose.waitForTag("report-net-worth")
        compose.onNodeWithTag("report-cash-flow").assertExists()
        compose.screenshot("05-reports")

        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.onNodeWithText("Local demo · changes stay on this device").assertExists()
        compose.screenshot("06-settings")
    }

    @Test
    fun assigningMoneyChangesToBudget() {
        openDemo()
        val before = toBudgetText()
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("category-vacation"))
        compose.onNodeWithTag("category-vacation").performClick()
        compose.waitForTag("category-sheet")
        compose.screenshot("07-category-sheet")
        compose.onNodeWithTag("assign-field").performTextClearance()
        compose.onNodeWithTag("assign-field").performTextInput("350.00")
        compose.onNodeWithTag("assign-save").performClick()
        compose.waitForGone("category-sheet")
        // The banner scrolled out of the lazy list while the sheet was open.
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("to-budget-banner"))
        compose.waitUntil(30_000) { toBudgetText() != before }
        assertNotEquals(before, toBudgetText())
        compose.screenshot("08-after-assign")
        // Undo restores To Budget; redo applies the assignment again.
        val assigned = toBudgetText()
        compose.onNodeWithTag("undo").performClick()
        compose.waitUntil(30_000) { toBudgetText() == before }
        compose.waitForTag("redo")
        compose.onNodeWithTag("redo").performClick()
        compose.waitUntil(30_000) { toBudgetText() == assigned }
        // History lists the gesture.
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("show-history")
        compose.onNodeWithTag("show-history").performClick()
        compose.waitOrCapture("history") { compose.onAllNodes(hasText("Changed 1 budget amount")).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("09-history")
    }

    @Test
    fun addsATransactionThroughTheEditor() {
        openDemo()
        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transaction-list")
        compose.onNodeWithTag("add-transaction").performClick()
        compose.waitForTag("transaction-editor")
        compose.onNodeWithTag("editor-amount").performTextInput("12.34")
        compose.onNodeWithTag("editor-payee").performClick()
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("Synthetic Corner Cafe")
        compose.onNodeWithTag("picker-create").performClick()
        compose.onNodeWithTag("editor-category").performClick()
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("Dining")
        compose.onNodeWithTag("pick-dining").performClick()
        compose.screenshot("09-editor")
        compose.onNodeWithTag("editor-save").performClick()
        compose.waitForTag("transaction-list")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Synthetic Corner Cafe").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasText("-$12.34")).fetchSemanticsNodes().isNotEmpty())
        compose.screenshot("10-after-add")
    }

    @Test
    fun rulePreviewCategorizesFromThePayee() {
        openDemo()
        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transaction-list")
        compose.onNodeWithTag("add-transaction").performClick()
        compose.waitForTag("transaction-editor")
        compose.onNodeWithTag("editor-amount").performTextInput("4.50")
        compose.onNodeWithTag("editor-payee").performClick()
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("Daily Grind")
        compose.onNodeWithTag("pick-cafe").performClick()
        // The demo rule "payee is Daily Grind -> Dining" fills the category before saving.
        compose.waitForTag("rules-applied")
        compose.onNodeWithTag("editor-category", useUnmergedTree = false).assertExists()
        assertTrue(compose.onAllNodes(hasText("Dining")).fetchSemanticsNodes().isNotEmpty())
        compose.screenshot("11-rule-preview")
        compose.onNodeWithTag("editor-save").performClick()
        compose.waitForTag("transaction-list")
    }

    @Test
    fun reconcilesAnAccount() {
        openDemo()
        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitForTag("budget-accounts")
        // Rows can sit below an empty group's card: bring the row into view first.
        compose.onNodeWithTag("accounts-list").performScrollToNode(hasTestTag("account-savings"))
        compose.onNodeWithTag("account-savings").performClick()
        compose.waitForTag("account-balance")
        compose.onNodeWithTag("reconcile").performClick()
        compose.waitForTag("reconcile-balance")
        compose.waitOrCapture("reconcile-cleared-loaded") { compose.onAllNodes(hasText("Cleared balance: …")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("reconcile-balance").performTextInput("20000.00")
        compose.waitForTag("reconcile-difference")
        compose.screenshot("12-reconcile")
        // Reconciling is a review in steps, as Actualist's: review, create the adjustment, then lock.
        compose.onNodeWithTag("reconcile-continue").performClick()
        compose.waitForTag("reconcile-adjust")
        compose.onNodeWithTag("reconcile-adjust").performClick()
        compose.waitForTag("reconcile-adjust-confirm")
        compose.onNodeWithTag("reconcile-adjust-confirm").performClick()
        compose.waitForTag("reconcile-finish")
        compose.onNodeWithTag("reconcile-finish").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Reconciled and locked")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitOrCapture("reconcile-adjustment-row") { compose.onAllNodes(hasText("Reconciliation balance adjustment", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        // The header reloads too: the cleared balance now equals the statement balance.
        compose.waitOrCapture("reconcile-cleared-header") { compose.onAllNodes(hasText("$20,000.00")).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("13-after-reconcile")
        compose.displayScreenshot("13b-after-reconcile-display")
    }

    @Test
    fun showsAndPostsSchedules() {
        openDemo()
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.onNodeWithTag("show-schedules").performClick()
        compose.waitForTag("schedules-screen")
        compose.onNodeWithText("Rent").assertExists()
        compose.screenshot("14-schedules")
        compose.onNodeWithTag("schedule-post-demo-schedule-streaming").performClick()
        // Posting now asks for the date first, as Actualist's posting review does.
        compose.waitForTag("schedule-post-confirm")
        compose.onNodeWithTag("schedule-post-confirm").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Posted StreamFlix")).fetchSemanticsNodes().isNotEmpty() }
        // Edit the schedule's name through the ported schedule writer.
        compose.onNodeWithTag("schedule-edit-demo-schedule-streaming").performClick()
        compose.waitForTag("schedule-edit-dialog")
        compose.onNodeWithTag("schedule-edit-name").performTextClearance()
        compose.onNodeWithTag("schedule-edit-name").performTextInput("Synthetic Streaming")
        // Several days and a weekday of the month in one rule.
        compose.onNodeWithTag("schedule-edit-pattern-days").performScrollTo().performClick()
        compose.onNodeWithTag("schedule-edit-days").performScrollTo().performTextInput("15, 3rd fri, 1")
        compose.onNodeWithTag("schedule-edit-save").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Synthetic Streaming")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitOrCapture("schedule-days-described") { compose.onAllNodes(hasText("on the 1st, the 15th, the 3rd Friday", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        // Reopened, the rule reads back into the same form.
        compose.onNodeWithTag("schedule-edit-demo-schedule-streaming").performClick()
        compose.waitForTag("schedule-edit-dialog")
        compose.waitOrCapture("schedule-days-read-back") { compose.onAllNodes(hasTestTag("schedule-edit-days") and hasText("1, 15, 3rd fri", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("14b-schedule-days")
    }

    @Test
    fun createsARuleInTheEditor() {
        openDemo()
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.onNodeWithTag("show-rules").performClick()
        compose.waitForTag("rule-add")
        compose.onNodeWithTag("rule-add").performClick()
        compose.waitForTag("rule-editor")
        compose.onNodeWithTag("rule-condition-0-value").performClick()
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("MarketPlace")
        compose.onNodeWithTag("pick-store").performClick()
        compose.onNodeWithTag("rule-action-0-value").performClick()
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("Shopping")
        compose.onNodeWithTag("pick-shopping").performClick()
        // Only amounts in a range.
        compose.onNodeWithTag("rule-add-condition").performScrollTo().performClick()
        compose.onNodeWithTag("rule-condition-1-field").performScrollTo().performClick()
        compose.onNodeWithTag("pick-amount").performClick()
        compose.onNodeWithTag("rule-condition-1-op").performScrollTo().performClick()
        compose.onNodeWithTag("pick-isbetween").performClick()
        compose.onNodeWithTag("rule-condition-1-text").performScrollTo().performTextInput("-500.00")
        compose.onNodeWithTag("rule-condition-1-text2").performScrollTo().performTextInput("-5.00")
        // A post-stage rule that also adds a tag after the existing notes.
        compose.onNodeWithTag("rule-stage-post").performScrollTo().performClick()
        compose.onNodeWithTag("rule-add-action").performScrollTo().performClick()
        compose.onNodeWithTag("rule-action-1-op").performScrollTo().performClick()
        compose.onNodeWithTag("pick-append-notes").performClick()
        compose.onNodeWithTag("rule-action-1-text").performScrollTo().performTextInput(" #shop")
        compose.screenshot("15-rule-editor")
        compose.onNodeWithTag("rule-save").performScrollTo().performClick()
        // Post-stage rules are listed last, below the fold of the lazy list.
        compose.waitOrCapture("rule-saved") {
            runCatching { compose.onNodeWithTag("rules-screen").performScrollToNode(hasText("payee is MarketPlace", substring = true)) }.isSuccess &&
                compose.onAllNodes(hasText("payee is MarketPlace", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitOrCapture("rule-notes-action") { compose.onAllNodes(hasText("append-notes \" #shop\"", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Stage: post").assertExists()
        compose.waitOrCapture("rule-range-condition") { compose.onAllNodes(hasText("amount isbetween", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("16-rules")
    }

    @Test
    fun duplicatesATransaction() {
        openDemo()
        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitForTag("budget-accounts")
        compose.onNodeWithTag("account-checking").performClick()
        compose.waitForTag("transaction-list")
        val before = compose.onAllNodes(hasText("Payroll Inc", substring = true)).fetchSemanticsNodes().size
        compose.onAllNodes(hasText("Payroll Inc", substring = true))[0].performClick()
        compose.waitForTag("editor-duplicate")
        compose.onNodeWithTag("editor-duplicate").performClick()
        compose.waitForTag("transaction-list")
        compose.waitOrCapture("duplicated") { compose.onAllNodes(hasText("Payroll Inc", substring = true)).fetchSemanticsNodes().size > before }
    }

    @Test
    fun appliesBudgetTemplatesToTheNextMonth() {
        openDemo()
        compose.onNodeWithTag("month-next").performClick()
        compose.waitForTag("to-budget-banner")
        compose.onNodeWithTag("to-budget-banner").performClick()
        compose.waitForTag("templates-open")
        compose.onNodeWithTag("templates-open").performClick()
        compose.waitOrCapture("templates-preview") { compose.onAllNodes(hasText("$0.00 → $600.00", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("17-templates")
        compose.onNodeWithTag("templates-apply").performClick()
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("category-groceries"))
        compose.waitOrCapture("templates-applied") { compose.onAllNodes(hasText("$600.00")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun addsASchedule() {
        openDemo()
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.onNodeWithTag("show-schedules").performClick()
        compose.waitForTag("schedule-add")
        compose.onNodeWithTag("schedule-add").performClick()
        compose.waitForTag("schedule-dialog")
        compose.onNodeWithTag("schedule-name").performTextInput("Synthetic Gym")
        compose.onNodeWithTag("schedule-account").performClick()
        compose.waitForTag("pick-checking")
        compose.onNodeWithTag("pick-checking").performClick()
        compose.onNodeWithTag("schedule-amount").performTextInput("40.00")
        compose.screenshot("18-add-schedule")
        compose.onNodeWithTag("schedule-save").performClick()
        compose.waitOrCapture("schedule-added") { compose.onAllNodes(hasText("Synthetic Gym")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun editsATemplateInTheCategorySheet() {
        openDemo()
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("category-dining"))
        compose.onNodeWithTag("category-dining").performClick()
        compose.waitForTag("category-template")
        compose.onNodeWithTag("category-template").performClick()
        compose.waitForTag("template-amount")
        compose.onNodeWithTag("template-amount").performTextInput("80.00")
        // A second entry: a balance goal next to the monthly amount (it assigns nothing itself).
        compose.onNodeWithTag("template-add-entry").performScrollTo().performClick()
        compose.waitForTag("template-amount-1")
        compose.onNodeWithTag("template-amount-1").performTextInput("500.00")
        compose.screenshot("19-template-editor")
        compose.onNodeWithTag("template-save").performClick()
        compose.waitForGone("category-sheet")
        compose.onNodeWithTag("month-next").performClick()
        compose.waitForTag("to-budget-banner")
        compose.onNodeWithTag("to-budget-banner").performClick()
        compose.waitForTag("templates-open")
        compose.onNodeWithTag("templates-open").performClick()
        compose.waitOrCapture("template-in-preview") { compose.onAllNodes(hasText("$0.00 → $80.00", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun groupsAnAccount() {
        openDemo()
        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitForTag("budget-accounts")
        compose.onNodeWithTag("accounts-list").performScrollToNode(hasTestTag("manage-groups"))
        compose.onNodeWithTag("manage-groups").performClick()
        compose.waitForTag("groups-dialog")
        compose.onNodeWithTag("group-name").performTextInput("Synthetic Cash")
        compose.onNodeWithTag("group-create").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Synthetic Cash")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Done").performClick()
        compose.waitForGone("groups-dialog")
        // Rows can sit below an empty group's card: bring the row into view first.
        compose.onNodeWithTag("accounts-list").performScrollToNode(hasTestTag("account-savings"))
        compose.onNodeWithTag("account-savings").performClick()
        compose.waitForTag("manage-account")
        compose.onNodeWithTag("manage-account").performClick()
        compose.waitForTag("account-group")
        compose.onNodeWithTag("account-group").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("No group")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Synthetic Cash"))[0].performClick()
        compose.onNodeWithTag("nav-back").performClick()
        compose.waitForTag("budget-accounts")
        compose.waitOrCapture("grouped") { compose.onAllNodes(hasText("Synthetic Cash")).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("28-account-groups")
    }

    @Test
    fun selectsTransactionsAndActsOnThemTogether() {
        openDemo()
        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transaction-list")
        val rowMatcher = androidx.compose.ui.test.SemanticsMatcher("a transaction row") { node ->
            val tag = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
            tag != null && tag.startsWith("transaction-") && tag !in setOf("transaction-list", "transaction-search")
        }
        compose.waitUntil(30_000) { compose.onAllNodes(rowMatcher).fetchSemanticsNodes().size >= 2 }
        compose.onAllNodes(rowMatcher)[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.waitOrCapture("selection-bar") { compose.onAllNodes(hasTestTag("selection-bar")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(rowMatcher)[1].performClick()
        compose.waitOrCapture("two-selected") { compose.onAllNodes(hasText("2 selected")).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("29-selection")
        // Clear first, then unclear: whatever the rows' state, the second action changes both.
        compose.onNodeWithTag("selection-bar").performScrollToNode(hasTestTag("batch-clear"))
        compose.onNodeWithTag("batch-clear").performClick()
        compose.waitForTag("batch-review-confirm")
        compose.onNodeWithTag("batch-review-confirm").performClick()
        compose.waitForGone("selection-bar")
        compose.onAllNodes(rowMatcher)[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.waitForTag("selection-bar")
        compose.onAllNodes(rowMatcher)[1].performClick()
        compose.waitOrCapture("two-selected-again") { compose.onAllNodes(hasText("2 selected")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("selection-bar").performScrollToNode(hasTestTag("batch-unclear"))
        compose.onNodeWithTag("batch-unclear").performClick()
        compose.waitForTag("batch-review-confirm")
        compose.onNodeWithTag("batch-review-confirm").performClick()
        compose.waitForGone("selection-bar")
        compose.waitOrCapture("batch-message") { compose.onAllNodes(hasText("Uncleared 2", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun turnsATransactionIntoASchedule() {
        openDemo()
        val scheduleCards = androidx.compose.ui.test.SemanticsMatcher("a schedule edit button") { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("schedule-edit-") == true &&
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) != "schedule-edit-dialog"
        }
        fun countSchedules(): Int {
            compose.onNodeWithTag("open-settings").performClick()
            compose.waitForTag("show-schedules")
            compose.onNodeWithTag("show-schedules").performClick()
            compose.waitForTag("schedules-screen")
            compose.waitUntil(30_000) { compose.onAllNodes(scheduleCards).fetchSemanticsNodes().isNotEmpty() }
            val count = compose.onAllNodes(scheduleCards).fetchSemanticsNodes().size
            compose.onNodeWithTag("nav-back").performClick()
            compose.onNodeWithTag("nav-back").performClick()
            compose.waitForTag("bottom-bar")
            return count
        }
        val before = countSchedules()
        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitForTag("budget-accounts")
        compose.onNodeWithTag("account-checking").performClick()
        compose.waitForTag("transaction-list")
        compose.onAllNodes(hasText("Payroll Inc", substring = true))[0].performClick()
        compose.waitForTag("editor-make-schedule")
        compose.onNodeWithTag("editor-make-schedule").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Every month")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Every month").performClick()
        // Creating asks for a review first, as Actualist's schedule conversion does.
        compose.waitForTag("schedule-review-create")
        compose.onNodeWithTag("schedule-review-create").performClick()
        compose.waitForTag("transaction-list")
        compose.onNodeWithTag("nav-back").performClick()
        compose.waitForTag("bottom-bar")
        // The editor closes only after the schedule was written.
        assertTrue(countSchedules() > before)
    }

    @Test
    fun reportsSwitchMonthsAndDrillIntoACategory() {
        openDemo()
        compose.onNodeWithTag("tab-reports").performClick()
        compose.waitForTag("reports-list")
        compose.onNodeWithTag("reports-previous").performClick()
        compose.onNodeWithTag("reports-next").performClick()
        compose.onNodeWithTag("reports-list").performScrollToNode(hasTestTag("report-budget-comparison"))
        compose.screenshot("30-budget-vs-spent")
        val categoryRow = androidx.compose.ui.test.SemanticsMatcher("a report category row") { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("report-category-") == true
        }
        compose.onNodeWithTag("reports-list").performScrollToNode(categoryRow)
        compose.onAllNodes(categoryRow)[0].performClick()
        compose.waitForTag("transaction-list")
        compose.onNodeWithTag("nav-back").performClick()
        // The explorer: a wider range, then focus one category's transactions. The list comes
        // back scrolled to the drill-down row, so bring the explorer button into view.
        compose.waitForTag("reports-list")
        compose.onNodeWithTag("reports-list").performScrollToNode(hasTestTag("open-explorer"))
        compose.waitForTag("open-explorer")
        compose.onNodeWithTag("open-explorer").performClick()
        compose.waitForTag("explorer-totals")
        compose.onNodeWithTag("explorer-range-Last 3 Months").performClick()
        compose.waitOrCapture("explorer-categories") { compose.onAllNodes(hasText("transactions", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("33-explorer")
        // The matching transactions are further down the list.
        compose.onNodeWithTag("explorer-screen").performScrollToNode(hasTestTag("explorer-row"))
        compose.waitOrCapture("explorer-rows") { compose.onAllNodes(hasTestTag("explorer-row")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theWidgetIsRegisteredAndReadsTheOpenBudget() {
        openDemo()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val providers = android.appwidget.AppWidgetManager.getInstance(context).getInstalledProvidersForPackage(context.packageName, null)
        assertTrue(providers.any { it.provider.className == com.sporez.actualist.android.widget.BudgetWidgetReceiver::class.java.name })
        val container = (context.applicationContext as ActualistApplication).container
        // Off the test thread, so the open session's composition keeps advancing (v2 test API).
        val summary = java.util.concurrent.atomic.AtomicReference<com.sporez.actualist.android.widget.WidgetSummary?>(null)
        Thread { summary.set(kotlinx.coroutines.runBlocking { com.sporez.actualist.android.widget.WidgetData.load(container) }) }.start()
        compose.waitUntil(30_000) { summary.get() != null }
        assertEquals(com.sporez.actualist.core.demo.DemoBudget.NAME, summary.get()!!.budgetName)
        assertEquals(toBudgetText().filter { it.isDigit() }, com.sporez.actualist.core.money.Money.format(summary.get()!!.toBudget).filter { it.isDigit() })
    }

    @Test
    fun wideScreensShowTheNextMonthsBesideThisOne() {
        openDemo()
        compose.onNodeWithTag("budget-following").assertDoesNotExist()
        // A phone in landscape is wider than 840dp: the side-by-side layout appears.
        compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        try {
            compose.waitOrCapture("following-months") { compose.onAllNodes(hasTestTag("budget-following")).fetchSemanticsNodes().isNotEmpty() }
            compose.screenshot("32-landscape-months")
            val next = java.time.YearMonth.now().plusMonths(1).toString()
            // The assigned amount in a later column edits that month in place.
            compose.onNodeWithTag("budget-following").performScrollToNode(hasTestTag("following-assign-$next-groceries"))
            compose.onNodeWithTag("following-assign-$next-groceries").performClick()
            compose.waitForTag("following-assign-dialog")
            compose.onNodeWithTag("following-assign-field").performTextClearance()
            compose.onNodeWithTag("following-assign-field").performTextInput("123.45")
            compose.onNodeWithTag("following-assign-save").performClick()
            compose.waitForGone("following-assign-dialog")
            compose.waitOrCapture("assigned-in-place") {
                compose.onAllNodes(hasTestTag("following-assign-$next-groceries") and hasText("123.45", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(monthTitle(java.time.YearMonth.now().toString()), compose.onNodeWithTag("month-title").fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString())
            compose.screenshot("32b-assigned-in-place")
            compose.onNodeWithTag("budget-following").performScrollToNode(hasTestTag("following-$next"))
            compose.onNodeWithTag("following-$next").performClick()
            // Only after switching to next month does the month after next-plus-one get a column.
            compose.waitOrCapture("switched-month") { compose.onAllNodes(hasTestTag("following-${java.time.YearMonth.parse(next).plusMonths(2)}")).fetchSemanticsNodes().isNotEmpty() }
            // A later month's cell opens that month's category sheet.
            val later = java.time.YearMonth.parse(next).plusMonths(1).toString()
            compose.onNodeWithTag("budget-following").performScrollToNode(hasTestTag("following-$later-groceries"))
            compose.onNodeWithTag("following-$later-groceries").performClick()
            compose.waitForTag("category-sheet")
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
    }

    @Test
    fun theOpenBudgetSurvivesActivityRecreation() {
        openDemo()
        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitForTag("budget-accounts")
        compose.activityRule.scenario.recreate()
        // The selected budget is persisted, so recreation reopens it instead of onboarding.
        compose.waitForTag("bottom-bar")
        compose.onNodeWithTag("onboarding").assertDoesNotExist()
        // The selection is persisted, which is what reopens the budget after process death.
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("actualist_settings", android.content.Context.MODE_PRIVATE)
        org.junit.Assert.assertEquals(com.sporez.actualist.core.demo.DemoBudget.FILE_ID, prefs.getString("selected_budget", null))
    }
}
