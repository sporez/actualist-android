package com.sporez.actualist.core.store

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.budget.BudgetWrites
import com.sporez.actualist.core.demo.DemoBudget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetSessionTest {
    /** A screen that leaves while its write runs must not lose the change notification. */
    @Test
    fun aStartedWriteFinishesAndNotifiesWhenItsCallerIsCancelled() = runBlocking {
        val clock = TestClock()
        val database = DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), clock)
        BudgetSession("demo", "Demo", database, isDemo = true, clock = clock, io = Dispatchers.IO).use { session ->
            val before = session.revision.value
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                session.write {
                    started.complete(Unit)
                    // Hold the IO thread until the caller has been cancelled.
                    runBlocking { release.await() }
                    BudgetWrites(database).assign("2026-10", "dining", 4_321)
                }
            }
            started.await()
            job.cancel()
            release.complete(Unit)
            job.join()
            assertEquals(4_321L, com.sporez.actualist.core.budget.BudgetReads(database).fetchBudgetMonth("2026-10").category("dining")!!.budgeted)
            assertTrue("the session was not told about the change", session.revision.value > before)
        }
    }
}
