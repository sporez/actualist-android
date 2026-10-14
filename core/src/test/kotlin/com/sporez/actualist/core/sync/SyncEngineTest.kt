package com.sporez.actualist.core.sync

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.server.ServerException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two or three simulated devices syncing one synthetic budget through [FakeActualServer]:
 * convergence, conflicts, duplicate delivery, interrupted uploads, restarts, encryption
 * and late messages found only through the merkle comparison.
 */
class SyncEngineTest {
    private val clock = TestClock()
    private val token = FakeActualServer.TOKEN

    private class Device(val file: File, var database: BudgetDatabase, val nodeId: String)

    private fun device(nodeId: String): Device {
        val file = TestDatabases.tempFile()
        return Device(file, TestDatabases.starter(nodeId = nodeId, clock = clock, file = file), nodeId)
    }

    private fun engine(device: Device, server: FakeActualServer, encryption: EncryptionContext? = null) =
        SyncEngine(device.database, server.transport, server.configuration(encryption), clock)

    private fun Device.write(dataset: String, row: String, column: String, value: SyncValue) =
        database.commitLocal(listOf(CellDraft(dataset, row, column, value))).single()

    private fun Device.read(table: String, row: String, column: String) =
        database.sql.queryString("SELECT $column FROM $table WHERE id = ?", row)

    @Test
    fun aChangeOnOneDeviceReachesTheOther() {
        val server = FakeActualServer()
        val a = device("aaaaaaaaaaaaaaaa")
        val b = device("bbbbbbbbbbbbbbbb")
        a.write("accounts", "acct-1", "name", SyncValue.text("Synthetic Checking"))
        assertEquals(1, a.database.pendingLocalMessageCount())

        val pushed = engine(a, server).sync(token)
        assertEquals(1, pushed.uploaded)
        assertEquals(0, pushed.pendingAfter)
        assertEquals(0, a.database.pendingLocalMessageCount())

        clock.advance(1_000)
        val pulled = engine(b, server).sync(token)
        assertEquals(1, pulled.applied)
        assertEquals("Synthetic Checking", b.read("accounts", "acct-1", "name"))
        assertEquals(a.database.merkleTrie().hash, b.database.merkleTrie().hash)
        assertNull(a.database.merkleDivergence(b.database.merkleTrie()))
    }

    @Test
    fun conflictingOfflineEditsConvergeToTheLaterWrite() {
        val server = FakeActualServer()
        val a = device("aaaaaaaaaaaaaaaa")
        val b = device("bbbbbbbbbbbbbbbb")
        a.write("categories", "cat-1", "name", SyncValue.text("Groceries A"))
        clock.advance(5_000)
        b.write("categories", "cat-1", "name", SyncValue.text("Groceries B"))

        engine(b, server).sync(token)
        clock.advance(1_000)
        engine(a, server).sync(token)
        clock.advance(1_000)
        engine(b, server).sync(token)

        // B wrote later, so B's value wins on both devices regardless of upload order.
        assertEquals("Groceries B", a.read("categories", "cat-1", "name"))
        assertEquals("Groceries B", b.read("categories", "cat-1", "name"))
        assertEquals(a.database.merkleTrie().hash, b.database.merkleTrie().hash)
        assertEquals(setOf(2), setOf(server.storedTimestamps.size))
    }

    @Test
    fun aDroppedResponseKeepsTheOutboxAndTheRetryIsConfirmedWithoutDuplicates() {
        val server = FakeActualServer()
        val a = device("aaaaaaaaaaaaaaaa")
        a.write("payees", "p-1", "name", SyncValue.text("Synthetic Market"))
        server.failAfterCommit = 1
        assertThrows(ServerException.Unreachable::class.java) { engine(a, server).sync(token) }
        // Not confirmed: the change stays queued, with the failure recorded.
        val pending = a.database.pendingLocalMessages().single()
        assertEquals(1, pending.attemptCount)
        assertTrue(pending.lastError!!.contains("reach the server"))
        assertEquals(1, server.storedTimestamps.size)

        clock.advance(10_000)
        val retry = engine(a, server).sync(token)
        assertEquals(1, retry.uploaded)
        assertEquals(0, a.database.pendingLocalMessageCount())
        // The server kept one copy and the client did not re-apply its own change.
        assertEquals(1, server.storedTimestamps.size)
        assertEquals(1L, a.database.messageCount())
    }

    @Test
    fun aConnectionFailureBeforeTheServerSeesTheUploadIsRetried() {
        val server = FakeActualServer()
        val a = device("aaaaaaaaaaaaaaaa")
        a.write("payees", "p-1", "name", SyncValue.text("Synthetic Market"))
        server.failBeforeCommit = 1
        assertThrows(ServerException.Unreachable::class.java) { engine(a, server).sync(token) }
        assertEquals(0, server.storedTimestamps.size)
        assertEquals(1, a.database.pendingLocalMessageCount())
        engine(a, server).sync(token)
        assertEquals(0, a.database.pendingLocalMessageCount())
        assertEquals(1, server.storedTimestamps.size)
    }

    @Test
    fun duplicateDeliveryIsAppliedOnce() {
        val server = FakeActualServer()
        val a = device("aaaaaaaaaaaaaaaa")
        val b = device("bbbbbbbbbbbbbbbb")
        a.write("accounts", "acct-1", "name", SyncValue.text("Once"))
        engine(a, server).sync(token)
        server.duplicateReplies = true
        val result = engine(b, server).sync(token)
        assertEquals(1, result.applied)
        assertEquals(1L, b.database.messageCount())
        assertEquals(a.database.merkleTrie().hash, b.database.merkleTrie().hash)
    }

    @Test
    fun queuedChangesSurviveAnAppRestart() {
        val server = FakeActualServer()
        val a = device("aaaaaaaaaaaaaaaa")
        a.write("accounts", "acct-1", "name", SyncValue.text("Before restart"))
        a.database.close()
        a.database = TestDatabases.reopen(a.file, nodeId = a.nodeId, clock = clock)
        assertEquals(1, a.database.pendingLocalMessageCount())
        engine(a, server).sync(token)
        assertEquals(0, a.database.pendingLocalMessageCount())
        assertEquals(1, server.storedTimestamps.size)
    }

    @Test
    fun aLateOlderMessageIsFoundThroughTheMerkleComparison() {
        val server = FakeActualServer()
        val a = device("aaaaaaaaaaaaaaaa")
        val c = device("cccccccccccccccc")
        // C writes first but stays offline.
        c.write("categories", "cat-old", "name", SyncValue.text("From C"))
        clock.advance(120_000)
        a.write("categories", "cat-new", "name", SyncValue.text("From A"))
        engine(a, server).sync(token)
        clock.advance(60_000)
        engine(c, server).sync(token)
        clock.advance(60_000)
        // A's since is past C's change; only the merkle repull can bring it in.
        val result = engine(a, server).sync(token)
        assertEquals(1, result.applied)
        assertEquals("From C", a.read("categories", "cat-old", "name"))
        assertEquals(c.database.merkleTrie().hash, a.database.merkleTrie().hash)
    }

    @Test
    fun aServerThatNeverReturnsAMessageIsReportedOutOfSync() {
        val server = FakeActualServer()
        val a = device("aaaaaaaaaaaaaaaa")
        val c = device("cccccccccccccccc")
        val hidden = c.write("categories", "cat-x", "name", SyncValue.text("Hidden"))
        engine(c, server).sync(token)
        server.withholdFromReplies = setOf(hidden.timestamp)
        assertThrows(LocalFirstException.SyncOutOfSync::class.java) { engine(a, server).sync(token) }
        assertNull(a.read("categories", "cat-x", "name"))
    }

    @Test
    fun encryptedBudgetsSyncWithoutPlaintextOnTheServer() {
        val server = FakeActualServer()
        val key = EncryptionContext("key-1", ActualCrypto.deriveKey("synthetic-password-1", "c3ludGhldGljLXNhbHQ="))
        val a = device("aaaaaaaaaaaaaaaa")
        val b = device("bbbbbbbbbbbbbbbb")
        val written = a.write("notes", "budget-2026-10", "note", SyncValue.text("Synthetic secret note"))
        engine(a, server, key).sync(token)
        val stored = server.envelope(written.timestamp)!!
        assertTrue(stored.isEncrypted)
        assertFalse(String(stored.content, Charsets.ISO_8859_1).contains("Synthetic secret note"))

        engine(b, server, key).sync(token)
        assertEquals("Synthetic secret note", b.read("notes", "budget-2026-10", "note"))

        val noKey = device("dddddddddddddddd")
        assertThrows(LocalFirstException.EncryptedBudgetRequiresPassword::class.java) { engine(noKey, server).sync(token) }
        val wrongKey = EncryptionContext("key-1", ActualCrypto.deriveKey("wrong-password", "c3ludGhldGljLXNhbHQ="))
        assertThrows(LocalFirstException.UndecryptableMessage::class.java) { engine(noKey, server, wrongKey).sync(token) }
        assertEquals(0L, noKey.database.messageCount())
    }

    @Test
    fun aBadTokenFailsWithoutLosingQueuedChanges() {
        val server = FakeActualServer()
        val a = device("aaaaaaaaaaaaaaaa")
        a.write("accounts", "acct-1", "name", SyncValue.text("Queued"))
        assertThrows(ServerException.AuthenticationFailed::class.java) { engine(a, server).sync("expired") }
        assertEquals(1, a.database.pendingLocalMessageCount())
    }

    @Test
    fun largeOutboxesUploadInBatches() {
        val server = FakeActualServer()
        val a = device("aaaaaaaaaaaaaaaa")
        a.database.commitLocal((1..12).map { CellDraft("payees", "p-$it", "name", SyncValue.text("Payee $it")) })
        val engine = SyncEngine(a.database, server.transport, server.configuration(), clock, batchSize = 5)
        assertEquals(7, engine.sync(token).pendingAfter)
        assertEquals(2, engine.sync(token).pendingAfter)
        assertEquals(0, engine.sync(token).pendingAfter)
        assertEquals(12, server.storedTimestamps.size)
    }
}
