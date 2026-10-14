package com.sporez.actualist.core.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate

/** Numeric helpers shared by rule evaluation, matching Swift's `Double(String)` and `rounded()`. */
object RuleNumbers {
    private val decimal = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

    /** Swift `Double(String)`: no surrounding whitespace, no suffixes, no hex. */
    fun parseDouble(text: String): Double? = if (decimal.matches(text)) text.toDoubleOrNull() else null

    /** Swift `Double.rounded()`: ties away from zero. */
    fun roundHalfAway(value: Double): Double {
        if (!value.isFinite()) return value
        val magnitude = Math.abs(value)
        val floor = Math.floor(magnitude)
        val rounded = if (magnitude - floor >= 0.5) floor + 1.0 else floor
        return if (value < 0) -rounded else rounded
    }

    /** Whole-number Long for a finite double within range, else null (Swift `Int(exactly:)`). */
    fun exactLong(value: Double): Long? =
        if (value.isFinite() && value == Math.floor(value) && value >= -9.223372036854775E18 && value < 9.223372036854775E18) value.toLong() else null

    /** Truncating conversion that refuses values outside the Long range. */
    fun truncatedLong(value: Double): Long? =
        if (value.isFinite() && value > -9.223372036854775E18 && value < 9.223372036854775E18) value.toLong() else null
}

/** Actualist `RuleJSONValue`. */
sealed class RuleValue {
    object Null : RuleValue()
    data class Bool(val value: Boolean) : RuleValue()
    data class Num(val value: Double) : RuleValue()
    data class Text(val value: String) : RuleValue()
    data class Arr(val values: List<RuleValue>) : RuleValue()
    data class Obj(val values: Map<String, RuleValue>) : RuleValue()

    val string: String? get() = (this as? Text)?.value

    val number: Double?
        get() = when (this) {
            is Num -> value
            is Text -> RuleNumbers.parseDouble(value)
            else -> null
        }

    val integer: Long?
        get() = number?.let { RuleNumbers.exactLong(it) }

    val bool: Boolean? get() = (this as? Bool)?.value

    val isStringLike: Boolean get() = this is Text || this is Null

    val isNumberLike: Boolean
        get() = when (this) {
            is Num -> true
            is Text -> RuleNumbers.parseDouble(value) != null
            else -> false
        }

    companion object {
        fun fromJson(element: JsonElement): RuleValue = when (element) {
            is JsonNull -> Null
            is JsonPrimitive -> {
                if (element.isString) {
                    Text(element.content)
                } else {
                    val flag = element.booleanOrNull
                    val number = element.doubleOrNull
                    when {
                        flag != null -> Bool(flag)
                        number != null -> Num(number)
                        else -> Text(element.content)
                    }
                }
            }
            is JsonArray -> Arr(element.map { fromJson(it) })
            is JsonObject -> Obj(element.entries.associate { entry -> entry.key to fromJson(entry.value) })
        }
    }
}

enum class RuleStage(val raw: String) {
    PRE("pre"), NORMAL("normal"), POST("post");

    companion object {
        fun fromRaw(raw: String): RuleStage? = values().firstOrNull { it.raw == raw }
    }
}

enum class RuleConditionJoin(val raw: String) {
    AND("and"), OR("or");

    companion object {
        fun fromRaw(raw: String): RuleConditionJoin? = values().firstOrNull { it.raw == raw }
    }
}

enum class RuleValueKind(val raw: String) {
    ID("id"), STRING("string"), NUMBER("number"), DATE("date"), BOOLEAN("boolean")
}

/** Field naming and operator tables from Actualist `RuleCondition` and `RulePresentation`. */
object RuleFields {
    fun presentationField(field: String): String = when (field) {
        "description" -> "payee"
        "acct" -> "account"
        "imported_description" -> "imported_payee"
        else -> field
    }

    fun serializedField(field: String): String = when (field) {
        "amount-inflow", "amount-outflow" -> "amount"
        "payee" -> "description"
        "account" -> "acct"
        else -> field
    }

    fun valueKind(field: String): RuleValueKind? = when (serializedField(field)) {
        "account", "acct", "category", "category_group", "description" -> RuleValueKind.ID
        "imported_payee", "payee_name", "notes" -> RuleValueKind.STRING
        "amount" -> RuleValueKind.NUMBER
        "date" -> RuleValueKind.DATE
        "cleared", "reconciled", "transfer", "parent" -> RuleValueKind.BOOLEAN
        else -> null
    }

    fun isDirectionalAmountField(field: String): Boolean = field == "amount-inflow" || field == "amount-outflow"

    fun operations(field: String): List<String> = when (valueKind(field)) {
        RuleValueKind.ID -> {
            val operations = mutableListOf("is", "oneOf", "isNot", "notOneOf")
            if (presentationField(field) == "account") operations.addAll(listOf("onBudget", "offBudget"))
            operations
        }
        RuleValueKind.STRING -> {
            val operations = mutableListOf("is", "contains", "matches", "oneOf", "isNot", "doesNotContain", "notOneOf", "hasTags", "hasAnyTag")
            if (serializedField(field) == "imported_payee") operations.removeAll(listOf("hasTags", "hasAnyTag"))
            if (serializedField(field) == "notes") operations.removeAll(listOf("oneOf", "notOneOf"))
            operations
        }
        RuleValueKind.NUMBER ->
            if (isDirectionalAmountField(field)) {
                listOf("is", "isapprox", "gt", "gte", "lt", "lte")
            } else {
                listOf("is", "isapprox", "isbetween", "gt", "gte", "lt", "lte")
            }
        RuleValueKind.DATE -> listOf("is", "isapprox", "gt", "gte", "lt", "lte")
        RuleValueKind.BOOLEAN -> listOf("is")
        null -> emptyList()
    }
}

data class RuleCondition(
    val field: String,
    val op: String,
    val value: RuleValue,
    val type: String? = null,
    val options: Map<String, RuleValue>? = null,
) {
    val editorField: String
        get() = when {
            field == "description" -> "payee"
            field == "acct" -> "account"
            field == "amount" && options?.get("inflow") == RuleValue.Bool(true) -> "amount-inflow"
            field == "amount" && options?.get("outflow") == RuleValue.Bool(true) -> "amount-outflow"
            else -> field
        }

    val canRoundTripAndEvaluate: Boolean
        get() {
            if (op !in RuleFields.operations(editorField) || !hasSupportedOptions) return false
            if (!(type == null || type == RuleFields.valueKind(field)?.raw)) return false
            if (op == "oneOf" || op == "notOneOf") {
                val values = (value as? RuleValue.Arr)?.values ?: return false
                if (!values.all { it is RuleValue.Text }) return false
            } else {
                when (RuleFields.valueKind(field)) {
                    RuleValueKind.NUMBER -> if (!(op == "isbetween" || value.isNumberLike)) return false
                    RuleValueKind.DATE -> {
                        val text = (value as? RuleValue.Text)?.value ?: return false
                        if (!isSupportedDate(text, op)) return false
                    }
                    RuleValueKind.ID -> if (!(op == "onBudget" || op == "offBudget" || value.isStringLike)) return false
                    RuleValueKind.STRING -> {
                        val text = (value as? RuleValue.Text)?.value ?: return false
                        if (op in listOf("contains", "doesNotContain", "matches", "hasTags", "hasAnyTag") && text.isEmpty()) return false
                    }
                    RuleValueKind.BOOLEAN -> if (value !is RuleValue.Bool) return false
                    null -> return false
                }
            }
            if (op == "isbetween") {
                val range = (value as? RuleValue.Obj)?.values ?: return false
                if (range["num1"]?.isNumberLike != true || range["num2"]?.isNumberLike != true) return false
            }
            return true
        }

    private val hasSupportedOptions: Boolean
        get() {
            val options = options
            if (options == null || options.isEmpty()) return true
            if (field != "amount") return false
            if (!setOf("inflow", "outflow").containsAll(options.keys)) return false
            if (!options.values.all { it is RuleValue.Bool }) return false
            if (options["inflow"] == RuleValue.Bool(true) && options["outflow"] == RuleValue.Bool(true)) return false
            return true
        }

    companion object {
        private fun isSupportedDate(value: String, operation: String): Boolean {
            val components = value.split("-")
            if (components.size !in 1..3) return false
            val year = components[0].toIntOrNull()
            if (year == null || year !in 1..9999) return false
            if (components.size >= 2) {
                if (components[0].length != 4 || components[1].length != 2) return false
                val month = components[1].toIntOrNull()
                if (month == null || month !in 1..12) return false
            }
            if (components.size == 3) {
                if (components[2].length != 2) return false
                val date = try {
                    LocalDate.parse(value)
                } catch (e: Exception) {
                    return false
                }
                if (date.toString() != value) return false
            }
            if (operation != "is") return components.size == 3
            return value.length == 4 || value.length == 7 || value.length == 10
        }
    }
}

data class RuleAction(
    val op: String,
    val field: String? = null,
    val value: RuleValue = RuleValue.Null,
    val type: String? = null,
    val options: Map<String, RuleValue>? = null,
) {
    val editorField: String?
        get() = when (field) {
            "description" -> "payee"
            "acct" -> "account"
            else -> field
        }

    val targetsSplitTransaction: Boolean
        get() = op == "set-split-amount" || options?.get("splitIndex") != null || options?.get("method") != null

    val splitIndex: Int?
        get() = when (val index = options?.get("splitIndex")) {
            is RuleValue.Num -> {
                val number = index.value
                if (number.isFinite() && number >= 0 && number == Math.floor(number) && number < Int.MAX_VALUE) number.toInt() else null
            }
            is RuleValue.Text -> index.value.toIntOrNull()
            else -> null
        }

    val splitMethod: String? get() = (options?.get("method") as? RuleValue.Text)?.value

    private fun splitIndexInRange(index: RuleValue): Boolean = when (index) {
        is RuleValue.Num -> index.value.isFinite() && index.value >= 0 && index.value == Math.floor(index.value) && index.value < 100
        is RuleValue.Text -> index.value.toIntOrNull()?.let { it in 0..99 } == true
        else -> false
    }

    private val hasSupportedActionOptions: Boolean
        get() {
            val options = options
            if (options == null || options.isEmpty()) return true
            if (!setOf("splitIndex", "method").containsAll(options.keys)) return false
            val method = options["method"]
            if (method != null) {
                if (method !is RuleValue.Text || method.value !in listOf("fixed-amount", "fixed-percent", "remainder")) return false
            }
            if (op == "set-split-amount" && method == null) return false
            val index = options["splitIndex"]
            if (index != null && !splitIndexInRange(index)) return false
            return true
        }

    val canRoundTripAndEvaluate: Boolean
        get() {
            if (targetsSplitTransaction || !hasSupportedActionOptions) return false
            return when (op) {
                "set" -> when (editorField) {
                    "account", "category", "date", "notes", "payee" -> value.isStringLike
                    "amount" -> value.isNumberLike
                    "cleared" -> value is RuleValue.Bool
                    else -> false
                }
                "prepend-notes", "append-notes" -> field == null && value.isStringLike && splitIndex == null
                "delete-transaction" -> field == null && value.isStringLike && splitIndex == null
                else -> false
            }
        }

    private val hasSupportedSplitRuntimeOptions: Boolean
        get() {
            val options = options ?: emptyMap()
            if (!setOf("splitIndex", "method", "formula").containsAll(options.keys)) return false
            val index = options["splitIndex"]
            if (index != null && !splitIndexInRange(index)) return false
            val method = options["method"]
            if (method != null) {
                if (method !is RuleValue.Text || method.value !in listOf("fixed-amount", "fixed-percent", "remainder", "formula")) return false
            }
            val formula = options["formula"]
            if (formula != null && formula !is RuleValue.Text) return false
            return true
        }

    /** One-based Actual split contract. Index 0 is apply-to-all. Template and QUERY stay fail-closed. */
    val canExecuteSplitAction: Boolean
        get() {
            if (!hasSupportedSplitRuntimeOptions) return false
            return when (op) {
                "set-split-amount" -> {
                    val method = splitMethod ?: return false
                    if (method !in listOf("fixed-amount", "fixed-percent", "remainder", "formula")) return false
                    if (method == "formula") {
                        val formula = options?.get("formula") as? RuleValue.Text ?: return false
                        if (!formula.value.startsWith("=")) return false
                    } else if (options?.get("formula") != null) {
                        return false
                    }
                    true
                }
                "set" -> {
                    if (options?.get("formula") != null || options?.get("template") != null) return false
                    when (editorField) {
                        "account", "category", "date", "notes", "payee" -> value.isStringLike
                        "amount" -> value.isNumberLike
                        "cleared" -> value is RuleValue.Bool
                        else -> false
                    }
                }
                "prepend-notes", "append-notes" -> field == null && value.isStringLike && splitMethod == null
                else -> false
            }
        }

    /** Runtime-safe actions: the editable set plus `link-schedule` and one-based split actions. */
    val canExecuteAtRuntime: Boolean
        get() {
            if (op == "link-schedule") {
                val scheduleId = value as? RuleValue.Text ?: return false
                return scheduleId.value.isNotEmpty()
            }
            if (targetsSplitTransaction) return canExecuteSplitAction
            return canRoundTripAndEvaluate
        }
}

data class RuleDraft(
    val stage: RuleStage,
    val join: RuleConditionJoin,
    val conditions: List<RuleCondition>,
    val actions: List<RuleAction>,
) {
    val canRoundTripAndEvaluate: Boolean
        get() = conditions.isNotEmpty() && actions.isNotEmpty() &&
            conditions.all { it.canRoundTripAndEvaluate } && actions.all { it.canRoundTripAndEvaluate }
}

/** A rule row. [draft] is null when Actualist cannot round-trip it, but it may still execute. */
class ManagedRule(
    val id: String,
    val draft: RuleDraft?,
    val rawStage: String?,
    val rawConditionsJson: String,
    val rawActionsJson: String,
    val isCompletedScheduleRule: Boolean,
    val rawConditionsJoin: String?,
) {
    /** Conditions and actions that can run even when the rule is not editable. */
    fun executionDraft(): RuleDraft? {
        if (draft != null) return draft
        val conditions = RuleJson.decodeConditions(rawConditionsJson) ?: return null
        val actions = RuleJson.decodeActions(rawActionsJson) ?: return null
        if (conditions.isEmpty() || actions.isEmpty()) return null
        val join = rawConditionsJoin?.let { RuleConditionJoin.fromRaw(it) } ?: RuleConditionJoin.AND
        val stage = rawStage?.let { RuleStage.fromRaw(it) } ?: RuleStage.NORMAL
        return RuleDraft(stage, join, conditions, actions)
    }
}

/** Strict decoding of the `rules.conditions` and `rules.actions` JSON, as Swift `JSONDecoder` does. */
object RuleJson {
    private class DecodeFailure : Exception()

    private val conditionKeys = setOf("field", "op", "value", "type", "options")
    private val actionKeys = setOf("op", "field", "value", "type", "options")

    fun decodeConditions(raw: String): List<RuleCondition>? = decodeArray(raw) { decodeCondition(it) }

    fun decodeActions(raw: String): List<RuleAction>? = decodeArray(raw) { decodeAction(it) }

    fun conditionsHaveOnlyKnownKeys(raw: String): Boolean = hasOnlyKnownKeys(raw, conditionKeys)

    fun actionsHaveOnlyKnownKeys(raw: String): Boolean = hasOnlyKnownKeys(raw, actionKeys)

    private fun <T> decodeArray(raw: String, decode: (JsonObject) -> T): List<T>? {
        return try {
            val array = Json.parseToJsonElement(raw) as? JsonArray ?: return null
            array.map { element -> decode(element as? JsonObject ?: throw DecodeFailure()) }
        } catch (e: Exception) {
            null
        }
    }

    private fun hasOnlyKnownKeys(raw: String, allowed: Set<String>): Boolean {
        return try {
            val array = Json.parseToJsonElement(raw) as? JsonArray ?: return false
            array.all { element -> element is JsonObject && allowed.containsAll(element.keys) }
        } catch (e: Exception) {
            false
        }
    }

    private fun requiredString(obj: JsonObject, key: String): String {
        val primitive = obj[key] as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw DecodeFailure()
        return primitive.content
    }

    private fun optionalString(obj: JsonObject, key: String): String? {
        val element = obj[key] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw DecodeFailure()
        return primitive.content
    }

    private fun optionalOptions(obj: JsonObject): Map<String, RuleValue>? {
        val element = obj["options"] ?: return null
        if (element is JsonNull) return null
        val options = element as? JsonObject ?: throw DecodeFailure()
        return options.entries.associate { entry -> entry.key to RuleValue.fromJson(entry.value) }
    }

    private fun decodeCondition(obj: JsonObject): RuleCondition {
        val field = requiredString(obj, "field")
        val op = requiredString(obj, "op")
        val value = RuleValue.fromJson(obj["value"] ?: throw DecodeFailure())
        return RuleCondition(field, op, value, optionalString(obj, "type"), optionalOptions(obj))
    }

    private fun decodeAction(obj: JsonObject): RuleAction {
        val op = requiredString(obj, "op")
        val field = optionalString(obj, "field")
        val value = obj["value"]?.let { RuleValue.fromJson(it) } ?: RuleValue.Null
        return RuleAction(op, field, value, optionalString(obj, "type"), optionalOptions(obj))
    }
}
