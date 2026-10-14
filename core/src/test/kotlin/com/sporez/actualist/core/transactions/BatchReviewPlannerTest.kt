package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BatchReviewPlannerTest {
    private fun row(
        id: String,
        cleared: Boolean = false,
        reconciled: Boolean = false,
        category: String? = "Groceries",
        isParent: Boolean = false,
        transferId: String? = null,
    ) = TransactionRow(
        id = id, accountId = "a", accountName = "Checking", date = LocalDate.of(2026, 10, 3), amount = -1250,
        payeeId = "p", payeeName = "Corner Store", categoryId = category?.lowercase(), categoryName = category, notes = null,
        cleared = cleared, reconciled = reconciled, isParent = isParent, isChild = false, parentId = null,
        transferId = transferId, transferAccountName = null, startingBalance = false,
    )

    @Test
    fun clearShowsBeforeAndAfterAndSkipsNothingForPlainRows() {
        val review = BatchReviewPlanner.plan(BatchIntent.CLEAR, listOf("1", "2"), listOf(row("1"), row("2", cleared = true)))
        assertEquals("Review Clear Transactions", review.title)
        assertEquals(1, review.changedCount)
        assertEquals(BatchRowStatus.WILL_CHANGE, review.rows[0].status)
        assertEquals(BatchEffect("Cleared", "Uncleared", "Cleared"), review.rows[0].effects.single())
        assertEquals(BatchRowStatus.NO_CHANGE, review.rows[1].status)
    }

    @Test
    fun reconciledRowsNeedConfirmation() {
        val review = BatchReviewPlanner.plan(BatchIntent.DELETE, listOf("1"), listOf(row("1", reconciled = true, cleared = true)))
        assertEquals(BatchRowStatus.NEEDS_CONFIRMATION, review.rows.single().status)
        assertTrue(review.needsReconciledConfirmation)
        assertTrue(review.reconciledMessage.startsWith("1 reconciled transaction is"))
        val duplicate = BatchReviewPlanner.plan(BatchIntent.DUPLICATE, listOf("1"), listOf(row("1", reconciled = true, cleared = true)))
        assertFalse(duplicate.needsReconciledConfirmation)
    }

    @Test
    fun categorizeSkipsSplitsTransfersAndMissingRows() {
        val rows = listOf(row("plain"), row("split", isParent = true), row("transfer", transferId = "x"))
        val review = BatchReviewPlanner.plan(BatchIntent.CATEGORIZE, listOf("plain", "split", "transfer", "gone"), rows, "Dining")
        assertEquals(listOf(BatchRowStatus.WILL_CHANGE, BatchRowStatus.SKIPPED, BatchRowStatus.SKIPPED, BatchRowStatus.SKIPPED), review.rows.map { it.status })
        assertEquals(3, review.skippedCount)
        assertEquals(BatchEffect("Category", "Groceries", "Dining"), review.rows[0].effects.single())
    }

    @Test
    fun confirmedEditsUnlockThenRelock() {
        val clock = TestClock()
        TestDatabases.starter(clock = clock).use { database ->
            database.sql.executeScript("INSERT INTO accounts (id, name, offbudget, closed, tombstone, sort_order) VALUES ('checking', 'Checking', 0, 0, 0, 1);")
            database.sql.execute(
                "INSERT INTO transactions (id, acct, date, amount, cleared, reconciled, isParent, isChild, tombstone, sort_order) VALUES ('t1', 'checking', 20260914, -100, 1, 1, 0, 0, 0, 0)",
            )
            val mutations = ReconciledMutations(database)
            assertTrue(mutations.hasReconciled(listOf("t1")))
            val locked = mutations.unlock(listOf("t1"))
            assertEquals(listOf("t1"), locked)
            assertFalse(mutations.hasReconciled(listOf("t1")))
            mutations.relock(locked)
            assertTrue(mutations.hasReconciled(listOf("t1")))
        }
    }
}
