package com.sporez.actualist.core.query

import com.sporez.actualist.core.budget.normalizedMonthSql
import com.sporez.actualist.core.db.BudgetDatabase

/**
 * Transaction list pages in the four split modes (`all`, `inline`, `none`, `grouped`), ported from
 * Actualist `BudgetDatabase.fetchTransactionPage`. With no split mode and no month the read is the
 * structured query read, so a search groups the way the account screen does.
 */
internal class TransactionPageEngine(private val database: BudgetDatabase, private val queryEngine: TransactionQueryEngine) {
    private val reads = QueryReads(database)
    private val sql get() = database.sql

    fun page(
        accountId: String?,
        matching: String?,
        limit: Int?,
        offset: Int,
        splits: SplitMode?,
        month: String?,
        status: StatusFilter,
    ): TransactionPage = sql.locked {
        if (splits == null && month == null) {
            val result = queryEngine.page(QueryScope.of(accountId), FeedQuery(status = status, text = matching), limit, offset)
            return@locked TransactionPage(result.transactions, result.reachedEnd, result.nextOffset)
        }
        val rowOffset = if (offset > 0) offset else 0
        if (!database.tableExists("transactions")) return@locked TransactionPage(emptyList(), true, rowOffset)

        val columns = database.columnSet("transactions")
        val split = QuerySplit(columns)
        var joins = reads.readJoins(split, columns, includeNames = true)
        val mode = splits ?: (if (matching.isNullOrEmpty()) SplitMode.GROUPED else SplitMode.ALL)

        val conditions = mutableListOf(split.liveEffectivePredicate())
        val arguments = mutableListOf<Any?>()
        if (accountId != null) {
            conditions.add("${split.account} = ?")
            arguments.add(accountId)
        }
        if (month != null) {
            if (!isYearMonthId(month)) return@locked TransactionPage(emptyList(), true, rowOffset)
            conditions.add("${normalizedMonthSql(split.date)} = ?")
            arguments.add(month)
        }
        if (!matching.isNullOrEmpty()) {
            conditions.add(reads.searchPredicate(split, joins))
            val like = "%${reads.escapeLikePattern(matching)}%"
            repeat(4) { arguments.add(like) }
        }
        if (status == StatusFilter.UNCATEGORIZED) {
            val context = reads.uncategorizedContext()
            joins = context.joins
            conditions.addAll(context.conditions.drop(1))
            conditions.add(split.splitModePredicate(SplitMode.INLINE))
        } else {
            reads.statusFilterPredicate(status, split)?.let { conditions.add(it) }
        }

        val rowLimit = limit?.let { if (it >= 1) it else 1 }
        when (mode) {
            SplitMode.ALL, SplitMode.INLINE, SplitMode.NONE ->
                flatPage(split, joins, mode, conditions, arguments, rowLimit, rowOffset)
            SplitMode.GROUPED -> groupedPage(
                split = split,
                joins = joins,
                conditions = conditions,
                arguments = arguments,
                selectsMatchedGroups = !matching.isNullOrEmpty() || status != StatusFilter.ALL,
                allowsUnfilteredFastPath = status == StatusFilter.ALL && matching.isNullOrEmpty(),
                rowLimit = rowLimit,
                rowOffset = rowOffset,
            )
        }
    }

    private fun flatPage(
        split: QuerySplit,
        joins: ReadJoins,
        mode: SplitMode,
        conditions: List<String>,
        arguments: List<Any?>,
        rowLimit: Int?,
        rowOffset: Int,
    ): TransactionPage {
        val pageConditions = conditions + split.splitModePredicate(mode)
        val pageArguments = arguments.toMutableList()
        val limitSql = reads.limitSql(rowLimit, rowOffset, pageArguments)
        val rows = sql.query(
            """
            SELECT ${reads.selectList(split, joins)}
            FROM transactions t
            ${joins.sql}
            ${split.parentJoin()}
            WHERE ${pageConditions.joinToString(" AND ")}
            ORDER BY ${split.defaultOrder()}
            $limitSql
            """.trimIndent(),
            *pageArguments.toTypedArray(),
        )
        val reachedEnd = rowLimit?.let { rows.size <= it } ?: true
        val page = rowLimit?.let { rows.take(it) } ?: rows
        val mapped = page.map { reads.mapRow(it) }
        val transactions = if (mode == SplitMode.ALL) reads.attachingSplitFamilies(mapped, split, joins) else mapped
        return TransactionPage(transactions, reachedEnd, rowOffset + page.size)
    }

    private fun groupedPage(
        split: QuerySplit,
        joins: ReadJoins,
        conditions: List<String>,
        arguments: List<Any?>,
        selectsMatchedGroups: Boolean,
        allowsUnfilteredFastPath: Boolean,
        rowLimit: Int?,
        rowOffset: Int,
    ): TransactionPage {
        if (rowLimit == null && allowsUnfilteredFastPath) {
            val rows = sql.query(
                """
                SELECT ${reads.selectList(split, joins)}
                FROM transactions t
                ${joins.sql}
                ${split.parentJoin()}
                WHERE ${conditions.joinToString(" AND ")}
                ORDER BY ${split.defaultOrder()}
                """.trimIndent(),
                *arguments.toTypedArray(),
            )
            val transactions = reads.assemble(rows)
            return TransactionPage(transactions, true, transactions.size)
        }

        val fetched: List<String>
        val pageArguments = arguments.toMutableList()
        val limitSql = reads.limitSql(rowLimit, rowOffset, pageArguments)
        if (selectsMatchedGroups) {
            fetched = sql.query(
                """
                SELECT g.group_id AS group_id FROM (
                    SELECT DISTINCT IFNULL(${split.effectiveParentId}, t.id) AS group_id
                    FROM transactions t
                    ${joins.sql}
                    ${split.parentJoin()}
                    WHERE ${conditions.joinToString(" AND ")}
                ) g
                JOIN transactions t ON t.id = g.group_id
                ORDER BY ${split.defaultOrder()}
                $limitSql
                """.trimIndent(),
                *pageArguments.toTypedArray(),
            ).mapNotNull { it.string("group_id") }
        } else {
            val parentConditions = conditions + split.splitModePredicate(SplitMode.NONE)
            fetched = sql.query(
                """
                SELECT t.id AS id
                FROM transactions t
                ${joins.sql}
                ${split.parentJoin()}
                WHERE ${parentConditions.joinToString(" AND ")}
                ORDER BY ${split.defaultOrder()}
                $limitSql
                """.trimIndent(),
                *pageArguments.toTypedArray(),
            ).mapNotNull { it.string("id") }
        }
        val reachedEnd = rowLimit?.let { fetched.size <= it } ?: true
        val groupIds = rowLimit?.let { fetched.take(it) } ?: fetched
        if (groupIds.isEmpty()) return TransactionPage(emptyList(), true, rowOffset)

        val assembled = reads.assembledForGroups(groupIds, split, joins)
        val ordered = reads.orderedByGroups(assembled, groupIds)
        return TransactionPage(ordered, reachedEnd, rowOffset + groupIds.size)
    }

    private fun isYearMonthId(value: String): Boolean {
        val parts = value.split("-")
        if (parts.size != 2 || parts[0].length != 4 || parts[1].length != 2) return false
        if (!parts.all { part -> part.all { it in '0'..'9' } }) return false
        return parts[1].toInt() in 1..12
    }
}
