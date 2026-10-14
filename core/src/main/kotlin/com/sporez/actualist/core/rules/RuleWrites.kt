package com.sporez.actualist.core.rules

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Rule create, update and delete. Port of Actualist `createRuleMessages`, `updateRuleMessages`
 * and `deleteRuleMessages`: conditions and actions are stored as sorted-key JSON, `stage` is
 * null for the default stage, and a rule the engine cannot safely evaluate is refused.
 */
class RuleWrites(private val database: BudgetDatabase, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    fun create(draft: RuleDraft): Pair<String, List<SyncMessage>> {
        val id = newId()
        val messages = database.commitLocal { createDrafts(id, draft) to Unit }.messages
        return id to messages
    }

    fun update(ruleId: String, draft: RuleDraft): List<SyncMessage> = database.commitLocal {
        if (!database.liveRowExists("rules", ruleId)) throw LocalFirstException.InvalidLocalWrite("rule no longer exists")
        cells(ruleId, draft) to Unit
    }.messages

    fun delete(ruleId: String): List<SyncMessage> = database.commitLocal {
        if (!database.rowExists("rules", ruleId)) throw LocalFirstException.InvalidLocalWrite("rule no longer exists")
        listOf(CellDraft("rules", ruleId, "tombstone", SyncValue.bool(true))) to Unit
    }.messages

    fun createDrafts(ruleId: String, draft: RuleDraft): List<CellDraft> =
        cells(ruleId, draft) + CellDraft("rules", ruleId, "tombstone", SyncValue.bool(false))

    private fun cells(ruleId: String, draft: RuleDraft): List<CellDraft> {
        database.requiredColumns("rules", listOf("conditions", "actions"))
        if (!draft.canRoundTripAndEvaluate) {
            throw LocalFirstException.InvalidLocalWrite("this rule contains fields Actualist cannot safely evaluate")
        }
        val columns = database.columnSet("rules")
        val drafts = mutableListOf(
            CellDraft("rules", ruleId, "conditions", SyncValue.text(encodeConditions(draft.conditions))),
            CellDraft("rules", ruleId, "actions", SyncValue.text(encodeActions(draft.actions))),
        )
        if ("stage" in columns) drafts += CellDraft("rules", ruleId, "stage", if (draft.stage == RuleStage.NORMAL) SyncValue.Null else SyncValue.text(draft.stage.raw))
        if ("conditions_op" in columns) drafts += CellDraft("rules", ruleId, "conditions_op", SyncValue.text(draft.join.raw))
        return drafts
    }

    companion object {
        fun encodeConditions(conditions: List<RuleCondition>): String = JsonArray(
            conditions.map { condition ->
                sortedObject(
                    listOfNotNull(
                        "field" to JsonPrimitive(condition.fieldName),
                        "op" to JsonPrimitive(condition.op),
                        condition.options?.let { "options" to sortedObject(it.map { (k, v) -> k to encode(v) }) },
                        condition.type?.let { "type" to JsonPrimitive(it) },
                        "value" to encode(condition.value),
                    ),
                )
            },
        ).toString()

        fun encodeActions(actions: List<RuleAction>): String = JsonArray(
            actions.map { action ->
                sortedObject(
                    listOfNotNull(
                        action.fieldName?.let { "field" to JsonPrimitive(it) },
                        "op" to JsonPrimitive(action.op),
                        action.options?.let { "options" to sortedObject(it.map { (k, v) -> k to encode(v) }) },
                        action.type?.let { "type" to JsonPrimitive(it) },
                        "value" to encode(action.value),
                    ),
                )
            },
        ).toString()

        private fun sortedObject(entries: List<Pair<String, JsonElement>>): JsonObject =
            JsonObject(LinkedHashMap<String, JsonElement>().apply { entries.sortedBy { it.first }.forEach { (k, v) -> put(k, v) } })

        /** Whole numbers are written without a fraction, as Swift's `JSONEncoder` does. */
        fun encode(value: RuleValue): JsonElement = when (value) {
            RuleValue.Null -> JsonNull
            is RuleValue.Bool -> JsonPrimitive(value.value)
            is RuleValue.Num -> if (value.value == Math.rint(value.value) && kotlin.math.abs(value.value) < 9.0e15) {
                JsonPrimitive(value.value.toLong())
            } else {
                JsonPrimitive(value.value)
            }
            is RuleValue.Text -> JsonPrimitive(value.value)
            is RuleValue.Arr -> JsonArray(value.values.map { encode(it) })
            is RuleValue.Obj -> sortedObject(value.values.map { (k, v) -> k to encode(v) })
        }
    }
}
