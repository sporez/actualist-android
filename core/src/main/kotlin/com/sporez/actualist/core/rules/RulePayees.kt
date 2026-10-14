package com.sporez.actualist.core.rules

/**
 * The payee ids a rule refers to, through a `payee` or `description` condition or a "set payee"
 * action. Null when the rule cannot be decoded at all, so a payee delete stays blocked.
 */
object RulePayees {
    private fun idsIn(value: RuleValue): List<String> = when (value) {
        is RuleValue.Text -> listOf(value.value)
        is RuleValue.Arr -> value.values.flatMap { idsIn(it) }
        else -> emptyList()
    }

    private fun isPayeeField(field: String?) = field == "payee" || field == "description"

    fun referencedIds(rule: ManagedRule): Set<String>? {
        val draft = rule.executionDraft() ?: return null
        val ids = LinkedHashSet<String>()
        for (condition in draft.conditions) {
            if (isPayeeField(condition.fieldName) && (condition.type == null || condition.type == RuleValueKind.ID.raw)) {
                ids += idsIn(condition.value)
            }
        }
        for (action in draft.actions) {
            if (action.op == "set" && isPayeeField(action.fieldName)) ids += idsIn(action.value)
        }
        return ids
    }

    /** Rules that reference [payeeId], skipping completed schedule rules. */
    fun rulesFor(rules: List<ManagedRule>, payeeId: String): List<ManagedRule> =
        rules.filter { !it.isCompletedScheduleRule && referencedIds(it)?.contains(payeeId) == true }
}
