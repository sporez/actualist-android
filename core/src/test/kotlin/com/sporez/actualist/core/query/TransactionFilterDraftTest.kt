package com.sporez.actualist.core.query

import com.sporez.actualist.core.TestDatabases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransactionFilterDraftTest {
    @Test
    fun buildsOneOfConditionsAndSkipsEmptyFields() {
        val draft = TransactionFilterDraft(
            join = QueryJoin.OR,
            payee = TransactionFilterIdDraft(QueryIdOperation.IS_ONE_OF, setOf("b", "a")),
        )
        assertEquals(listOf<QueryCondition>(QueryCondition.Payee(QueryIdCondition.oneOf(listOf("a", "b")))), draft.conditions())
    }

    @Test
    fun noPayeeNeedsAScalarOperation() {
        val bad = TransactionFilterDraft(payee = TransactionFilterIdDraft(QueryIdOperation.IS_ONE_OF, setOf("a"), true))
        try {
            bad.conditions()
            fail("expected a refusal")
        } catch (error: TransactionFilterDraftException) {
            assertEquals("Use “Is” or “Is not” to filter transactions with no payee.", error.message)
        }
        val good = TransactionFilterDraft(category = TransactionFilterIdDraft(QueryIdOperation.IS_EQUAL, emptySet(), true))
        assertEquals(listOf<QueryCondition>(QueryCondition.Category(QueryIdCondition.equalTo(null))), good.conditions())
    }

    @Test
    fun scalarOperationRefusesTwoChoices() {
        val draft = TransactionFilterDraft(account = TransactionFilterIdDraft(QueryIdOperation.IS_EQUAL, setOf("a", "b")))
        try {
            draft.conditions()
            fail("expected a refusal")
        } catch (error: TransactionFilterDraftException) {
            assertEquals("Choose one account for this condition.", error.message)
        }
    }

    @Test
    fun unauthorableConditionsArePreservedAndRoundTrip() {
        val day = QueryDay.of("2026-10-01")!!
        val conditions = listOf<QueryCondition>(
            QueryCondition.Date(QueryDateCondition(QueryDateOperation.IS_AFTER, day)),
            QueryCondition.Date(QueryDateCondition(QueryDateOperation.IS_BEFORE, QueryDay.of("2026-10-20")!!)),
            QueryCondition.Account(QueryIdCondition.oneOf(listOf("x", null))),
            QueryCondition.Transfer(true),
            QueryCondition.Payee(QueryIdCondition.notOneOf(listOf("p1", "p2"))),
        )
        val draft = TransactionFilterDraft.from(conditions, QueryJoin.AND)
        assertEquals(3, draft.preserved.size)
        assertEquals(QueryIdOperation.IS_NOT_ONE_OF, draft.payee.operation)
        assertEquals(conditions.map { it.canonicalKey }.sorted(), draft.conditions().map { it.canonicalKey }.sorted())
        assertTrue(draft.reset().conditions().isEmpty())
    }

    @Test
    fun savedFilterLifecycleRenameUpdateDelete() = TestDatabases.starter().use { database ->
        var counter = 0
        val queries = TransactionQueries(database) { "filter-${++counter}" }
        val id = queries.createFilter(
            "Groceries",
            listOf<QueryCondition>(QueryCondition.Category(QueryIdCondition.equalTo("groceries"))),
            QueryJoin.AND,
        )
        queries.renameFilter(id, "  Food ")
        assertEquals("Food", queries.savedFilters().single().name)

        val other = queries.createFilter(
            "Dining",
            listOf<QueryCondition>(QueryCondition.Category(QueryIdCondition.equalTo("dining"))),
            QueryJoin.AND,
        )
        try {
            queries.renameFilter(other, "Food")
            fail("expected a duplicate-name refusal")
        } catch (error: com.sporez.actualist.core.LocalFirstException.InvalidLocalWrite) {
            assertTrue(error.message!!.contains("There is already a saved filter named Food"))
        }

        val updated = listOf<QueryCondition>(
            QueryCondition.Payee(QueryIdCondition.oneOf(listOf("p1", "p2"))),
        )
        queries.updateFilter(id, updated, QueryJoin.OR)
        val stored = queries.savedFilters().first { it.id == id }
        assertEquals("or", stored.conditionsOperation)
        assertTrue(stored.isSupported)
        assertEquals(updated.map { it.canonicalKey }, stored.queryConditions!!.map { it.canonicalKey })

        queries.deleteFilter(id)
        assertFalse(queries.savedFilters().any { it.id == id })
    }
}
