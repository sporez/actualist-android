package com.sporez.actualist.core.templates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** A template the engine cannot apply, with the reason shown to the user. Port of `LocalFirstError.unsupportedTemplate`. */
class UnsupportedTemplateException(val reason: String) : Exception(reason)

/** Whether an Apply Templates run fills only empty budgets or overwrites existing ones. */
enum class TemplateApplyMode { FILL_EMPTY, OVERWRITE }

/**
 * The open budget's currency for template math: ISO code, minor-unit places and hide-fraction.
 * Port of the parts of Actualist `BudgetCurrency` the engine uses.
 */
data class TemplateCurrency(val code: String, val decimalPlaces: Int, val hideFraction: Boolean) {
    companion object {
        val NONE = TemplateCurrency("", 2, false)
        val USD = TemplateCurrency("USD", 2, false)
        val JPY = TemplateCurrency("JPY", 0, false)
        private val zeroDecimalCodes = setOf("JPY", "KRW")

        fun catalog(code: String, hideFraction: Boolean = false): TemplateCurrency {
            val resolved = code.trim().uppercase()
            return TemplateCurrency(resolved, if (resolved in zeroDecimalCodes) 0 else 2, hideFraction)
        }
    }
}

data class TemplatePeriod(val amount: Int?, val period: String?)

data class TemplateLimit(val amount: Double?, val period: String?, val hold: Boolean?, val start: String?)

/** A goal_def entry from Actual's template JSON. */
data class TemplateEntry(
    val type: String,
    val directive: String? = null,
    val priority: Int? = null,
    val monthly: Double? = null,
    val amount: Double? = null,
    val percentage: Double? = null,
    val percent: Double? = null,
    val previous: Boolean? = null,
    val sourceCategory: String? = null,
    val numMonths: Int? = null,
    val adjustment: Double? = null,
    val adjustmentType: String? = null,
    val period: TemplatePeriod? = null,
    val starting: String? = null,
    val lookBack: Int? = null,
    val limit: TemplateLimit? = null,
    val standaloneLimit: TemplateLimit? = null,
    val month: String? = null,
    val fromMonth: String? = null,
    val annual: Boolean? = null,
    val repeatInterval: Int? = null,
    val weight: Double? = null,
    val name: String? = null,
    val scheduleId: String? = null,
    val full: Boolean? = null,
) {
    val percentageAmount: Double? get() = percent ?: percentage

    /** Actual requires `directive`. Only `template` entries change the budget. */
    val setsBudget: Boolean get() = directive == "template"
    val isGoal: Boolean get() = directive == "goal" && type == "goal"

    val presentScheduleId: String? get() = scheduleId?.takeIf { it.isNotEmpty() }

    val trimmedScheduleName: String? get() = name?.trim()?.takeIf { it.isNotEmpty() }

    val scheduleLookupKey: String? get() = presentScheduleId ?: trimmedScheduleName

    val missingScheduleReason: String
        get() {
            presentScheduleId?.let { return "Schedule ${name ?: it} does not exist" }
            trimmedScheduleName?.let { return "Schedule $it does not exist" }
            return "Schedule template has no scheduleId or name"
        }
}

/** Goal directives only: no `template` entry other than a limit. */
val List<TemplateEntry>.isGoalOnly: Boolean
    get() = none { it.directive == "template" && it.type != "limit" } && any { it.isGoal }

/** Strict decoding of `categories.goal_def`, as Swift's `JSONDecoder` does for `BudgetTemplateEntry`. */
object TemplateEntryDecoder {
    fun decode(json: String): List<TemplateEntry> {
        val root = try {
            Json.parseToJsonElement(json)
        } catch (error: Exception) {
            throw UnsupportedTemplateException("unreadable template definition")
        }
        val array = root as? JsonArray ?: throw UnsupportedTemplateException("can't decode template: expected an array")
        return array.mapIndexed { index, element ->
            val obj = element as? JsonObject ?: throw UnsupportedTemplateException("can't decode template[$index]: expected an object")
            decodeEntry(obj, "template[$index]")
        }
    }

    private fun fail(path: String, key: String, expected: String): Nothing =
        throw UnsupportedTemplateException("can't decode $path.$key: expected $expected")

    private fun present(obj: JsonObject, key: String): JsonElement? = obj[key]?.takeIf { it !is JsonNull }

    private fun string(obj: JsonObject, key: String, path: String): String? {
        val element = present(obj, key) ?: return null
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) fail(path, key, "a string")
        return primitive.content
    }

    private fun double(obj: JsonObject, key: String, path: String): Double? {
        val element = present(obj, key) ?: return null
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString) fail(path, key, "a number")
        return primitive.doubleOrNull ?: fail(path, key, "a number")
    }

    private fun int(obj: JsonObject, key: String, path: String): Int? {
        val value = double(obj, key, path) ?: return null
        if (value != Math.rint(value) || value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) {
            fail(path, key, "an integer")
        }
        return value.toInt()
    }

    private fun bool(obj: JsonObject, key: String, path: String): Boolean? {
        val element = present(obj, key) ?: return null
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString) fail(path, key, "a boolean")
        return primitive.booleanOrNull ?: fail(path, key, "a boolean")
    }

    private fun decodeLimit(obj: JsonObject, path: String): TemplateLimit = TemplateLimit(
        amount = double(obj, "amount", path),
        period = string(obj, "period", path),
        hold = bool(obj, "hold", path),
        start = string(obj, "start", path),
    )

    private fun decodeEntry(obj: JsonObject, path: String): TemplateEntry {
        val type = string(obj, "type", path) ?: throw UnsupportedTemplateException("can't decode $path.type: missing required field")
        val amount = double(obj, "amount", path)
        val limitElement = present(obj, "limit")
        val limit = if (limitElement == null) {
            null
        } else {
            decodeLimit(limitElement as? JsonObject ?: fail(path, "limit", "an object"), "$path.limit")
        }
        var period: TemplatePeriod? = null
        var standalone: TemplateLimit? = null
        if (type == "limit") {
            standalone = TemplateLimit(
                amount = amount,
                period = string(obj, "period", path),
                hold = bool(obj, "hold", path),
                start = string(obj, "start", path),
            )
        } else {
            val periodElement = present(obj, "period")
            if (periodElement != null) {
                val periodObject = periodElement as? JsonObject ?: fail(path, "period", "an object")
                period = TemplatePeriod(
                    amount = int(periodObject, "amount", "$path.period"),
                    period = string(periodObject, "period", "$path.period"),
                )
            }
        }
        return TemplateEntry(
            type = type,
            directive = string(obj, "directive", path),
            priority = int(obj, "priority", path),
            monthly = double(obj, "monthly", path),
            amount = amount,
            percentage = double(obj, "percentage", path),
            percent = double(obj, "percent", path),
            previous = bool(obj, "previous", path),
            sourceCategory = string(obj, "category", path),
            numMonths = int(obj, "numMonths", path),
            adjustment = double(obj, "adjustment", path),
            adjustmentType = string(obj, "adjustmentType", path),
            period = period,
            starting = string(obj, "starting", path),
            lookBack = int(obj, "lookBack", path),
            limit = limit,
            standaloneLimit = standalone,
            month = string(obj, "month", path),
            fromMonth = string(obj, "from", path),
            annual = bool(obj, "annual", path),
            repeatInterval = int(obj, "repeat", path),
            weight = double(obj, "weight", path),
            name = string(obj, "name", path),
            scheduleId = string(obj, "scheduleId", path),
            full = bool(obj, "full", path),
        )
    }
}
