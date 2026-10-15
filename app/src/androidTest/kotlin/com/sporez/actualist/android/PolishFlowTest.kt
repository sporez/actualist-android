package com.sporez.actualist.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Explorer range validation, the schedule detail page and its Manage menu, and the add-account validation message. */
@RunWith(AndroidJUnit4::class)
class PolishFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
    }

    private fun hasTag(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun hasVisibleText(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun click(tag: String) {
        if (tag.startsWith("explorer-range-")) compose.onNodeWithTag("explorer-ranges").performScrollToNode(hasTestTag(tag))
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test
    fun aTooLongCustomDailyRangeShowsTheRangeError() {
        openDemo()
        compose.onNodeWithTag("tab-reports").performClick()
        compose.waitForTag("reports-list")
        compose.onNodeWithTag("reports-list").performScrollToNode(hasTestTag("open-explorer"))
        click("open-explorer")
        compose.waitForTag("explorer-chart-CASH_FLOW")
        click("explorer-interval-DAY")
        click("explorer-range-Custom Range")
        compose.waitForTag("explorer-custom-start")
        click("explorer-custom-start")
        compose.waitForTag("explorer-date-ok")
        compose.onNode(hasContentDescription("text input", substring = true)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("01/01/2020")
        click("explorer-date-ok")
        compose.waitForGone("explorer-date-ok")
        compose.waitForTag("explorer-error")
        compose.waitOrCapture("range-too-long") { hasVisibleText("That range is too long") }
    }

    @Test
    fun theScheduleDetailPageHasAManageMenu() {
        openDemo()
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.onNodeWithTag("show-schedules").performClick()
        compose.waitForTag("schedules-screen")
        val open = "schedule-open-demo-schedule-rent"
        compose.waitOrCapture("scroll-$open") {
            runCatching { compose.onNodeWithTag("schedules-screen").performScrollToNode(hasTestTag(open)) }
            hasTag(open)
        }
        compose.onNodeWithTag(open).performClick()
        compose.waitForTag("schedule-detail")
        compose.waitOrCapture("detail-rows") {
            compose.onAllNodes(hasText("Next date")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("Automatic posting")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("State")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("schedule-manage").performClick()
        compose.waitForTag("schedule-manage-skip")
        compose.onNodeWithTag("schedule-manage-skip").performClick()
        compose.waitForTag("schedule-review")
        compose.onNodeWithTag("schedule-review-cancel").performClick()
        compose.waitForGone("schedule-review")
    }

    @Test
    fun addAccountWithoutANameShowsTheNameMessage() {
        openDemo()
        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitForTag("budget-accounts")
        compose.waitOrCapture("scroll-add-account") {
            runCatching { compose.onNodeWithTag("accounts-list").performScrollToNode(hasTestTag("add-account")) }
            hasTag("add-account")
        }
        compose.onNodeWithTag("add-account").performClick()
        compose.waitForTag("account-create")
        compose.onNodeWithTag("account-create").performClick()
        compose.waitForTag("account-name-error")
        compose.waitOrCapture("name-message") { hasVisibleText("Enter an account name.") }
    }
}
