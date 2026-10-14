package com.sporez.actualist.core.query

import com.sporez.actualist.core.db.BudgetDatabase
import java.time.LocalDate

private class CompiledQuery(
    val split: QuerySplit,
    val joins: ReadJoins,
    val conditions: List<String>,
    val arguments: List<Any?>,
    val usesGroupedParentSelection: Boolean,
) {
    val whereSql: String get() = conditions.joinToString(" AND ")
}

private class QuerySelection(val page: QueryPage)

/**
 * Structured transaction queries over the split-aware `transactions` reads, ported from Actualist
 * `BudgetDatabase+TransactionQuery`. Without free text the result is grouped by split family (a
 * match on any child selects the whole family); with free text it is the flat matching rows with
 * their split families attached.
 */
internal class TransactionQueryEngine(private val database: BudgetDatabase) {
    private val reads = QueryReads(database)
    private val sql get() = database.sql

    fun page(scope: QueryScope, query: FeedQuery, limit: Int? = null, offset: Int = 0): QueryPage = sql.locked {
        selection(scope, query, limit, offset).page
    }

    private fun selection(scope: QueryScope, query: FeedQuery, limit: Int?, offset: Int): QuerySelection {
        if (!database.tableExists("transactions")) {
            return QuerySelection(emptyPage(query, max(0, offset), 0, reachedEnd = true))
        }
        val compiled = compile(scope, query)
        return if (query.text == null) {
            groupedSelection(query, compiled, limit, offset)
        } else {
            flatSelection(query, compiled, limit, offset)
        }
    }

    private fun max(left: Int, right: Int) = if (left >= right) left else right

    private fun compile(scope: QueryScope, query: FeedQuery): CompiledQuery {
        val columns = database.columnSet("transactions")
        val split = QuerySplit(columns)
        var joins = reads.readJoins(split, columns, includeNames = true)
        var conditions = mutableListOf(split.liveEffectivePredicate())
        var arguments = mutableListOf<Any?>()

        if (scope is QueryScope.Account) {
            conditions.add("${split.account} = ?")
            arguments.add(scope.accountId)
        }

        if (query.status == StatusFilter.UNCATEGORIZED) {
            val context = reads.uncategorizedContext()
            joins = context.joins
            conditions = context.conditions.toMutableList()
            if (scope is QueryScope.Account) {
                conditions.add("${split.account} = ?")
                arguments = mutableListOf(scope.accountId)
            }
            conditions.add(split.splitModePredicate(SplitMode.INLINE))
        } else {
            reads.statusFilterPredicate(query.status, split)?.let { conditions.add(it) }
        }

        if (query.conditions.isNotEmpty()) {
            val conditionArguments = mutableListOf<Any?>()
            val predicates = query.conditions.map { structuredPredicate(it, split, joins, conditionArguments) }
            val join = if (query.conditionsJoin == QueryJoin.AND) " AND " else " OR "
            conditions.add("(${predicates.joinToString(join)})")
            arguments.addAll(conditionArguments)
        }

        val text = query.text
        if (text != null) {
            conditions.add(reads.searchPredicate(split, joins))
            val like = "%${reads.escapeLikePattern(text)}%"
            repeat(4) { arguments.add(like) }
        }

        val hasOnlyParentSafeConditions = query.conditions.all { condition ->
            when (condition) {
                is QueryCondition.Date, is QueryCondition.Account -> true
                is QueryCondition.Payee, is QueryCondition.Category, is QueryCondition.Transfer -> false
            }
        }
        val usesGroupedParentSelection =
            text == null && query.status == StatusFilter.ALL && hasOnlyParentSafeConditions
        return CompiledQuery(split, joins, conditions, arguments, usesGroupedParentSelection)
    }

    private fun groupedSelection(query: FeedQuery, compiled: CompiledQuery, limit: Int?, offset: Int): QuerySelection {
        val rowOffset = max(0, offset)
        val rowLimit = limit?.let { max(1, it) }
        val split = compiled.split
        val totalMatchCount: Int
        val fetchedGroupIds: List<String>

        if (compiled.usesGroupedParentSelection) {
            val rootWhere = (compiled.conditions + split.splitModePredicate(SplitMode.NONE)).joinToString(" AND ")
            totalMatchCount = (sql.queryLong(
                """
                SELECT COUNT(*)
                FROM transactions t
                ${compiled.joins.sql}
                ${split.parentJoin()}
                WHERE $rootWhere
                """.trimIndent(),
                *compiled.arguments.toTypedArray(),
            ) ?: 0L).toInt()
            val pageArguments = compiled.arguments.toMutableList()
            val limitSql = reads.limitSql(rowLimit, rowOffset, pageArguments)
            fetchedGroupIds = sql.query(
                """
                SELECT t.id
                FROM transactions t
                ${compiled.joins.sql}
                ${split.parentJoin()}
                WHERE $rootWhere
                ORDER BY ${split.defaultOrder()}
                $limitSql
                """.trimIndent(),
                *pageArguments.toTypedArray(),
            ).mapNotNull { it.string("id") }
        } else {
            val matchedRows = matchedRowsSql(compiled)
            totalMatchCount = (sql.queryLong(
                "SELECT COUNT(DISTINCT group_id) FROM ($matchedRows) matched",
                *compiled.arguments.toTypedArray(),
            ) ?: 0L).toInt()
            val pageArguments = compiled.arguments.toMutableList()
            val limitSql = reads.limitSql(rowLimit, rowOffset, pageArguments)
            fetchedGroupIds = sql.query(
                """
                SELECT family_groups.group_id AS group_id
                FROM (SELECT DISTINCT group_id FROM ($matchedRows) matched) family_groups
                JOIN transactions t ON t.id = family_groups.group_id
                ORDER BY ${split.defaultOrder()}
                $limitSql
                """.trimIndent(),
                *pageArguments.toTypedArray(),
            ).mapNotNull { it.string("group_id") }
        }

        val reachedEnd = rowLimit?.let { fetchedGroupIds.size <= it } ?: true
        val groupIds = rowLimit?.let { fetchedGroupIds.take(it) } ?: fetchedGroupIds
        if (groupIds.isEmpty()) {
            return QuerySelection(emptyPage(query, rowOffset, totalMatchCount, reachedEnd = true))
        }

        val assembled = reads.assembledForGroups(groupIds, split, compiled.joins)
        val displayed = reads.orderedByGroups(assembled, groupIds)
        val physical = physicalTransactions(displayed)
        val displayedIds = physical.mapNotNull { it.id }.toSet()
        val matchingIds: Set<String> =
            if (compiled.usesGroupedParentSelection) displayedIds else matchingTransactionIds(compiled, groupIds)
        return makeSelection(query, displayed, physical, matchingIds, reachedEnd, rowOffset + groupIds.size, totalMatchCount)
    }

    private fun flatSelection(query: FeedQuery, compiled: CompiledQuery, limit: Int?, offset: Int): QuerySelection {
        val rowOffset = max(0, offset)
        val rowLimit = limit?.let { max(1, it) }
        val split = compiled.split
        val totalMatchCount = (sql.queryLong(
            """
            SELECT COUNT(*)
            FROM transactions t
            ${compiled.joins.sql}
            ${split.parentJoin()}
            WHERE ${compiled.whereSql}
            """.trimIndent(),
            *compiled.arguments.toTypedArray(),
        ) ?: 0L).toInt()
        val pageArguments = compiled.arguments.toMutableList()
        val limitSql = reads.limitSql(rowLimit, rowOffset, pageArguments)
        val fetchedIds = sql.query(
            """
            SELECT t.id
            FROM transactions t
            ${compiled.joins.sql}
            ${split.parentJoin()}
            WHERE ${compiled.whereSql}
            ORDER BY ${split.defaultOrder()}
            $limitSql
            """.trimIndent(),
            *pageArguments.toTypedArray(),
        ).mapNotNull { it.string("id") }
        val reachedEnd = rowLimit?.let { fetchedIds.size <= it } ?: true
        val matchingIds = rowLimit?.let { fetchedIds.take(it) } ?: fetchedIds
        if (matchingIds.isEmpty()) {
            return QuerySelection(emptyPage(query, rowOffset, totalMatchCount, reachedEnd = true))
        }

        val mapped = transactionsForIds(matchingIds, compiled)
        val displayed = reads.attachingSplitFamilies(mapped, split, compiled.joins)
        val physical = physicalTransactions(displayed)
        return makeSelection(
            query, displayed, physical, matchingIds.toSet(), reachedEnd, rowOffset + matchingIds.size, totalMatchCount,
        )
    }

    private fun matchedRowsSql(compiled: CompiledQuery): String =
        """
        SELECT t.id AS id, IFNULL(${compiled.split.effectiveParentId}, t.id) AS group_id
        FROM transactions t
        ${compiled.joins.sql}
        ${compiled.split.parentJoin()}
        WHERE ${compiled.whereSql}
        """.trimIndent()

    private fun matchingTransactionIds(compiled: CompiledQuery, groupIds: List<String>): Set<String> {
        val placeholders = groupIds.joinToString(", ") { "?" }
        val arguments = ArrayList<Any?>(compiled.arguments)
        arguments.addAll(groupIds)
        return sql.query(
            """
            SELECT matched.id AS id
            FROM (${matchedRowsSql(compiled)}) matched
            WHERE matched.group_id IN ($placeholders)
            """.trimIndent(),
            *arguments.toTypedArray(),
        ).mapNotNull { it.string("id") }.toSet()
    }

    private fun transactionsForIds(ids: List<String>, compiled: CompiledQuery): List<QueryTransaction> {
        val placeholders = ids.joinToString(", ") { "?" }
        val rows = sql.query(
            """
            SELECT ${reads.selectList(compiled.split, compiled.joins)}
            FROM transactions t
            ${compiled.joins.sql}
            ${compiled.split.parentJoin()}
            WHERE ${compiled.split.liveEffectivePredicate()}
              AND t.id IN ($placeholders)
            """.trimIndent(),
            *ids.toTypedArray(),
        )
        val byId = HashMap<String, QueryTransaction>()
        for (row in rows) {
            val transaction = reads.mapRow(row)
            transaction.id?.let { byId[it] = transaction }
        }
        return ids.mapNotNull { byId[it] }
    }

    private fun makeSelection(
        query: FeedQuery,
        transactions: List<QueryTransaction>,
        physical: List<QueryTransaction>,
        matchingIds: Set<String>,
        reachedEnd: Boolean,
        nextOffset: Int,
        totalMatchCount: Int,
    ): QuerySelection {
        val contributing = physical.filter { transaction ->
            val id = transaction.id
            id != null && !transaction.isParent && id in matchingIds
        }
        val contributingIds = contributing.mapNotNull { it.id }.toSet()
        val displayedIds = physical.mapNotNull { it.id }.toSet()
        return QuerySelection(
            QueryPage(
                transactions = transactions,
                reachedEnd = reachedEnd,
                nextOffset = nextOffset,
                totalMatchCount = totalMatchCount,
                query = query,
                matchingTransactionIds = matchingIds,
                contributingTransactionIds = contributingIds,
                attachedContextTransactionIds = displayedIds - matchingIds,
                contributingTransactions = contributing,
            ),
        )
    }

    private fun emptyPage(query: FeedQuery, offset: Int, totalMatchCount: Int, reachedEnd: Boolean) = QueryPage(
        transactions = emptyList(),
        reachedEnd = reachedEnd,
        nextOffset = offset,
        totalMatchCount = totalMatchCount,
        query = query,
        matchingTransactionIds = emptySet(),
        contributingTransactionIds = emptySet(),
        attachedContextTransactionIds = emptySet(),
        contributingTransactions = emptyList(),
    )

    /** Every row once, a parent followed by its children, in display order. */
    private fun physicalTransactions(transactions: List<QueryTransaction>): List<QueryTransaction> {
        val seen = HashSet<String>()
        val ordered = mutableListOf<QueryTransaction>()
        for (root in transactions) {
            for (transaction in listOf(root) + root.subtransactions) {
                val id = transaction.id
                if (id != null && !seen.add(id)) continue
                ordered += transaction
            }
        }
        return ordered
    }

    // region Predicates

    private fun structuredPredicate(
        condition: QueryCondition,
        split: QuerySplit,
        joins: ReadJoins,
        arguments: MutableList<Any?>,
    ): String = when (condition) {
        is QueryCondition.Date -> datePredicate(condition.condition, split.normalizedDate, arguments)
        is QueryCondition.Account -> idPredicate(condition.condition, split.account, arguments)
        is QueryCondition.Payee -> idPredicate(condition.condition, joins.mappedPayee, arguments)
        is QueryCondition.Category -> {
            val category = split.effectiveCategory(joins.mappedCategory)
            val ids = condition.condition
            if (ids.operation == QueryIdOperation.IS_EQUAL && ids.values == listOf<String?>(null)) {
                "($category IS NULL AND ${joins.transferIdExpression} IS NULL AND ${split.isParent} = 0)"
            } else {
                idPredicate(ids, category, arguments)
            }
        }
        is QueryCondition.Transfer -> "(${joins.isTransferExpression}) = ${if (condition.isTransfer) 1 else 0}"
    }

    private fun datePredicate(condition: QueryDateCondition, expression: String, arguments: MutableList<Any?>): String {
        val day = condition.day.raw
        return when (condition.operation) {
            QueryDateOperation.IS -> {
                arguments.add(day)
                "$expression = ?"
            }
            QueryDateOperation.IS_APPROXIMATELY -> {
                val (lower, upper) = approximateBounds(condition.day)
                arguments.add(lower)
                arguments.add(upper)
                "($expression >= ? AND $expression <= ?)"
            }
            QueryDateOperation.IS_AFTER -> {
                arguments.add(day)
                "$expression > ?"
            }
            QueryDateOperation.IS_ON_OR_AFTER -> {
                arguments.add(day)
                "$expression >= ?"
            }
            QueryDateOperation.IS_BEFORE -> {
                arguments.add(day)
                "$expression < ?"
            }
            QueryDateOperation.IS_ON_OR_BEFORE -> {
                arguments.add(day)
                "$expression <= ?"
            }
        }
    }

    /** Two days either side; the day is validated, so a failure collapses to the day itself. */
    private fun approximateBounds(day: QueryDay): Pair<String, String> = try {
        val date = LocalDate.parse(day.raw)
        date.minusDays(2).toString() to date.plusDays(2).toString()
    } catch (error: Exception) {
        day.raw to day.raw
    }

    private fun idPredicate(condition: QueryIdCondition, expression: String, arguments: MutableList<Any?>): String {
        val negatedEquality = condition.operation == QueryIdOperation.IS_NOT_EQUAL
        val negatedSet = condition.operation == QueryIdOperation.IS_NOT_ONE_OF
        val isScalar = condition.operation == QueryIdOperation.IS_EQUAL || negatedEquality
        if (isScalar) {
            if (condition.values.size != 1) return "0 = 1"
            return idEquality(condition.values[0], expression, negatedEquality, arguments)
        }
        if (condition.values.isEmpty()) return "0 = 1"
        val predicates = condition.values.map { idEquality(it, expression, negatedSet, arguments) }
        return "(${predicates.joinToString(if (negatedSet) " AND " else " OR ")})"
    }

    private fun idEquality(value: String?, expression: String, negated: Boolean, arguments: MutableList<Any?>): String {
        if (value == null) return if (negated) "$expression IS NOT NULL" else "$expression IS NULL"
        arguments.add(value)
        return if (negated) "($expression IS NULL OR $expression != ?)" else "$expression = ?"
    }

    // endregion
}
