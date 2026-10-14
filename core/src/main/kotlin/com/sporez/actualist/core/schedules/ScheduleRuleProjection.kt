package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.rules.RuleCondition
import com.sporez.actualist.core.rules.RuleJson
import com.sporez.actualist.core.rules.RuleNumbers
import com.sporez.actualist.core.rules.RuleValue
import com.sporez.actualist.core.rules.ScheduleRecurrence

/**
 * Port of Actualist `ScheduleRuleProjection`: a schedule's linked rule read as account, payee,
 * amount and date, with what may be edited safely.
 */
class ScheduleRuleProjection(
    val rawConditionsJson: String?,
    val rawActionsJson: String?,
    val accountId: String?,
    val payeeMappingId: String?,
    val amount: ScheduleAmount,
    val dateRule: ScheduleDateRule,
    val occurrenceMatchingMode: ScheduleOccurrenceMatchingMode,
    val capabilities: ScheduleMutationCapabilities,
    val unsupportedReasons: List<ScheduleUnsupportedReason>,
) {
    companion object {
        fun read(scheduleId: String, conditionsJson: String?, actionsJson: String?): ScheduleRuleProjection {
            val conditions = conditionsJson?.let { RuleJson.decodeConditions(it) }
            if (conditionsJson == null || conditions == null) {
                return unsupported(
                    scheduleId,
                    conditionsJson,
                    actionsJson,
                    if (conditionsJson == null) ScheduleUnsupportedReason.MISSING_RULE else ScheduleUnsupportedReason.MALFORMED_CONDITIONS,
                )
            }
            val actions = actionsJson?.let { RuleJson.decodeActions(it) }
            if (actionsJson == null || actions == null) {
                return unsupported(
                    scheduleId,
                    conditionsJson,
                    actionsJson,
                    if (actionsJson == null) ScheduleUnsupportedReason.MISSING_RULE else ScheduleUnsupportedReason.MALFORMED_ACTIONS,
                )
            }

            val account = preferredReference(conditions, "account", "acct")
            val payee = preferredReference(conditions, "payee", "description")
            val amountConditions = conditions.filter { it.fieldName == "amount" }
            val dateConditions = conditions.filter { it.fieldName == "date" }
            val amountCondition = if (amountConditions.size == 1) amountConditions[0] else null
            val dateCondition = if (dateConditions.size == 1) dateConditions[0] else null

            val amount = amountCondition?.let { amountFrom(it) } ?: ScheduleAmount.Unavailable
            val dateRule = dateCondition?.let { dateRuleFrom(it) } ?: ScheduleDateRule.Unavailable
            val occurrenceMatchingMode =
                if (dateCondition?.op == "isapprox") ScheduleOccurrenceMatchingMode.APPROXIMATE else ScheduleOccurrenceMatchingMode.EXACT
            val actionsCanExecute = actions.all { it.canExecuteAtRuntime }
            val editability = ScheduleRuleMutation.editability(scheduleId, conditionsJson, actionsJson)
            val hasValidLinkage = editability.hasValidLink
            val accountConditions = conditions.filter { it.fieldName == "account" || it.fieldName == "acct" }
            val reasons = mutableListOf<ScheduleUnsupportedReason>()
            if (amountConditions.isEmpty()) {
                reasons.add(ScheduleUnsupportedReason.MISSING_AMOUNT)
            } else if (amountConditions.size > 1) {
                reasons.add(ScheduleUnsupportedReason.UNSUPPORTED_AMOUNT)
            } else if (amount == ScheduleAmount.Unavailable) {
                reasons.add(ScheduleUnsupportedReason.UNSUPPORTED_AMOUNT)
            }
            if (dateConditions.isEmpty()) {
                reasons.add(ScheduleUnsupportedReason.MISSING_DATE)
            } else if (dateConditions.size > 1) {
                reasons.add(ScheduleUnsupportedReason.UNSUPPORTED_DATE)
            } else if (dateRule == ScheduleDateRule.Unavailable) {
                reasons.add(ScheduleUnsupportedReason.UNSUPPORTED_DATE)
            }
            if (!actionsCanExecute) reasons.add(ScheduleUnsupportedReason.UNSUPPORTED_ACTIONS)
            if (!hasValidLinkage) reasons.add(ScheduleUnsupportedReason.CORRUPT_RULE_LINKAGE)

            val hasSupportedDate = dateRule != ScheduleDateRule.Unavailable
            val hasSupportedAmount = amount != ScheduleAmount.Unavailable
            val canEditMetadata = hasValidLinkage
            val canEditAccount = canEditMetadata && editability.canEditAccount && accountConditions.size == 1 && hasSupportedDate
            val canEditPayee = canEditMetadata && editability.canEditPayee
            val canEditAmount = canEditMetadata && editability.canEditAmount
            val canEditDate = canEditMetadata && editability.canEditDate
            return ScheduleRuleProjection(
                rawConditionsJson = conditionsJson,
                rawActionsJson = actionsJson,
                accountId = account,
                payeeMappingId = payee,
                amount = amount,
                dateRule = dateRule,
                occurrenceMatchingMode = occurrenceMatchingMode,
                capabilities = ScheduleMutationCapabilities(
                    canRead = true,
                    canEditMetadata = canEditMetadata,
                    canEditAccount = canEditAccount,
                    canEditPayee = canEditPayee,
                    canEditAmount = canEditAmount,
                    canEditDate = canEditDate,
                    canSkip = hasValidLinkage && dateRule.recurrence != null,
                    canComplete = hasValidLinkage && hasSupportedDate && dateRule.recurrence == null,
                    canDelete = hasValidLinkage,
                    canPost = hasValidLinkage && hasSupportedDate && hasSupportedAmount && actionsCanExecute && account != null,
                ),
                unsupportedReasons = reasons,
            )
        }

        private fun unsupported(
            scheduleId: String,
            conditionsJson: String?,
            actionsJson: String?,
            reason: ScheduleUnsupportedReason,
        ): ScheduleRuleProjection {
            val linkage = ScheduleRuleMutation.editability(scheduleId, conditionsJson, actionsJson)
            return ScheduleRuleProjection(
                rawConditionsJson = conditionsJson,
                rawActionsJson = actionsJson,
                accountId = null,
                payeeMappingId = null,
                amount = ScheduleAmount.Unavailable,
                dateRule = ScheduleDateRule.Unavailable,
                occurrenceMatchingMode = ScheduleOccurrenceMatchingMode.EXACT,
                capabilities = ScheduleMutationCapabilities(
                    canRead = true,
                    canEditMetadata = linkage.hasValidLink,
                    canEditAccount = false,
                    canEditPayee = false,
                    canEditAmount = false,
                    canEditDate = false,
                    canSkip = false,
                    canComplete = false,
                    canDelete = linkage.hasValidLink,
                    canPost = false,
                ),
                unsupportedReasons = listOf(reason),
            )
        }

        private fun preferredReference(conditions: List<RuleCondition>, primaryField: String, fallbackField: String): String? =
            conditions.firstOrNull { it.op == "is" && it.fieldName == primaryField }?.value?.string
                ?: conditions.firstOrNull { it.op == "is" && it.fieldName == fallbackField }?.value?.string

        /** Port of `ScheduleRuleProjection.amount(from:)`. */
        fun amountFrom(condition: RuleCondition): ScheduleAmount {
            when (condition.op) {
                "is", "isapprox" -> {
                    val value = condition.value.number ?: return ScheduleAmount.Unavailable
                    val rounded = actualRounded(value) ?: return ScheduleAmount.Unavailable
                    return if (condition.op == "is") ScheduleAmount.Exact(rounded) else ScheduleAmount.Approximate(rounded)
                }
                "isbetween" -> {
                    val range = (condition.value as? RuleValue.Obj)?.values ?: return ScheduleAmount.Unavailable
                    val lowerValue = range["num1"]?.number ?: return ScheduleAmount.Unavailable
                    val upperValue = range["num2"]?.number ?: return ScheduleAmount.Unavailable
                    if (!lowerValue.isFinite() || !upperValue.isFinite()) return ScheduleAmount.Unavailable
                    val lower = actualRounded(lowerValue) ?: return ScheduleAmount.Unavailable
                    val upper = actualRounded(upperValue) ?: return ScheduleAmount.Unavailable
                    val posting = actualRounded((lowerValue + upperValue) / 2) ?: return ScheduleAmount.Unavailable
                    return ScheduleAmount.Range(lower, upper, posting)
                }
                else -> return ScheduleAmount.Unavailable
            }
        }

        /** Port of `ScheduleRuleProjection.dateRule(from:)`. */
        fun dateRuleFrom(condition: RuleCondition): ScheduleDateRule {
            if (condition.op != "is" && condition.op != "isapprox") return ScheduleDateRule.Unavailable
            return when (val value = condition.value) {
                is RuleValue.Text -> {
                    if (ScheduleRecurrence.parseDay(value.value) == null) {
                        ScheduleDateRule.Unavailable
                    } else {
                        ScheduleDateRule.OneTime(value.value, condition.op)
                    }
                }
                is RuleValue.Obj -> try {
                    ScheduleDateRule.Recurring(ScheduleRecurrence.fromRule(value), condition.op)
                } catch (e: Exception) {
                    ScheduleDateRule.Unavailable
                }
                else -> ScheduleDateRule.Unavailable
            }
        }

        /** Swift `Int(exactly: floor(amount + 0.5))`. */
        internal fun actualRounded(amount: Double): Long? {
            if (!amount.isFinite()) return null
            return RuleNumbers.exactLong(Math.floor(amount + 0.5))
        }
    }
}
