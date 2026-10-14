package com.sporez.actualist.core.banksync

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class BankSyncRunReportTest {
    private fun ok(id: String, added: Int) = SimpleFinAccountResult(id, BankSyncStatus.OK, addedCount = added)

    @Test
    fun linesAndOutcomeForMixedRun() {
        val good = BankSyncRunReport.line("a", "Checking", SimpleFinSyncSummary(listOf(ok("a", 2))))
        assertFalse(good.failed)
        assertEquals("Added 2 transactions", good.text)
        val skipped = BankSyncRunReport.line("b", "Savings", SimpleFinSyncSummary(listOf(SimpleFinAccountResult("b", BankSyncStatus.ACCOUNT_MISSING, errorCode = "ACCOUNT_MISSING"))))
        assertTrue(skipped.failed)
        assertEquals("Skipped: ACCOUNT_MISSING", skipped.text)
        val thrown = BankSyncRunReport.failure("c", "Card", null)
        assertEquals("Bank sync failed.", thrown.text)
        assertEquals("Synced 1 of 3 accounts, 2 failed.", BankSyncRunReport.outcome(listOf(good, skipped, thrown)))
        assertEquals("Synced 1 account.", BankSyncRunReport.outcome(listOf(good)))
        assertEquals("All 2 accounts failed.", BankSyncRunReport.outcome(listOf(skipped, thrown)))
        assertEquals("No linked accounts to sync.", BankSyncRunReport.outcome(emptyList()))
    }
}
