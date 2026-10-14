package com.sporez.actualist.core.rules

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Compiled `matches` patterns. Invalid patterns are cached as null and never match. ICU
 * (Swift `NSRegularExpression`) treats `\w`, `\d` and `\s` as Unicode classes, so the Java
 * flag for that is always on.
 */
object RuleRegexCache {
    private const val CAPACITY = 128
    private val expressions = HashMap<String, Pattern?>()

    @Synchronized
    fun expression(pattern: String): Pattern? {
        if (expressions.containsKey(pattern)) return expressions[pattern]
        if (expressions.size >= CAPACITY) expressions.clear()
        val compiled = try {
            Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS)
        } catch (e: PatternSyntaxException) {
            null
        }
        expressions[pattern] = compiled
        return compiled
    }
}

/**
 * Live `schedules.rule` relationship used by loot-core `runRules`. Completed and tombstoned
 * schedule rows are excluded from the active map so they never force-run or suppress rules.
 */
class RuleScheduleIndex(
    val ruleIdByScheduleId: Map<String, String>,
    val completedRuleIds: Set<String>,
) {
    private val liveLinkedRuleIds: Set<String> = ruleIdByScheduleId.values.toSet()

    fun ruleId(scheduleId: String?): String? {
        if (scheduleId.isNullOrEmpty()) return null
        return ruleIdByScheduleId[scheduleId]
    }

    fun shouldForceExecute(ruleId: String, attachedRuleId: String?): Boolean {
        if (attachedRuleId == null) return false
        return ruleId == attachedRuleId && ruleId !in completedRuleIds
    }

    /**
     * Actual only skips schedule-linked rules (completed ones included) for a transaction that
     * came from a schedule; otherwise a rule linked to a completed schedule is an ordinary rule.
     */
    fun shouldSkip(ruleId: String, attachedRuleId: String?): Boolean {
        if (attachedRuleId == null) return false
        return ruleId != attachedRuleId && (ruleId in liveLinkedRuleIds || ruleId in completedRuleIds)
    }

    companion object {
        val EMPTY = RuleScheduleIndex(emptyMap(), emptySet())
    }
}

/** loot-core `rankRules` / `computeScore`: lower scores run first, exact matches count double. */
object RuleRanking {
    private val operationScores = mapOf(
        "is" to 10, "isNot" to 10, "oneOf" to 9, "notOneOf" to 9, "isapprox" to 5, "isbetween" to 5,
        "gt" to 1, "gte" to 1, "lt" to 1, "lte" to 1,
        "contains" to 0, "doesNotContain" to 0, "matches" to 0, "hasTags" to 0, "onBudget" to 0, "offBudget" to 0,
    )

    private val exactMatchOperations = setOf("is", "isNot", "isapprox", "oneOf", "notOneOf")

    fun rank(rules: List<ManagedRule>): List<ManagedRule> {
        val grouped = rules.groupBy { stage(it) }
        return rankGroup(grouped[RuleStage.PRE].orEmpty()) +
            rankGroup(grouped[RuleStage.NORMAL].orEmpty()) +
            rankGroup(grouped[RuleStage.POST].orEmpty())
    }

    fun score(rule: ManagedRule): Int {
        val conditions = rule.executionDraft()?.conditions ?: emptyList()
        var initial = 0
        for (condition in conditions) {
            val points = operationScores[condition.op]
            // An unknown operator resets the running score, as the Swift reduce does.
            initial = if (points == null) 0 else initial + points
        }
        if (conditions.all { it.op in exactMatchOperations }) return initial * 2
        return initial
    }

    private fun rankGroup(rules: List<ManagedRule>): List<ManagedRule> =
        rules.sortedWith(Comparator<ManagedRule> { lhs, rhs ->
            val lhsScore = score(lhs)
            val rhsScore = score(rhs)
            if (lhsScore != rhsScore) lhsScore.compareTo(rhsScore) else lhs.id.compareTo(rhs.id)
        })

    private fun stage(rule: ManagedRule): RuleStage =
        rule.draft?.stage ?: rule.rawStage?.let { RuleStage.fromRaw(it) } ?: RuleStage.NORMAL
}

/**
 * Which rules Actual's `runRules` considers at all. Actual indexes each rule under its first
 * `payee` condition and its first `imported_payee` condition, then runs only the union of the
 * rules the transaction's payee id and imported-payee first character select, plus the
 * unindexed ones.
 */
object RuleCandidateIndex {
    fun isCandidate(draft: RuleDraft, payeeId: String?, importedPayee: String?): Boolean =
        applicable(draft, "payee", key(payeeId, false)) ||
            applicable(draft, "imported_payee", key(importedPayee, true))

    private fun applicable(draft: RuleDraft, field: String, transactionKey: String?): Boolean {
        val keys = indexKeys(draft, field)
        return "*" in keys || (transactionKey != null && transactionKey in keys)
    }

    private fun indexKeys(draft: RuleDraft, field: String): Set<String> {
        val firstCharacterOnly = field == "imported_payee"
        val condition = draft.conditions.firstOrNull { RuleFields.presentationField(it.fieldName) == field }
        if (condition == null || condition.op !in listOf("is", "isNot", "oneOf", "notOneOf")) return setOf("*")
        fun ruleKey(value: RuleValue): String {
            if (value is RuleValue.Text) return key(value.value, firstCharacterOnly) ?: "*"
            return "*"
        }
        if (condition.op == "oneOf" || condition.op == "notOneOf") {
            val values = (condition.value as? RuleValue.Arr)?.values ?: return emptySet()
            return values.map { ruleKey(it) }.toSet()
        }
        return setOf(ruleKey(condition.value))
    }

    private fun key(value: String?, firstCharacterOnly: Boolean): String? {
        if (value.isNullOrEmpty()) return null
        return if (firstCharacterOnly) value.substring(0, value.offsetByCodePoints(0, 1)).lowercase() else value.lowercase()
    }
}
