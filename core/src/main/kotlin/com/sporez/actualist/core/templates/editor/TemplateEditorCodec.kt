package com.sporez.actualist.core.templates.editor

import com.sporez.actualist.core.templates.TemplateEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.time.LocalDate
import java.util.TreeMap

/**
 * The editor's loss-aware JSON boundary: draft entries to and from Actual's stored `goal_def` document.
 * Port of `BudgetTemplateEditorCodec` and `BudgetTemplateEditorEncoder`. `TemplateEntry` is intentionally
 * not used here because its apply-focused shape omits authoring metadata.
 */
object TemplateEditorCodec {
    /**
     * Decodes the normalized authoring catalog. Legacy fixed/simple nested limits become standalone Limit
     * entries before the editor sees them. Null means the definition is unreadable, unsupported, or holds
     * a value the editor cannot represent: it stays view-only.
     */
    fun decodeEditor(json: String?, today: LocalDate): List<TemplateDraftEntry>? {
        return try {
            val raw = rawEntries(json)
            val drafts = normalize(raw, today)
            if (!(raw.isEmpty() || drafts.isNotEmpty()) || !drafts.all { isEditorReady(it) }) null else drafts
        } catch (error: TemplateDefinitionParseFailure) {
            null
        } catch (error: Exception) {
            null
        }
    }

    /** Strictly decodes and normalizes the complete catalog; throws [TemplateDefinitionParseFailure]. */
    fun decodeNormalized(json: String?, today: LocalDate): List<TemplateDraftEntry> =
        normalize(rawEntries(json), today)

    /**
     * Representability is distinct from validity. Known, finite values can open for repair; the shared
     * authoring validator gates every save.
     */
    fun isEditorReady(entry: TemplateDraftEntry): Boolean = when (entry) {
        is TemplateDraftEntry.MonthlyFixed -> entry.amount.isFinite()
        is TemplateDraftEntry.DateTarget -> entry.amount.isFinite()
        is TemplateDraftEntry.Percentage -> entry.percent.isFinite()
        is TemplateDraftEntry.BalanceLimit -> entry.amount.isFinite()
        is TemplateDraftEntry.Refill -> true
        is TemplateDraftEntry.Copy -> true
        is TemplateDraftEntry.Average -> entry.adjustment?.value?.isFinite() ?: true
        is TemplateDraftEntry.Schedule -> entry.adjustment?.value?.isFinite() ?: true
        is TemplateDraftEntry.Remainder -> entry.weight.isFinite() && entry.legacyLimit == null
        is TemplateDraftEntry.Goal -> entry.amount.isFinite()
    }

    // region Decode

    private class RawPeriod(val period: String?, val amount: Int?)

    private class RawLimit(val amount: Double?, val hold: Boolean?, val period: String?, val start: String?)

    private class RawEntry(
        val type: String,
        val directive: String?,
        val description: String?,
        val priority: Int?,
        val monthly: Double?,
        val amount: Double?,
        val percentage: Double?,
        val percent: Double?,
        val previous: Boolean?,
        val sourceCategory: String?,
        val numMonths: Int?,
        val adjustment: Double?,
        val adjustmentType: String?,
        val period: RawPeriod?,
        val starting: String?,
        val lookBack: Int?,
        val limit: RawLimit?,
        val month: String?,
        val fromMonth: String?,
        val annual: Boolean?,
        val repeatInterval: Int?,
        val weight: Double?,
        val name: String?,
        val scheduleId: String?,
        val full: Boolean?,
        val limitPeriod: String?,
        val limitAmount: Double?,
        val limitHold: Boolean?,
        val limitStart: String?,
    ) {
        val editorLimit: TemplateUpToHold?
            get() {
                val nested = limit
                if (nested != null) {
                    val nestedAmount = nested.amount ?: return null
                    val nestedPeriod = nested.period ?: return null
                    return TemplateUpToHold(nestedAmount, nested.hold ?: false, nestedPeriod, nested.start)
                }
                val flatAmount = limitAmount ?: return null
                val flatPeriod = limitPeriod ?: return null
                return TemplateUpToHold(flatAmount, limitHold ?: false, flatPeriod, limitStart)
            }

        val normalizedDescription: String?
            get() = if (description == null || description.trim().isEmpty()) null else description

        val presentScheduleId: String?
            get() = if (scheduleId == null || scheduleId.trim().isEmpty()) null else scheduleId
    }

    private val commonKeys = setOf("directive", "type", "description")

    private val allowedKeysByType: Map<String, Set<String>> = mapOf(
        "simple" to commonKeys + setOf("monthly", "priority", "limit"),
        "periodic" to commonKeys + setOf("amount", "period", "starting", "priority", "limit"),
        "by" to commonKeys + setOf("amount", "month", "annual", "repeat", "from", "priority"),
        "spend" to commonKeys + setOf("amount", "month", "annual", "repeat", "from", "priority"),
        "percentage" to commonKeys + setOf("percent", "percentage", "previous", "category", "priority"),
        "limit" to commonKeys + setOf("amount", "period", "hold", "start", "priority"),
        "refill" to commonKeys + setOf("priority"),
        "schedule" to commonKeys + setOf("name", "scheduleId", "full", "adjustment", "adjustmentType", "priority"),
        "average" to commonKeys + setOf("numMonths", "adjustment", "adjustmentType", "priority"),
        "copy" to commonKeys + setOf("lookBack", "priority", "limit"),
        "remainder" to commonKeys + setOf("weight", "limit", "priority"),
        "goal" to commonKeys + setOf("amount", "priority"),
    )

    private fun unreadable(): Nothing = throw TemplateDefinitionParseFailure.Unreadable()

    private fun present(obj: JsonObject, key: String) = obj[key]?.takeIf { it !is JsonNull }

    private fun string(obj: JsonObject, key: String): String? {
        val element = present(obj, key) ?: return null
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) unreadable()
        return primitive.content
    }

    private fun double(obj: JsonObject, key: String): Double? {
        val element = present(obj, key) ?: return null
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString) unreadable()
        return primitive.doubleOrNull ?: unreadable()
    }

    private fun int(obj: JsonObject, key: String): Int? {
        val element = present(obj, key) ?: return null
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString) unreadable()
        primitive.content.toIntOrNull()?.let { return it }
        val asDouble = primitive.doubleOrNull ?: unreadable()
        if (asDouble != Math.rint(asDouble) || Math.abs(asDouble) > Int.MAX_VALUE.toDouble()) unreadable()
        return asDouble.toInt()
    }

    private fun bool(obj: JsonObject, key: String): Boolean? {
        val element = present(obj, key) ?: return null
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString) unreadable()
        return primitive.booleanOrNull ?: unreadable()
    }

    private fun nestedObject(obj: JsonObject, key: String): JsonObject? {
        val element = present(obj, key) ?: return null
        return element as? JsonObject ?: unreadable()
    }

    private fun parseRawEntry(obj: JsonObject): RawEntry {
        val type = string(obj, "type") ?: unreadable()
        val limitObject = nestedObject(obj, "limit")
        val limit = limitObject?.let {
            RawLimit(double(it, "amount"), bool(it, "hold"), string(it, "period"), string(it, "start"))
        }
        val amount = double(obj, "amount")
        val isLimitType = type == "limit"
        val periodObject = if (isLimitType) null else nestedObject(obj, "period")
        val period = periodObject?.let { RawPeriod(string(it, "period"), int(it, "amount")) }
        return RawEntry(
            type = type,
            directive = string(obj, "directive"),
            description = string(obj, "description"),
            priority = int(obj, "priority"),
            monthly = double(obj, "monthly"),
            amount = amount,
            percentage = double(obj, "percentage"),
            percent = double(obj, "percent"),
            previous = bool(obj, "previous"),
            sourceCategory = string(obj, "category"),
            numMonths = int(obj, "numMonths"),
            adjustment = double(obj, "adjustment"),
            adjustmentType = string(obj, "adjustmentType"),
            period = period,
            starting = string(obj, "starting"),
            lookBack = int(obj, "lookBack"),
            limit = limit,
            month = string(obj, "month"),
            fromMonth = string(obj, "from"),
            annual = bool(obj, "annual"),
            repeatInterval = int(obj, "repeat"),
            weight = double(obj, "weight"),
            name = string(obj, "name"),
            scheduleId = string(obj, "scheduleId"),
            full = bool(obj, "full"),
            limitPeriod = if (isLimitType) string(obj, "period") else null,
            limitAmount = if (isLimitType) amount else null,
            limitHold = if (isLimitType) bool(obj, "hold") else null,
            limitStart = if (isLimitType) string(obj, "start") else null,
        )
    }

    private fun validateNested(value: kotlinx.serialization.json.JsonElement?, type: String, label: String, allowed: Set<String>) {
        if (value == null || value is JsonNull) return
        val obj = value as? JsonObject ?: unreadable()
        val unknown = obj.keys.filter { it !in allowed }.sorted().firstOrNull()
        if (unknown != null) throw TemplateDefinitionParseFailure.UnsupportedField("$type.$label.$unknown")
    }

    private fun rawEntries(json: String?): List<RawEntry> {
        if (json == null) return emptyList()
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == "null") return emptyList()
        val root = try {
            Json.parseToJsonElement(trimmed)
        } catch (error: Exception) {
            unreadable()
        }
        val array = root as? JsonArray ?: unreadable()
        val objects = array.map { it as? JsonObject ?: unreadable() }
        for (obj in objects) {
            val typePrimitive = obj["type"] as? JsonPrimitive
            val type = if (typePrimitive != null && typePrimitive.isString) typePrimitive.content else null
            val allowed = if (type == null) null else allowedKeysByType[type]
            if (type == null || allowed == null) {
                throw TemplateDefinitionParseFailure.UnsupportedType("unknown template type")
            }
            val unknown = obj.keys.filter { it !in allowed }.sorted().firstOrNull()
            if (unknown != null) throw TemplateDefinitionParseFailure.UnsupportedField("$type.$unknown")
            validateNested(obj["limit"], type, "limit", setOf("amount", "hold", "period", "start"))
            if (type != "limit") validateNested(obj["period"], type, "period", setOf("amount", "period"))
        }
        val entries = objects.map { parseRawEntry(it) }
        for (entry in entries) {
            val limit = entry.limit
            if (limit != null && (limit.amount == null || limit.period == null)) {
                throw TemplateDefinitionParseFailure.UnsupportedType(entry.type + " limit")
            }
            val period = entry.period
            if (period != null && (period.period == null || period.amount == null)) {
                throw TemplateDefinitionParseFailure.UnsupportedType(entry.type + " period")
            }
        }
        return entries
    }

    private fun normalize(entries: List<RawEntry>, today: LocalDate): List<TemplateDraftEntry> {
        val drafts = mutableListOf<TemplateDraftEntry>()
        for (entry in entries) drafts.addAll(normalizedDrafts(entry, today))
        if (drafts.size > TemplateEngine.Bounds.MAXIMUM_ENTRIES_PER_CATEGORY) {
            throw TemplateDefinitionParseFailure.UnsupportedType("too many template entries")
        }
        return drafts
    }

    private fun unsupported(type: String): Nothing = throw TemplateDefinitionParseFailure.UnsupportedType(type)

    private fun normalizedDrafts(entry: RawEntry, today: LocalDate): List<TemplateDraftEntry> {
        val directive = entry.directive?.trim()
        val directiveOk = directive != null &&
            ((entry.type == "goal" && directive == "goal") || (entry.type != "goal" && directive == "template"))
        if (!directiveOk) unsupported("unsupported template directive")
        when (entry.type) {
            "simple" -> {
                val result = mutableListOf<TemplateDraftEntry>()
                val monthly = entry.monthly
                val hasMonthly = monthly != null && monthly != 0.0
                val limit = requiredOrNilLimit(entry.editorLimit, entry.type)
                if (limit != null) {
                    result.add(
                        TemplateDraftEntry.balanceLimit(
                            amount = limit.amount,
                            hold = limit.hold,
                            period = limitPeriod(limit.period),
                            start = limit.start,
                            description = if (hasMonthly) null else entry.normalizedDescription,
                        ),
                    )
                    if (monthly == null) {
                        result.add(TemplateDraftEntry.refill(priority = requiredPriority(entry.priority, entry.type)))
                    }
                }
                if (monthly != null && (hasMonthly || entry.limit == null)) {
                    result.add(
                        TemplateDraftEntry.monthlyFixed(
                            amount = monthly,
                            priority = requiredPriority(entry.priority, entry.type),
                            today = today,
                            description = entry.normalizedDescription,
                        ),
                    )
                }
                return result
            }
            "periodic" -> {
                val amount = entry.amount
                val period = entry.period
                val cadence = TemplateCadence.fromRaw(period?.period)
                val interval = period?.amount
                if (amount == null || period == null || cadence == null || interval == null) unsupported("periodic")
                val starting = entry.starting
                val base = TemplateDraftEntry.MonthlyFixed(
                    amount = amount,
                    priority = requiredPriority(entry.priority, entry.type),
                    starting = if (starting != null && starting.isNotEmpty()) starting else TemplateDefinition.firstDayOfCurrentMonth(today),
                    cadence = cadence,
                    interval = interval,
                    description = entry.normalizedDescription,
                )
                return baseAndLimit(base, entry.editorLimit)
            }
            "by", "spend" -> {
                val amount = entry.amount ?: unsupported("invalid " + entry.type + " template")
                val repeatInterval = entry.repeatInterval ?: (if (entry.annual == null) null else 1)
                return listOf(
                    TemplateDraftEntry.DateTarget(
                        amount = amount,
                        month = entry.month ?: "",
                        priority = requiredPriority(entry.priority, entry.type),
                        repeatInterval = repeatInterval,
                        annual = entry.annual ?: false,
                        fromMonth = entry.fromMonth,
                        isSpend = entry.type == "spend",
                        description = entry.normalizedDescription,
                    ),
                )
            }
            "percentage" -> {
                val percent = entry.percent ?: entry.percentage
                val priority = entry.priority
                if (percent == null || priority == null) unsupported("percentage")
                return listOf(
                    TemplateDraftEntry.Percentage(
                        percent = percent,
                        sourceCategory = entry.sourceCategory ?: "",
                        previous = entry.previous ?: false,
                        priority = priority,
                        description = entry.normalizedDescription,
                    ),
                )
            }
            "limit" -> {
                val amount = entry.limitAmount
                val period = entry.limitPeriod
                if (amount == null || period == null) unsupported("limit")
                return listOf(
                    TemplateDraftEntry.BalanceLimit(
                        amount = amount,
                        hold = entry.limitHold ?: false,
                        period = limitPeriod(period),
                        start = entry.limitStart,
                        description = entry.normalizedDescription,
                    ),
                )
            }
            "refill" -> return listOf(
                TemplateDraftEntry.refill(priority = requiredPriority(entry.priority, entry.type), description = entry.normalizedDescription),
            )
            "copy" -> {
                val lookBack = entry.lookBack ?: unsupported("copy")
                return listOf(
                    TemplateDraftEntry.copyPrevious(
                        lookBack = lookBack,
                        priority = requiredPriority(entry.priority, entry.type),
                        legacyLimit = requiredOrNilLimit(entry.editorLimit, entry.type),
                        description = entry.normalizedDescription,
                    ),
                )
            }
            "average" -> {
                val numMonths = entry.numMonths ?: unsupported("average")
                return listOf(
                    TemplateDraftEntry.average(
                        numMonths = numMonths,
                        priority = requiredPriority(entry.priority, entry.type),
                        adjustment = adjustment(entry.adjustment, entry.adjustmentType),
                        description = entry.normalizedDescription,
                    ),
                )
            }
            "schedule" -> return listOf(
                TemplateDraftEntry.Schedule(
                    name = entry.name ?: "",
                    scheduleId = entry.presentScheduleId,
                    priority = requiredPriority(entry.priority, entry.type),
                    full = entry.full ?: false,
                    adjustment = adjustment(entry.adjustment, entry.adjustmentType),
                    description = entry.normalizedDescription,
                ),
            )
            "remainder" -> {
                val weight = entry.weight ?: unsupported("remainder")
                val base = TemplateDraftEntry.remainder(weight = weight, description = entry.normalizedDescription)
                return baseAndLimit(base, entry.editorLimit)
            }
            "goal" -> {
                val amount = entry.amount ?: unsupported("goal")
                return listOf(TemplateDraftEntry.goal(amount = amount, description = entry.normalizedDescription))
            }
            else -> unsupported(entry.type)
        }
    }

    private fun baseAndLimit(base: TemplateDraftEntry, rawLimit: TemplateUpToHold?): List<TemplateDraftEntry> {
        if (rawLimit == null) return listOf(base)
        val limit = requiredOrNilLimit(rawLimit, "limit") ?: return listOf(base)
        return listOf(
            base,
            TemplateDraftEntry.balanceLimit(
                amount = limit.amount,
                hold = limit.hold,
                period = limitPeriod(limit.period),
                start = limit.start,
            ),
        )
    }

    private fun adjustment(value: Double?, type: String?): TemplateAdjustment? {
        if (value == null && type == null) return null
        if (value != null && type == "fixed") return TemplateAdjustment.Fixed(value)
        if (value != null && type == "percent") return TemplateAdjustment.Percent(value)
        unsupported("adjustment")
    }

    private fun requiredPriority(priority: Int?, type: String): Int =
        priority ?: unsupported("$type requires a priority")

    private fun requiredOrNilLimit(limit: TemplateUpToHold?, type: String): TemplateUpToHold? {
        if (limit == null) return null
        if (!limit.amount.isFinite() || TemplateLimitPeriod.fromRaw(limit.period) == null) unsupported("$type limit")
        return limit
    }

    private fun limitPeriod(period: String): TemplateLimitPeriod =
        TemplateLimitPeriod.fromRaw(period) ?: unsupported("limit period")

    // endregion

    // region Encode

    /**
     * Encodes entries into Actual's `goal_def` document: compact JSON with keys sorted (as Actualist's
     * `.sortedKeys`), slashes unescaped, whole numbers without a fraction, and `"priority":null` on Limit,
     * Remainder and Goal. Throws [TemplateDefinitionParseFailure.Unreadable] for a non-finite number.
     */
    fun encode(entries: List<TemplateDraftEntry>): String {
        val builder = StringBuilder("[")
        entries.forEachIndexed { index, entry ->
            if (index > 0) builder.append(',')
            writeValue(builder, encodedFields(entry))
        }
        builder.append(']')
        return builder.toString()
    }

    private fun encodedFields(entry: TemplateDraftEntry): Map<String, Any?> {
        val fields = TreeMap<String, Any?>()
        val description = entry.description
        if (description != null && description.trim().isNotEmpty()) fields["description"] = description

        fun putAdjustment(adjustment: TemplateAdjustment?) {
            if (adjustment == null) return
            fields["adjustment"] = adjustment.value
            fields["adjustmentType"] = adjustment.type
        }

        fun putLimit(limit: TemplateUpToHold?) {
            if (limit == null) return
            val nested = TreeMap<String, Any?>()
            nested["amount"] = limit.amount
            nested["hold"] = limit.hold
            nested["period"] = limit.period
            if (limit.start != null) nested["start"] = limit.start
            fields["limit"] = nested
        }

        when (entry) {
            is TemplateDraftEntry.MonthlyFixed -> {
                fields["directive"] = "template"
                fields["type"] = "periodic"
                fields["amount"] = entry.amount
                val period = TreeMap<String, Any?>()
                period["period"] = entry.cadence.raw
                period["amount"] = entry.interval
                fields["period"] = period
                fields["starting"] = entry.starting
                fields["priority"] = entry.priority
            }
            is TemplateDraftEntry.DateTarget -> {
                fields["directive"] = "template"
                fields["type"] = if (entry.isSpend) "spend" else "by"
                fields["amount"] = entry.amount
                fields["month"] = entry.month
                fields["priority"] = entry.priority
                if (entry.repeatInterval != null) {
                    fields["repeat"] = entry.repeatInterval
                    fields["annual"] = entry.annual
                }
                if (entry.fromMonth != null) fields["from"] = entry.fromMonth
            }
            is TemplateDraftEntry.Percentage -> {
                fields["directive"] = "template"
                fields["type"] = "percentage"
                fields["percent"] = entry.percent
                fields["previous"] = entry.previous
                fields["category"] = entry.sourceCategory
                fields["priority"] = entry.priority
            }
            is TemplateDraftEntry.BalanceLimit -> {
                fields["directive"] = "template"
                fields["type"] = "limit"
                fields["amount"] = entry.amount
                fields["hold"] = entry.hold
                fields["period"] = entry.period.raw
                fields["priority"] = null
                if (entry.start != null) fields["start"] = entry.start
            }
            is TemplateDraftEntry.Refill -> {
                fields["directive"] = "template"
                fields["type"] = "refill"
                fields["priority"] = entry.priority
            }
            is TemplateDraftEntry.Copy -> {
                fields["directive"] = "template"
                fields["type"] = "copy"
                fields["lookBack"] = entry.lookBack
                fields["priority"] = entry.priority
                putLimit(entry.legacyLimit)
            }
            is TemplateDraftEntry.Average -> {
                fields["directive"] = "template"
                fields["type"] = "average"
                fields["numMonths"] = entry.numMonths
                fields["priority"] = entry.priority
                putAdjustment(entry.adjustment)
            }
            is TemplateDraftEntry.Schedule -> {
                fields["directive"] = "template"
                fields["type"] = "schedule"
                if (entry.name.isNotEmpty()) fields["name"] = entry.name
                fields["priority"] = entry.priority
                if (entry.full) fields["full"] = true
                if (entry.scheduleId != null) fields["scheduleId"] = entry.scheduleId
                putAdjustment(entry.adjustment)
            }
            is TemplateDraftEntry.Remainder -> {
                fields["directive"] = "template"
                fields["type"] = "remainder"
                fields["weight"] = entry.weight
                fields["priority"] = null
                putLimit(entry.legacyLimit)
            }
            is TemplateDraftEntry.Goal -> {
                fields["directive"] = "goal"
                fields["type"] = "goal"
                fields["amount"] = entry.amount
                fields["priority"] = null
            }
        }
        return fields
    }

    private fun writeValue(builder: StringBuilder, value: Any?) {
        when (value) {
            null -> builder.append("null")
            is Map<*, *> -> {
                builder.append('{')
                var first = true
                for ((key, nested) in value) {
                    if (!first) builder.append(',')
                    first = false
                    builder.append(JsonPrimitive(key as String).toString()).append(':')
                    writeValue(builder, nested)
                }
                builder.append('}')
            }
            is String -> builder.append(JsonPrimitive(value).toString())
            is Boolean -> builder.append(if (value) "true" else "false")
            is Int -> builder.append(value.toString())
            is Double -> builder.append(formatNumber(value))
            else -> throw TemplateDefinitionParseFailure.Unreadable()
        }
    }

    /** Swift's `JSONEncoder` spelling of a Double: shortest round-trip digits, no ".0" on whole numbers. */
    private fun formatNumber(value: Double): String {
        if (!value.isFinite()) throw TemplateDefinitionParseFailure.Unreadable()
        if (value == Math.rint(value) && Math.abs(value) < 1.0e15) {
            return if (value == 0.0 && 1.0 / value < 0) "-0" else value.toLong().toString()
        }
        val text = value.toString()
        return if (text.contains('E') || text.contains('e')) BigDecimal(text).toPlainString() else text
    }

    // endregion
}
