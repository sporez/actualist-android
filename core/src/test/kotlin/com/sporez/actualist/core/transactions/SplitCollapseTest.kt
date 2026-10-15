package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class SplitCollapseTest {
    private val clock = TestClock()
    private val day = LocalDate.of(2026, 10, 14)

    @Test
    fun collapsingASplitLeavesOneNormalTransactionWithTheSurvivorsCategory() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), clock).use { database ->
            var ids = 0
            val writes = TransactionWrites(database, clock) { "collapse-${++ids}" }
            val created = writes.create(
                TransactionDraft(
                    accountId = "checking", date = day, amount = -10_000, payeeName = "Synthetic Warehouse",
                    splits = listOf(SplitDraft("groceries", -6_000), SplitDraft("dining", -4_000)),
                ),
            )
            writes.collapseSplit(created.transactionId, "dining", TransactionEdit(amount = -10_000))
            val ledger = LedgerReads(database)
            val row = ledger.transactions(TransactionQuery(accountId = "checking", limit = 10_000)).first { it.id == created.transactionId }
            assertFalse(row.isParent)
            assertEquals("dining", row.categoryId)
            assertEquals(-10_000L, row.amount)
            assertEquals(0, row.children.size)
        }
    }
}
