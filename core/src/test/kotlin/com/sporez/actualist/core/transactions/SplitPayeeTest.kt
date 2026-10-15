package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.sync.SyncValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SplitPayeeTest {
    private val clock = TestClock()
    private val day = LocalDate.of(2026, 10, 14)

    private fun demo() = DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), clock)

    private fun family(database: com.sporez.actualist.core.db.BudgetDatabase, id: String): TransactionRow =
        LedgerReads(database).transactions(TransactionQuery(accountId = "checking", limit = 10_000)).first { it.id == id }

    @Test
    fun eachLineKeepsItsOwnPayeeNotesAndSign() {
        demo().use { database ->
            val writes = TransactionWrites(database, clock)
            val created = writes.create(
                TransactionDraft(
                    accountId = "checking", date = day, amount = -5_000, payeeName = "Synthetic Warehouse",
                    splits = listOf(
                        SplitDraft("groceries", -7_000, notes = "bulk", payeeName = "Synthetic Farm Stand"),
                        SplitDraft("dining", 2_000, notes = "refund"),
                    ),
                ),
            )
            val parent = family(database, created.transactionId)
            assertEquals(2, parent.children.size)
            val outflow = parent.children.first { it.amount == -7_000L }
            val inflow = parent.children.first { it.amount == 2_000L }
            assertEquals("Synthetic Farm Stand", outflow.payeeName)
            assertEquals("bulk", outflow.notes)
            assertEquals("refund", inflow.notes)
            // A line with no payee inherits the one the transaction was created with.
            assertEquals("Synthetic Warehouse", inflow.payeeName)

            // Editing without a payee keeps the line's payee; naming one changes only that line.
            writes.updateSplit(
                created.transactionId,
                SplitFamilyEdit(
                    amount = -5_000,
                    splits = listOf(
                        SplitEdit(outflow.id, "groceries", -7_000, notes = "bulk"),
                        SplitEdit(inflow.id, "dining", 2_000, notes = "refund", payeeName = "Synthetic Cafe"),
                    ),
                ),
            )
            val after = family(database, created.transactionId)
            assertEquals("Synthetic Farm Stand", after.children.first { it.id == outflow.id }.payeeName)
            assertEquals("Synthetic Cafe", after.children.first { it.id == inflow.id }.payeeName)
        }
    }

    @Test
    fun aTransferLineGetsAPairedRowInTheOtherAccount() {
        demo().use { database ->
            val writes = TransactionWrites(database, clock)
            val created = writes.create(
                TransactionDraft(
                    accountId = "checking", date = day, amount = -3_000, payeeName = "Synthetic Warehouse",
                    splits = listOf(SplitDraft("groceries", -1_000), SplitDraft(null, -2_000, payeeId = "xfer-savings")),
                ),
            )
            val parent = family(database, created.transactionId)
            val transferChild = parent.children.first { it.amount == -2_000L }
            assertNotNull(transferChild.transferId)
            assertNull(parent.children.first { it.amount == -1_000L }.transferId)
            val paired = database.sql.queryLong(
                "SELECT amount FROM transactions WHERE id = ? AND acct = 'savings'",
                transferChild.transferId,
            )
            assertEquals(2_000L, paired)
        }
    }

    @Test
    fun theBankReportedBalanceIsReadForReconciliation() {
        demo().use { database ->
            val reconciliation = Reconciliation(database, clock)
            assertNull(reconciliation.bankBalance("checking"))
            database.commitLocal { listOf(CellDraft("accounts", "checking", "balance_current", SyncValue.int(123_456))) to Unit }
            assertEquals(123_456L, reconciliation.bankBalance("checking"))
            assertTrue(reconciliation.bankBalance("savings") == null)
        }
    }
}
