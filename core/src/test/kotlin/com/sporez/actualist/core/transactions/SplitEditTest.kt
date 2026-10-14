package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SplitEditTest {
    private val clock = TestClock()
    private val day = LocalDate.of(2026, 10, 14)

    private fun demo() = DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), clock)

    @Test
    fun splitsAreCreatedEditedAndCountedPerCategory() {
        demo().use { database ->
            var ids = 0
            val writes = TransactionWrites(database, clock) { "id-${++ids}" }
            val reads = BudgetReads(database)
            val diningBefore = reads.fetchBudgetMonth("2026-10").category("dining")!!.spent
            val created = writes.create(
                TransactionDraft(
                    accountId = "checking", date = day, amount = -10_000, payeeName = "Synthetic Warehouse",
                    splits = listOf(SplitDraft("groceries", -6_000), SplitDraft("dining", -4_000)),
                ),
            )
            val ledger = LedgerReads(database)
            val parent = ledger.transactions(TransactionQuery(accountId = "checking", limit = 10_000)).first { it.id == created.transactionId }
            assertTrue(parent.isParent)
            assertEquals(listOf(-6_000L, -4_000L).sorted(), parent.children.map { it.amount }.sorted())
            assertEquals(diningBefore - 4_000, reads.fetchBudgetMonth("2026-10").category("dining")!!.spent)

            val groceriesChild = parent.children.first { it.categoryId == "groceries" }
            writes.updateSplit(
                created.transactionId,
                SplitFamilyEdit(
                    amount = -12_000,
                    date = day.minusDays(1),
                    cleared = true,
                    splits = listOf(SplitEdit(groceriesChild.id, "groceries", -7_000), SplitEdit(null, "shopping", -5_000)),
                ),
            )
            val after = ledger.transactions(TransactionQuery(accountId = "checking", limit = 10_000)).first { it.id == created.transactionId }
            assertEquals(-12_000L, after.amount)
            assertEquals(setOf("groceries", "shopping"), after.children.map { it.categoryId }.toSet())
            assertTrue(after.children.all { it.date == day.minusDays(1) && it.cleared })
            // The removed dining child no longer counts, and the parent never counts.
            assertEquals(diningBefore, reads.fetchBudgetMonth("2026-10").category("dining")!!.spent)
            assertEquals(1L, database.sql.queryLong("SELECT COUNT(*) FROM transactions WHERE parent_id = ? AND tombstone = 1", created.transactionId))
        }
    }

    @Test
    fun aSimpleRowBecomesASplitAndInvalidSplitsAreRefused() {
        demo().use { database ->
            val writes = TransactionWrites(database, clock)
            val simple = writes.create(TransactionDraft(accountId = "checking", date = day, amount = -3_000, categoryId = "dining", payeeName = "Synthetic Deli"))
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) {
                writes.updateSplit(simple.transactionId, SplitFamilyEdit(amount = -3_000, splits = listOf(SplitEdit(null, "dining", -1_000))))
            }
            writes.updateSplit(
                simple.transactionId,
                SplitFamilyEdit(amount = -3_000, splits = listOf(SplitEdit(null, "dining", -1_000), SplitEdit(null, "groceries", -2_000))),
            )
            val row = LedgerReads(database).transactions(TransactionQuery(accountId = "checking", limit = 10_000)).first { it.id == simple.transactionId }
            assertTrue(row.isParent)
            assertEquals(null, row.categoryId)
            assertEquals(2, row.children.size)
            // Transfers cannot be split.
            val transfer = writes.create(TransactionDraft(accountId = "checking", date = day, amount = -500, payeeId = "xfer-savings", isTransfer = true))
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) {
                writes.updateSplit(transfer.transactionId, SplitFamilyEdit(amount = -500, splits = listOf(SplitEdit(null, null, -500))))
            }
        }
    }
}
