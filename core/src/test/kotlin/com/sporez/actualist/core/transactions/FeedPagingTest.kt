package com.sporez.actualist.core.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPagingTest {
    @Test
    fun windowReportsWhetherOlderRowsRemain() {
        val rows = (1..5).toList()
        val first = FeedPaging.window(rows, 3)
        assertEquals(listOf(1, 2, 3), first.items)
        assertTrue(first.hasMore)
        val all = FeedPaging.window(rows, 5)
        assertEquals(rows, all.items)
        assertFalse(all.hasMore)
        assertEquals(1, FeedPaging.window(rows, 0).items.size)
    }

    @Test
    fun growthAddsOnePage() {
        assertEquals(1000, FeedPaging.grown(500, 500))
        assertEquals(4, FeedPaging.grown(3, 0))
    }

    private fun row(id: String, payee: String?) = TransactionRow(
        id, "a", "Account", null, -100, payee, null, null, null, null, false, false, false, false, null, null, null, false,
    )

    @Test
    fun recentPayeesAreDistinctNewestFirst() {
        val rows = listOf(row("1", "b"), row("2", null), row("3", "a"), row("4", "b"), row("5", "c"))
        assertEquals(listOf("b", "a", "c"), PayeeRecents.ids(rows))
        assertEquals(listOf("b", "a"), PayeeRecents.ids(rows, 2))
    }
}
