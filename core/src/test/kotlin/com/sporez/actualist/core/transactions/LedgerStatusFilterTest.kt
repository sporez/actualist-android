package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.query.StatusFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The feed status filters partition the rows, as Actualist's All/Uncleared/Cleared/Reconciled do. */
class LedgerStatusFilterTest {
    @Test
    fun statusesPartitionTheFeed() {
        val clock = TestClock()
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), clock).use { db ->
            val ledger = LedgerReads(db)
            // Reconcile checking so every bucket has rows.
            Reconciliation(db, clock).let { reconciliation ->
                val snapshot = reconciliation.snapshot("checking")
                reconciliation.finish("checking", snapshot.clearedBalance)
            }
            fun ids(status: StatusFilter) = ledger.transactions(TransactionQuery(status = status, limit = 100_000)).map { it.id }.toSet()
            val all = ids(StatusFilter.ALL)
            val uncleared = ids(StatusFilter.UNCLEARED)
            val cleared = ids(StatusFilter.CLEARED)
            val reconciled = ids(StatusFilter.RECONCILED)
            assertTrue(uncleared.isNotEmpty() && reconciled.isNotEmpty())
            assertEquals(all, uncleared + cleared + reconciled)
            assertEquals(all.size, uncleared.size + cleared.size + reconciled.size)
            val rows = ledger.transactions(TransactionQuery(limit = 100_000)).associateBy { it.id }
            assertTrue(reconciled.all { rows.getValue(it).reconciled })
            assertTrue(uncleared.all { !rows.getValue(it).cleared })
        }
    }
}
