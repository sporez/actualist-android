package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.budget.TransactionSplitSql
import com.sporez.actualist.core.budget.normalizedDateSql
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Sql
import com.sporez.actualist.core.rules.ScheduleRecurrence
import java.time.DateTimeException

/** What a write sees after re-reading and validating a [ScheduleMutationReview]. */
class ScheduleMutationCurrentState(
    val review: ScheduleMutationReview,
    val projection: ScheduleRuleProjection,
) {
    val effectiveNextDate: String? get() = review.uniqueNextDate?.effectiveDate
    val accountId: String? get() = projection.accountId
}

/**
 * Schedule reads. Port of Actualist `BudgetDatabase+ScheduleReads` and the review capture in
 * `ScheduleMutationPrecondition`. Every method takes the connection lock, so it is safe to call
 * inside [BudgetDatabase.commitLocal]'s prepare step.
 */
class ScheduleReads(private val database: BudgetDatabase) {
    private val sql: Sql get() = database.sql

    fun fetchSchedules(today: String): LoadedSchedules = sql.locked { loadSchedules(today, null) }

    /** One schedule's detail, identical to `fetchSchedules(today).detail(id)`. */
    fun fetchScheduleDetail(scheduleId: String, today: String): ScheduleDetail? =
        sql.locked { loadSchedules(today, scheduleId).detail(scheduleId) }

    fun scheduleMutationReview(scheduleId: String): ScheduleMutationReview = sql.locked { captureReview(scheduleId) }

    /** Re-reads the schedule and refuses when it differs from [review] or can no longer be edited. */
    fun validateReview(review: ScheduleMutationReview): ScheduleMutationCurrentState = sql.locked {
        val current = captureReview(review.scheduleId)
        if (current != review) throw ScheduleMutationCommandException.ReviewChanged()
        if (current.rule.tombstone) {
            throw ScheduleMutationCommandException.UnsupportedCapability("The schedule's linked rule is unavailable.")
        }
        val projection = ScheduleRuleProjection.read(current.scheduleId, current.rule.conditionsJson, current.rule.actionsJson)
        if (!projection.capabilities.canDelete) {
            throw ScheduleMutationCommandException.UnsupportedCapability("The schedule's rule link cannot be edited safely.")
        }
        if (ruleHasAnotherLiveOwner(current.ruleId, current.scheduleId)) {
            throw ScheduleMutationCommandException.UnsupportedCapability("The schedule's rule is shared by another live schedule.")
        }
        ScheduleMutationCurrentState(current, projection)
    }

    fun requiredColumns(table: String, required: List<String>): Set<String> = try {
        database.requiredColumns(table, required)
    } catch (e: LocalFirstException.SchemaUnavailable) {
        throw ScheduleMutationCommandException.UnsupportedSchema()
    }

    private fun column(name: String, fallback: String, columns: Set<String>): String = if (name in columns) name else fallback

    private class RuleRow(val conditions: String?, val actions: String?)

    private class NextDateRow(
        val id: String?,
        val localDate: String?,
        val localTimestamp: String?,
        val baseDate: String?,
        val baseTimestamp: String?,
    ) {
        val effectiveDate: String? get() = if (localTimestamp != null && localTimestamp == baseTimestamp) localDate else baseDate
    }

    private class AccountFact(val name: String, val isClosed: Boolean)

    private fun loadSchedules(today: String, onlyScheduleId: String?): LoadedSchedules {
        if (!database.tableExists("schedules")) return LoadedSchedules.empty()
        val scheduleColumns = database.columnSet("schedules")
        if ("id" !in scheduleColumns) return LoadedSchedules.empty()

        val defaultUpcomingLength = upcomingLengthPreference()
        val nextDates = nextDateRows(onlyScheduleId)
        val nextDateColumns = if (database.tableExists("schedules_next_date")) database.columnSet("schedules_next_date") else emptySet()
        val supportsNextDateWrites = listOf(
            "id", "schedule_id", "local_next_date", "local_next_date_ts", "base_next_date", "base_next_date_ts", "tombstone",
        ).all { it in nextDateColumns }
        val supportsScheduleRuleEdit = listOf("id", "rule", "completed", "posts_transaction", "tombstone").all { it in scheduleColumns }
        val supportsScheduleMetadataEdit = supportsScheduleRuleEdit && listOf("name", "custom_upcoming_length").all { it in scheduleColumns }
        val supportsScheduleCompletion = listOf("id", "rule", "completed", "tombstone").all { it in scheduleColumns }
        val supportsScheduleDeletion = listOf("id", "rule", "tombstone").all { it in scheduleColumns }
        val ruleColumns = if (database.tableExists("rules")) database.columnSet("rules") else emptySet()
        val supportsScheduleRuleDeletion = listOf("id", "tombstone").all { it in ruleColumns }
        // Authoring shares the creation column requirements exactly, so the entry points and the
        // write path agree.
        val supportsAuthoring = supportsScheduleRuleEdit && supportsNextDateWrites &&
            listOf("id", "conditions", "actions", "tombstone").all { it in ruleColumns }
        val accountFacts = accountFacts()
        val payeeFacts = payeeFacts()
        val payeeTargets = payeeTargets()

        val name = column("name", "NULL", scheduleColumns)
        val rule = column("rule", "NULL", scheduleColumns)
        val completed = column("completed", "0", scheduleColumns)
        val posts = column("posts_transaction", "0", scheduleColumns)
        val customUpcoming = column("custom_upcoming_length", "NULL", scheduleColumns)
        val sortOrder = column("sort_order", "NULL", scheduleColumns)
        val filter = if (onlyScheduleId == null) "" else "AND id = ?"
        val arguments: Array<Any?> = if (onlyScheduleId == null) emptyArray() else arrayOf<Any?>(onlyScheduleId)
        val rows = sql.query(
            """
            SELECT id, $name AS name, $rule AS rule,
                   $completed AS completed, $posts AS posts_transaction,
                   $customUpcoming AS custom_upcoming_length,
                   $sortOrder AS sort_order
            FROM schedules
            WHERE ${BudgetDatabase.livePredicate(scheduleColumns)}
            $filter
            """.trimIndent(),
            *arguments,
        )
        val rules = ruleRows(if (onlyScheduleId == null) null else rows.mapNotNull { it.string("rule") })

        val projectionsByScheduleId = HashMap<String, ScheduleRuleProjection>()
        val transactionLowerBounds = HashMap<String, String>()
        for (row in rows) {
            val id = row.string("id")
            if (id.isNullOrEmpty()) continue
            val ruleId = row.string("rule")
            val rawRule = ruleId?.let { rules[it] }
            val projection = ScheduleRuleProjection.read(id, rawRule?.conditions, rawRule?.actions)
            projectionsByScheduleId[id] = projection
            val candidates = nextDates[id] ?: emptyList()
            if (candidates.size != 1) continue
            val occurrenceDate = candidates[0].effectiveDate ?: continue
            transactionLowerBounds[id] = transactionLowerBound(
                occurrenceDate,
                projection.occurrenceMatchingMode,
                row.bool("posts_transaction"),
            )
        }
        val transactionDates = transactionDates(transactionLowerBounds)

        val details = mutableListOf<ScheduleDetail>()
        for (row in rows) {
            val id = row.string("id")
            if (id.isNullOrEmpty()) continue
            val ruleId = row.string("rule")
            val projection = projectionsByScheduleId[id] ?: continue
            val nextDateCandidates = nextDates[id] ?: emptyList()
            val selectedNextDate = if (nextDateCandidates.size == 1) nextDateCandidates[0] else null
            val effectiveNextDate = selectedNextDate?.effectiveDate
            val reasons = projection.unsupportedReasons.toMutableList()
            if (nextDateCandidates.isEmpty()) {
                reasons.add(ScheduleUnsupportedReason.MISSING_NEXT_DATE)
            } else if (nextDateCandidates.size > 1) {
                reasons.add(ScheduleUnsupportedReason.AMBIGUOUS_NEXT_DATE)
            } else if (effectiveNextDate == null) {
                reasons.add(ScheduleUnsupportedReason.MISSING_NEXT_DATE)
            }

            val account = accountReference(projection.accountId, accountFacts)
            val payee = payeeReference(projection.payeeMappingId, payeeTargets, payeeFacts)
            val isCompleted = row.bool("completed")
            val postsTransaction = row.bool("posts_transaction")
            val upcomingLength = row.string("custom_upcoming_length") ?: defaultUpcomingLength
            val hasPaidTransaction = effectiveNextDate?.let {
                hasMatchingTransaction(transactionDates[id] ?: emptyList(), it, projection.occurrenceMatchingMode, postsTransaction)
            } ?: false
            val status = if (effectiveNextDate != null) {
                ScheduleStatus.resolve(effectiveNextDate, isCompleted, hasPaidTransaction, today, upcomingLength)
            } else if (isCompleted) {
                ScheduleStatus.COMPLETED
            } else {
                ScheduleStatus.SCHEDULED
            }
            val hasUniqueNextDateRow = selectedNextDate != null
            val hasEffectiveNextDate = effectiveNextDate != null && hasUniqueNextDateRow
            val hasUniqueRuleOwner = if (ruleId != null) !ruleHasAnotherLiveOwner(ruleId, id) else false
            val hasBaseTimestamp = selectedNextDate?.baseTimestamp?.toLongOrNull() != null
            val capabilities = ScheduleMutationCapabilities(
                canRead = true,
                canEditMetadata = projection.capabilities.canEditMetadata && supportsScheduleMetadataEdit && hasUniqueRuleOwner,
                canEditAccount = projection.capabilities.canEditAccount && supportsScheduleRuleEdit && hasUniqueNextDateRow &&
                    supportsNextDateWrites && hasBaseTimestamp && hasUniqueRuleOwner,
                canEditPayee = projection.capabilities.canEditPayee && supportsScheduleRuleEdit && hasUniqueRuleOwner,
                canEditAmount = projection.capabilities.canEditAmount && supportsScheduleRuleEdit && hasUniqueRuleOwner,
                canEditDate = projection.capabilities.canEditDate && supportsScheduleRuleEdit && hasUniqueNextDateRow &&
                    supportsNextDateWrites && hasBaseTimestamp && hasUniqueRuleOwner,
                canSkip = projection.capabilities.canSkip && hasEffectiveNextDate && supportsNextDateWrites &&
                    hasBaseTimestamp && hasUniqueRuleOwner,
                canComplete = projection.capabilities.canComplete && supportsScheduleCompletion && !isCompleted && hasUniqueRuleOwner,
                canDelete = projection.capabilities.canDelete && supportsScheduleDeletion && supportsScheduleRuleDeletion &&
                    hasUniqueRuleOwner,
                canPost = projection.capabilities.canPost && hasEffectiveNextDate,
            )
            details.add(
                ScheduleDetail(
                    id = id,
                    ruleId = ruleId,
                    name = row.string("name"),
                    amount = projection.amount,
                    dateRule = projection.dateRule,
                    account = account,
                    payee = payee,
                    effectiveNextDate = effectiveNextDate,
                    status = status,
                    completed = isCompleted,
                    postsTransaction = postsTransaction,
                    customUpcomingLength = row.string("custom_upcoming_length"),
                    sortOrder = if ("sort_order" in scheduleColumns) row.double("sort_order") else null,
                    rawConditionsJson = projection.rawConditionsJson,
                    rawActionsJson = projection.rawActionsJson,
                    capabilities = capabilities,
                    unsupportedReasons = reasons.distinct().sortedBy { it.message },
                    occurrenceIdentity = ScheduleOccurrenceIdentity(
                        scheduleId = id,
                        nextDateRowId = selectedNextDate?.id,
                        effectiveNextDate = effectiveNextDate,
                        localNextDateTimestamp = selectedNextDate?.localTimestamp,
                        baseNextDateTimestamp = selectedNextDate?.baseTimestamp,
                    ),
                ),
            )
        }

        val ordered = details.sortedWith(
            Comparator { lhs, rhs ->
                if (lhs.completed != rhs.completed) {
                    if (!lhs.completed) -1 else 1
                } else {
                    val left = lhs.effectiveNextDate
                    val right = rhs.effectiveNextDate
                    when {
                        left != null && right != null && left != right -> left.compareTo(right)
                        left != null && right == null -> -1
                        left == null && right != null -> 1
                        else -> lhs.id.compareTo(rhs.id)
                    }
                }
            },
        )
        return LoadedSchedules(
            schedules = ordered.map { it.summary },
            detailsById = ordered.associateBy { it.id },
            defaultUpcomingLength = defaultUpcomingLength,
            supportsAuthoring = supportsAuthoring,
        )
    }

    /** `ruleIds == null` reads every live rule; otherwise only those rules. */
    private fun ruleRows(ruleIds: List<String>?): Map<String, RuleRow> {
        if (!database.tableExists("rules")) return emptyMap()
        val columns = database.columnSet("rules")
        if ("id" !in columns) return emptyMap()
        if (ruleIds != null && ruleIds.isEmpty()) return emptyMap()
        val idFilter = if (ruleIds == null) "" else "AND id IN (${ruleIds.joinToString(", ") { "?" }})"
        val conditions = column("conditions", "NULL", columns)
        val actions = column("actions", "NULL", columns)
        val arguments: Array<Any?> = if (ruleIds == null) emptyArray() else Array<Any?>(ruleIds.size) { ruleIds[it] }
        val rows = sql.query(
            "SELECT id, $conditions AS conditions, $actions AS actions FROM rules " +
                "WHERE ${BudgetDatabase.livePredicate(columns)} $idFilter",
            *arguments,
        )
        val result = HashMap<String, RuleRow>()
        for (row in rows) {
            val id = row.string("id") ?: continue
            result[id] = RuleRow(row.string("conditions"), row.string("actions"))
        }
        return result
    }

    fun ruleHasAnotherLiveOwner(ruleId: String, scheduleId: String): Boolean {
        val columns = requiredColumns("schedules", listOf("id", "rule"))
        return sql.queryOne(
            "SELECT id FROM schedules WHERE rule = ? AND id != ? AND ${BudgetDatabase.livePredicate(columns)} LIMIT 1",
            ruleId,
            scheduleId,
        ) != null
    }

    private fun nextDateRows(scheduleId: String?): Map<String, List<NextDateRow>> {
        if (!database.tableExists("schedules_next_date")) return emptyMap()
        val columns = database.columnSet("schedules_next_date")
        if ("schedule_id" !in columns) return emptyMap()
        val id = column("id", "NULL", columns)
        val localDate = column("local_next_date", "NULL", columns)
        val localTimestamp = column("local_next_date_ts", "NULL", columns)
        val baseDate = column("base_next_date", "NULL", columns)
        val baseTimestamp = column("base_next_date_ts", "NULL", columns)
        val filter = if (scheduleId == null) "" else "AND schedule_id = ?"
        val arguments: Array<Any?> = if (scheduleId == null) emptyArray() else arrayOf<Any?>(scheduleId)
        val rows = sql.query(
            """
            SELECT $id AS id, schedule_id,
                   $localDate AS local_next_date,
                   $localTimestamp AS local_next_date_ts,
                   $baseDate AS base_next_date,
                   $baseTimestamp AS base_next_date_ts
            FROM schedules_next_date
            WHERE ${BudgetDatabase.livePredicate(columns)}
            $filter
            """.trimIndent(),
            *arguments,
        )
        val result = LinkedHashMap<String, MutableList<NextDateRow>>()
        for (row in rows) {
            val owner = row.string("schedule_id") ?: continue
            result.getOrPut(owner) { mutableListOf() }.add(
                NextDateRow(
                    id = row.string("id"),
                    localDate = canonicalDay(row.string("local_next_date")),
                    localTimestamp = row.string("local_next_date_ts"),
                    baseDate = canonicalDay(row.string("base_next_date")),
                    baseTimestamp = row.string("base_next_date_ts"),
                ),
            )
        }
        return result
    }

    private fun accountFacts(): Map<String, AccountFact> {
        if (!database.tableExists("accounts")) return emptyMap()
        val columns = database.columnSet("accounts")
        if ("id" !in columns) return emptyMap()
        val name = column("name", "id", columns)
        val closed = column("closed", "0", columns)
        val rows = sql.query("SELECT id, $name AS name, $closed AS closed FROM accounts WHERE ${BudgetDatabase.livePredicate(columns)}")
        val result = HashMap<String, AccountFact>()
        for (row in rows) {
            val id = row.string("id") ?: continue
            result[id] = AccountFact(row.string("name") ?: id, row.bool("closed"))
        }
        return result
    }

    private fun payeeFacts(): Map<String, String> {
        if (!database.tableExists("payees")) return emptyMap()
        val columns = database.columnSet("payees")
        if ("id" !in columns) return emptyMap()
        val name = column("name", "id", columns)
        val rows = sql.query("SELECT id, $name AS name FROM payees WHERE ${BudgetDatabase.livePredicate(columns)}")
        val result = HashMap<String, String>()
        for (row in rows) {
            val id = row.string("id") ?: continue
            result[id] = row.string("name") ?: id
        }
        return result
    }

    private fun payeeTargets(): Map<String, String> {
        if (!database.tableExists("payee_mapping")) return emptyMap()
        val columns = database.columnSet("payee_mapping")
        if ("id" !in columns) return emptyMap()
        val target = when {
            "targetId" in columns -> "targetId"
            "target_id" in columns -> "target_id"
            else -> return emptyMap()
        }
        val result = HashMap<String, String>()
        for (row in sql.query("SELECT id, $target AS target_id FROM payee_mapping")) {
            val id = row.string("id") ?: continue
            val targetId = row.string("target_id") ?: continue
            result[id] = targetId
        }
        return result
    }

    private fun transactionDates(lowerBoundsByScheduleId: Map<String, String>): Map<String, List<String>> {
        if (lowerBoundsByScheduleId.isEmpty()) return emptyMap()
        if (!database.tableExists("transactions")) return emptyMap()
        val columns = database.columnSet("transactions")
        if ("schedule" !in columns || "date" !in columns) return emptyMap()
        val split = TransactionSplitSql(columns)
        val parentAlias = "schedule_parent"
        val result = HashMap<String, MutableList<String>>()
        val bounds = lowerBoundsByScheduleId.entries.sortedBy { it.key }
        for (start in bounds.indices step 400) {
            val chunk = bounds.subList(start, minOf(start + 400, bounds.size))
            val filters = chunk.joinToString(" OR ") { "(${split.schedule} = ? AND ${normalizedDateSql(split.date)} >= ?)" }
            val arguments = ArrayList<Any?>()
            for (entry in chunk) {
                arguments.add(entry.key)
                arguments.add(entry.value)
            }
            val rows = sql.query(
                """
                SELECT ${split.schedule} AS schedule, ${normalizedDateSql(split.date)} AS date
                FROM transactions t
                ${split.parentJoin(parentAlias)}
                WHERE ${split.schedule} IS NOT NULL
                  AND ${split.liveEffectivePredicate(parentAlias)}
                  AND ($filters)
                """.trimIndent(),
                *arguments.toTypedArray(),
            )
            for (row in rows) {
                val scheduleId = row.string("schedule") ?: continue
                val dayId = canonicalDay(row.string("date")) ?: continue
                result.getOrPut(scheduleId) { mutableListOf() }.add(dayId)
            }
        }
        return result
    }

    fun defaultUpcomingLength(): String = sql.locked { upcomingLengthPreference() }

    private fun upcomingLengthPreference(): String {
        if (!database.tableExists("preferences")) return "7"
        val columns = database.columnSet("preferences")
        if ("id" !in columns || "value" !in columns) return "7"
        return sql.queryString("SELECT value FROM preferences WHERE id = 'upcomingScheduledTransactionLength' LIMIT 1") ?: "7"
    }

    private fun accountReference(id: String?, facts: Map<String, AccountFact>): ScheduleAccountReference {
        val fact = if (id == null) null else facts[id]
        if (id == null || fact == null) return ScheduleAccountReference(id, null, ScheduleReferenceAvailability.MISSING)
        return ScheduleAccountReference(
            id,
            fact.name,
            if (fact.isClosed) ScheduleReferenceAvailability.CLOSED else ScheduleReferenceAvailability.AVAILABLE,
        )
    }

    private fun payeeReference(mappingId: String?, targets: Map<String, String>, facts: Map<String, String>): SchedulePayeeReference {
        if (mappingId == null) return SchedulePayeeReference(null, null, false)
        val targetId = targets[mappingId] ?: return SchedulePayeeReference(mappingId, null, true)
        return SchedulePayeeReference(targetId, facts[targetId], facts[targetId] == null)
    }

    private fun hasMatchingTransaction(
        dates: List<String>,
        occurrenceDate: String,
        matchingMode: ScheduleOccurrenceMatchingMode,
        postsTransaction: Boolean,
    ): Boolean {
        val lowerBound = transactionLowerBound(occurrenceDate, matchingMode, postsTransaction)
        // Pinned Actual's status query uses only this lower bound.
        return dates.any { it >= lowerBound }
    }

    /** Actual's exact-date / approximate lookback contract, shared by status reads and posting. */
    fun transactionLowerBound(
        occurrenceDate: String,
        matchingMode: ScheduleOccurrenceMatchingMode,
        postsTransaction: Boolean,
    ): String {
        if (matchingMode == ScheduleOccurrenceMatchingMode.EXACT || postsTransaction) return occurrenceDate
        val occurrence = ScheduleRecurrence.parseDay(occurrenceDate) ?: return occurrenceDate
        return try {
            ScheduleRecurrence.dayId(occurrence.minusDays(2))
        } catch (e: DateTimeException) {
            occurrenceDate
        }
    }

    private fun canonicalDay(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        val dayId = if (trimmed.length == 8 && trimmed.all { it.isDigit() }) {
            trimmed.substring(0, 4) + "-" + trimmed.substring(4, 6) + "-" + trimmed.substring(6, 8)
        } else {
            trimmed
        }
        return if (ScheduleRecurrence.parseDay(dayId) == null) null else dayId
    }

    // region Review capture

    private fun captureReview(scheduleId: String): ScheduleMutationReview {
        val scheduleColumns = requiredColumns("schedules", listOf("id", "rule"))
        val scheduleFields = listOf("name", "completed", "posts_transaction", "custom_upcoming_length", "sort_order", "tombstone", "active")
        val selection = scheduleFields.joinToString(", ") { field -> column(field, "NULL", scheduleColumns) + " AS " + Sql.quoted(field) }
        val row = sql.queryOne(
            "SELECT rule, $selection FROM schedules WHERE id = ? AND ${BudgetDatabase.livePredicate(scheduleColumns)} LIMIT 1",
            scheduleId,
        )
        val ruleId = row?.string("rule")
        if (row == null || ruleId.isNullOrEmpty()) throw ScheduleMutationCommandException.ReviewChanged()
        val schedule = ScheduleRowRevision(
            name = row.string("name"),
            completed = row.bool("completed"),
            postsTransaction = row.bool("posts_transaction"),
            customUpcomingLength = row.string("custom_upcoming_length"),
            sortOrder = row.double("sort_order"),
            tombstone = row.bool("tombstone"),
            active = row.bool("active"),
        )

        val ruleColumns = requiredColumns("rules", listOf("id", "tombstone"))
        val conditions = column("conditions", "NULL", ruleColumns)
        val actions = column("actions", "NULL", ruleColumns)
        val ruleSelection = listOf("stage", "conditions_op").joinToString(", ") { field ->
            column(field, "NULL", ruleColumns) + " AS " + Sql.quoted(field)
        }
        val ruleRow = sql.queryOne(
            "SELECT $conditions AS conditions, $actions AS actions, tombstone, $ruleSelection FROM rules " +
                "WHERE id = ? AND ${BudgetDatabase.livePredicate(ruleColumns)} LIMIT 1",
            ruleId,
        ) ?: throw ScheduleMutationCommandException.UnsupportedCapability("The schedule's linked rule is unavailable.")
        val rule = ScheduleRuleRevision(
            conditionsJson = ruleRow.string("conditions"),
            actionsJson = ruleRow.string("actions"),
            stage = ruleRow.string("stage"),
            conditionsOperation = ruleRow.string("conditions_op"),
            tombstone = ruleRow.bool("tombstone"),
        )

        val projection = ScheduleRuleProjection.read(scheduleId, rule.conditionsJson, rule.actionsJson)
        val nextDates = captureNextDates(scheduleId)
        val account = projection.accountId?.let { captureAccount(it) }
        return ScheduleMutationReview(scheduleId, ruleId, schedule, rule, nextDates, account)
    }

    private fun captureNextDates(scheduleId: String): List<ScheduleNextDateRevision> {
        if (!database.tableExists("schedules_next_date")) return emptyList()
        val columns = database.columnSet("schedules_next_date")
        if (!columns.containsAll(listOf("id", "schedule_id"))) return emptyList()
        val localDate = column("local_next_date", "NULL", columns)
        val localTimestamp = column("local_next_date_ts", "NULL", columns)
        val baseDate = column("base_next_date", "NULL", columns)
        val baseTimestamp = column("base_next_date_ts", "NULL", columns)
        val tombstone = column("tombstone", "0", columns)
        val rows = sql.query(
            """
            SELECT id, $localDate AS local_next_date,
                   $localTimestamp AS local_next_date_ts,
                   $baseDate AS base_next_date,
                   $baseTimestamp AS base_next_date_ts,
                   $tombstone AS tombstone
            FROM schedules_next_date
            WHERE schedule_id = ?
            ORDER BY id
            """.trimIndent(),
            scheduleId,
        )
        return rows.mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            ScheduleNextDateRevision(
                id = id,
                localDate = row.string("local_next_date"),
                localTimestamp = row.string("local_next_date_ts"),
                baseDate = row.string("base_next_date"),
                baseTimestamp = row.string("base_next_date_ts"),
                tombstone = row.bool("tombstone"),
            )
        }
    }

    private fun captureAccount(id: String): ScheduleAccountRevision? {
        if (!database.tableExists("accounts")) return null
        val columns = database.columnSet("accounts")
        if ("id" !in columns) return null
        val name = column("name", "NULL", columns)
        val offBudget = column("offbudget", "0", columns)
        val closed = column("closed", "0", columns)
        val tombstone = column("tombstone", "0", columns)
        val row = sql.queryOne(
            "SELECT $name AS name, $offBudget AS offbudget, $closed AS closed, $tombstone AS tombstone FROM accounts WHERE id = ? LIMIT 1",
            id,
        ) ?: return null
        return ScheduleAccountRevision(id, row.string("name"), row.bool("offbudget"), row.bool("closed"), row.bool("tombstone"))
    }

    // endregion
}
