package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Reconciliation against the cases of Actualist's pinned Reconciliation oracle
 * (`reconciliation-contract.json`, Actual v26.10.0): cleared balance, lock, unlock of
 * simple rows, split parents and split children. Seeds are the oracle's scripted rows.
 */
class ReconciliationTest {
    private val clock = TestClock()
    private val today = LocalDate.of(2026, 10, 15)

    private fun seeded(block: (BudgetDatabase, Reconciliation) -> Unit) {
        TestDatabases.starter(clock = clock).use { database ->
            database.sql.executeScript(
                """
                INSERT INTO accounts (id, name, offbudget, closed, tombstone, sort_order) VALUES ('checking', 'Checking', 0, 0, 0, 1), ('savings', 'Savings', 0, 0, 0, 2);
                """.trimIndent(),
            )
            block(database, Reconciliation(database, clock) { "generated" })
        }
    }

    private fun BudgetDatabase.row(id: String, account: String, amount: Long, cleared: Boolean, reconciled: Boolean = false, parent: String? = null, isParent: Boolean = false) {
        sql.execute(
            "INSERT INTO transactions (id, acct, date, amount, cleared, reconciled, isParent, isChild, parent_id, tombstone, sort_order) VALUES (?, ?, 20260914, ?, ?, ?, ?, ?, ?, 0, 0)",
            id, account, amount, cleared, reconciled, isParent, parent != null, parent,
        )
    }

    private fun BudgetDatabase.family(reconciledParent: Boolean, reconciledChildren: Boolean) {
        row("parent", "checking", -5000, cleared = true, reconciled = reconciledParent, isParent = true)
        row("child-a", "checking", -2000, cleared = true, reconciled = reconciledChildren, parent = "parent")
        row("child-b", "checking", -3000, cleared = true, reconciled = reconciledChildren, parent = "parent")
    }

    private fun reconciled(database: BudgetDatabase) =
        database.sql.query("SELECT id, reconciled FROM transactions ORDER BY id").associate { it.string("id")!! to it.bool("reconciled") }

    @Test
    fun clearedBalanceSumsClearedRowsOfTheAccountOnly() = seeded { db, reconciliation ->
        db.row("cleared-1", "checking", -10000, cleared = true)
        db.row("cleared-2", "checking", 1700, cleared = true)
        db.row("uncleared", "checking", 500, cleared = false)
        db.row("other-account", "savings", 900, cleared = true)
        assertEquals(-8300L, reconciliation.snapshot("checking").clearedBalance)
        assertEquals(1, reconciliation.snapshot("checking").unclearedCount)
    }

    @Test
    fun adjustmentClosesTheGapAndFinishLocksOnlyAtZeroDifference() = seeded { db, reconciliation ->
        db.row("cleared-1", "checking", 3500, cleared = true)
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { reconciliation.finish("checking", 4000) }
        assertEquals(false, reconciled(db)["cleared-1"])
        val adjustment = reconciliation.createAdjustment("checking", 4000, today)
        assertTrue(adjustment.any { it.column == "notes" && it.serializedValue == "S:Reconciliation balance adjustment" })
        assertTrue(adjustment.any { it.column == "amount" && it.serializedValue == "N:500" })
        assertEquals(4000L, reconciliation.snapshot("checking").clearedBalance)
        assertTrue(reconciliation.createAdjustment("checking", 4000, today).isEmpty())
        reconciliation.finish("checking", 4000)
        assertEquals(mapOf("cleared-1" to true, "generated" to true), reconciled(db))
        assertEquals(clock.millis, reconciliation.snapshot("checking").lastReconciled)
    }

    @Test
    fun lockIncludesSplitParentsAndChildren() = seeded { db, reconciliation ->
        db.row("simple", "checking", -2500, cleared = true)
        db.family(reconciledParent = false, reconciledChildren = false)
        val locked = reconciliation.finishDrafts("checking", -7500).filter { it.dataset == "transactions" }.map { it.row }
        assertEquals(listOf("child-a", "child-b", "parent", "simple"), locked)
    }

    @Test
    fun unlockFollowsActualForSimpleRowsParentsAndChildren() = seeded { db, reconciliation ->
        db.row("simple", "checking", -2500, cleared = true, reconciled = true)
        assertEquals(listOf("simple"), reconciliation.unlockDrafts("simple").map { it.row })
        db.family(reconciledParent = true, reconciledChildren = true)
        // A child of a locked parent: Actual's unlock sends nothing.
        assertTrue(reconciliation.unlockDrafts("child-a").isEmpty())
        assertEquals(setOf("parent", "child-a", "child-b"), reconciliation.unlockDrafts("parent").map { it.row }.toSet())
    }

    @Test
    fun unlockingAChildOfAnUnlockedParentUnlocksTheChildren() = seeded { db, reconciliation ->
        db.family(reconciledParent = false, reconciledChildren = true)
        assertEquals(setOf("child-a", "child-b"), reconciliation.unlockDrafts("child-a").map { it.row }.toSet())
        reconciliation.unlock("child-a")
        assertEquals(false, reconciled(db)["child-b"])
    }

    @Test
    fun reconciledRowsCannotBeEditedOrUncleared() = seeded { db, reconciliation ->
        db.row("simple", "checking", -2500, cleared = true, reconciled = true)
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { reconciliation.setCleared("simple", false) }
        val writes = TransactionWrites(db, clock)
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { writes.update("simple", TransactionEdit(amount = -1)) }
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { writes.delete("simple") }
    }

    @Test
    fun theAdjustmentShowsInTheDemoAccountList() {
        val day = LocalDate.of(2026, 10, 15)
        com.sporez.actualist.core.demo.DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, clock).use { db ->
            val reconciliation = Reconciliation(db, clock)
            val cleared = reconciliation.snapshot("savings").clearedBalance
            reconciliation.createAdjustment("savings", cleared + 728_955, day)
            reconciliation.finish("savings", cleared + 728_955)
            val rows = LedgerReads(db).transactions(TransactionQuery(accountId = "savings", limit = 500))
            val adjustment = rows.firstOrNull { it.notes == Reconciliation.ADJUSTMENT_NOTE }
            assertTrue("adjustment missing from ${rows.take(3)}", adjustment != null)
            assertEquals(728_955L, adjustment!!.amount)
            assertEquals(adjustment.id, rows.first().id)
        }
    }
}
