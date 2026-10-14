package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PayeeWritesTest {
    @Test
    fun mergeRemapsTransactionsAndRenameWritesOneCell() {
        val clock = TestClock()
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), clock).use { database ->
            val payees = PayeeWrites(database)
            val before = payees.usage().associateBy { it.id }
            val cafe = before.getValue("cafe").transactionCount
            val restaurant = before.getValue("restaurant").transactionCount
            assertTrue(cafe > 0 && restaurant > 0)

            val messages = payees.merge(setOf("cafe"), "restaurant")
            assertEquals(listOf("payee_mapping/cafe/targetId=S:restaurant", "payees/cafe/tombstone=N:1"), messages.map { "${it.dataset}/${it.row}/${it.column}=${it.serializedValue}" })
            val after = payees.usage().associateBy { it.id }
            assertEquals(cafe + restaurant, after.getValue("restaurant").transactionCount)
            assertTrue("cafe" !in after)
            // Transactions keep their stored payee id and read the merged name through payee_mapping.
            val row = LedgerReads(database).transactions(TransactionQuery(limit = 10_000)).first { it.payeeId == "cafe" }
            assertEquals("Bistro Nove", row.payeeName)

            assertEquals(1, payees.rename("restaurant", "Bistro Nove & Cafe").size)
            assertTrue(payees.rename("restaurant", "Bistro Nove & Cafe").isEmpty())
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { payees.merge(setOf("restaurant"), "restaurant") }
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { payees.rename("xfer-checking", "Nope") }
            // A second merge into the first target follows existing mappings too.
            payees.merge(setOf("restaurant"), "grocer")
            assertEquals("grocer", database.sql.queryString("SELECT targetId FROM payee_mapping WHERE id = 'cafe'"))
        }
    }
}
