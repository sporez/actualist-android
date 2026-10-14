package com.sporez.actualist.core.accountgroups

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AccountGroupsTest {
    private fun starterWithAccounts(): BudgetDatabase {
        val database = TestDatabases.starter()
        database.sql.execute(
            "INSERT INTO accounts (id, name, offbudget, closed, tombstone, sort_order) VALUES " +
                "('checking', 'Checking', 0, 0, 0, 1), ('savings', 'Savings', 0, 0, 0, 2)",
        )
        return database
    }

    private fun groupsOf(database: BudgetDatabase, ids: Iterator<String>): AccountGroups =
        AccountGroups(database, TestClock()) { ids.next() }

    @Test
    fun createAppendsSortOrderAndRejectsDuplicates() {
        starterWithAccounts().use { database ->
            val groups = groupsOf(database, listOf("cash", "cash-2").iterator())
            groups.create("Cash")
            val listed = groups.groups()
            assertEquals(listOf("cash"), listed.map { it.id })
            assertEquals("Cash", listed.first().name)
            assertEquals(16_384.0, listed.first().sortOrder, 0.0)

            val error = assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { groups.create("cash") }
            assertEquals("An 'Cash' account group already exists.", error.reason)
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { groups.create("   ") }
        }
    }

    @Test
    fun createReusesATombstonedNameWithANewId() {
        starterWithAccounts().use { database ->
            val groups = groupsOf(database, listOf("cash", "cash-new").iterator())
            groups.create("Cash")
            groups.delete("cash")
            groups.create("Cash")
            assertEquals(listOf("cash-new"), groups.groups().map { it.id })
        }
    }

    @Test
    fun renameMoveUngroupAndDeleteWritePerColumnMessages() {
        starterWithAccounts().use { database ->
            val groups = groupsOf(database, listOf("cash", "credit").iterator())
            groups.create("Cash")
            groups.create("Credit")
            groups.moveAccount("checking", "cash")
            groups.moveAccount("savings", "cash")

            val before = database.messageCount()
            groups.rename("cash", "Ready Cash")
            assertEquals(1L, database.messageCount() - before)
            // Renaming to the current name writes nothing.
            groups.rename("cash", "Ready Cash")
            assertEquals(1L, database.messageCount() - before)

            groups.moveAccount("checking", "credit")
            assertEquals("credit", groups.accountGroupIds()["checking"])
            groups.moveAccount("checking", null)
            assertNull(groups.accountGroupIds()["checking"])
            groups.moveAccount("savings", "credit")
            groups.delete("credit")

            assertEquals(listOf("cash"), groups.groups().map { it.id })
            assertEquals("Ready Cash", groups.groups().first().name)
            assertNull(groups.accountGroupIds()["checking"])
            assertNull(groups.accountGroupIds()["savings"])
        }
    }

    @Test
    fun movingAnAccountToTheGroupItAlreadyHasWritesNothing() {
        starterWithAccounts().use { database ->
            val groups = groupsOf(database, listOf("cash").iterator())
            groups.create("Cash")
            groups.moveAccount("checking", "cash")
            val before = database.messageCount()
            groups.moveAccount("checking", "cash")
            groups.moveAccount("savings", null)
            assertEquals(before, database.messageCount())
        }
    }

    @Test
    fun invalidInputIsRefused() {
        starterWithAccounts().use { database ->
            val groups = groupsOf(database, listOf("a", "b").iterator())
            groups.create("A")
            groups.create("B")
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { groups.rename("a", "b") }
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { groups.rename("nope", "X") }
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { groups.delete("nope") }
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { groups.moveAccount("missing", "a") }
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { groups.moveAccount("checking", "nope") }
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { groups.moveGroup("a", "nope") }
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { groups.moveGroup("nope", null) }
        }
    }

    @Test
    fun reorderUsesMidpointSortOrders() {
        starterWithAccounts().use { database ->
            val groups = groupsOf(database, listOf("a", "b", "c").iterator())
            groups.create("A")
            groups.create("B")
            groups.create("C")
            groups.moveGroup("c", "a")
            assertEquals(listOf("c", "a", "b"), groups.groups().map { it.id })
            assertEquals(8_192.0, groups.groups().first().sortOrder, 0.0)
            // Moving to the end appends after the last group.
            groups.moveGroup("c", null)
            assertEquals(listOf("a", "b", "c"), groups.groups().map { it.id })
        }
    }

    @Test
    fun compatibilityBackfillsStoredCrdtOnceAndKeepsTheNewestMembership() {
        val file = TestDatabases.tempFile()
        TestDatabases.starter(file = file).use { database ->
            val sql = database.sql
            sql.execute("DROP TABLE account_groups")
            sql.execute("ALTER TABLE accounts DROP COLUMN account_group_id")
            sql.execute(
                "INSERT INTO accounts (id, name, offbudget, closed, tombstone, sort_order) VALUES " +
                    "('checking', 'Checking', 0, 0, 0, 1), ('savings', 'Savings', 0, 0, 0, 2)",
            )
            val rows = listOf(
                Triple("2026-07-01T12:00:00.000Z-0000-remote", "account_groups" to "cash", "name" to "S:Cash"),
                Triple("2026-07-01T12:00:00.001Z-0000-remote", "account_groups" to "cash", "sort_order" to "N:16384"),
                Triple("2026-07-01T12:00:00.002Z-0000-remote", "account_groups" to "credit", "name" to "S:Credit"),
                Triple("2026-07-01T12:00:01.000Z-0000-remote", "accounts" to "checking", "account_group_id" to "S:cash"),
                Triple("2026-07-01T12:00:02.000Z-0000-remote", "accounts" to "checking", "account_group_id" to "S:credit"),
                Triple("2026-07-01T12:00:03.000Z-0000-remote", "accounts" to "ghost", "account_group_id" to "S:cash"),
            )
            for ((timestamp, target, cell) in rows) {
                sql.execute(
                    "INSERT INTO messages_crdt (timestamp, dataset, \"row\", \"column\", value) VALUES (?, ?, ?, ?, ?)",
                    timestamp, target.first, target.second, cell.first, cell.second,
                )
            }
            database.invalidateSchemaCaches()

            AccountGroupCompatibility.prepare(database)
            val groups = AccountGroups(database)
            assertEquals(listOf("cash", "credit"), groups.groups().map { it.id }.sorted())
            assertEquals("Cash", groups.groups().first { it.id == "cash" }.name)
            assertEquals("credit", groups.accountGroupIds()["checking"])
            assertNull(groups.accountGroupIds()["savings"])
            assertTrue("ghost" !in groups.accountGroupIds())

            // A second pass must not replay the history over later physical edits.
            database.sql.execute("UPDATE accounts SET account_group_id = NULL WHERE id = 'checking'")
            AccountGroupCompatibility.prepare(database)
            assertNull(groups.accountGroupIds()["checking"])
        }
    }

    @Test
    fun schemaIsStillEnsuredAfterTheReplayMigrationIsRecorded() {
        TestDatabases.starter().use { database ->
            database.sql.execute("ALTER TABLE accounts DROP COLUMN account_group_id")
            database.recordLocalMigration(AccountGroupCompatibility.MIGRATION)
            database.invalidateSchemaCaches()
            AccountGroupCompatibility.prepare(database)
            assertTrue("account_group_id" in database.columnSet("accounts"))
        }
    }

    @Test
    fun roundTripOnTheDemoBudget() {
        val clock = TestClock()
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), clock).use { database ->
            AccountGroupCompatibility.prepare(database)
            var n = 0
            val groups = AccountGroups(database, clock) { "group-${++n}" }
            val everyday = groups.create("Everyday")
            val debt = groups.create("Debt")
            groups.moveAccount("checking", everyday)
            groups.moveAccount("savings", everyday)
            groups.moveAccount("credit", debt)
            assertEquals(mapOf("checking" to everyday, "savings" to everyday, "credit" to debt), groups.accountGroupIds())
            groups.moveGroup(debt, everyday)
            assertEquals(listOf("Debt", "Everyday"), groups.groups().map { it.name })
            groups.delete(everyday)
            assertEquals(listOf("Debt"), groups.groups().map { it.name })
            assertEquals(mapOf("checking" to null, "savings" to null, "credit" to debt), groups.accountGroupIds())
            assertTrue(database.pendingLocalMessageCount() > 0)
        }
    }
}
