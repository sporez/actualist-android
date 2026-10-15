package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LedgerSearchTest {
    @Test
    fun textSearchMatchesOnlyRowsContainingTheText() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), TestClock()).use { db ->
            val ledger = LedgerReads(db)
            val all = ledger.transactions(TransactionQuery(limit = 100_000))
            val fresh = ledger.transactions(TransactionQuery(search = "Fresh Market", limit = 100_000))
            assertTrue(fresh.isNotEmpty())
            assertTrue(fresh.size < all.size)
            assertTrue(fresh.all { (it.payeeName ?: "").contains("Fresh Market", ignoreCase = true) || (it.notes ?: "").contains("Fresh Market", ignoreCase = true) })
            // No digits: the amount clause must not turn into a match-everything pattern.
            assertTrue(ledger.transactions(TransactionQuery(search = "zzz-no-such-text", limit = 100_000)).isEmpty())
            // Digits still search amounts.
            assertTrue(ledger.transactions(TransactionQuery(search = "2450", limit = 100_000)).isNotEmpty())
        }
    }
}
