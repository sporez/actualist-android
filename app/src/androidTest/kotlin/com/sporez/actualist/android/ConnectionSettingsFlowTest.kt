package com.sporez.actualist.android

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The server screen's local-network http rule and the per-header editor (no server is contacted). */
@RunWith(AndroidJUnit4::class)
class ConnectionSettingsFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(FreshAppState()).around(compose).around(FailureScreenshot())

    private fun present(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun openServerScreen() {
        compose.waitForTag("onboarding")
        compose.onNodeWithTag("onboarding-connect").performClick()
        compose.waitForTag("server-url-field")
    }

    private fun typeUrl(url: String) {
        compose.onNodeWithTag("server-url-field").performTextClearance()
        compose.onNodeWithTag("server-url-field").performTextInput(url)
    }

    @Test
    fun aLocalNetworkHttpAddressShowsTheUnencryptedWarning() {
        openServerScreen()
        typeUrl("http://192.168.1.10:5006")
        compose.waitUntil(10_000) { present("local-http-warning") }
        assertTrue(!present("server-url-error"))
    }

    @Test
    fun aPublicHttpAddressIsRefusedAndHasNoWarning() {
        openServerScreen()
        typeUrl("http://budget.example.com")
        compose.waitUntil(10_000) { present("server-url-error") }
        assertTrue(!present("local-http-warning"))
    }

    @Test
    fun theHeaderEditorAddsAndRemovesAHeaderRow() {
        openServerScreen()
        compose.onNodeWithTag("add-header").performScrollTo()
        compose.onNodeWithTag("add-header").performClick()
        compose.waitForTag("header-name-0")
        compose.onNodeWithTag("header-name-0").performScrollTo()
        compose.onNodeWithTag("header-name-0").performTextInput("X-Proxy-Token")
        compose.onNodeWithTag("header-value-0").performTextInput("synthetic-secret")
        compose.onNodeWithTag("header-reveal-0").performScrollTo()
        compose.onNodeWithTag("header-reveal-0").performClick()
        compose.onNodeWithTag("header-remove-0").performScrollTo()
        compose.onNodeWithTag("header-remove-0").performClick()
        compose.waitUntil(10_000) { !present("header-name-0") }
    }
}
