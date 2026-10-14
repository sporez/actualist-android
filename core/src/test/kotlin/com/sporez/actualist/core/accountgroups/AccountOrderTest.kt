package com.sporez.actualist.core.accountgroups

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountOrderTest {
    private fun database(): BudgetDatabase {
        val database = TestDatabases.starter()
        database.sql.execute(
            "INSERT INTO accounts (id, name, offbudget, closed, tombstone, sort_order) VALUES " +
                "('a', 'A', 0, 0, 0, 16384), ('b', 'B', 0, 0, 0, 32768), ('c', 'C', 0, 0, 0, 49152), " +
                "('loan', 'Loan', 1, 0, 0, 65536)",
        )
        return database
    }

    private fun order(database: BudgetDatabase): List<String> =
        database.sql.query("SELECT id FROM accounts WHERE offbudget = 0 ORDER BY sort_order, id").mapNotNull { it.string("id") }

    @Test
    fun moveDownAndUpSwapNeighbours() {
        database().use { database ->
            val groups = AccountGroups(database, TestClock())
            groups.moveAccountBy("a", 1)
            assertEquals(listOf("b", "a", "c"), order(database))
            groups.moveAccountBy("c", -1)
            assertEquals(listOf("b", "c", "a"), order(database))
        }
    }

    @Test
    fun edgesDoNothing() {
        database().use { database ->
            val groups = AccountGroups(database, TestClock())
            groups.moveAccountBy("a", -1)
            groups.moveAccountBy("c", 1)
            groups.moveAccountBy("loan", 1)
            assertEquals(listOf("a", "b", "c"), order(database))
        }
    }

    @Test
    fun moveToEndAndIntoGroup() {
        database().use { database ->
            val ids = listOf("g1").iterator()
            val groups = AccountGroups(database, TestClock()) { ids.next() }
            groups.create("Cash")
            groups.reorderAccount("a", null, setGroup = true, groupId = "g1")
            assertEquals(listOf("b", "c", "a"), order(database))
            assertEquals("g1", groups.accountGroupIds()["a"])
            groups.reorderAccount("a", null, setGroup = true, groupId = null)
            assertEquals(null, groups.accountGroupIds()["a"])
        }
    }
}
