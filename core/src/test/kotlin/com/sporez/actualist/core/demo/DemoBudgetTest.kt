package com.sporez.actualist.core.demo

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.transactions.LedgerReads
import com.sporez.actualist.core.transactions.TransactionQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DemoBudgetTest {
    private val anchor = LocalDate.of(2026, 10, 15)

    private fun snapshot(): Pair<String, String> {
        val file = TestDatabases.tempFile()
        DemoBudget.create(TestDatabases.driver, file.absolutePath, anchor, TestClock()).use { database ->
            val rows = database.sql.query("SELECT id, acct, date, amount, description, category, transferred_id FROM transactions ORDER BY id")
            val budgets = database.sql.query("SELECT id, amount, carryover FROM zero_budgets ORDER BY id")
            return rows.joinToString("\n") + "|" to budgets.joinToString("\n")
        }
    }

    @Test
    fun theDemoIsDeterministicForAnAnchor() {
        assertEquals(snapshot(), snapshot())
    }

    @Test
    fun theDemoIsLocalOnlyAndInternallyConsistent() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, anchor, TestClock()).use { database ->
            assertEquals(0, database.pendingLocalMessageCount())
            assertTrue(database.messageCount() > 1_000)
            val ledger = LedgerReads(database)
            val accounts = ledger.accounts()
            assertEquals(listOf("Everyday Checking", "High-Yield Savings", "Visa Credit Card", "Car Loan"), accounts.map { it.name })
            assertTrue(accounts.single { it.id == "carloan" }.offBudget)
            // Transfers are paired: every transfer leg points at a live row pointing back.
            val all = ledger.transactions(TransactionQuery(limit = 100_000))
            val byId = all.associateBy { it.id }
            for (row in all.filter { it.isTransfer }) {
                assertEquals(row.id, byId.getValue(row.transferId!!).transferId)
                assertEquals(-row.amount, byId.getValue(row.transferId!!).amount)
            }
            // Nothing dated after the anchor.
            assertTrue(all.all { it.date!! <= anchor })

            val reads = BudgetReads(database)
            assertEquals((4..10).map { "2026-%02d".format(it) }, reads.availableMonths())
            val october = reads.fetchBudgetMonth("2026-10")
            val september = reads.fetchBudgetMonth("2026-09")
            assertEquals(october.totalIncome + october.fromLastMonth, october.incomeAvailable)
            assertEquals(september.toBudget + september.forNextMonth, october.fromLastMonth)
            assertTrue(october.categories.any { it.id == "rent" && it.budgeted == 180_000L })
            // Savings goals roll over and so carry their whole balance forward.
            val vacation = october.category("vacation")!!
            assertTrue(vacation.carryover)
            assertEquals(7 * 20_000L, vacation.balance)
            assertEquals("Demo month. Every number here is synthetic.", database.sql.queryString("SELECT note FROM notes WHERE id = 'budget-2026-10'"))

            // The demo's rules run through the ported engine: a cafe purchase is categorized as dining.
            val result = com.sporez.actualist.core.rules.RuleEngine(database).run(
                com.sporez.actualist.core.rules.RuleTransactionInput(accountId = "credit", date = anchor, amount = -450, payeeId = "cafe", payeeName = "Daily Grind"),
                anchor,
            )
            assertEquals("dining", result.categoryId)
            assertEquals(listOf("demo-rule-cafe"), result.appliedRuleIds)
        }
    }
}
