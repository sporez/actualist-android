package com.sporez.actualist.android

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sporez.actualist.core.money.BudgetPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The budget's `defaultCurrencyCode` preference decides how the To Budget banner prints amounts. */
@RunWith(AndroidJUnit4::class)
class CurrencyFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    @Test
    fun euroBudgetShowsTheEuroSymbolAndRestoresAfterwards() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
        compose.onNodeWithTag("to-budget-amount", useUnmergedTree = true).assertTextContains("$", substring = true)

        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ActualistApplication).container
        val budgetId = container.library.list().first().id
        val session = container.sessions.acquire(budgetId) { container.library.open(budgetId) ?: error("The demo budget is missing.") }
        try {
            val previous = runBlocking(Dispatchers.IO) { session.read { BudgetPreferences(database).value("defaultCurrencyCode") } }
            try {
                runBlocking(Dispatchers.IO) {
                    session.write { database.sql.execute("INSERT OR REPLACE INTO preferences (id, value) VALUES (?, ?)", "defaultCurrencyCode", "EUR") }
                }
                compose.waitOrCapture("euro-banner") {
                    runCatching { compose.onNodeWithTag("to-budget-amount", useUnmergedTree = true).assertTextContains("€", substring = true) }.isSuccess
                }
                assertTrue(runBlocking(Dispatchers.IO) { session.read { BudgetPreferences(database).format().symbol } } == "€")
            } finally {
                runBlocking(Dispatchers.IO) {
                    session.write {
                        if (previous == null) database.sql.execute("DELETE FROM preferences WHERE id = ?", "defaultCurrencyCode")
                        else database.sql.execute("INSERT OR REPLACE INTO preferences (id, value) VALUES (?, ?)", "defaultCurrencyCode", previous)
                    }
                }
            }
            compose.waitOrCapture("dollar-banner") {
                runCatching { compose.onNodeWithTag("to-budget-amount", useUnmergedTree = true).assertTextContains("$", substring = true) }.isSuccess
            }
        } finally {
            container.sessions.release(session)
        }
    }
}
