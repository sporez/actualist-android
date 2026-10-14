package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.transactions.LedgerReads
import com.sporez.actualist.core.transactions.TransactionQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SchedulePostDateTest {
    private val anchor = LocalDate.of(2026, 10, 15)
    private val today = anchor.toString()

    private fun <T> withDemo(block: (Schedules, LedgerReads) -> T): T {
        val clock = TestClock()
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, anchor, clock).use { database ->
            return block(Schedules(database, clock), LedgerReads(database))
        }
    }

    private fun postedDate(ledger: LedgerReads, transactionId: String): LocalDate? =
        ledger.transactions(TransactionQuery(limit = 100_000)).firstOrNull { it.id == transactionId }?.date

    @Test
    fun postingTodayDatesTheTransactionToday() = withDemo { schedules, ledger ->
        val before = schedules.list(today).single { it.id == "demo-schedule-streaming" }
        assertTrue(before.canPost)
        val receipt = schedules.postNow("demo-schedule-streaming", today, postsToday = true)
        assertEquals(today, receipt.postedDayId)
        assertEquals(before.nextDate, receipt.occurrenceDayId)
        assertEquals(anchor, postedDate(ledger, receipt.transactionId))
    }

    @Test
    fun postingAtTheScheduledDateUsesTheNextDate() = withDemo { schedules, ledger ->
        val before = schedules.list(today).single { it.id == "demo-schedule-phone" }
        val scheduled = before.nextDate
        assertNotNull(scheduled)
        val receipt = schedules.postNow("demo-schedule-phone", today)
        assertEquals(scheduled, receipt.postedDayId)
        assertEquals(LocalDate.parse(scheduled), postedDate(ledger, receipt.transactionId))
    }
}
