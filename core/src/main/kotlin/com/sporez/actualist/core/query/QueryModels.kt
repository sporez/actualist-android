package com.sporez.actualist.core.query

/** Actualist `TransactionQueryJoin`. */
enum class QueryJoin(val raw: String) {
    AND("and"), OR("or");

    companion object {
        fun fromRaw(raw: String): QueryJoin? = values().firstOrNull { it.raw == raw }
    }
}

/** Actualist `TransactionQueryDateOperation`. */
enum class QueryDateOperation(val raw: String) {
    IS("is"), IS_APPROXIMATELY("isapprox"), IS_AFTER("gt"), IS_ON_OR_AFTER("gte"), IS_BEFORE("lt"), IS_ON_OR_BEFORE("lte");

    companion object {
        fun fromRaw(raw: String): QueryDateOperation? = values().firstOrNull { it.raw == raw }
    }
}

/** A validated `YYYY-MM-DD` calendar day (Actualist `TransactionQueryDay`). */
class QueryDay private constructor(val raw: String) {
    override fun equals(other: Any?): Boolean = other is QueryDay && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()
    override fun toString(): String = raw

    companion object {
        fun of(text: String): QueryDay? {
            val trimmed = text.trim()
            val parts = trimmed.split("-")
            if (parts.size != 3 || parts[0].length != 4 || parts[1].length != 2 || parts[2].length != 2) return null
            if (!parts.all { part -> part.all { it in '0'..'9' } }) return null
            val year = parts[0].toInt()
            val month = parts[1].toInt()
            val day = parts[2].toInt()
            if (year <= 0 || month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
            return QueryDay(trimmed)
        }

        private fun daysInMonth(year: Int, month: Int): Int = when (month) {
            2 -> if (year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}

data class QueryDateCondition(val operation: QueryDateOperation, val day: QueryDay)

/** Actualist `TransactionQueryIDOperation`. */
enum class QueryIdOperation(val raw: String) {
    IS_EQUAL("is"), IS_NOT_EQUAL("isNot"), IS_ONE_OF("oneOf"), IS_NOT_ONE_OF("notOneOf");

    companion object {
        fun fromRaw(raw: String): QueryIdOperation? = values().firstOrNull { it.raw == raw }
    }
}

/** An id comparison. A null value means "no id" (a null payee or category). */
class QueryIdCondition private constructor(val operation: QueryIdOperation, val values: List<String?>) {
    override fun equals(other: Any?): Boolean =
        other is QueryIdCondition && other.operation == operation && other.values == values

    override fun hashCode(): Int = 31 * operation.hashCode() + values.hashCode()
    override fun toString(): String = "QueryIdCondition($operation, $values)"

    companion object {
        fun equalTo(value: String?): QueryIdCondition = QueryIdCondition(QueryIdOperation.IS_EQUAL, normalizedScalar(value))
        fun notEqualTo(value: String?): QueryIdCondition = QueryIdCondition(QueryIdOperation.IS_NOT_EQUAL, normalizedScalar(value))
        fun oneOf(values: List<String?>): QueryIdCondition = QueryIdCondition(QueryIdOperation.IS_ONE_OF, normalized(values))
        fun notOneOf(values: List<String?>): QueryIdCondition = QueryIdCondition(QueryIdOperation.IS_NOT_ONE_OF, normalized(values))

        private fun normalizedScalar(value: String?): List<String?> {
            if (value == null) return listOf(null)
            val trimmed = value.trim()
            return if (trimmed.isEmpty()) emptyList() else listOf(trimmed)
        }

        private fun normalized(values: List<String?>): List<String?> {
            val seen = LinkedHashSet<String?>()
            for (value in values) {
                if (value == null) {
                    seen.add(null)
                } else {
                    val trimmed = value.trim()
                    if (trimmed.isNotEmpty()) seen.add(trimmed)
                }
            }
            // Null sorts first, then ids by code point order.
            return seen.sortedWith(Comparator<String?> { left, right ->
                when {
                    left == null && right == null -> 0
                    left == null -> -1
                    right == null -> 1
                    else -> left.compareTo(right)
                }
            })
        }
    }
}

/** Actualist `TransactionQueryCondition`. */
sealed class QueryCondition {
    data class Date(val condition: QueryDateCondition) : QueryCondition()
    data class Account(val condition: QueryIdCondition) : QueryCondition()
    data class Payee(val condition: QueryIdCondition) : QueryCondition()
    data class Category(val condition: QueryIdCondition) : QueryCondition()
    data class Transfer(val isTransfer: Boolean) : QueryCondition()

    val canonicalKey: String
        get() = when (this) {
            is Date -> "date|${condition.operation.raw}|${condition.day.raw}"
            is Account -> idKey("account", condition)
            is Payee -> idKey("payee", condition)
            is Category -> idKey("category", condition)
            is Transfer -> "transfer|$isTransfer"
        }

    private fun idKey(field: String, condition: QueryIdCondition): String {
        val values = condition.values.joinToString("\u001f") { it ?: "<null>" }
        return "$field|${condition.operation.raw}|$values"
    }
}

/** Actualist `TransactionStatusFilter`. */
enum class StatusFilter(val raw: String) {
    ALL("all"), UNCLEARED("uncleared"), CLEARED("cleared"), RECONCILED("reconciled"), UNCATEGORIZED("uncategorized")
}

/** Actualist `TransactionQueryScope`: one account, or every account. */
sealed class QueryScope {
    data class Account(val accountId: String) : QueryScope()
    data object Spending : QueryScope()

    companion object {
        fun of(accountId: String?): QueryScope = if (accountId == null) Spending else Account(accountId)
    }
}

/**
 * Actualist `TransactionFeedQuery`: text is trimmed (blank becomes null) and the conditions are
 * de-duplicated and put in canonical order, so equal queries are equal values.
 */
class FeedQuery(
    val status: StatusFilter = StatusFilter.ALL,
    text: String? = null,
    val conditionsJoin: QueryJoin = QueryJoin.AND,
    conditions: List<QueryCondition> = emptyList(),
) {
    val text: String? = text?.trim()?.takeIf { it.isNotEmpty() }
    val conditions: List<QueryCondition> = conditions.distinct().sortedBy { it.canonicalKey }
    val hasStructuredConditions: Boolean get() = this.conditions.isNotEmpty()

    val stableSortKey: String
        get() = listOf(
            status.raw,
            this.text ?: "",
            conditionsJoin.raw,
            this.conditions.joinToString("\u001e") { it.canonicalKey },
        ).joinToString("\u001d")

    override fun equals(other: Any?): Boolean =
        other is FeedQuery && other.status == status && other.text == text &&
            other.conditionsJoin == conditionsJoin && other.conditions == conditions

    override fun hashCode(): Int = stableSortKey.hashCode()

    companion object {
        val ALL = FeedQuery()
    }
}

/** Actualist `TransactionSplitQueryMode`. */
enum class SplitMode { ALL, INLINE, NONE, GROUPED }

/** One transaction as the query reads return it, with split children nested under a parent. */
data class QueryTransaction(
    val id: String?,
    val accountId: String,
    /** `YYYY-MM-DD`. */
    val date: String,
    val amount: Long?,
    val payeeId: String?,
    val payeeName: String?,
    val importedPayee: String?,
    val categoryId: String?,
    val notes: String?,
    val cleared: Boolean,
    val reconciled: Boolean,
    val isParent: Boolean,
    val isChild: Boolean,
    val parentId: String?,
    val scheduleId: String?,
    val subtransactions: List<QueryTransaction> = emptyList(),
)

/** A page of the flat or grouped transaction list (Actualist `TransactionFetchResult`). */
data class TransactionPage(
    val transactions: List<QueryTransaction>,
    val reachedEnd: Boolean,
    val nextOffset: Int,
)

/**
 * The result of a structured query (Actualist `TransactionQueryPage`): [transactions] are the roots
 * in display order with their children attached; [matchingTransactionIds] are the rows that matched
 * individually; [attachedContextTransactionIds] are shown only as family context.
 */
data class QueryPage(
    val transactions: List<QueryTransaction>,
    val reachedEnd: Boolean,
    val nextOffset: Int,
    val totalMatchCount: Int,
    val query: FeedQuery,
    val matchingTransactionIds: Set<String>,
    val contributingTransactionIds: Set<String>,
    val attachedContextTransactionIds: Set<String>,
    val contributingTransactions: List<QueryTransaction>,
)
