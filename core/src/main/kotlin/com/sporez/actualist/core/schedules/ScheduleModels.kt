package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.rules.ScheduleRecurrence
import java.time.DateTimeException

/** Actualist `ScheduleStatus`: Actual's `getStatus` result for one schedule. */
enum class ScheduleStatus(val raw: String) {
    COMPLETED("completed"),
    PAID("paid"),
    DUE("due"),
    UPCOMING("upcoming"),
    MISSED("missed"),
    SCHEDULED("scheduled");

    /**
     * Actual's "Post transaction" menu is offered for every non-completed schedule. Paid stays
     * refused: its occurrence already has a transaction, and the refusal keeps a double tap or a
     * peer's post from duplicating it.
     */
    val allowsManualPosting: Boolean
        get() = when (this) {
            DUE, UPCOMING, MISSED, SCHEDULED -> true
            COMPLETED, PAID -> false
        }

    companion object {
        fun resolve(
            nextDate: String,
            completed: Boolean,
            hasMatchingTransaction: Boolean,
            today: String,
            upcomingLength: String,
        ): ScheduleStatus {
            if (completed) return COMPLETED
            if (hasMatchingTransaction) return PAID
            if (nextDate == today) return DUE
            if (nextDate < today) return MISSED
            val todayDate = ScheduleRecurrence.parseDay(today) ?: return SCHEDULED
            val nextDateValue = ScheduleRecurrence.parseDay(nextDate) ?: return SCHEDULED
            val end = try {
                todayDate.plusDays(ScheduleUpcomingLength.days(upcomingLength, today))
            } catch (e: DateTimeException) {
                return SCHEDULED
            }
            return if (nextDateValue <= end) UPCOMING else SCHEDULED
        }
    }
}

/** Amounts are integer minor units. */
sealed class ScheduleAmount {
    abstract val postingAmount: Long?

    data class Exact(val amount: Long) : ScheduleAmount() {
        override val postingAmount: Long? get() = amount
    }

    data class Approximate(val amount: Long) : ScheduleAmount() {
        override val postingAmount: Long? get() = amount
    }

    data class Range(val lower: Long, val upper: Long, override val postingAmount: Long) : ScheduleAmount()

    object Unavailable : ScheduleAmount() {
        override val postingAmount: Long? get() = null
        override fun toString(): String = "Unavailable"
    }

    /** A stable text form, so oracle replays can compare amounts. */
    val canonical: String
        get() = when (this) {
            is Exact -> "exact($amount)"
            is Approximate -> "approximate($amount)"
            is Range -> "range($lower,$upper,$postingAmount)"
            is Unavailable -> "unavailable"
        }
}

/** Actualist `ScheduleDateRule`. Equality compares the rule's [signature]. */
sealed class ScheduleDateRule {
    abstract val signature: String

    data class OneTime(val dayId: String, val operation: String) : ScheduleDateRule() {
        override val signature: String get() = "oneTime($dayId,$operation)"
    }

    class Recurring(val recurrenceValue: ScheduleRecurrence, val operation: String) : ScheduleDateRule() {
        override val signature: String get() = "recurring(${recurrenceValue.signature()},$operation)"
        override fun equals(other: Any?): Boolean = other is Recurring && other.signature == signature
        override fun hashCode(): Int = signature.hashCode()
        override fun toString(): String = signature
    }

    object Unavailable : ScheduleDateRule() {
        override val signature: String get() = "unavailable"
        override fun toString(): String = signature
    }

    val recurrence: ScheduleRecurrence?
        get() = (this as? Recurring)?.recurrenceValue
}

enum class ScheduleOccurrenceMatchingMode { EXACT, APPROXIMATE }

enum class ScheduleReferenceAvailability { AVAILABLE, CLOSED, MISSING }

data class ScheduleAccountReference(
    val id: String?,
    val name: String?,
    val availability: ScheduleReferenceAvailability,
)

data class SchedulePayeeReference(
    val id: String?,
    val name: String?,
    val isMissing: Boolean,
) {
    /**
     * The payee id a posted transaction may carry. A missing payee (no mapping row, or a mapping
     * whose payee is gone) posts with no payee, like Actual's null `schedule._payee`.
     */
    val postingPayeeId: String? get() = if (isMissing) null else id
}

enum class ScheduleUnsupportedReason(val message: String) {
    MISSING_RULE("The linked rule is unavailable."),
    MALFORMED_CONDITIONS("The linked rule cannot be read safely."),
    MALFORMED_ACTIONS("The linked rule cannot be read safely."),
    MISSING_DATE("The schedule uses date options Actualist cannot safely interpret."),
    UNSUPPORTED_DATE("The schedule uses date options Actualist cannot safely interpret."),
    MISSING_AMOUNT("The schedule uses amount options Actualist cannot safely interpret."),
    UNSUPPORTED_AMOUNT("The schedule uses amount options Actualist cannot safely interpret."),
    UNSUPPORTED_ACTIONS("Created with schedule actions Actualist cannot safely edit."),
    CORRUPT_RULE_LINKAGE("The linked rule does not point back to this schedule."),
    MISSING_NEXT_DATE("The next occurrence is unavailable."),
    AMBIGUOUS_NEXT_DATE("The next occurrence is unavailable."),
}

data class ScheduleMutationCapabilities(
    val canRead: Boolean,
    val canEditMetadata: Boolean,
    val canEditAccount: Boolean,
    val canEditPayee: Boolean,
    val canEditAmount: Boolean,
    val canEditDate: Boolean,
    val canSkip: Boolean,
    val canComplete: Boolean,
    val canDelete: Boolean,
    val canPost: Boolean,
) {
    val canEdit: Boolean
        get() = canEditMetadata || canEditAccount || canEditPayee || canEditAmount || canEditDate
}

data class ScheduleOccurrenceIdentity(
    val scheduleId: String,
    val nextDateRowId: String?,
    val effectiveNextDate: String?,
    val localNextDateTimestamp: String?,
    val baseNextDateTimestamp: String?,
)

data class ScheduleSummary(
    val id: String,
    val name: String?,
    val amount: ScheduleAmount,
    val account: ScheduleAccountReference,
    val payee: SchedulePayeeReference,
    val effectiveNextDate: String?,
    val status: ScheduleStatus,
    val postsTransaction: Boolean,
    val sortOrder: Double?,
    val unsupportedReasons: List<ScheduleUnsupportedReason>,
) {
    val displayName: String
        get() = name?.trim().orEmpty().ifEmpty { "Unnamed schedule" }

    val isSupported: Boolean get() = unsupportedReasons.isEmpty()
}

data class ScheduleDetail(
    val id: String,
    val ruleId: String?,
    val name: String?,
    val amount: ScheduleAmount,
    val dateRule: ScheduleDateRule,
    val account: ScheduleAccountReference,
    val payee: SchedulePayeeReference,
    val effectiveNextDate: String?,
    val status: ScheduleStatus,
    val completed: Boolean,
    val postsTransaction: Boolean,
    val customUpcomingLength: String?,
    val sortOrder: Double?,
    val rawConditionsJson: String?,
    val rawActionsJson: String?,
    val capabilities: ScheduleMutationCapabilities,
    val unsupportedReasons: List<ScheduleUnsupportedReason>,
    val occurrenceIdentity: ScheduleOccurrenceIdentity,
) {
    val summary: ScheduleSummary
        get() = ScheduleSummary(
            id, name, amount, account, payee, effectiveNextDate, status, postsTransaction, sortOrder, unsupportedReasons,
        )
}

class LoadedSchedules(
    val schedules: List<ScheduleSummary>,
    val detailsById: Map<String, ScheduleDetail>,
    val defaultUpcomingLength: String,
    /**
     * Whether this budget's schema can accept schedule authoring (creating schedules). Computed
     * from the same column requirements the write path enforces.
     */
    val supportsAuthoring: Boolean = true,
) {
    fun detail(id: String): ScheduleDetail? = detailsById[id]

    companion object {
        fun empty(): LoadedSchedules = LoadedSchedules(emptyList(), emptyMap(), "7", supportsAuthoring = false)
    }
}

// region Mutation drafts

sealed class ScheduleAmountDraft {
    data class Exact(val amount: Long) : ScheduleAmountDraft()
    data class Approximate(val amount: Long) : ScheduleAmountDraft()
    data class Range(val lower: Long, val upper: Long) : ScheduleAmountDraft()
}

data class ScheduleDefinitionDraft(
    val accountId: String,
    val payeeMappingId: String?,
    val amount: ScheduleAmountDraft,
    val dateRule: ScheduleDateRule,
)

data class ScheduleCreateIdentity(val scheduleId: String, val ruleId: String, val nextDateId: String) {
    companion object {
        fun make(): ScheduleCreateIdentity = ScheduleCreateIdentity(
            java.util.UUID.randomUUID().toString(),
            java.util.UUID.randomUUID().toString(),
            java.util.UUID.randomUUID().toString(),
        )
    }
}

data class ScheduleCreateCommand(
    val identity: ScheduleCreateIdentity,
    val name: String?,
    val definition: ScheduleDefinitionDraft,
    val postsTransaction: Boolean,
    val customUpcomingLength: String?,
    val asOfDayId: String,
)

/** An edit to an optional value: leave it, or assign it (null clears). */
sealed class ScheduleOptionalChange<out T> {
    object Unchanged : ScheduleOptionalChange<Nothing>() {
        override fun toString(): String = "Unchanged"
    }

    data class Assign<out T>(val value: T?) : ScheduleOptionalChange<T>()

    val isUnchanged: Boolean get() = this is Unchanged
}

data class ScheduleEditFields(
    val name: ScheduleOptionalChange<String> = ScheduleOptionalChange.Unchanged,
    val accountId: ScheduleOptionalChange<String> = ScheduleOptionalChange.Unchanged,
    val payeeMappingId: ScheduleOptionalChange<String> = ScheduleOptionalChange.Unchanged,
    val amount: ScheduleOptionalChange<ScheduleAmountDraft> = ScheduleOptionalChange.Unchanged,
    val dateRule: ScheduleOptionalChange<ScheduleDateRule> = ScheduleOptionalChange.Unchanged,
    val postsTransaction: Boolean? = null,
    val customUpcomingLength: ScheduleOptionalChange<String> = ScheduleOptionalChange.Unchanged,
    val resetNextDate: Boolean = false,
) {
    val changesDefinition: Boolean
        get() = !accountId.isUnchanged || !payeeMappingId.isUnchanged || !amount.isUnchanged || !dateRule.isUnchanged

    val isEmpty: Boolean
        get() = name.isUnchanged && !changesDefinition && postsTransaction == null &&
            customUpcomingLength.isUnchanged && !resetNextDate
}

// endregion

// region Reviews

data class ScheduleRowRevision(
    val name: String?,
    val completed: Boolean,
    val postsTransaction: Boolean,
    val customUpcomingLength: String?,
    val sortOrder: Double?,
    val tombstone: Boolean,
    val active: Boolean,
)

data class ScheduleRuleRevision(
    val conditionsJson: String?,
    val actionsJson: String?,
    val stage: String?,
    val conditionsOperation: String?,
    val tombstone: Boolean,
)

data class ScheduleNextDateRevision(
    val id: String,
    val localDate: String?,
    val localTimestamp: String?,
    val baseDate: String?,
    val baseTimestamp: String?,
    val tombstone: Boolean,
) {
    val effectiveDate: String?
        get() {
            val raw = if (localTimestamp != null && localTimestamp == baseTimestamp) localDate else baseDate
            if (raw == null) return null
            val trimmed = raw.trim()
            if (trimmed.length == 8 && trimmed.all { it.isDigit() }) {
                return trimmed.substring(0, 4) + "-" + trimmed.substring(4, 6) + "-" + trimmed.substring(6, 8)
            }
            return trimmed
        }
}

data class ScheduleAccountRevision(
    val id: String,
    val name: String?,
    val offBudget: Boolean,
    val isClosed: Boolean,
    val tombstone: Boolean,
)

/** A snapshot of one schedule's rows, re-read and compared inside every write. */
data class ScheduleMutationReview(
    val scheduleId: String,
    val ruleId: String,
    val schedule: ScheduleRowRevision,
    val rule: ScheduleRuleRevision,
    val nextDates: List<ScheduleNextDateRevision>,
    val account: ScheduleAccountRevision?,
) {
    val uniqueNextDate: ScheduleNextDateRevision?
        get() {
            val liveRows = nextDates.filter { !it.tombstone }
            return if (liveRows.size == 1) liveRows[0] else null
        }
}

enum class ScheduleMutationKind { CREATED, UPDATED, DELETED, SKIPPED, COMPLETED, UNCHANGED }

data class ScheduleMutationResult(
    val scheduleId: String,
    val kind: ScheduleMutationKind,
    val appliedMessageCount: Int,
)

// endregion

// region Errors

sealed class ScheduleMutationCommandException(message: String) : Exception(message) {
    class ReviewChanged : ScheduleMutationCommandException("This schedule changed. Review its latest details and try again.")
    class IdentityConflict :
        ScheduleMutationCommandException("This schedule could not be created because its identity is already in use.")
    class DuplicateName : ScheduleMutationCommandException("A live schedule already uses this name.")
    class UnsupportedSchema :
        ScheduleMutationCommandException("This budget's schedule tables predate what Actualist can write.")
    class UnsupportedCapability(message: String) : ScheduleMutationCommandException(message)
    class InvalidCommand(message: String) : ScheduleMutationCommandException(message)
}

/** A deterministic reason a scheduled occurrence cannot be posted. */
enum class SchedulePostingRefusal(val message: String) {
    INVALID_COMMAND("This schedule cannot be posted safely."),
    UNSUPPORTED_OCCURRENCE("This schedule cannot be posted safely."),
    ACCOUNT_UNAVAILABLE("This schedule occurrence is no longer available to post."),
    OCCURRENCE_UNAVAILABLE("This schedule occurrence is no longer available to post."),
    OCCURRENCE_DATE_MISMATCH("This schedule changed during sync. Review its latest details and try again."),
    DRAFT_MISMATCH("This schedule changed during sync. Review its latest details and try again."),
    RULE_DELETES_TRANSACTION("A matching rule removes this scheduled transaction, so it cannot be posted."),
    RULE_CHANGED_SCHEDULE_LINK(
        "A matching rule changed the transaction's schedule link, so Actual will not mark this occurrence as paid. " +
            "Update the rule to keep it linked to this schedule.",
    ),
    UNSUPPORTED_BUDGET_SCHEMA("This budget's file layout does not support posting this schedule."),
    REFERENCED_ROW_UNAVAILABLE(
        "This schedule's rule or transfer points to an account, category, or payee that was deleted or closed. " +
            "Edit the schedule's rule or reopen the account, then post it again.",
    ),
}

class SchedulePostingRefusalException(val refusal: SchedulePostingRefusal) : Exception(refusal.message)

enum class ScheduleRuleMutationError { MALFORMED_CONDITIONS, MALFORMED_ACTIONS, INVALID_DEFINITION, UNSUPPORTED_FIELD, INVALID_SCHEDULE_LINK }

class ScheduleRuleMutationException(val error: ScheduleRuleMutationError, val field: String? = null) :
    Exception("Schedule rule mutation failed: $error")

// endregion
