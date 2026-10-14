package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.rules.RuleCondition
import com.sporez.actualist.core.rules.RuleNumbers
import com.sporez.actualist.core.rules.RuleValue
import com.sporez.actualist.core.rules.ScheduleEnding
import com.sporez.actualist.core.rules.ScheduleRecurrence
import com.sporez.actualist.core.rules.ScheduleRecurrenceException
import com.sporez.actualist.core.rules.SchedulePattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

data class ScheduleRuleEditability(
    val hasValidLink: Boolean,
    val canEditAccount: Boolean,
    val canEditPayee: Boolean,
    val canEditAmount: Boolean,
    val canEditDate: Boolean,
)

/** The merged rule JSON; a null side is unchanged. */
data class MergedScheduleRule(val conditions: String?, val actions: String?)

/** Small JSON helpers over `JsonElement`, shared by the schedule rule code. */
internal object ScheduleJson {
    fun decodeArray(json: String?): List<JsonElement>? {
        if (json == null) return null
        return try {
            (Json.parseToJsonElement(json) as? JsonArray)?.toList()
        } catch (e: Exception) {
            null
        }
    }

    fun string(element: JsonElement?, key: String): String? {
        val primitive = (element as? JsonObject)?.get(key) as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    fun setKey(element: JsonElement, key: String, value: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        val updated = obj.toMutableMap()
        updated[key] = value
        return JsonObject(updated)
    }

    fun ruleValue(element: JsonElement?): RuleValue? = element?.let { RuleValue.fromJson(it) }

    /** JavaScript truthiness of a decoded value. */
    fun isTruthy(element: JsonElement): Boolean = when (element) {
        is JsonNull -> false
        is JsonPrimitive -> {
            if (element.isString) {
                element.content.isNotEmpty()
            } else {
                val flag = element.booleanOrNull
                val number = element.doubleOrNull
                when {
                    flag != null -> flag
                    number != null -> number != 0.0 && !number.isNaN()
                    else -> true
                }
            }
        }
        else -> true
    }

    /** Compact JSON with sorted keys and unescaped slashes, like Swift's `.sortedKeys, .withoutEscapingSlashes`. */
    fun encode(values: List<JsonElement>): String {
        val builder = StringBuilder()
        write(JsonArray(values), builder)
        return builder.toString()
    }

    private fun write(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> if (element.isString) quote(element.content, out) else out.append(element.content)
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonObject -> {
                out.append('{')
                element.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    quote(key, out)
                    out.append(':')
                    write(element.getValue(key), out)
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

/** Port of Actualist `ScheduleRuleMutation`: schedule rule JSON editing and next-date rules. */
object ScheduleRuleMutation {
    private const val MAX_EXACT_DOUBLE = 9_007_199_254_740_992L

    fun editability(scheduleId: String, conditionsJson: String?, actionsJson: String?): ScheduleRuleEditability {
        val conditions = ScheduleJson.decodeArray(conditionsJson)
        val actions = ScheduleJson.decodeArray(actionsJson)
        val linkActions = actions?.filter { ScheduleJson.string(it, "op") == "link-schedule" } ?: emptyList()
        val hasValidLink = linkActions.size == 1 && ScheduleJson.string(linkActions[0], "value") == scheduleId
        val conditionObjects = conditions?.mapNotNull { it as? JsonObject } ?: emptyList()
        val account = conditionObjects.filter { (ScheduleJson.string(it, "field") ?: "") in listOf("account", "acct") }
        val payee = conditionObjects.filter { (ScheduleJson.string(it, "field") ?: "") in listOf("payee", "description") }
        val amount = conditionObjects.filter { ScheduleJson.string(it, "field") == "amount" }
        val date = conditionObjects.filter { ScheduleJson.string(it, "field") == "date" }
        val dateRule: ScheduleDateRule = if (date.size == 1) {
            val value = ScheduleJson.ruleValue(date[0]["value"])
            if (value == null) {
                ScheduleDateRule.Unavailable
            } else {
                ScheduleRuleProjection.dateRuleFrom(RuleCondition("date", ScheduleJson.string(date[0], "op") ?: "", value))
            }
        } else {
            ScheduleDateRule.Unavailable
        }
        return ScheduleRuleEditability(
            hasValidLink = hasValidLink,
            canEditAccount = account.size == 1 &&
                ScheduleJson.string(account[0], "op") == "is" &&
                ScheduleJson.string(account[0], "value") != null,
            canEditPayee = payee.size <= 1 &&
                (payee.isEmpty() || (ScheduleJson.string(payee[0], "op") == "is" && ScheduleJson.string(payee[0], "value") != null)),
            canEditAmount = amount.size == 1 && validAmount(amount[0]),
            canEditDate = date.size == 1 &&
                (ScheduleJson.string(date[0], "op") ?: "") in listOf("is", "isapprox") &&
                dateRule != ScheduleDateRule.Unavailable,
        )
    }

    fun merge(conditionsJson: String, actionsJson: String, scheduleId: String, edits: ScheduleEditFields): MergedScheduleRule {
        if (!edits.changesDefinition) {
            val linkActions = ScheduleJson.decodeArray(actionsJson)
                ?: throw ScheduleRuleMutationException(ScheduleRuleMutationError.MALFORMED_ACTIONS)
            val links = linkActions.filter { ScheduleJson.string(it, "op") == "link-schedule" }
            if (links.size != 1 || ScheduleJson.string(links[0], "value") != scheduleId) {
                throw ScheduleRuleMutationException(ScheduleRuleMutationError.INVALID_SCHEDULE_LINK)
            }
            return MergedScheduleRule(null, null)
        }
        val conditions = (ScheduleJson.decodeArray(conditionsJson)
            ?: throw ScheduleRuleMutationException(ScheduleRuleMutationError.MALFORMED_CONDITIONS)).toMutableList()
        var actions: List<JsonElement> = ScheduleJson.decodeArray(actionsJson)
            ?: throw ScheduleRuleMutationException(ScheduleRuleMutationError.MALFORMED_ACTIONS)
        val links = actions.filter { ScheduleJson.string(it, "op") == "link-schedule" }
        if (links.size != 1 || ScheduleJson.string(links[0], "value") != scheduleId) {
            throw ScheduleRuleMutationException(ScheduleRuleMutationError.INVALID_SCHEDULE_LINK)
        }

        val originalConditions: List<JsonElement> = conditions.toList()
        val originalActions: List<JsonElement> = actions
        replace(conditions, setOf("account", "acct"), edits.accountId, "account", setOf("is"))
        replace(conditions, setOf("payee", "description"), edits.payeeMappingId, "description", setOf("is"))
        if (!edits.amount.isUnchanged) {
            val change = edits.amount as? ScheduleOptionalChange.Assign<ScheduleAmountDraft>
            val amount = change?.value ?: throw ScheduleRuleMutationException(ScheduleRuleMutationError.INVALID_DEFINITION)
            val (operation, value) = amountValue(amount)
            replaceAmount(conditions, operation, value)
        }
        if (!edits.dateRule.isUnchanged) {
            val change = edits.dateRule as? ScheduleOptionalChange.Assign<ScheduleDateRule>
            val dateRule = change?.value ?: throw ScheduleRuleMutationException(ScheduleRuleMutationError.INVALID_DEFINITION)
            val (operation, value) = dateValue(dateRule)
            replaceDate(conditions, operation, value)
        }

        val amountCondition = conditions.firstOrNull { element ->
            (ScheduleJson.string(element, "op") ?: "") in listOf("is", "isapprox", "isbetween") &&
                ScheduleJson.string(element, "field") == "amount"
        }
        val postingAmount = postingAmountValue((amountCondition as? JsonObject)?.get("value"))
        if (postingAmount != null) {
            actions = actions.map { action ->
                if (ScheduleJson.string(action, "op") == "set" &&
                    ScheduleJson.string(action, "field") == "amount" &&
                    !hasTruthyFormulaOrTemplate(action)
                ) {
                    ScheduleJson.setKey(action, "value", postingAmount)
                } else {
                    action
                }
            }
        }

        val conditionData = if (conditions.toList() == originalConditions) null else ScheduleJson.encode(conditions)
        val actionData = if (actions == originalActions) null else ScheduleJson.encode(actions)
        return MergedScheduleRule(conditionData, actionData)
    }

    fun newRuleJson(scheduleId: String, definition: ScheduleDefinitionDraft): Pair<String, String> {
        val conditions = mutableListOf<JsonElement>()
        conditions.add(condition("account", "is", JsonPrimitive(definition.accountId)))
        definition.payeeMappingId?.let { conditions.add(condition("description", "is", JsonPrimitive(it))) }
        val (amountOperation, amount) = amountValue(definition.amount)
        conditions.add(condition("amount", amountOperation, amount))
        val (dateOperation, date) = dateValue(definition.dateRule)
        if (dateOperation != "is" && dateOperation != "isapprox") {
            throw ScheduleRuleMutationException(ScheduleRuleMutationError.INVALID_DEFINITION)
        }
        conditions.add(condition("date", dateOperation, date))
        val actions = listOf<JsonElement>(
            JsonObject(mapOf("op" to JsonPrimitive("link-schedule"), "value" to JsonPrimitive(scheduleId))),
        )
        return ScheduleJson.encode(conditions) to ScheduleJson.encode(actions)
    }

    /** An ended schedule keeps its last occurrence, like Actual's `getNextDate`. */
    fun initialNextDate(rule: ScheduleDateRule, asOf: String): String? = when (rule) {
        is ScheduleDateRule.OneTime -> rule.dayId
        is ScheduleDateRule.Recurring -> rule.recurrenceValue.nextOccurrence(asOf, true) ?: rule.recurrenceValue.lastOccurrence()
        is ScheduleDateRule.Unavailable -> throw ScheduleRuleMutationException(ScheduleRuleMutationError.INVALID_DEFINITION)
    }

    fun nextDateAfterSkip(recurrence: ScheduleRecurrence, currentDayId: String): String? = try {
        recurrence.nextDateAfter(currentDayId)
    } catch (e: ScheduleRecurrenceException) {
        if (e.message == "invalid start date") {
            throw ScheduleRuleMutationException(ScheduleRuleMutationError.INVALID_DEFINITION)
        }
        throw e
    }

    fun updateNextDate(rule: ScheduleDateRule, asOf: String, currentEffectiveDate: String?): String? {
        val next = initialNextDate(rule, asOf) ?: return null
        return if (next == currentEffectiveDate) null else next
    }

    private fun replace(
        conditions: MutableList<JsonElement>,
        fieldNames: Set<String>,
        change: ScheduleOptionalChange<String>,
        newField: String,
        permittedOperations: Set<String>,
    ) {
        if (change !is ScheduleOptionalChange.Assign<String>) return
        val value: String? = change.value
        val matches = conditions.indices.filter { index ->
            val element = conditions[index]
            element is JsonObject &&
                (ScheduleJson.string(element, "field") ?: "") in fieldNames &&
                (ScheduleJson.string(element, "op") ?: "") in permittedOperations
        }
        if (matches.size > 1) throw ScheduleRuleMutationException(ScheduleRuleMutationError.UNSUPPORTED_FIELD, newField)
        val index = matches.firstOrNull()
        if (index != null) {
            if (value == null) {
                conditions.removeAt(index)
            } else {
                var updated = conditions[index]
                updated = ScheduleJson.setKey(updated, "field", JsonPrimitive(newField))
                updated = ScheduleJson.setKey(updated, "value", JsonPrimitive(value))
                conditions[index] = updated
            }
        } else if (value != null) {
            conditions.add(condition(newField, "is", JsonPrimitive(value)))
        }
    }

    private fun replaceAmount(conditions: MutableList<JsonElement>, operation: String, value: JsonElement) {
        val indices = conditions.indices.filter { ScheduleJson.string(conditions[it], "field") == "amount" }
        if (indices.size != 1) throw ScheduleRuleMutationException(ScheduleRuleMutationError.UNSUPPORTED_FIELD, "amount")
        val index = indices[0]
        var updated = ScheduleJson.setKey(conditions[index], "op", JsonPrimitive(operation))
        updated = ScheduleJson.setKey(
            updated,
            "value",
            preservingUnknownObjectKeys((conditions[index] as? JsonObject)?.get("value"), value, setOf("num1", "num2")),
        )
        conditions[index] = updated
    }

    private fun replaceDate(conditions: MutableList<JsonElement>, operation: String, value: JsonElement) {
        val indices = conditions.indices.filter { index ->
            val element = conditions[index]
            element is JsonObject &&
                ScheduleJson.string(element, "field") == "date" &&
                (ScheduleJson.string(element, "op") ?: "") in listOf("is", "isapprox")
        }
        if (indices.size != 1) throw ScheduleRuleMutationException(ScheduleRuleMutationError.UNSUPPORTED_FIELD, "date")
        val index = indices[0]
        var updated = ScheduleJson.setKey(conditions[index], "op", JsonPrimitive(operation))
        updated = ScheduleJson.setKey(
            updated,
            "value",
            preservingUnknownObjectKeys(
                (conditions[index] as? JsonObject)?.get("value"),
                value,
                setOf(
                    "start", "frequency", "interval", "patterns", "skipWeekend", "weekendSolveMode",
                    "endMode", "endOccurrences", "endDate",
                ),
            ),
        )
        conditions[index] = updated
    }

    private fun preservingUnknownObjectKeys(oldValue: JsonElement?, newValue: JsonElement, knownKeys: Set<String>): JsonElement {
        val oldObject = oldValue as? JsonObject ?: return newValue
        val newObject = newValue as? JsonObject ?: return newValue
        val merged = LinkedHashMap<String, JsonElement>()
        for ((key, value) in oldObject) {
            if (key !in knownKeys) merged[key] = value
        }
        for ((key, value) in newObject) merged[key] = value
        return JsonObject(merged)
    }

    private fun exactlyRepresentable(value: Long): Boolean = Math.abs(value) <= MAX_EXACT_DOUBLE

    private fun amountValue(amount: ScheduleAmountDraft): Pair<String, JsonElement> = when (amount) {
        is ScheduleAmountDraft.Exact -> {
            if (!exactlyRepresentable(amount.amount)) throw ScheduleRuleMutationException(ScheduleRuleMutationError.INVALID_DEFINITION)
            "is" to JsonPrimitive(amount.amount)
        }
        is ScheduleAmountDraft.Approximate -> {
            if (!exactlyRepresentable(amount.amount)) throw ScheduleRuleMutationException(ScheduleRuleMutationError.INVALID_DEFINITION)
            "isapprox" to JsonPrimitive(amount.amount)
        }
        is ScheduleAmountDraft.Range -> {
            if (!exactlyRepresentable(amount.lower) || !exactlyRepresentable(amount.upper)) {
                throw ScheduleRuleMutationException(ScheduleRuleMutationError.INVALID_DEFINITION)
            }
            "isbetween" to JsonObject(mapOf("num1" to JsonPrimitive(amount.lower), "num2" to JsonPrimitive(amount.upper)))
        }
    }

    private fun postingAmountValue(value: JsonElement?): JsonElement? {
        if (value == null) return null
        if (value is JsonObject) {
            val lower = ScheduleJson.ruleValue(value["num1"])?.number
            val upper = ScheduleJson.ruleValue(value["num2"])?.number
            if (lower != null && upper != null) return roundedNumber((lower + upper) / 2)
        }
        val number = ScheduleJson.ruleValue(value)?.number ?: return null
        return roundedNumber(number)
    }

    private fun roundedNumber(value: Double): JsonElement? {
        if (!value.isFinite()) return null
        val integer = RuleNumbers.exactLong(Math.floor(value + 0.5)) ?: return null
        return JsonPrimitive(integer)
    }

    private fun dateValue(rule: ScheduleDateRule): Pair<String, JsonElement> = when (rule) {
        is ScheduleDateRule.OneTime -> {
            if (ScheduleRecurrence.parseDay(rule.dayId) == null || rule.operation !in listOf("is", "isapprox")) {
                throw ScheduleRuleMutationException(ScheduleRuleMutationError.INVALID_DEFINITION)
            }
            rule.operation to JsonPrimitive(rule.dayId)
        }
        is ScheduleDateRule.Recurring -> {
            if (rule.operation !in listOf("is", "isapprox")) {
                throw ScheduleRuleMutationException(ScheduleRuleMutationError.INVALID_DEFINITION)
            }
            val recurrence = rule.recurrenceValue
            val config = LinkedHashMap<String, JsonElement>()
            config["start"] = JsonPrimitive(recurrence.startDayId)
            config["frequency"] = JsonPrimitive(recurrence.frequency.raw)
            if (recurrence.interval != 1L) config["interval"] = JsonPrimitive(recurrence.interval)
            if (recurrence.patterns.isNotEmpty()) {
                config["patterns"] = JsonArray(
                    recurrence.patterns.map { pattern ->
                        when (pattern) {
                            is SchedulePattern.DayOfMonth ->
                                JsonObject(mapOf("type" to JsonPrimitive("day"), "value" to JsonPrimitive(pattern.day)))
                            is SchedulePattern.Weekday ->
                                JsonObject(mapOf("type" to JsonPrimitive(pattern.weekday.raw), "value" to JsonPrimitive(pattern.ordinal)))
                        }
                    },
                )
            }
            if (recurrence.skipWeekend) {
                config["skipWeekend"] = JsonPrimitive(true)
                config["weekendSolveMode"] = JsonPrimitive(recurrence.weekendAdjustment.raw)
            }
            when (val limit = recurrence.ending) {
                is ScheduleEnding.Never -> Unit
                is ScheduleEnding.AfterOccurrences -> {
                    config["endMode"] = JsonPrimitive("after_n_occurrences")
                    config["endOccurrences"] = JsonPrimitive(limit.count)
                }
                is ScheduleEnding.OnDate -> {
                    config["endMode"] = JsonPrimitive("on_date")
                    config["endDate"] = JsonPrimitive(limit.dayId)
                }
            }
            rule.operation to JsonObject(config)
        }
        is ScheduleDateRule.Unavailable -> throw ScheduleRuleMutationException(ScheduleRuleMutationError.INVALID_DEFINITION)
    }

    private fun validAmount(condition: JsonObject): Boolean {
        val value = condition["value"] ?: return false
        return when (ScheduleJson.string(condition, "op")) {
            "is", "isapprox" -> ScheduleJson.ruleValue(value)?.number?.isFinite() == true
            "isbetween" -> {
                val range = value as? JsonObject ?: return false
                val lower = ScheduleJson.ruleValue(range["num1"])?.number ?: return false
                val upper = ScheduleJson.ruleValue(range["num2"])?.number ?: return false
                lower.isFinite() && upper.isFinite()
            }
            else -> false
        }
    }

    private fun condition(field: String, operation: String, value: JsonElement): JsonElement =
        JsonObject(mapOf("op" to JsonPrimitive(operation), "field" to JsonPrimitive(field), "value" to value))

    private fun hasTruthyFormulaOrTemplate(action: JsonElement): Boolean {
        val options = (action as? JsonObject)?.get("options") as? JsonObject ?: return false
        return options["formula"]?.let { ScheduleJson.isTruthy(it) } == true ||
            options["template"]?.let { ScheduleJson.isTruthy(it) } == true
    }
}
