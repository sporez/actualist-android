package com.sporez.actualist.core.store

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.sync.SyncValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetSessionTest {
    @Test
    fun demoWritesStayLocalAndRealWritesQueueForUpload() = runBlocking {
        val clock = TestClock()
        val demo = BudgetSession("demo", "Demo", TestDatabases.starter(clock = clock), isDemo = true, clock = clock, io = Dispatchers.IO)
        demo.use { session ->
            assertEquals(SyncStatus.LocalOnly, session.syncStatus.value)
            val before = session.revision.value
            session.write { database.commitLocal(listOf(CellDraft("accounts", "a", "name", SyncValue.text("Demo")))) }
            assertEquals(before + 1, session.revision.value)
            assertEquals(0, session.database.pendingLocalMessageCount())
            assertEquals(1L, session.database.messageCount())
        }
        val real = BudgetSession("real", "Real", TestDatabases.starter(clock = clock), isDemo = false, clock = clock, io = Dispatchers.IO)
        real.use { session ->
            assertTrue(session.syncStatus.value is SyncStatus.Idle)
            session.write { database.commitLocal(listOf(CellDraft("accounts", "a", "name", SyncValue.text("Real")))) }
            assertEquals(1, session.database.pendingLocalMessageCount())
            // A failed write changes nothing and does not bump the revision.
            val before = session.revision.value
            runCatching { session.write { database.commitLocal(listOf(CellDraft("accounts", "a", "bogus", SyncValue.text("x")))) } }
            assertEquals(before, session.revision.value)
            assertEquals(1, session.database.pendingLocalMessageCount())
        }
    }
}
