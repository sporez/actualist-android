package com.sporez.actualist.core.templates

import java.util.regex.Pattern
import kotlin.math.abs

/**
 * Pure note-directive parser for the note-managed template staleness guard. Port of Actualist
 * `BudgetTemplateNoteParser`: a comparison parser only, it never writes `goal_def`.
 */
object TemplateNoteParser {
    /** One `#template` / `#goal` line scanned from a category note; [type] is null when the line cannot be classified. */
    data class Directive(
        val keyword: String,
        val type: String?,
        val monthly: Double? = null,
        val amount: Double? = null,
        val percent: Double? = null,
        val previous: Boolean = false,
        val sourceCategory: String? = null,
        val numMonths: Int? = null,
        val lookBack: Int? = null,
        val weight: Double? = null,
        val periodAmount: Int? = null,
        val periodPeriod: String? = null,
        val starting: String? = null,
        val month: String? = null,
        val fromMonth: String? = null,
        val annual: Boolean = false,
        val repeatInterval: Int? = null,
        val scheduleName: String? = null,
        val scheduleId: String? = null,
        val full: Boolean = false,
        val adjustment: Double? = null,
        val adjustmentType: String? = null,
        val limitAmount: Double? = null,
        val limitPeriod: String? = null,
        val limitStart: String? = null,
    )

    private val directivePattern: Pattern = Pattern.compile("^\\s*#(template|goal)(?:[ \\t]+(.*))?$", Pattern.MULTILINE)
    private val numberPattern = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

    /** Scans every `#template` / `#goal` directive line in [note], in order. */
    fun directives(note: String): List<Directive> {
        val matcher = directivePattern.matcher(note)
        val result = mutableListOf<Directive>()
        while (matcher.find()) {
            val keyword = matcher.group(1) ?: continue
            val argument = matcher.group(2)?.trim { it == ' ' || it == '\t' } ?: ""
            result.add(parse(keyword, argument))
        }
        return result
    }

    /**
     * A human-readable staleness reason when the scanned note directives do not reconcile with the
     * stored `goal_def` entries, or null when the stored definition is consistent with the note.
     */
    fun stalenessReason(noteDirectives: List<Directive>, goalDefEntries: List<TemplateEntry>): String? {
        if (goalDefEntries.isEmpty()) return null
        if (noteDirectives.isEmpty()) {
            return "the category note no longer contains a #template/#goal directive but a stored template definition still exists"
        }
        if (noteDirectives.size != goalDefEntries.size) {
            val entryWord = if (goalDefEntries.size == 1) "entry" else "entries"
            return "the category note has ${noteDirectives.size} #template/#goal directive(s) but the stored definition has ${goalDefEntries.size} $entryWord"
        }
        for (index in noteDirectives.indices) {
            mismatchReason(noteDirectives[index], goalDefEntries[index])?.let { return it }
        }
        return null
    }

    // region Reconciliation

    private fun mismatchReason(note: Directive, goal: TemplateEntry): String? {
        val goalDirective = goal.directive ?: ""

        if (goalDirective == "error") {
            if (note.type != null) {
                return "the category note line was previously malformed but is now parseable; the stored error definition is stale"
            }
            return null
        }

        if (note.type == null) return "the category note directive is malformed but a valid stored definition exists"
        if (note.keyword != goalDirective) return "the category note directive changed between #template and #goal"
        if (note.type != goal.type) return "the category note template type changed"

        when (note.type) {
            "simple" -> {
                if (!equal(note.monthly, goal.monthly)) return "the category note template amount changed"
                limitMismatch(note, goal.limit)?.let { return it }
            }
            "periodic" -> {
                if (!equal(note.amount, goal.amount)) return "the category note periodic amount changed"
                if (note.periodAmount != goal.period?.amount) return "the category note periodic interval changed"
                if (normalized(note.periodPeriod) != normalized(goal.period?.period)) return "the category note periodic period changed"
                if (normalized(note.starting) != normalized(goal.starting)) return "the category note periodic start date changed"
                limitMismatch(note, goal.limit)?.let { return it }
            }
            "copy" -> {
                if (note.lookBack != goal.lookBack) return "the category note copy look-back changed"
            }
            "average" -> {
                if (note.numMonths != goal.numMonths) return "the category note average window changed"
            }
            "remainder" -> {
                if (!equal(note.weight, goal.weight)) return "the category note remainder weight changed"
                limitMismatch(note, goal.limit)?.let { return it }
            }
            "percentage" -> {
                if (!equal(note.percent, goal.percentageAmount)) return "the category note percentage changed"
                if (note.previous != (goal.previous ?: false)) return "the category note percentage previous flag changed"
                if (normalizeSourceCategory(note.sourceCategory) != normalizeSourceCategory(goal.sourceCategory)) {
                    return "the category note percentage source changed"
                }
            }
            "schedule" -> scheduleMismatch(note, goal)?.let { return it }
            "spend" -> {
                if (!equal(note.amount, goal.amount)) return "the category note spend amount changed"
                if (normalized(note.month) != normalized(goal.month)) return "the category note spend target month changed"
                if (normalized(note.fromMonth) != normalized(goal.fromMonth)) return "the category note spend start month changed"
            }
            "by" -> {
                if (!equal(note.amount, goal.amount)) return "the category note by amount changed"
                if (normalized(note.month) != normalized(goal.month)) return "the category note by target month changed"
                if (note.annual != (goal.annual ?: false)) return "the category note by annual flag changed"
            }
            "limit" -> {
                val goalLimit = goal.standaloneLimit
                if (!equal(note.limitAmount, goalLimit?.amount)) return "the category note up-to limit amount changed"
                if (normalized(note.limitPeriod) != normalized(goalLimit?.period)) return "the category note up-to limit period changed"
                if (normalized(note.limitStart) != normalized(goalLimit?.start)) return "the category note up-to limit start date changed"
            }
            "goal" -> {
                if (!equal(note.amount, goal.amount)) return "the category note goal amount changed"
                // The goal target month is only compared when both sides express it; Actual may
                // store the target on `long_goal` instead.
                val noteMonth = normalized(note.month)
                val goalMonth = normalized(goal.month)
                if (noteMonth != null && goalMonth != null && noteMonth != goalMonth) {
                    return "the category note goal target month changed"
                }
            }
            else -> return "the category note uses a template type that cannot be verified"
        }
        return null
    }

    private fun limitMismatch(note: Directive, goalLimit: TemplateLimit?): String? {
        // A limit clause is optional on simple/periodic/remainder. Only compare when the note expresses one.
        if (note.limitAmount == null && note.limitPeriod == null && note.limitStart == null) return null
        if (!equal(note.limitAmount, goalLimit?.amount)) return "the category note up-to limit amount changed"
        if (normalized(note.limitPeriod) != normalized(goalLimit?.period)) return "the category note up-to limit period changed"
        if (normalized(note.limitStart) != normalized(goalLimit?.start)) return "the category note up-to limit start date changed"
        return null
    }

    private fun scheduleMismatch(note: Directive, goal: TemplateEntry): String? {
        val parsedId = note.scheduleId
        val parsedName = note.scheduleName
        if (parsedId != null) {
            if (parsedId != (goal.scheduleId ?: "")) return "the category note schedule reference changed"
        } else if (parsedName != null) {
            if (normalized(parsedName) != normalized(goal.name)) return "the category note schedule reference changed"
        } else {
            return "the category note schedule reference changed"
        }
        if (note.full != (goal.full ?: false)) return "the category note schedule full flag changed"
        if (!equal(note.adjustment, goal.adjustment) || normalized(note.adjustmentType) != normalized(goal.adjustmentType)) {
            return "the category note schedule adjustment changed"
        }
        return null
    }

    private fun equal(a: Double?, b: Double?, tolerance: Double = 0.01): Boolean = when {
        a != null && b != null -> abs(a - b) < tolerance
        a == null && b == null -> true
        else -> false
    }

    private fun normalized(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

    private fun normalizeSourceCategory(s: String?): String? {
        val trimmed = normalized(s) ?: return null
        val lower = trimmed.lowercase()
        return if (lower == "income" || lower == "all income") "all income" else trimmed
    }

    // endregion

    // region Parsing

    private fun parse(keyword: String, argument: String): Directive =
        if (keyword == "goal") parseGoal(argument) else parseTemplate(argument)

    private fun parseGoal(argument: String): Directive {
        val tokens = tokenize(argument)
        val amount = tokens.firstOrNull()?.let { parseNumber(it) } ?: return malformed("goal")
        val byIndex = tokens.indexOf("by")
        val month = if (byIndex >= 0 && byIndex + 1 < tokens.size) tokens[byIndex + 1] else null
        return Directive(keyword = "goal", type = "goal", amount = amount, month = month)
    }

    private fun parseTemplate(argument: String): Directive {
        val tokens = tokenize(argument)
        val first = tokens.firstOrNull() ?: return malformed("template")
        when (first.lowercase()) {
            "remainder" -> return parseRemainder(tokens)
            "copy" -> return parseCopy(tokens)
            "average" -> return parseAverage(tokens)
            "schedule" -> return parseSchedule(tokens)
            "spend" -> return parseSpend(tokens)
            "by" -> return parseBy(tokens)
            "up" -> return parseStandaloneLimit(tokens)
            else -> Unit
        }

        if (argument.contains("%")) return parsePercentage(tokens)

        // Number-led: simple or periodic.
        val value = parseNumber(first) ?: return malformed("template")

        val period = periodFromTokens(tokens, 0)
        if (period != null) {
            return Directive(
                keyword = "template", type = "periodic", amount = value,
                periodAmount = period.first, periodPeriod = period.second,
            )
        }

        val limit = limitFromTokens(tokens, 0)
        return Directive(
            keyword = "template", type = "simple", monthly = value,
            limitAmount = limit?.amount, limitPeriod = limit?.period, limitStart = limit?.start,
        )
    }

    private fun parseRemainder(tokens: List<String>): Directive {
        // `remainder` optionally followed by a weight and/or an up-to limit.
        var weight: Double? = null
        var rest = tokens.drop(1)
        val head = rest.firstOrNull()
        val headNumber = if (head != null) parseNumber(head) else null
        if (head != null && headNumber != null && !isLimitStart(head)) {
            weight = headNumber
            rest = rest.drop(1)
        }
        val limit = limitFromTokens(rest, -1)
        return Directive(
            keyword = "template", type = "remainder", weight = weight ?: 1.0,
            limitAmount = limit?.amount, limitPeriod = limit?.period, limitStart = limit?.start,
        )
    }

    private fun parseCopy(tokens: List<String>): Directive {
        var lookBack: Int? = null
        val next = tokens.drop(1).firstOrNull()
        val number = if (next != null) parseNumber(next) else null
        if (number != null) lookBack = doubleToInt(number)
        return Directive(keyword = "template", type = "copy", lookBack = lookBack ?: 1)
    }

    private fun parseAverage(tokens: List<String>): Directive {
        val next = tokens.drop(1).firstOrNull() ?: return malformed("template")
        val number = parseNumber(next) ?: return malformed("template")
        return Directive(keyword = "template", type = "average", numMonths = doubleToInt(number))
    }

    private fun parseSchedule(tokens: List<String>): Directive {
        // `schedule <ref> [full] [<adjustment>]`
        var rest = tokens.drop(1)
        val ref = rest.firstOrNull() ?: return malformed("template")
        rest = rest.drop(1)

        var scheduleId: String? = null
        var scheduleName: String? = null
        if (ref.lowercase().startsWith("id:")) scheduleId = ref.substring(3) else scheduleName = ref

        var full = false
        var adjustment: Double? = null
        var adjustmentType: String? = null
        for (token in rest) {
            if (token.lowercase() == "full") {
                full = true
            } else {
                val adj = parseAdjustment(token)
                if (adj != null) {
                    adjustment = adj.first
                    adjustmentType = adj.second
                }
            }
        }
        return Directive(
            keyword = "template", type = "schedule", scheduleName = scheduleName, scheduleId = scheduleId,
            full = full, adjustment = adjustment, adjustmentType = adjustmentType,
        )
    }

    private fun parseSpend(tokens: List<String>): Directive {
        // `spend <amount> from <start> to <end>`
        val rest = tokens.drop(1)
        val amount = rest.firstOrNull()?.let { parseNumber(it) } ?: return malformed("template")
        return Directive(
            keyword = "template", type = "spend", amount = amount,
            month = tokenAfter(rest, "to"), fromMonth = tokenAfter(rest, "from"),
        )
    }

    private fun parseBy(tokens: List<String>): Directive {
        // `by <month> <amount> [repeat every year]`
        val rest = tokens.drop(1)
        val month = rest.firstOrNull() ?: return malformed("template")
        val afterMonth = rest.drop(1)
        val amount = afterMonth.firstOrNull()?.let { parseNumber(it) } ?: return malformed("template")
        return Directive(
            keyword = "template", type = "by", amount = amount, month = month, annual = containsAnnualKeyword(afterMonth),
        )
    }

    private fun parseStandaloneLimit(tokens: List<String>): Directive {
        // `up to <amount> [daily|weekly|monthly] [starting <date>]`
        if (tokens.firstOrNull()?.lowercase() != "up" || tokens.size <= 1 || tokens[1].lowercase() != "to") {
            return malformed("template")
        }
        val rest = tokens.drop(2)
        val amount = rest.firstOrNull()?.let { parseNumber(it) } ?: return malformed("template")
        val afterAmount = rest.drop(1)
        val period = periodKeywordFromTokens(afterAmount) ?: "monthly"
        return Directive(
            keyword = "template", type = "limit",
            limitAmount = amount, limitPeriod = period, limitStart = tokenAfter(afterAmount, "starting"),
        )
    }

    private fun parsePercentage(tokens: List<String>): Directive {
        // `<pct>% [of] [previous] <source>` / `previous <pct>% of income`
        var previous = false
        var percent: Double? = null
        var source: String? = null
        for (token in tokens) {
            if (token.lowercase() == "previous") {
                previous = true
                continue
            }
            if (token.contains("%")) {
                percent = parseNumber(token.replace("%", ""))
                continue
            }
            if (token.lowercase() == "of") continue
            source = if (source == null) token else "$source $token"
        }
        if (percent == null) return malformed("template")
        return Directive(keyword = "template", type = "percentage", percent = percent, previous = previous, sourceCategory = source)
    }

    // endregion

    // region Token helpers

    /** Splits an argument into whitespace-separated tokens, treating quoted substrings as one token (quotes stripped). */
    private fun tokenize(argument: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in argument) {
            if (ch == '"') {
                inQuotes = !inQuotes
                continue
            }
            if (ch.isWhitespace() && !inQuotes) {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.setLength(0)
                }
            } else {
                current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    private fun parseNumber(token: String): Double? {
        val cleaned = token.trim { it == ' ' || it == '\t' }
        if (!numberPattern.matches(cleaned)) return null
        return cleaned.toDoubleOrNull()
    }

    private fun doubleToInt(value: Double): Int? =
        if (value.isFinite() && value >= Int.MIN_VALUE.toDouble() && value <= Int.MAX_VALUE.toDouble()) value.toInt() else null

    private fun parseAdjustment(token: String): Pair<Double, String>? {
        if (token.isEmpty()) return null
        val isPercent = token.endsWith("%")
        val body = if (isPercent) token.dropLast(1) else token
        val sign = body.firstOrNull() ?: return null
        if (sign != '+' && sign != '-') return null
        val value = parseNumber(body) ?: return null
        return value to (if (isPercent) "percent" else "fixed")
    }

    private val periodKeywords: Map<String, Pair<Int, String>> = mapOf(
        "daily" to (1 to "day"), "day" to (1 to "day"), "days" to (1 to "day"),
        "weekly" to (1 to "week"), "week" to (1 to "week"), "weeks" to (1 to "week"),
        "monthly" to (1 to "month"), "month" to (1 to "month"), "months" to (1 to "month"),
        "yearly" to (1 to "year"), "year" to (1 to "year"), "years" to (1 to "year"),
    )

    /** Limit periods use Actual's adjectival vocabulary, distinct from the periodic noun vocabulary. */
    private val limitPeriodKeywords: Map<String, String> = mapOf(
        "daily" to "daily", "day" to "daily", "days" to "daily",
        "weekly" to "weekly", "week" to "weekly", "weeks" to "weekly",
        "monthly" to "monthly", "month" to "monthly", "months" to "monthly",
    )

    /** `<word>`, `every <word>` and `every <n> <word>` after [start] in [tokens]: (interval, period). */
    private fun periodFromTokens(tokens: List<String>, start: Int): Pair<Int, String>? {
        var i = start + 1
        if (i < tokens.size && tokens[i].lowercase() == "every") i += 1
        if (i < tokens.size && i + 1 < tokens.size) {
            val n = parseNumber(tokens[i])
            val period = periodKeywords[tokens[i + 1].lowercase()]
            if (n != null && period != null) {
                val interval = doubleToInt(n)
                if (interval != null) return maxOf(interval, 1) to period.second
            }
        }
        if (i < tokens.size) {
            val period = periodKeywords[tokens[i].lowercase()]
            if (period != null) return period.first to period.second
        }
        return null
    }

    private fun periodKeywordFromTokens(tokens: List<String>): String? {
        for (token in tokens) {
            limitPeriodKeywords[token.lowercase()]?.let { return it }
            if (token.lowercase() == "starting") break
        }
        return null
    }

    private fun isLimitStart(token: String): Boolean = token.lowercase() == "up" || token.lowercase() == "to"

    private class ParsedLimit(val amount: Double, val period: String?, val start: String?)

    /** An `up to <amount> [period] [starting <date>]` clause anywhere in [tokens] after [start]. */
    private fun limitFromTokens(tokens: List<String>, start: Int): ParsedLimit? {
        var search = start + 1
        while (search < tokens.size - 1) {
            if (tokens[search].lowercase() == "up" && tokens[search + 1].lowercase() == "to") {
                val after = tokens.drop(search + 2)
                val amount = after.firstOrNull()?.let { parseNumber(it) } ?: return null
                val afterAmount = after.drop(1)
                val period = periodKeywordFromTokens(afterAmount) ?: "monthly"
                return ParsedLimit(amount, period, tokenAfter(afterAmount, "starting"))
            }
            search += 1
        }
        return null
    }

    private fun tokenAfter(tokens: List<String>, keyword: String): String? {
        val index = tokens.indexOfFirst { it.lowercase() == keyword }
        if (index < 0 || index + 1 >= tokens.size) return null
        return tokens[index + 1]
    }

    private fun containsAnnualKeyword(tokens: List<String>): Boolean =
        tokens.any { it.lowercase() == "year" || it.lowercase() == "yearly" } ||
            (tokens.any { it.lowercase() == "repeat" } && tokens.any { it.lowercase() == "year" })

    private fun malformed(keyword: String) = Directive(keyword = keyword, type = null)

    // endregion
}
