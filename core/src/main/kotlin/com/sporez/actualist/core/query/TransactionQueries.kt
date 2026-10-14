package com.sporez.actualist.core.query

import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.rules.RuleCondition
import com.sporez.actualist.core.rules.RuleConditionJoin
import java.util.UUID

/**
 * What the UI calls for structured transaction queries, free-text search, split modes and saved
 * filters. [idGenerator] mints the id of a new saved filter (tests inject a fixed one).
 */
class TransactionQueries(
    private val database: BudgetDatabase,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val queryEngine = TransactionQueryEngine(database)
    private val pageEngine = TransactionPageEngine(database, queryEngine)
    private val filterStore = SavedFilterStore(database)

    /**
     * Runs conditions (joined by [join]) and optional free [text] over one account, or every account
     * when [accountId] is null. Without text the result is grouped by split family; with text it is
     * the matching rows with their split families attached. [limit] pages by root.
     */
    fun query(
        accountId: String? = null,
        conditions: List<QueryCondition> = emptyList(),
        join: QueryJoin = QueryJoin.AND,
        text: String? = null,
        limit: Int? = null,
        offset: Int = 0,
        status: StatusFilter = StatusFilter.ALL,
    ): QueryPage = queryEngine.page(
        QueryScope.of(accountId),
        FeedQuery(status = status, text = text, conditionsJoin = join, conditions = conditions),
        limit,
        offset,
    )

    /** Runs a saved filter; null when the filter's conditions are not supported. */
    fun query(filter: SavedFilter, accountId: String? = null, text: String? = null, limit: Int? = null, offset: Int = 0): QueryPage? {
        val feed = filter.feedQuery(text) ?: return null
        return queryEngine.page(QueryScope.of(accountId), feed, limit, offset)
    }

    /**
     * A transaction list page. [splits] null reads like the account screen (structured query read);
     * a mode reads `ALL`, `INLINE`, `NONE` or `GROUPED` rows. [month] is `YYYY-MM`.
     */
    fun page(
        accountId: String? = null,
        matching: String? = null,
        limit: Int? = null,
        offset: Int = 0,
        splits: SplitMode? = null,
        month: String? = null,
        status: StatusFilter = StatusFilter.ALL,
    ): TransactionPage = pageEngine.page(accountId, matching, limit, offset, splits, month, status)

    /** Live saved filters by name; tombstoned rows are left out unless [includeDeleted]. Empty when the budget has none. */
    fun savedFilters(includeDeleted: Boolean = false): List<SavedFilter> =
        when (val result = filterStore.read()) {
            is SavedFilterReadResult.Available -> result.filters.filter { includeDeleted || !it.tombstone }
            is SavedFilterReadResult.Unavailable -> emptyList()
        }

    /** The raw read, telling "no saved filters" from "this budget cannot store them". */
    fun savedFilterRead(): SavedFilterReadResult = filterStore.read()

    /**
     * Saves a filter from stored-form conditions and returns its id. Throws
     * [LocalFirstException.InvalidLocalWrite] with the reason for a blank name, no conditions, a
     * duplicate name ("There is already a saved filter named ...") or duplicate conditions ("These
     * conditions are already saved as ...").
     */
    fun createFilter(name: String, conditions: List<RuleCondition>, join: RuleConditionJoin): String =
        filterStore.create(idGenerator(), name, conditions, join)

    /** Saves a filter built from query conditions; a transfer condition has no stored form and is refused. */
    fun createFilter(name: String, conditions: List<QueryCondition>, join: QueryJoin): String {
        val stored = conditions.map { SavedFilter.ruleCondition(it) ?: throw SavedFilterMutationError.UnsupportedFilter.exception() }
        val storedJoin = if (join == QueryJoin.AND) RuleConditionJoin.AND else RuleConditionJoin.OR
        return createFilter(name, stored, storedJoin)
    }

    /** Renames a live saved filter; throws `InvalidLocalWrite` for a blank or duplicate name or an unsupported filter. */
    fun renameFilter(id: String, name: String) = filterStore.update(id, name, null, null)

    /** Replaces a saved filter's conditions (and join) with [conditions]; a transfer condition is refused. */
    fun updateFilter(id: String, conditions: List<QueryCondition>, join: QueryJoin) {
        val stored = conditions.map { SavedFilter.ruleCondition(it) ?: throw SavedFilterMutationError.UnsupportedFilter.exception() }
        val storedJoin = if (join == QueryJoin.AND) RuleConditionJoin.AND else RuleConditionJoin.OR
        filterStore.update(id, null, stored, storedJoin)
    }

    /** Marks a saved filter deleted. Throws `InvalidLocalWrite` when it is not a live filter. */
    fun deleteFilter(id: String) = filterStore.delete(id)
}
