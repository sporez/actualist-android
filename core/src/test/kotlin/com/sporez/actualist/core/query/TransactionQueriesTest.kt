package com.sporez.actualist.core.query

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.rules.RuleCondition
import com.sporez.actualist.core.rules.RuleConditionJoin
import com.sporez.actualist.core.rules.RuleValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransactionQueriesTest {
    @Test
    fun queryDayValidatesCalendarDays() {
        assertEquals("2026-02-28", QueryDay.of(" 2026-02-28 ")?.raw)
        assertNull(QueryDay.of("2026-02-29"))
        assertEquals("2028-02-29", QueryDay.of("2028-02-29")?.raw)
        assertNull(QueryDay.of("2026-13-01"))
        assertNull(QueryDay.of("2026-1-01"))
        assertNull(QueryDay.of("2026-09"))
        assertNull(QueryDay.of("0000-01-01"))
    }

    @Test
    fun idConditionsNormalizeValues() {
        assertEquals(listOf<String?>(null, "a", "b"), QueryIdCondition.oneOf(listOf("b", " a ", null, "a", "")).values)
        assertEquals(emptyList<String?>(), QueryIdCondition.equalTo("  ").values)
        assertEquals(listOf<String?>(null), QueryIdCondition.equalTo(null).values)
    }

    @Test
    fun feedQueryCanonicalizesConditionsAndText() {
        val first = QueryCondition.Account(QueryIdCondition.equalTo("b"))
        val second = QueryCondition.Account(QueryIdCondition.equalTo("a"))
        val one = FeedQuery(text = "  needle ", conditions = listOf(first, second, first))
        val two = FeedQuery(text = "needle", conditions = listOf(second, first))
        assertEquals(one, two)
        assertEquals("needle", one.text)
        assertEquals(2, one.conditions.size)
        assertNull(FeedQuery(text = "   ").text)
    }

    @Test
    fun standardCompareOrdersNumbersByValue() {
        val sorted = listOf("Filter 10", "filter 2", "Filter 1").sortedWith(Comparator<String> { a, b -> StandardCompare.compare(a, b) })
        assertEquals(listOf("Filter 1", "filter 2", "Filter 10"), sorted)
    }

    @Test
    fun savedFiltersCreateListAndDelete() {
        TestDatabases.starter().use { database ->
            var counter = 0
            val queries = TransactionQueries(database) { "filter-${++counter}" }
            val conditions = listOf(RuleCondition("account", "is", RuleValue.Text("acct-a"), "id"))

            val id = queries.createFilter("  Checking only ", conditions, RuleConditionJoin.AND)
            assertEquals("filter-1", id)
            val saved = queries.savedFilters()
            assertEquals(1, saved.size)
            assertEquals("Checking only", saved[0].name)
            assertTrue(saved[0].isSupported)
            assertEquals(QueryJoin.AND, saved[0].queryJoin)
            assertEquals(
                listOf<QueryCondition>(QueryCondition.Account(QueryIdCondition.equalTo("acct-a"))),
                saved[0].queryConditions,
            )

            assertRefused(queries, "Checking only", conditions.map { it.copy(value = RuleValue.Text("other")) }, "already a saved filter named")
            assertRefused(queries, "Another name", conditions, "already saved as")

            queries.deleteFilter(id)
            assertTrue(queries.savedFilters().isEmpty())
            assertEquals(1, queries.savedFilters(includeDeleted = true).size)
            // A deleted filter no longer blocks the same name or conditions.
            queries.createFilter("Another name", conditions, RuleConditionJoin.AND)
            try {
                queries.deleteFilter(id)
                fail("deleting a deleted filter should be refused")
            } catch (error: LocalFirstException.InvalidLocalWrite) {
                assertTrue(error.reason.contains("no longer available"))
            }
        }
    }

    @Test
    fun queryConditionsConvertToStoredFormAndBack() {
        TestDatabases.starter().use { database ->
            val queries = TransactionQueries(database) { "filter-1" }
            val day = QueryDay.of("2026-08-15")!!
            val query = listOf(
                QueryCondition.Date(QueryDateCondition(QueryDateOperation.IS_ON_OR_AFTER, day)),
                QueryCondition.Payee(QueryIdCondition.oneOf(listOf("p2", "p1", null))),
            )
            queries.createFilter("Recent", query, QueryJoin.OR)
            val saved = queries.savedFilters().single()
            assertTrue(saved.isSupported)
            assertEquals(QueryJoin.OR, saved.queryJoin)
            assertEquals(FeedQuery(conditionsJoin = QueryJoin.OR, conditions = query), saved.feedQuery())
            try {
                queries.createFilter("Transfers", listOf<QueryCondition>(QueryCondition.Transfer(true)), QueryJoin.AND)
                fail("a transfer condition has no stored form")
            } catch (error: LocalFirstException.InvalidLocalWrite) {
                assertFalse(error.reason.isEmpty())
            }
        }
    }

    @Test
    fun emptyBudgetQueriesReturnEmptyPages() {
        TestDatabases.starter().use { database ->
            val queries = TransactionQueries(database)
            val page = queries.query(conditions = listOf(QueryCondition.Transfer(false)), text = "x", limit = 5)
            assertTrue(page.transactions.isEmpty())
            assertTrue(page.reachedEnd)
            assertEquals(0, page.totalMatchCount)
            assertTrue(queries.page(splits = SplitMode.GROUPED).transactions.isEmpty())
        }
    }

    private fun assertRefused(queries: TransactionQueries, name: String, conditions: List<RuleCondition>, reason: String) {
        try {
            queries.createFilter(name, conditions, RuleConditionJoin.AND)
            fail("expected a refusal containing: $reason")
        } catch (error: LocalFirstException.InvalidLocalWrite) {
            assertTrue(error.reason, error.reason.contains(reason))
        }
    }
}
