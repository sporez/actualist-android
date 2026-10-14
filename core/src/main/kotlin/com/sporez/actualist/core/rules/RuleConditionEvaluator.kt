package com.sporez.actualist.core.rules

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.regex.Pattern

/** Lookup tables a rule pass reads, loaded once per batch. */
class RuleMetadata(
    val accountNames: Map<String, String>,
    val offBudgetAccountIds: Set<String>,
    val categoryNames: Map<String, String>,
    val categoryGroupsByCategoryId: Map<String, String>,
    val categoryGroupNames: Map<String, String>,
    val payeeNames: Map<String, String>,
    val transferPayeeIds: Set<String>,
)

class RuleEvaluationSplit(
    val id: String?,
    val categoryId: String?,
    val amount: Long,
    val payeeId: String?,
    val notes: String?,
    val sortOrder: Double?,
)

/** The mutable transaction state one rule pass reads and rewrites (Actualist `RuleEvaluationContext`). */
class RuleEvaluationContext(
    val metadata: RuleMetadata,
    var accountId: String,
    var accountName: String,
    var accountIsOffBudget: Boolean,
    var amount: Long,
    var categoryId: String?,
    var categoryName: String?,
    var categoryGroupId: String?,
    var categoryGroupName: String?,
    var date: LocalDate,
    var notes: String?,
    var payeeId: String?,
    var payeeName: String,
    var importedPayee: String?,
    var cleared: Boolean,
    var reconciled: Boolean,
    var isTransfer: Boolean,
    var isParent: Boolean,
    var sortOrder: Double? = null,
    var scheduleId: String? = null,
    var evaluationId: String = "parent-1",
    var isChild: Boolean = false,
    var parentId: String? = null,
    var startingBalance: Boolean = false,
    var balance: Long = 0,
    var balanceOfPrefetch: Map<String, Long> = emptyMap(),
    var today: String = "",
    var deletesTransaction: Boolean = false,
    var splits: List<RuleEvaluationSplit> = emptyList(),
    val appliedRuleIds: MutableList<String> = mutableListOf(),
)

object RuleConditionEvaluator {
    /** Runs [rules] over [context] in order and returns the same, rewritten, context. */
    fun applying(
        rules: List<ManagedRule>,
        context: RuleEvaluationContext,
        schedules: RuleScheduleIndex = RuleScheduleIndex.EMPTY,
        idGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
    ): RuleEvaluationContext {
        val candidatePayeeId = context.payeeId
        val candidateImportedPayee = context.importedPayee
        val attachedRuleId = schedules.ruleId(context.scheduleId)
        for (rule in rules) {
            if (schedules.shouldSkip(rule.id, attachedRuleId)) continue
            val forceExecute = schedules.shouldForceExecute(rule.id, attachedRuleId)
            val execution = rule.executionDraft() ?: continue
            if (!execution.actions.all { it.canExecuteAtRuntime }) continue
            if (!RuleCandidateIndex.isCandidate(execution, candidatePayeeId, candidateImportedPayee)) continue
            if (!forceExecute) {
                if (!conditionsMatch(execution, context, readsTransferAndParent = false)) continue
            }
            // Actual only marks the transaction deleted; later rules and this rule's other actions still run.
            val actions = execution.actions.filter { it.op != "delete-transaction" }
            if (actions.size != execution.actions.size) context.deletesTransaction = true
            if (actions.any { (it.splitIndex ?: 0) != 0 || it.op == "set-split-amount" }) {
                if (!RuleSplitActionExecutor.apply(actions, context, idGenerator)) continue
            } else {
                for (action in actions) apply(action, context)
            }
            context.appliedRuleIds.add(rule.id)
        }
        return context
    }

    /**
     * `readsTransferAndParent` is false while rules run on a transaction: Actual's `runRules`
     * never fills `transfer` or `parent`, so conditions on them cannot match.
     */
    fun conditionsMatch(draft: RuleDraft, context: RuleEvaluationContext, readsTransferAndParent: Boolean = true): Boolean {
        if (draft.conditions.isEmpty()) return false
        val matches = draft.conditions.map { conditionMatches(it, context, readsTransferAndParent) }
        return if (draft.join == RuleConditionJoin.AND) matches.all { it } else matches.any { it }
    }

    fun conditionMatches(condition: RuleCondition, context: RuleEvaluationContext, readsTransferAndParent: Boolean = true): Boolean {
        val field = RuleFields.presentationField(condition.fieldName)
        if (!readsTransferAndParent && (field == "transfer" || field == "parent")) return false
        if (field == "account" && condition.op == "onBudget") return !context.accountIsOffBudget
        if (field == "account" && condition.op == "offBudget") return context.accountIsOffBudget

        val kind = RuleFields.valueKind(field)
        var actual: RuleValue = when (field) {
            "account" -> RuleValue.Text(context.accountId)
            "amount" -> RuleValue.Num(context.amount.toDouble())
            "category" -> context.categoryId?.let { RuleValue.Text(it) } ?: RuleValue.Null
            "category_group" -> context.categoryGroupId?.let { RuleValue.Text(it) } ?: RuleValue.Null
            "date" -> RuleValue.Text(ScheduleRecurrence.dayId(context.date))
            "notes" -> RuleValue.Text(context.notes ?: "")
            "payee" -> context.payeeId?.let { RuleValue.Text(it) } ?: RuleValue.Null
            "imported_payee" -> RuleValue.Text(context.importedPayee ?: "")
            "payee_name" -> RuleValue.Text(context.payeeName)
            "cleared" -> RuleValue.Bool(context.cleared)
            "reconciled" -> RuleValue.Bool(context.reconciled)
            "transfer" -> RuleValue.Bool(context.isTransfer)
            "parent" -> RuleValue.Bool(context.isParent)
            else -> return false
        }

        val options = condition.options
        if (field == "amount" && options != null) {
            if (options["outflow"] == RuleValue.Bool(true)) {
                if (context.amount > 0) return false
                actual = RuleValue.Num(-context.amount.toDouble())
            } else if (options["inflow"] == RuleValue.Bool(true)) {
                if (context.amount < 0) return false
            }
        }

        if (condition.op in listOf("contains", "doesNotContain", "matches")) {
            when (field) {
                "account" -> actual = RuleValue.Text(context.accountName)
                "category" -> {
                    if (context.categoryId == null) return false
                    actual = RuleValue.Text(context.categoryName ?: "")
                }
                "category_group" -> {
                    if (context.categoryGroupId == null) return false
                    actual = RuleValue.Text(context.categoryGroupName ?: "")
                }
                else -> Unit
            }
        }

        if (kind == RuleValueKind.DATE) return compareDate(actual, condition.op, condition.value)
        return compare(actual, condition.op, condition.value, kind)
    }

    private fun compare(actual: RuleValue, operation: String, expected: RuleValue, kind: RuleValueKind?): Boolean {
        when (operation) {
            "is" -> return equivalent(actual, expected, kind)
            "isNot" -> return !equivalent(actual, expected, kind)
            "oneOf", "notOneOf" -> {
                if (actual == RuleValue.Null) return false
                val values = (expected as? RuleValue.Arr)?.values ?: return false
                val contains = values.any { equivalent(actual, it, kind) }
                return if (operation == "oneOf") contains else !contains
            }
            "contains", "doesNotContain", "matches", "hasTags", "hasAnyTag" -> {
                if (actual == RuleValue.Null) return false
                val actualText = comparisonText(actual).lowercase()
                val expectedText = normalizedExpectedText(comparisonText(expected), kind)
                val matches = when (operation) {
                    "matches" -> RuleRegexCache.expression(expectedText)?.matcher(actualText)?.find() ?: false
                    "hasTags", "hasAnyTag" -> {
                        val tagMatches = extractTags(expectedText).map { tag ->
                            val pattern = Pattern.compile("(?<!#)" + Pattern.quote(tag) + "([\\s#]|$)", PlatformRegex.unicodeFlag)
                            pattern.matcher(actualText).find()
                        }
                        if (operation == "hasTags") tagMatches.all { it } else tagMatches.any { it }
                    }
                    else -> actualText.contains(expectedText)
                }
                return if (operation == "doesNotContain") !matches else matches
            }
            "gt", "gte", "lt", "lte", "isapprox" -> {
                val lhs = numericValue(actual) ?: return false
                val rhs = numericValue(expected) ?: return false
                return when (operation) {
                    "gt" -> lhs > rhs
                    "gte" -> lhs >= rhs
                    "lt" -> lhs < rhs
                    "lte" -> lhs <= rhs
                    else -> {
                        val threshold = RuleNumbers.roundHalfAway(Math.abs(rhs) * 0.075)
                        lhs >= rhs - threshold && lhs <= rhs + threshold
                    }
                }
            }
            "isbetween" -> {
                val range = (expected as? RuleValue.Obj)?.values ?: return false
                val lhs = numericValue(actual) ?: return false
                val first = range["num1"]?.let { numericValue(it) } ?: return false
                val second = range["num2"]?.let { numericValue(it) } ?: return false
                return lhs >= Math.min(first, second) && lhs <= Math.max(first, second)
            }
            else -> return false
        }
    }

    private fun comparisonText(value: RuleValue): String = when (value) {
        is RuleValue.Null -> ""
        is RuleValue.Bool -> if (value.value) "true" else "false"
        is RuleValue.Num -> {
            val number = value.value
            if (!number.isFinite()) {
                ""
            } else {
                val whole = if (RuleNumbers.roundHalfAway(number) == number) RuleNumbers.truncatedLong(number) else null
                whole?.toString() ?: number.toString()
            }
        }
        is RuleValue.Text -> value.value
        is RuleValue.Arr -> value.values.joinToString(", ") { comparisonText(it) }
        is RuleValue.Obj -> ""
    }

    private fun compareDate(actual: RuleValue, operation: String, expected: RuleValue): Boolean {
        val actualDate = (actual as? RuleValue.Text)?.value
        if (actualDate != null && expected is RuleValue.Obj) {
            return ScheduleRecurrence.matchesCondition(actualDate, operation, expected)
        }
        val expectedDate = (expected as? RuleValue.Text)?.value
        if (actualDate == null || expectedDate == null) return false
        return when (operation) {
            "is" -> when (expectedDate.length) {
                4, 7 -> actualDate.startsWith("$expectedDate-")
                10 -> actualDate == expectedDate
                else -> false
            }
            "isapprox" -> {
                val first = ScheduleRecurrence.parseDay(actualDate)
                val second = ScheduleRecurrence.parseDay(expectedDate)
                if (first == null || second == null) false else Math.abs(ChronoUnit.DAYS.between(first, second)) <= 2
            }
            "gt" -> actualDate > expectedDate
            "gte" -> actualDate >= expectedDate
            "lt" -> actualDate < expectedDate
            "lte" -> actualDate <= expectedDate
            else -> false
        }
    }

    private fun equivalent(lhs: RuleValue, rhs: RuleValue, kind: RuleValueKind?): Boolean {
        val lhsNumber = numericValue(lhs)
        val rhsNumber = numericValue(rhs)
        if (lhsNumber != null && rhsNumber != null) return lhsNumber == rhsNumber
        if (lhs is RuleValue.Null && rhs is RuleValue.Null) return true
        if (lhs is RuleValue.Bool && rhs is RuleValue.Bool) return lhs.value == rhs.value
        if (lhs is RuleValue.Text && rhs is RuleValue.Text) {
            return if (kind == RuleValueKind.STRING) lhs.value.lowercase() == rhs.value.lowercase() else lhs.value == rhs.value
        }
        return false
    }

    private fun normalizedExpectedText(value: String, kind: RuleValueKind?): String =
        if (kind == RuleValueKind.STRING) value.lowercase() else value

    private val tagExpression = Pattern.compile("#*([^#\\s]+)", PlatformRegex.unicodeFlag)

    private fun extractTags(value: String): List<String> {
        val seen = LinkedHashSet<String>()
        val matcher = tagExpression.matcher(value)
        while (matcher.find()) seen.add("#" + matcher.group(1))
        return seen.toList()
    }

    private fun numericValue(value: RuleValue): Double? = when (value) {
        is RuleValue.Num -> value.value
        is RuleValue.Text -> RuleNumbers.parseDouble(value.value)
        else -> null
    }

    fun apply(action: RuleAction, context: RuleEvaluationContext) {
        when (action.op) {
            "set" -> when (action.editorField) {
                "account" -> {
                    val value = (action.value as? RuleValue.Text)?.value
                    if (value != null) {
                        context.accountId = value
                        context.accountName = context.metadata.accountNames[value] ?: ""
                        context.accountIsOffBudget = value in context.metadata.offBudgetAccountIds
                    }
                }
                "amount" -> {
                    val number = numericValue(action.value)
                    val rounded = number?.let { RuleNumbers.truncatedLong(RuleNumbers.roundHalfAway(it)) }
                    if (rounded != null) context.amount = rounded
                }
                "category" -> setCategory(context, stringOrNil(action.value))
                "date" -> {
                    val text = (action.value as? RuleValue.Text)?.value
                    val date = text?.let { ScheduleRecurrence.parseDay(it) }
                    if (date != null) context.date = date
                }
                // Unlike the other text fields, an empty note is stored as an empty note.
                "notes" -> context.notes = (action.value as? RuleValue.Text)?.value
                // payee_name stays what it was before any rule ran, as in Actual.
                "payee" -> context.payeeId = stringOrNil(action.value)
                "cleared" -> {
                    val flag = (action.value as? RuleValue.Bool)?.value
                    if (flag != null) context.cleared = flag
                }
                else -> Unit
            }
            "prepend-notes" -> {
                val value = (action.value as? RuleValue.Text)?.value
                if (value != null) context.notes = value + (context.notes ?: "")
            }
            "append-notes" -> {
                val value = (action.value as? RuleValue.Text)?.value
                if (value != null) context.notes = (context.notes ?: "") + value
            }
            "link-schedule" -> {
                val value = (action.value as? RuleValue.Text)?.value
                if (!value.isNullOrEmpty()) context.scheduleId = value
            }
            else -> Unit
        }
    }

    private fun setCategory(context: RuleEvaluationContext, categoryId: String?) {
        val metadata = context.metadata
        context.categoryId = categoryId
        context.categoryName = categoryId?.let { metadata.categoryNames[it] }
        context.categoryGroupId = categoryId?.let { metadata.categoryGroupsByCategoryId[it] }
        context.categoryGroupName = context.categoryGroupId?.let { metadata.categoryGroupNames[it] }
    }

    private fun stringOrNil(value: RuleValue): String? {
        if (value is RuleValue.Text) return if (value.value.isEmpty()) null else value.value
        return null
    }
}
