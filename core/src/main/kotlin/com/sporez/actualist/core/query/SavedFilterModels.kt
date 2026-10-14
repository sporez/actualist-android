package com.sporez.actualist.core.query

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.rules.RuleCondition
import com.sporez.actualist.core.rules.RuleConditionJoin
import com.sporez.actualist.core.rules.RuleFields
import com.sporez.actualist.core.rules.RuleJson
import com.sporez.actualist.core.rules.RuleValue
import com.sporez.actualist.core.sync.SyncValue

/** Actualist `SavedTransactionFilterCompatibility`. */
sealed class SavedFilterCompatibility {
    data object Supported : SavedFilterCompatibility()
    data class Unsupported(val reason: String) : SavedFilterCompatibility()
}

/**
 * A stored filter (a `transaction_filters` row) and its projection into query conditions.
 * [conditions] are the stored rule-style conditions; [queryConditions] and [queryJoin] are set only
 * when every condition is one this app's filters can run ([compatibility] is then `Supported`).
 */
data class SavedFilter(
    val id: String,
    val name: String,
    val rawName: String?,
    val conditionsOperation: String?,
    val rawConditionsJson: String?,
    val conditions: List<RuleCondition>?,
    val queryJoin: QueryJoin?,
    val queryConditions: List<QueryCondition>?,
    val tombstone: Boolean,
    val compatibility: SavedFilterCompatibility,
) {
    val isSupported: Boolean get() = compatibility == SavedFilterCompatibility.Supported

    /** The query this filter runs, or null when it is unsupported. */
    fun feedQuery(text: String? = null): FeedQuery? {
        val join = queryJoin ?: return null
        val conditions = queryConditions ?: return null
        return FeedQuery(text = text, conditionsJoin = join, conditions = conditions)
    }

    companion object {
        /** Actualist `SavedTransactionFilter.project`. */
        fun project(
            id: String,
            name: String?,
            rawConditionsJson: String?,
            conditionsOperation: String?,
            tombstone: Boolean,
        ): SavedFilter {
            val effectiveOperation = conditionsOperation ?: "and"
            val join = RuleConditionJoin.fromRaw(effectiveOperation)
            val decoded: List<RuleCondition>? =
                rawConditionsJson?.let { RuleJson.decodeConditions(it) }?.takeIf { it.isNotEmpty() }
            val hasKnownKeys = rawConditionsJson?.let { RuleJson.conditionsHaveOnlyKnownKeys(it) } ?: false
            val projected: List<QueryCondition>? =
                if (decoded != null && hasKnownKeys) {
                    val mapped = decoded.mapNotNull { savedQueryCondition(it) }
                    if (mapped.size == decoded.size) mapped else null
                } else {
                    null
                }

            val compatibility: SavedFilterCompatibility = when {
                join == null -> SavedFilterCompatibility.Unsupported("Unknown condition join")
                decoded == null -> SavedFilterCompatibility.Unsupported("Malformed, empty, or unknown condition data")
                !hasKnownKeys -> SavedFilterCompatibility.Unsupported("Condition contains unknown fields")
                projected == null -> SavedFilterCompatibility.Unsupported("Condition is not supported by transaction filters")
                else -> SavedFilterCompatibility.Supported
            }
            val supported = compatibility == SavedFilterCompatibility.Supported
            return SavedFilter(
                id = id,
                name = name ?: "",
                rawName = name,
                conditionsOperation = conditionsOperation,
                rawConditionsJson = rawConditionsJson,
                conditions = decoded,
                queryJoin = if (supported) QueryJoin.fromRaw(effectiveOperation) else null,
                queryConditions = if (supported) projected else null,
                tombstone = tombstone,
                compatibility = compatibility,
            )
        }

        /** A stored rule-style condition as a query condition, or null when it is not supported. */
        fun savedQueryCondition(condition: RuleCondition): QueryCondition? {
            // Saved filters keep the public account name; rules store it as `acct`.
            val serialized = RuleFields.serializedField(condition.fieldName)
            val field = if (serialized == "acct") "account" else serialized
            val options = condition.options
            val noOptions = options == null || options.isEmpty()

            if (field == "date") {
                val operation = QueryDateOperation.fromRaw(condition.op) ?: return null
                val text = condition.value as? RuleValue.Text ?: return null
                val day = QueryDay.of(text.value) ?: return null
                if (!noOptions) return null
                return QueryCondition.Date(QueryDateCondition(operation, day))
            }

            if (field != "account" && field != "description" && field != "category") return null
            val operation = QueryIdOperation.fromRaw(condition.op) ?: return null
            if (!noOptions) return null

            val value = condition.value
            val values: List<String?> = when (operation) {
                QueryIdOperation.IS_EQUAL, QueryIdOperation.IS_NOT_EQUAL -> when (value) {
                    is RuleValue.Text -> listOf<String?>(value.value)
                    is RuleValue.Null -> listOf<String?>(null)
                    else -> return null
                }
                QueryIdOperation.IS_ONE_OF, QueryIdOperation.IS_NOT_ONE_OF -> {
                    val entries = value as? RuleValue.Arr ?: return null
                    val decodedValues = mutableListOf<String?>()
                    for (entry in entries.values) {
                        when (entry) {
                            is RuleValue.Text -> decodedValues.add(entry.value)
                            is RuleValue.Null -> decodedValues.add(null)
                            else -> return null
                        }
                    }
                    decodedValues
                }
            }

            val idCondition = when (operation) {
                QueryIdOperation.IS_EQUAL -> QueryIdCondition.equalTo(values[0])
                QueryIdOperation.IS_NOT_EQUAL -> QueryIdCondition.notEqualTo(values[0])
                QueryIdOperation.IS_ONE_OF -> QueryIdCondition.oneOf(values)
                QueryIdOperation.IS_NOT_ONE_OF -> QueryIdCondition.notOneOf(values)
            }
            return when (field) {
                "account" -> QueryCondition.Account(idCondition)
                "description" -> QueryCondition.Payee(idCondition)
                "category" -> QueryCondition.Category(idCondition)
                else -> null
            }
        }

        /** A query condition as the stored rule-style condition; transfer has no stored form. */
        fun ruleCondition(condition: QueryCondition): RuleCondition? = when (condition) {
            is QueryCondition.Date -> RuleCondition(
                "date", condition.condition.operation.raw, RuleValue.Text(condition.condition.day.raw), "date",
            )
            is QueryCondition.Account -> idRuleCondition(condition.condition, "account")
            is QueryCondition.Payee -> idRuleCondition(condition.condition, "description")
            is QueryCondition.Category -> idRuleCondition(condition.condition, "category")
            is QueryCondition.Transfer -> null
        }

        private fun idRuleCondition(condition: QueryIdCondition, field: String): RuleCondition? {
            val value: RuleValue = when (condition.operation) {
                QueryIdOperation.IS_EQUAL, QueryIdOperation.IS_NOT_EQUAL -> {
                    if (condition.values.size != 1) return null
                    condition.values[0]?.let { RuleValue.Text(it) } ?: RuleValue.Null
                }
                QueryIdOperation.IS_ONE_OF, QueryIdOperation.IS_NOT_ONE_OF ->
                    RuleValue.Arr(condition.values.map { entry -> if (entry == null) RuleValue.Null else RuleValue.Text(entry) })
            }
            return RuleCondition(field, condition.operation.raw, value, "id")
        }
    }
}

sealed class SavedFilterReadResult {
    data class Available(val filters: List<SavedFilter>) : SavedFilterReadResult()
    data class Unavailable(val reason: String) : SavedFilterReadResult()
}

/** Actualist `SavedTransactionFilterMutationError`: the text is the `InvalidLocalWrite` reason. */
sealed class SavedFilterMutationError(val text: String) {
    data object Unavailable : SavedFilterMutationError("Saved filters cannot be changed in this budget.")
    data object InvalidName : SavedFilterMutationError("Enter a name for this saved filter.")
    data class DuplicateName(val name: String) : SavedFilterMutationError("There is already a saved filter named $name.")
    data object MissingConditions : SavedFilterMutationError("Choose at least one condition for this saved filter.")
    data class DuplicateConditions(val name: String) : SavedFilterMutationError("These conditions are already saved as $name.")
    data object MissingFilter : SavedFilterMutationError("This saved filter is no longer available.")
    data object UnsupportedFilter : SavedFilterMutationError("This saved filter contains conditions that cannot be edited here.")

    fun exception(): LocalFirstException.InvalidLocalWrite = LocalFirstException.InvalidLocalWrite(text)
}

/** Actualist `SavedTransactionFilterComparator`: Actual's duplicate-name and duplicate-condition rules. */
object SavedFilterComparator {
    fun duplicateName(candidate: String, excludingId: String?, among: List<SavedFilter>): SavedFilter? =
        among.firstOrNull { !it.tombstone && it.id != excludingId && it.name == candidate }

    fun duplicateConditions(
        candidate: List<RuleCondition>,
        join: RuleConditionJoin,
        excludingId: String? = null,
        among: List<SavedFilter>,
    ): SavedFilter? = among.firstOrNull { filter ->
        filter.id != excludingId && hasDuplicateConditions(candidate, join, listOf(filter))
    }

    fun hasDuplicateConditions(candidate: List<RuleCondition>, join: RuleConditionJoin, among: List<SavedFilter>): Boolean {
        if (candidate.isEmpty()) return false
        return among.any { filter ->
            val existing = filter.conditions
            if (filter.tombstone || existing == null || existing.size != candidate.size ||
                !(candidate.size == 1 || (filter.conditionsOperation ?: "and") == join.raw)
            ) {
                false
            } else {
                candidate.all { condition -> existing.any { conditionsMatch(condition, it) } }
            }
        }
    }

    private fun conditionsMatch(left: RuleCondition, right: RuleCondition): Boolean =
        left.fieldName == right.fieldName &&
            left.op == right.op &&
            strictValueEqual(left.value, right.value) &&
            shallowOptionsMatch(left.options, right.options)

    private fun shallowOptionsMatch(left: Map<String, RuleValue>?, right: Map<String, RuleValue>?): Boolean {
        val leftMap = left ?: emptyMap()
        val rightMap = right ?: emptyMap()
        if (leftMap.size != rightMap.size) return false
        return leftMap.all { (key, value) ->
            val other = rightMap[key]
            other != null && strictValueEqual(value, other)
        }
    }

    /**
     * Actual compares object and array operands by JS reference identity. Distinct persisted JSON
     * decodes cannot establish shared identity, so those never match. A number never matches a string.
     */
    private fun strictValueEqual(left: RuleValue, right: RuleValue): Boolean = when {
        left is RuleValue.Null && right is RuleValue.Null -> true
        left is RuleValue.Bool && right is RuleValue.Bool -> left.value == right.value
        left is RuleValue.Num && right is RuleValue.Num -> left.value == right.value
        left is RuleValue.Text && right is RuleValue.Text -> left.value == right.value
        else -> false
    }
}

/** JSON text of stored conditions: keys in `field, op, value, type, options` order, no slash escaping. */
object SavedFilterJson {
    fun encodeConditions(conditions: List<RuleCondition>): String {
        val out = StringBuilder("[")
        conditions.forEachIndexed { index, condition ->
            if (index > 0) out.append(',')
            out.append("{\"field\":")
            quote(condition.fieldName, out)
            out.append(",\"op\":")
            quote(condition.op, out)
            out.append(",\"value\":")
            write(condition.value, out)
            condition.type?.let {
                out.append(",\"type\":")
                quote(it, out)
            }
            condition.options?.let {
                out.append(",\"options\":")
                write(RuleValue.Obj(it), out)
            }
            out.append('}')
        }
        out.append(']')
        return out.toString()
    }

    fun encodeValue(value: RuleValue): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: RuleValue, out: StringBuilder) {
        when (value) {
            is RuleValue.Null -> out.append("null")
            is RuleValue.Bool -> out.append(if (value.value) "true" else "false")
            is RuleValue.Num ->
                if (value.value.isFinite()) out.append(SyncValue.formatNumber(value.value)) else out.append("null")
            is RuleValue.Text -> quote(value.value, out)
            is RuleValue.Arr -> {
                out.append('[')
                value.values.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is RuleValue.Obj -> {
                out.append('{')
                value.values.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    quote(key, out)
                    out.append(':')
                    write(value.values.getValue(key), out)
                }
                out.append('}')
            }
        }
    }

    private fun quote(text: String, out: StringBuilder) {
        out.append('"')
        for (char in text) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char.code < 0x20 -> out.append(String.format(java.util.Locale.ROOT, "\\u%04x", char.code))
                else -> out.append(char)
            }
        }
        out.append('"')
    }
}
