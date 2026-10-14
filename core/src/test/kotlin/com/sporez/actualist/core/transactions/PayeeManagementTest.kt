package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PayeeManagementTest {
    private val day = LocalDate.of(2026, 10, 15)

    @Test
    fun overviewCountsUsageAndRuleReferencesAndListsTransferPayeesLast() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, TestClock()).use { database ->
            val overview = PayeeWrites(database).overview()
            val cafe = overview.payees.first { it.id == "cafe" }
            assertTrue(cafe.transactionCount > 0)
            assertTrue(cafe.ruleCount >= 1)
            assertFalse(overview.canDelete(cafe))
            val transfers = overview.payees.filter { it.isTransfer }
            assertTrue(transfers.isNotEmpty())
            assertTrue(transfers.all { it.name.isNotEmpty() && !overview.canDelete(it) })
            assertEquals(overview.payees.indexOfFirst { it.isTransfer }, overview.payees.size - transfers.size)
        }
    }

    @Test
    fun createThenDeleteAnUnusedPayee() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, TestClock()).use { database ->
            val payees = PayeeWrites(database)
            val (id, messages) = payees.create("  Synthetic Corner Shop ") { "new-payee" }
            assertEquals("new-payee", id)
            assertEquals(
                setOf("payees/new-payee/name=S:Synthetic Corner Shop", "payee_mapping/new-payee/targetId=S:new-payee"),
                messages.map { "${it.dataset}/${it.row}/${it.column}=${it.serializedValue}" }.toSet(),
            )
            val created = payees.overview().payees.first { it.id == id }
            assertTrue(created.isUnused && created.learnsCategories && !created.favorite)
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { payees.create("synthetic corner shop") }

            assertTrue(payees.overview().canDelete(created))
            assertEquals(1, payees.delete(id).size)
            assertTrue(payees.overview().payees.none { it.id == id })
        }
    }

    @Test
    fun deleteRefusesUsedReferencedAndTransferPayees() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, TestClock()).use { database ->
            val payees = PayeeWrites(database)
            // In use and referenced by a demo rule.
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { payees.delete("cafe") }
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { payees.delete("xfer-checking") }
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { payees.setFavorite("xfer-checking", true) }
        }
    }

    @Test
    fun favoriteTogglesOneCell() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, TestClock()).use { database ->
            val payees = PayeeWrites(database)
            assertEquals(1, payees.setFavorite("salon", true).size)
            assertTrue(payees.overview().payees.first { it.id == "salon" }.favorite)
            payees.setFavorite("salon", false)
            assertFalse(payees.overview().payees.first { it.id == "salon" }.favorite)
        }
    }
}
