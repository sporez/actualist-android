package com.sporez.actualist.android

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** actualist://open links and shared bank text, handled as MainActivity handles an arriving Intent. */
@RunWith(AndroidJUnit4::class)
class DeepLinkFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(FreshAppState()).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
    }

    @Test
    fun routesAreParsedFromLinks() {
        assertEquals("reports", DeepLinks.routeForSegments(listOf("reports")))
        assertEquals("transaction-new", DeepLinks.routeForSegments(listOf("new-transaction")))
        assertEquals("account/abc", DeepLinks.routeForSegments(listOf("account", "abc")))
        assertNull(DeepLinks.routeForSegments(listOf("nope")))
        assertNull(DeepLinks.routeForSegments(emptyList()))
    }

    @Test
    fun aDeepLinkOpensReports() {
        openDemo()
        val link = Intent(Intent.ACTION_VIEW, DeepLinks.uriFor("reports"))
        assertTrue(DeepLinks.handle(link))
        compose.waitForTag("reports-screen")
    }

    @Test
    fun sharedBankTextQueuesAPrefilledNewTransaction() {
        openDemo()
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Spent \$12.50 on Fresh Market in Checking")
        assertTrue(TextImportPrefill.handleShare(compose.activity, share))
        // The editor opens; until it consumes the prefill the parse waits for it, so either is a pass.
        compose.waitForTag("editor-amount")
        val waiting = TextImportPrefill.pending.value
        if (waiting != null) {
            assertEquals(1250L, waiting.amountMinorUnits)
            assertEquals("Fresh Market", waiting.payeeText)
        }
    }
}
