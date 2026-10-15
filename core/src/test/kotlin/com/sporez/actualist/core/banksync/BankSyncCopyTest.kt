package com.sporez.actualist.core.banksync

import com.sporez.actualist.core.importer.BankSyncReconciliation.Existing
import com.sporez.actualist.core.importer.BankSyncReconciliation.MatchedUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BankSyncCopyTest {
    @Test
    fun statusWordingFollowsActualist() {
        assertNull(BankSyncCopy.statusText("ok"))
        assertNull(BankSyncCopy.statusText(null))
        assertEquals("Reconnect required", BankSyncCopy.statusText("reauth-required"))
        assertEquals("Rate limited", BankSyncCopy.statusText("rate-limit-exceeded"))
        assertEquals("Account missing at bank", BankSyncCopy.statusText("account-missing"))
        assertEquals("Needs attention", BankSyncCopy.statusText("attention-required"))
        assertEquals("Timed out", BankSyncCopy.statusText("timed-out"))
        assertEquals("Failed", BankSyncCopy.statusText("anything-else"))
        assertTrue(BankSyncCopy.isFailure("failed"))
        assertFalse(BankSyncCopy.isFailure("ok"))
    }

    @Test
    fun lastSyncTextAndAge() {
        val now = 10_000_000_000L
        assertEquals("Not linked", BankSyncCopy.lastSyncText(null, false, now))
        assertEquals("Never synced", BankSyncCopy.lastSyncText(null, true, now))
        assertEquals("Synced just now", BankSyncCopy.lastSyncText((now - 5_000).toString(), true, now))
        assertEquals("Synced 5m ago", BankSyncCopy.lastSyncText((now - 5 * 60_000).toString(), true, now))
        assertEquals("Synced 2h ago", BankSyncCopy.lastSyncText((now - 2 * 3_600_000).toString(), true, now))
        assertEquals("Synced 3d ago", BankSyncCopy.lastSyncText((now - 3 * 86_400_000L).toString(), true, now))
    }

    @Test
    fun providerFailuresUseActualistWording() {
        assertEquals("SimpleFIN says this connection requires payment.", BankSyncCopy.failureText(SimpleFinException.HttpStatus(402)))
        assertEquals("SimpleFIN access was denied. The connection may have been revoked.", BankSyncCopy.failureText(SimpleFinException.HttpStatus(403)))
        assertNull(BankSyncCopy.failureText(SimpleFinException.HttpStatus(500)))
        val line = BankSyncRunReport.failureOf("a", "Checking", SimpleFinException.HttpStatus(402))
        assertTrue(line.failed)
        assertEquals("SimpleFIN says this connection requires payment.", line.text)
    }

    @Test
    fun problemSummaryGroupsMessages() {
        assertNull(BankSyncCopy.problemSummary(emptyList()))
        assertEquals("2× Bad amount · 1× Missing date", BankSyncCopy.problemSummary(listOf("Bad amount", "Missing date", "Bad amount")))
        val line = BankSyncRunReport.failureOf(
            "a",
            "Checking",
            SimpleFinException.UnresolvedProblems(listOf(BankSyncProblem("t1", "Bad amount"))),
        )
        assertEquals(listOf("Bad amount"), line.problems)
    }

    @Test
    fun matchChangesListFilledFields() {
        val existing = Existing(
            id = "e1", financialId = null, dayId = "20260110", amount = -500, payeeId = null, categoryId = null,
            notes = null, cleared = false, reconciled = false, importedPayee = null, isParent = false, isChild = false, parentId = null, transferId = null,
        )
        val update = MatchedUpdate(
            existingId = "e1", financialId = "bank-1", payeeId = null, categoryId = null, importedPayee = "ACME",
            notes = null, cleared = true, childIds = emptyList(), dayId = "20260112",
        )
        val changes = BankSyncMatchDetails.changes(update, existing)
        assertTrue("Attach bank transaction ID" in changes)
        assertTrue("Date: 2026-01-10 → 2026-01-12" in changes)
        assertTrue("Cleared: No → Yes" in changes)
        assertTrue("Bank payee: None → “ACME”" in changes)
    }
}
