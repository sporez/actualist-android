package com.sporez.actualist.core.query

/** The id conditions a filter sheet can author. */
enum class TransactionFilterField(val title: String) { ACCOUNT("Account"), PAYEE("Payee"), CATEGORY("Category") }

/** A refused draft; [message] is Actualist's `TransactionFilterDraftError` text. */
class TransactionFilterDraftException(message: String) : IllegalArgumentException(message)

/** One field's editable selection: an operation, chosen ids, and "no payee" or "uncategorized". */
data class TransactionFilterIdDraft(
    val operation: QueryIdOperation = QueryIdOperation.IS_ONE_OF,
    val ids: Set<String> = emptySet(),
    val includesNull: Boolean = false,
) {
    val isEmpty: Boolean get() = ids.isEmpty() && !includesNull
}

/**
 * Actualist `TransactionFilterWorkflow`'s draft: the conditions a filter sheet shows and edits
 * without saving. Conditions the sheet cannot author (a second date, a field that already has a
 * condition, a no-account value, a transfer condition, a multi-value null) are kept in
 * [preserved] and rebuilt unchanged.
 */
data class TransactionFilterDraft(
    val join: QueryJoin = QueryJoin.AND,
    val date: QueryDateCondition? = null,
    val account: TransactionFilterIdDraft = TransactionFilterIdDraft(),
    val payee: TransactionFilterIdDraft = TransactionFilterIdDraft(),
    val category: TransactionFilterIdDraft = TransactionFilterIdDraft(),
    val preserved: List<QueryCondition> = emptyList(),
) {
    fun field(field: TransactionFilterField): TransactionFilterIdDraft = when (field) {
        TransactionFilterField.ACCOUNT -> account
        TransactionFilterField.PAYEE -> payee
        TransactionFilterField.CATEGORY -> category
    }

    fun withField(field: TransactionFilterField, value: TransactionFilterIdDraft): TransactionFilterDraft = when (field) {
        TransactionFilterField.ACCOUNT -> copy(account = value)
        TransactionFilterField.PAYEE -> copy(payee = value)
        TransactionFilterField.CATEGORY -> copy(category = value)
    }

    /** Actualist `resetDraft`: everything cleared, preserved conditions included. */
    fun reset(): TransactionFilterDraft = TransactionFilterDraft()

    /** The conditions this draft stands for; throws [TransactionFilterDraftException] when it is invalid. */
    fun conditions(): List<QueryCondition> {
        validate(TransactionFilterField.ACCOUNT)
        validate(TransactionFilterField.PAYEE)
        validate(TransactionFilterField.CATEGORY)
        val out = preserved.toMutableList()
        date?.let { out.add(QueryCondition.Date(it)) }
        idCondition(account)?.let { out.add(QueryCondition.Account(it)) }
        idCondition(payee)?.let { out.add(QueryCondition.Payee(it)) }
        idCondition(category)?.let { out.add(QueryCondition.Category(it)) }
        return out
    }

    private fun validate(field: TransactionFilterField) {
        val draft = field(field)
        val noun = field.title.lowercase()
        val scalar = draft.operation == QueryIdOperation.IS_EQUAL || draft.operation == QueryIdOperation.IS_NOT_EQUAL
        if (scalar && draft.ids.size + (if (draft.includesNull) 1 else 0) > 1) {
            throw TransactionFilterDraftException("Choose one $noun for this condition.")
        }
        if (draft.includesNull && !scalar) {
            throw TransactionFilterDraftException(
                if (field == TransactionFilterField.CATEGORY) "Use “Is” or “Is not” to filter uncategorized transactions."
                else "Use “Is” or “Is not” to filter transactions with no $noun.",
            )
        }
    }

    private fun idCondition(draft: TransactionFilterIdDraft): QueryIdCondition? {
        val values: List<String?> = draft.ids.sorted() + (if (draft.includesNull) listOf<String?>(null) else emptyList())
        if (values.isEmpty()) return null
        return when (draft.operation) {
            QueryIdOperation.IS_EQUAL -> if (values.size == 1) QueryIdCondition.equalTo(values[0]) else null
            QueryIdOperation.IS_NOT_EQUAL -> if (values.size == 1) QueryIdCondition.notEqualTo(values[0]) else null
            QueryIdOperation.IS_ONE_OF -> if (draft.includesNull) null else QueryIdCondition.oneOf(values)
            QueryIdOperation.IS_NOT_ONE_OF -> if (draft.includesNull) null else QueryIdCondition.notOneOf(values)
        }
    }

    companion object {
        /** Actualist `hydrate`: splits conditions into editable fields and preserved ones. */
        fun from(conditions: List<QueryCondition>, join: QueryJoin): TransactionFilterDraft {
            var draft = TransactionFilterDraft(join = join)
            var hasDate = false
            var hasAccount = false
            var hasPayee = false
            var hasCategory = false
            val kept = mutableListOf<QueryCondition>()
            for (condition in conditions) {
                when (condition) {
                    is QueryCondition.Date -> {
                        if (hasDate) kept.add(condition) else {
                            hasDate = true
                            draft = draft.copy(date = condition.condition)
                        }
                    }
                    is QueryCondition.Account -> {
                        val value = condition.condition
                        if (value.values.isEmpty() || value.values.any { it == null } || hasAccount) kept.add(condition) else {
                            hasAccount = true
                            draft = draft.copy(account = TransactionFilterIdDraft(value.operation, value.values.filterNotNull().toSet(), false))
                        }
                    }
                    is QueryCondition.Payee -> {
                        val value = condition.condition
                        if (value.values.isEmpty() || !supportsScalarNull(value) || hasPayee) kept.add(condition) else {
                            hasPayee = true
                            draft = draft.copy(payee = TransactionFilterIdDraft(value.operation, value.values.filterNotNull().toSet(), value.values.any { it == null }))
                        }
                    }
                    is QueryCondition.Category -> {
                        val value = condition.condition
                        if (value.values.isEmpty() || !supportsScalarNull(value) || hasCategory) kept.add(condition) else {
                            hasCategory = true
                            draft = draft.copy(category = TransactionFilterIdDraft(value.operation, value.values.filterNotNull().toSet(), value.values.any { it == null }))
                        }
                    }
                    is QueryCondition.Transfer -> kept.add(condition)
                }
            }
            return draft.copy(preserved = kept)
        }

        private fun supportsScalarNull(value: QueryIdCondition): Boolean {
            if (value.values.none { it == null }) return true
            return value.values.size == 1 &&
                (value.operation == QueryIdOperation.IS_EQUAL || value.operation == QueryIdOperation.IS_NOT_EQUAL)
        }

        fun operationName(operation: QueryIdOperation): String = when (operation) {
            QueryIdOperation.IS_EQUAL -> "is"
            QueryIdOperation.IS_NOT_EQUAL -> "is not"
            QueryIdOperation.IS_ONE_OF -> "is one of"
            QueryIdOperation.IS_NOT_ONE_OF -> "is not one of"
        }

        fun operationName(operation: QueryDateOperation): String = when (operation) {
            QueryDateOperation.IS -> "is"
            QueryDateOperation.IS_APPROXIMATELY -> "is approximately"
            QueryDateOperation.IS_AFTER -> "is after"
            QueryDateOperation.IS_ON_OR_AFTER -> "is on or after"
            QueryDateOperation.IS_BEFORE -> "is before"
            QueryDateOperation.IS_ON_OR_BEFORE -> "is on or before"
        }

        /** Actualist's "N condition(s) · AND" summary. */
        fun summary(count: Int, join: QueryJoin): String =
            "$count condition${if (count == 1) "" else "s"} · ${join.raw.uppercase()}"
    }
}
