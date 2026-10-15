package com.sporez.actualist.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The schedules list on the demo budget: search, completed schedules, detail and the post review. */
@RunWith(AndroidJUnit4::class)
class ScheduleListFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
    }

    private fun openSchedules() {
        openDemo()
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.onNodeWithTag("show-schedules").performClick()
        compose.waitForTag("schedules-screen")
        compose.waitForTag("schedule-post-demo-schedule-streaming")
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("schedules-screen").performScrollToNode(hasTestTag(tag))
    }

    private fun hasTag(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun hasVisibleText(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** The search box also contains the typed name, so rows are detected by their header tag instead. */
    private fun hasAnyScheduleRow(): Boolean {
        val row = SemanticsMatcher("a schedule row") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("schedule-open-") == true
        }
        return compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun searchFiltersSchedulesByNamePayeeAndAccount() {
        openSchedules()
        compose.onNodeWithTag("schedule-search").performTextInput("rent")
        compose.waitOrCapture("search-rent") { hasTag("schedule-post-demo-schedule-rent") && !hasTag("schedule-post-demo-schedule-streaming") }

        compose.onNodeWithTag("schedule-search").performTextClearance()
        compose.onNodeWithTag("schedule-search").performTextInput("streamflix")
        compose.waitOrCapture("search-streamflix") { hasTag("schedule-post-demo-schedule-streaming") && !hasTag("schedule-post-demo-schedule-rent") }

        compose.onNodeWithTag("schedule-search").performTextClearance()
        compose.onNodeWithTag("schedule-search").performTextInput("zzzz no such schedule")
        compose.waitForTag("schedules-empty")
        compose.waitOrCapture("search-none") { !hasTag("schedule-post-demo-schedule-rent") && !hasTag("schedule-post-demo-schedule-phone") }

        compose.onNodeWithTag("schedule-search").performTextClearance()
        compose.waitOrCapture("search-cleared") { hasTag("schedule-post-demo-schedule-rent") }
    }

    @Test
    fun completedSchedulesAreHiddenUntilShown() {
        openSchedules()
        val completeButton = SemanticsMatcher("a schedule complete button") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("schedule-complete-") == true
        }
        compose.onNodeWithTag("schedule-add").performClick()
        compose.waitForTag("schedule-dialog")
        compose.onNodeWithTag("schedule-name").performTextInput("Synthetic Once")
        compose.onNodeWithTag("schedule-account").performClick()
        compose.waitForTag("pick-checking")
        compose.onNodeWithTag("pick-checking").performClick()
        compose.onNodeWithTag("schedule-amount").performTextInput("25.00")
        compose.onNodeWithTag("schedule-frequency-ONCE").performClick()
        compose.onNodeWithTag("schedule-save").performClick()
        // Search narrows the list to the new schedule so its complete button is the only one.
        compose.waitForTag("schedule-search")
        compose.onNodeWithTag("schedule-search").performTextInput("Synthetic Once")
        compose.waitOrCapture("once-added") { hasAnyScheduleRow() }

        compose.waitOrCapture("complete-button") {
            compose.onAllNodes(completeButton).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(completeButton)[0].performClick()
        // Complete asks for a review first, as Actualist's does.
        compose.waitForTag("schedule-review")
        compose.onNodeWithTag("schedule-review-confirm").performClick()
        compose.waitOrCapture("completed-hidden") { !hasAnyScheduleRow() }

        scrollTo("schedule-show-completed")
        compose.waitOrCapture("completed-count") { hasVisibleText("Show Completed (1)") }
        compose.onNodeWithTag("schedule-show-completed").performClick()
        compose.waitOrCapture("completed-shown") { hasTag("schedule-section-COMPLETED") }
        scrollTo("schedule-section-COMPLETED")
        compose.waitOrCapture("completed-listed") { hasAnyScheduleRow() }
    }

    @Test
    fun opensAScheduleDetail() {
        openSchedules()
        compose.onNodeWithTag("schedule-open-demo-schedule-rent").performClick()
        compose.waitForTag("schedule-detail")
        compose.waitOrCapture("detail-dates") {
            compose.onAllNodes(hasText("Upcoming dates")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("schedule-detail-close").performClick()
        compose.waitForGone("schedule-detail")
    }

    @Test
    fun postsTodayFromTheReview() {
        openSchedules()
        compose.onNodeWithTag("schedule-post-demo-schedule-streaming").performClick()
        compose.waitForTag("schedule-post-review")
        compose.onNodeWithTag("schedule-post-date-today").performClick()
        compose.onNodeWithTag("schedule-post-confirm").performClick()
        compose.waitOrCapture("posted-today") { hasVisibleText("Posted StreamFlix") }
        compose.waitForGone("schedule-post-review")
    }
}
