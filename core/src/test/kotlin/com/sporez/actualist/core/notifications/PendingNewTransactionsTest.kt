package com.sporez.actualist.core.notifications

import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.server.BudgetImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingNewTransactionsTest {
    private fun insert(database: BudgetDatabase, id: String, account: String) {
        database.sql.execute(
            "INSERT INTO transactions (id, acct, date, amount, tombstone) VALUES (?, ?, 20261001, -500, 0)",
            id,
            account,
        )
    }

    @Test
    fun detectsOnlyRowsThatAppearedAfterTheSnapshot() {
        val database = TestDatabases.starter()
        val pending = PendingNewTransactions(database)
        insert(database, "t1", "a1")
        val before = pending.liveTransactionAccounts()
        insert(database, "t2", "a1")
        insert(database, "t3", "a2")
        val fresh = pending.newSince(before)
        assertEquals(setOf("a1", "a2"), fresh.keys)
        assertEquals(listOf("t2"), fresh.getValue("a1"))
        assertEquals(listOf("t3"), fresh.getValue("a2"))
        database.close()
    }

    @Test
    fun reviewedRowsStayReviewedWhenSeenAgain() {
        val database = TestDatabases.starter()
        val pending = PendingNewTransactions(database)
        assertEquals(1, pending.record(mapOf("a1" to listOf("t1")), PendingNewTransactionSource.REMOTE_SYNC, "n1"))
        assertEquals(setOf("t1"), pending.pendingIds())
        assertEquals(setOf("t1"), pending.pendingIds("a1"))
        assertTrue(pending.pendingIds("a2").isEmpty())
        assertEquals(1, pending.review(listOf("t1"), "a1"))
        assertTrue(pending.pendingIds().isEmpty())
        assertEquals(0, pending.record(mapOf("a1" to listOf("t1")), PendingNewTransactionSource.REMOTE_SYNC, "n2"))
        assertTrue(pending.pendingIds().isEmpty())
        database.close()
    }

    @Test
    fun deliveryIsOfferedOnceUntilAcknowledged() {
        val database = TestDatabases.starter()
        val pending = PendingNewTransactions(database)
        pending.record(mapOf("a1" to listOf("t1", "t2")), PendingNewTransactionSource.REMOTE_SYNC, "n1")
        val delivery = pending.delivery()
        assertNotNull(delivery)
        assertEquals("n1", delivery!!.notificationId)
        assertEquals(listOf("t1", "t2"), delivery.transactionIds)
        pending.acknowledgeDelivery(delivery.transactionIds)
        assertNull(pending.delivery())
        assertEquals(setOf("t1", "t2"), pending.pendingIds())
        database.close()
    }

    @Test
    fun suppressedDeliveriesNeverNotifyButStayMarked() {
        val database = TestDatabases.starter()
        val pending = PendingNewTransactions(database)
        pending.record(mapOf("a1" to listOf("t1")), PendingNewTransactionSource.BANK_SYNC, "n1")
        pending.suppressDeliveries()
        assertNull(pending.delivery())
        assertEquals(setOf("t1"), pending.pendingIds())
        database.close()
    }

    @Test
    fun theTableIsNeverSyncedOrExported() {
        val database = TestDatabases.starter()
        val pending = PendingNewTransactions(database)
        val messagesBefore = database.messageCount()
        val outboxBefore = database.pendingLocalMessageCount()
        pending.record(mapOf("a1" to listOf("t1")), PendingNewTransactionSource.REMOTE_SYNC, "n1")
        assertTrue(BudgetDatabase.isReservedDataset(PendingNewTransactions.TABLE))
        assertEquals(outboxBefore, database.pendingLocalMessageCount())
        assertEquals(messagesBefore, database.messageCount())

        val file = TestDatabases.tempFile()
        database.sql.execute("VACUUM INTO ?", file.absolutePath)
        BudgetImporter.sanitize(file, TestDatabases.driver)
        val exported = TestDatabases.reopen(file)
        assertFalse(exported.tableExists(PendingNewTransactions.TABLE))
        exported.close()
        database.close()
    }
}
