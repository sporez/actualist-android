package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.db.Sql
import com.sporez.actualist.core.rules.ScheduleRecurrence
import com.sporez.actualist.core.sync.SyncValue

/**
 * Schedule writes as Actual CRDT cells. Port of Actualist `BudgetDatabase+ScheduleWrites` and
 * `ScheduleCreationMessagePlan`. Each operation validates a [ScheduleMutationReview] (or the
 * create command) and commits through [BudgetDatabase.commitLocal] in one transaction.
 */
class ScheduleWrites(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
) {
    private val sql: Sql get() = database.sql
    private val reads = ScheduleReads(database)

    private class Decision(val drafts: List<CellDraft>, val result: ScheduleMutationResult)

    fun createSchedule(command: ScheduleCreateCommand, nowMillis: Long = clock.nowMillis()): ScheduleMutationResult =
        commit(nowMillis) { prepareCreate(command, nowMillis) }

    fun updateSchedule(
        review: ScheduleMutationReview,
        fields: ScheduleEditFields,
        asOfDayId: String,
        nowMillis: Long = clock.nowMillis(),
    ): ScheduleMutationResult = commit(nowMillis) {
        prepareUpdate(reads.validateReview(review), fields, asOfDayId, nowMillis)
    }

    fun deleteSchedule(review: ScheduleMutationReview, nowMillis: Long = clock.nowMillis()): ScheduleMutationResult =
        commit(nowMillis) { prepareDelete(reads.validateReview(review)) }

    fun skipNextDate(review: ScheduleMutationReview, nowMillis: Long = clock.nowMillis()): ScheduleMutationResult =
        commit(nowMillis) { prepareSkip(reads.validateReview(review)) }

    fun completeSchedule(review: ScheduleMutationReview, nowMillis: Long = clock.nowMillis()): ScheduleMutationResult =
        commit(nowMillis) { prepareComplete(reads.validateReview(review)) }

    private fun commit(nowMillis: Long, prepare: () -> Decision): ScheduleMutationResult {
        val committed = database.commitLocal(nowMillis) {
            val decision = try {
                prepare()
            } catch (e: ScheduleRuleMutationException) {
                throw translate(e)
            }
            decision.drafts to decision.result
        }
        return ScheduleMutationResult(committed.outcome.scheduleId, committed.outcome.kind, committed.messages.size)
    }

    private fun translate(error: ScheduleRuleMutationException): ScheduleMutationCommandException = when (error.error) {
        ScheduleRuleMutationError.UNSUPPORTED_FIELD ->
            ScheduleMutationCommandException.UnsupportedCapability("The schedule's ${error.field} condition cannot be edited safely.")
        else -> ScheduleMutationCommandException.InvalidCommand("The schedule definition cannot be changed safely.")
    }

    // region Create

    private fun prepareCreate(command: ScheduleCreateCommand, nowMillis: Long): Decision {
        validateUniqueName(command.name, command.identity.scheduleId)
        validateAccount(command.definition.accountId)
        command.customUpcomingLength?.let { validateUpcomingLength(it) }
        command.definition.payeeMappingId?.let { validatePayee(it) }
        val json = ScheduleRuleMutation.newRuleJson(command.identity.scheduleId, command.definition)
        val nextDate = ScheduleRuleMutation.initialNextDate(command.definition.dateRule, command.asOfDayId)
        val drafts = creationDrafts(
            command.identity,
            normalizedName(command.name),
            command.postsTransaction,
            command.customUpcomingLength,
            json.first,
            json.second,
            nextDate,
            nowMillis,
        )
        return Decision(drafts, ScheduleMutationResult(command.identity.scheduleId, ScheduleMutationKind.CREATED, 0))
    }

    /**
     * Shared schedule, rule and next-date cells. Like Actual's `db.insert`, no cell names the
     * `id` column: the CRDT row id is the identity and applying the first cell creates the row.
     */
    private fun creationDrafts(
        identity: ScheduleCreateIdentity,
        name: String?,
        postsTransaction: Boolean,
        customUpcomingLength: String?,
        conditionsJson: String,
        actionsJson: String,
        nextDate: String?,
        nowMillis: Long,
    ): List<CellDraft> {
        val scheduleColumns = reads.requiredColumns("schedules", listOf("id", "rule", "completed", "posts_transaction", "tombstone"))
        val ruleColumns = reads.requiredColumns("rules", listOf("id", "conditions", "actions", "tombstone"))
        reads.requiredColumns(
            "schedules_next_date",
            listOf("id", "schedule_id", "local_next_date", "local_next_date_ts", "base_next_date", "base_next_date_ts", "tombstone"),
        )
        val ids = listOf(identity.scheduleId, identity.ruleId, identity.nextDateId)
        if (!ids.all { it.isNotEmpty() } || ids.toSet().size != ids.size) {
            throw ScheduleMutationCommandException.InvalidCommand("Schedule identifiers are invalid.")
        }
        val tables = listOf("schedules", "rules", "schedules_next_date")
        for (index in ids.indices) {
            if (database.rowExists(tables[index], ids[index])) throw ScheduleMutationCommandException.IdentityConflict()
        }

        val drafts = mutableListOf<CellDraft>()
        drafts.add(CellDraft("rules", identity.ruleId, "conditions", SyncValue.text(conditionsJson)))
        drafts.add(CellDraft("rules", identity.ruleId, "actions", SyncValue.text(actionsJson)))
        if ("stage" in ruleColumns) drafts.add(CellDraft("rules", identity.ruleId, "stage", SyncValue.Null))
        if ("conditions_op" in ruleColumns) drafts.add(CellDraft("rules", identity.ruleId, "conditions_op", SyncValue.text("and")))
        drafts.add(CellDraft("rules", identity.ruleId, "tombstone", SyncValue.bool(false)))

        drafts.add(CellDraft("schedules", identity.scheduleId, "rule", SyncValue.text(identity.ruleId)))
        drafts.add(CellDraft("schedules", identity.scheduleId, "completed", SyncValue.bool(false)))
        drafts.add(CellDraft("schedules", identity.scheduleId, "posts_transaction", SyncValue.bool(postsTransaction)))
        drafts.add(CellDraft("schedules", identity.scheduleId, "tombstone", SyncValue.bool(false)))
        if ("name" in scheduleColumns) {
            drafts.add(CellDraft("schedules", identity.scheduleId, "name", SyncValue.textOrNull(name)))
        } else if (name != null) {
            throw ScheduleMutationCommandException.UnsupportedCapability("This budget does not support schedule names.")
        }
        if (customUpcomingLength != null) {
            if ("custom_upcoming_length" !in scheduleColumns) {
                throw ScheduleMutationCommandException.UnsupportedCapability("This budget does not support a custom upcoming window.")
            }
            drafts.add(CellDraft("schedules", identity.scheduleId, "custom_upcoming_length", SyncValue.text(customUpcomingLength)))
        }
        if ("sort_order" in scheduleColumns) {
            drafts.add(CellDraft("schedules", identity.scheduleId, "sort_order", SyncValue.Real(0.0)))
        }
        val dateValue = if (nextDate == null) SyncValue.Null else scheduleDateValue(nextDate)
        val row = identity.nextDateId
        drafts.add(CellDraft("schedules_next_date", row, "schedule_id", SyncValue.text(identity.scheduleId)))
        drafts.add(CellDraft("schedules_next_date", row, "local_next_date", dateValue))
        drafts.add(CellDraft("schedules_next_date", row, "local_next_date_ts", SyncValue.int(nowMillis)))
        drafts.add(CellDraft("schedules_next_date", row, "base_next_date", dateValue))
        drafts.add(CellDraft("schedules_next_date", row, "base_next_date_ts", SyncValue.int(nowMillis)))
        drafts.add(CellDraft("schedules_next_date", row, "tombstone", SyncValue.bool(false)))
        return drafts
    }

    // endregion

    // region Update, delete, skip, complete

    private fun prepareUpdate(
        current: ScheduleMutationCurrentState,
        requested: ScheduleEditFields,
        asOfDayId: String,
        nowMillis: Long,
    ): Decision {
        val scheduleId = current.review.scheduleId
        if (requested.isEmpty) return unchanged(scheduleId)
        val scheduleColumns = reads.requiredColumns(
            "schedules",
            listOf("id", "rule", "completed", "posts_transaction", "tombstone"),
        )
        reads.requiredColumns("rules", listOf("id", "tombstone"))
        val capabilities = current.projection.capabilities
        if (!requested.accountId.isUnchanged && !capabilities.canEditAccount) {
            throw ScheduleMutationCommandException.UnsupportedCapability("The schedule's account condition cannot be edited safely.")
        }
        if (!requested.payeeMappingId.isUnchanged && !capabilities.canEditPayee) {
            throw ScheduleMutationCommandException.UnsupportedCapability("The schedule's payee condition cannot be edited safely.")
        }
        if (!requested.amount.isUnchanged && !capabilities.canEditAmount) {
            throw ScheduleMutationCommandException.UnsupportedCapability("The schedule's amount condition cannot be edited safely.")
        }
        if (!requested.dateRule.isUnchanged && !capabilities.canEditDate) {
            throw ScheduleMutationCommandException.UnsupportedCapability("The schedule's date condition cannot be edited safely.")
        }

        val fields = normalizedEdits(requested, current)
        if (fields.isEmpty) return unchanged(scheduleId)
        val newName: String? = when (val change = fields.name) {
            is ScheduleOptionalChange.Assign<String> -> normalizedName(change.value)
            else -> current.review.schedule.name
        }
        if (!fields.name.isUnchanged) {
            if ("name" !in scheduleColumns) {
                throw ScheduleMutationCommandException.UnsupportedCapability("This budget does not support schedule names.")
            }
            validateUniqueName(newName, scheduleId)
        }
        if (!fields.customUpcomingLength.isUnchanged) {
            if ("custom_upcoming_length" !in scheduleColumns) {
                throw ScheduleMutationCommandException.UnsupportedCapability("This budget does not support a custom upcoming window.")
            }
            val change = fields.customUpcomingLength
            if (change is ScheduleOptionalChange.Assign<String> && change.value != null) validateUpcomingLength(change.value)
        }
        val accountChange = fields.accountId
        if (accountChange is ScheduleOptionalChange.Assign<String> && accountChange.value == null) {
            throw ScheduleMutationCommandException.InvalidCommand("A schedule account is required.")
        }
        val accountChanged = accountTargetChanged(fields.accountId, current)
        val resetsNextDate = accountChanged || dateTargetChanged(fields.dateRule, current) || fields.resetNextDate
        if (resetsNextDate) {
            val columns = nextDateWriteColumns()
            val next = current.review.uniqueNextDate
            if (!(columns.contains("base_next_date") && columns.contains("base_next_date_ts")) || next == null) {
                throw ScheduleMutationCommandException.UnsupportedCapability("The schedule's next date is unavailable or ambiguous.")
            }
            if (next.baseTimestamp?.toLongOrNull() == null) {
                throw ScheduleMutationCommandException.UnsupportedCapability("The schedule's base date cannot be updated safely.")
            }
        }
        if (accountChanged && accountChange is ScheduleOptionalChange.Assign<String> && accountChange.value != null) {
            validateAccount(accountChange.value)
        }
        val payeeChange = fields.payeeMappingId
        if (payeeChange is ScheduleOptionalChange.Assign<String> && payeeChange.value != null &&
            payeeChange.value != current.projection.payeeMappingId
        ) {
            validatePayee(payeeChange.value)
        }
        val drafts = mutableListOf<CellDraft>()
        if (fields.changesDefinition) {
            reads.requiredColumns("rules", listOf("id", "conditions", "actions", "tombstone"))
            val oldConditions = current.review.rule.conditionsJson
            val oldActions = current.review.rule.actionsJson
            if (oldConditions == null || oldActions == null) {
                throw ScheduleMutationCommandException.UnsupportedCapability("The schedule definition is unavailable.")
            }
            val merged = ScheduleRuleMutation.merge(oldConditions, oldActions, scheduleId, fields)
            merged.conditions?.let {
                drafts.add(CellDraft("rules", current.review.ruleId, "conditions", SyncValue.text(it)))
            }
            merged.actions?.let {
                drafts.add(CellDraft("rules", current.review.ruleId, "actions", SyncValue.text(it)))
            }
        }
        if (!fields.name.isUnchanged) {
            drafts.add(CellDraft("schedules", scheduleId, "name", SyncValue.textOrNull(newName)))
        }
        val posts = fields.postsTransaction
        if (posts != null && posts != current.review.schedule.postsTransaction) {
            drafts.add(CellDraft("schedules", scheduleId, "posts_transaction", SyncValue.bool(posts)))
        }
        val upcomingChange = fields.customUpcomingLength
        if (upcomingChange is ScheduleOptionalChange.Assign<String> &&
            upcomingChange.value != current.review.schedule.customUpcomingLength
        ) {
            drafts.add(CellDraft("schedules", scheduleId, "custom_upcoming_length", SyncValue.textOrNull(upcomingChange.value)))
        }
        if (resetsNextDate) {
            val definition = updatedDateRule(fields.dateRule, current)
            val nextDate = ScheduleRuleMutation.updateNextDate(definition, asOfDayId, current.effectiveNextDate)
            if (nextDate != null) {
                val next = current.review.uniqueNextDate
                    ?: throw ScheduleMutationCommandException.UnsupportedCapability("The schedule's next date is unavailable.")
                drafts.addAll(nextDateDrafts(next.id, nextDate, true, next.baseTimestamp, nowMillis))
            }
        }
        if (drafts.isEmpty()) return unchanged(scheduleId)
        return Decision(drafts, ScheduleMutationResult(scheduleId, ScheduleMutationKind.UPDATED, 0))
    }

    private fun prepareDelete(current: ScheduleMutationCurrentState): Decision {
        reads.requiredColumns("schedules", listOf("id", "rule", "tombstone"))
        reads.requiredColumns("rules", listOf("id", "tombstone"))
        val drafts = listOf(
            CellDraft("rules", current.review.ruleId, "tombstone", SyncValue.bool(true)),
            CellDraft("schedules", current.review.scheduleId, "tombstone", SyncValue.bool(true)),
        )
        return Decision(drafts, ScheduleMutationResult(current.review.scheduleId, ScheduleMutationKind.DELETED, drafts.size))
    }

    private fun prepareSkip(current: ScheduleMutationCurrentState): Decision {
        val recurrence = current.projection.dateRule.recurrence
        val next = current.review.uniqueNextDate
        val effectiveDate = current.effectiveNextDate
        if (recurrence == null || next == null || effectiveDate == null) {
            throw ScheduleMutationCommandException.UnsupportedCapability("The schedule recurrence or next date is unsupported.")
        }
        val columns = nextDateWriteColumns()
        if (!(columns.contains("local_next_date") && columns.contains("local_next_date_ts"))) {
            throw ScheduleMutationCommandException.UnsupportedCapability("This budget does not support schedule next dates.")
        }
        val updated = ScheduleRuleMutation.nextDateAfterSkip(recurrence, effectiveDate)
        if (updated == null || updated == effectiveDate) return unchanged(current.review.scheduleId)
        val drafts = nextDateDrafts(next.id, updated, false, next.baseTimestamp, 0L)
        return Decision(drafts, ScheduleMutationResult(current.review.scheduleId, ScheduleMutationKind.SKIPPED, drafts.size))
    }

    private fun prepareComplete(current: ScheduleMutationCurrentState): Decision {
        val scheduleColumns = reads.requiredColumns("schedules", listOf("id", "rule", "completed", "tombstone"))
        if ("completed" !in scheduleColumns) {
            throw ScheduleMutationCommandException.UnsupportedCapability("This budget does not support completing schedules.")
        }
        if (current.projection.dateRule !is ScheduleDateRule.OneTime || current.review.schedule.completed) {
            if (current.review.schedule.completed) return unchanged(current.review.scheduleId)
            throw ScheduleMutationCommandException.UnsupportedCapability("Only a supported one-time schedule can be completed.")
        }
        val drafts = listOf(CellDraft("schedules", current.review.scheduleId, "completed", SyncValue.bool(true)))
        return Decision(drafts, ScheduleMutationResult(current.review.scheduleId, ScheduleMutationKind.COMPLETED, drafts.size))
    }

    private fun nextDateWriteColumns(): Set<String> = reads.requiredColumns(
        "schedules_next_date",
        listOf("id", "schedule_id", "local_next_date", "local_next_date_ts", "base_next_date", "base_next_date_ts", "tombstone"),
    )

    /**
     * The next-date cells: the base pair (a reset, stamped with [nowMillis]) or the local pair
     * (a skip or advance, stamped with the row's base timestamp so the local date stays effective).
     */
    internal fun nextDateDrafts(rowId: String, date: String, base: Boolean, baseTimestamp: String?, nowMillis: Long): List<CellDraft> {
        val parsedBase = baseTimestamp?.toLongOrNull()
            ?: throw ScheduleMutationCommandException.UnsupportedCapability("The schedule's base date cannot be updated safely.")
        if (base) {
            return listOf(
                CellDraft("schedules_next_date", rowId, "base_next_date", scheduleDateValue(date)),
                CellDraft("schedules_next_date", rowId, "base_next_date_ts", SyncValue.int(nowMillis)),
            )
        }
        return listOf(
            CellDraft("schedules_next_date", rowId, "local_next_date", scheduleDateValue(date)),
            CellDraft("schedules_next_date", rowId, "local_next_date_ts", SyncValue.int(parsedBase)),
        )
    }

    internal fun scheduleDateValue(dayId: String): SyncValue {
        val value = dayId.replace("-", "").toLongOrNull()
        if (value == null || ScheduleRecurrence.parseDay(dayId) == null) {
            throw ScheduleMutationCommandException.InvalidCommand("The schedule date is invalid.")
        }
        return SyncValue.int(value)
    }

    // endregion

    // region Validation

    private fun validateAccount(accountId: String) {
        reads.requiredColumns("accounts", listOf("id", "closed", "tombstone"))
        val row = sql.queryOne("SELECT closed, tombstone FROM accounts WHERE id = ? LIMIT 1", accountId)
        if (row == null || row.bool("closed") || row.bool("tombstone")) {
            throw ScheduleMutationCommandException.InvalidCommand("Choose an available, open account for this schedule.")
        }
    }

    private fun validatePayee(payeeMappingId: String) {
        val mappingColumns = reads.requiredColumns("payee_mapping", listOf("id"))
        val targetColumn = listOf("targetId", "target_id").firstOrNull { it in mappingColumns }
            ?: throw ScheduleMutationCommandException.UnsupportedSchema()
        val payeeColumns = reads.requiredColumns("payees", listOf("id"))
        val found = sql.queryOne(
            """
            SELECT mapping.id
            FROM payee_mapping AS mapping
            JOIN payees AS payee ON payee.id = mapping.${Sql.quoted(targetColumn)}
            WHERE mapping.id = ?
                AND mapping.${Sql.quoted(targetColumn)} IS NOT NULL
                AND ${BudgetDatabase.livePredicate(mappingColumns, "mapping")}
                AND ${BudgetDatabase.livePredicate(payeeColumns, "payee")}
            LIMIT 1
            """.trimIndent(),
            payeeMappingId,
        )
        if (found == null) throw ScheduleMutationCommandException.InvalidCommand("The selected schedule payee is unavailable.")
    }

    private fun validateUniqueName(rawName: String?, excludingScheduleId: String) {
        val name = normalizedName(rawName) ?: return
        reads.requiredColumns("schedules", listOf("id", "name", "tombstone"))
        val duplicate = sql.queryOne(
            "SELECT id FROM schedules WHERE tombstone = 0 AND name = ? AND id != ? LIMIT 1",
            name,
            excludingScheduleId,
        ) != null
        if (duplicate) throw ScheduleMutationCommandException.DuplicateName()
    }

    private fun validateUpcomingLength(value: String) {
        val valid = when (value) {
            "currentMonth", "oneMonth" -> true
            else -> {
                val days = value.toLongOrNull()
                if (days != null) {
                    days >= 0
                } else {
                    val pieces = value.split("-")
                    pieces.size == 2 && (pieces[0].toLongOrNull()?.let { it > 0 } == true) &&
                        pieces[1] in listOf("day", "week", "month", "year")
                }
            }
        }
        if (!valid) throw ScheduleMutationCommandException.InvalidCommand("The custom upcoming window is invalid.")
    }

    // endregion

    // region Edit normalization

    private fun normalizedName(rawName: String?): String? {
        val trimmed = rawName?.trim()
        return if (trimmed.isNullOrEmpty()) null else trimmed
    }

    private fun normalizedEdits(edits: ScheduleEditFields, current: ScheduleMutationCurrentState): ScheduleEditFields {
        var result = edits
        val name = result.name
        if (name is ScheduleOptionalChange.Assign<String> &&
            normalizedName(name.value) == normalizedName(current.review.schedule.name)
        ) {
            result = result.copy(name = ScheduleOptionalChange.Unchanged)
        }
        val account = result.accountId
        if (account is ScheduleOptionalChange.Assign<String> && account.value != null && account.value == current.projection.accountId) {
            result = result.copy(accountId = ScheduleOptionalChange.Unchanged)
        }
        val payee = result.payeeMappingId
        if (payee is ScheduleOptionalChange.Assign<String> && payee.value == current.projection.payeeMappingId) {
            result = result.copy(payeeMappingId = ScheduleOptionalChange.Unchanged)
        }
        val amount = result.amount
        if (amount is ScheduleOptionalChange.Assign<ScheduleAmountDraft> && amount.value != null &&
            amountMatches(amount.value, current.projection.amount)
        ) {
            result = result.copy(amount = ScheduleOptionalChange.Unchanged)
        }
        val dateRule = result.dateRule
        if (dateRule is ScheduleOptionalChange.Assign<ScheduleDateRule> && dateRule.value != null &&
            dateRule.value == current.projection.dateRule
        ) {
            result = result.copy(dateRule = ScheduleOptionalChange.Unchanged)
        }
        val posts = result.postsTransaction
        if (posts != null && posts == current.review.schedule.postsTransaction) {
            result = result.copy(postsTransaction = null)
        }
        val upcoming = result.customUpcomingLength
        if (upcoming is ScheduleOptionalChange.Assign<String> && upcoming.value == current.review.schedule.customUpcomingLength) {
            result = result.copy(customUpcomingLength = ScheduleOptionalChange.Unchanged)
        }
        return result
    }

    private fun amountMatches(draft: ScheduleAmountDraft, amount: ScheduleAmount): Boolean = when {
        draft is ScheduleAmountDraft.Exact && amount is ScheduleAmount.Exact -> draft.amount == amount.amount
        draft is ScheduleAmountDraft.Approximate && amount is ScheduleAmount.Approximate -> draft.amount == amount.amount
        draft is ScheduleAmountDraft.Range && amount is ScheduleAmount.Range -> draft.lower == amount.lower && draft.upper == amount.upper
        else -> false
    }

    private fun accountTargetChanged(change: ScheduleOptionalChange<String>, current: ScheduleMutationCurrentState): Boolean {
        if (change !is ScheduleOptionalChange.Assign<String>) return false
        val id = change.value ?: return false
        return id != current.accountId
    }

    private fun dateTargetChanged(change: ScheduleOptionalChange<ScheduleDateRule>, current: ScheduleMutationCurrentState): Boolean {
        if (change !is ScheduleOptionalChange.Assign<ScheduleDateRule>) return false
        val date = change.value ?: return false
        if (current.projection.dateRule == ScheduleDateRule.Unavailable) return false
        return date != current.projection.dateRule
    }

    private fun updatedDateRule(change: ScheduleOptionalChange<ScheduleDateRule>, current: ScheduleMutationCurrentState): ScheduleDateRule {
        if (change is ScheduleOptionalChange.Assign<ScheduleDateRule> && change.value != null) return change.value
        if (current.projection.dateRule == ScheduleDateRule.Unavailable) {
            throw ScheduleMutationCommandException.UnsupportedCapability("The schedule's date condition cannot be edited safely.")
        }
        return current.projection.dateRule
    }

    private fun unchanged(scheduleId: String): Decision =
        Decision(emptyList(), ScheduleMutationResult(scheduleId, ScheduleMutationKind.UNCHANGED, 0))

    // endregion
}
