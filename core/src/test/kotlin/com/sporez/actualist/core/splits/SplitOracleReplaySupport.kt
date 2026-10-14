package com.sporez.actualist.core.splits

import com.sporez.actualist.core.rules.RuleEvaluationContext
import com.sporez.actualist.core.rules.RuleJson
import com.sporez.actualist.core.rules.RuleMetadata
import com.sporez.actualist.core.rules.RuleSplitActionExecutor
import com.sporez.actualist.core.schedules.OracleValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate

/** The committed Splits oracle fixtures (Actual v26.10.0) and their manifest. */
object SplitFixtures {
    fun resource(name: String): JsonObject {
        val stream = SplitFixtures::class.java.getResourceAsStream("/oracle/Splits/$name")
            ?: throw IllegalStateException("missing oracle fixture $name")
        return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    fun bytes(name: String): ByteArray {
        val stream = SplitFixtures::class.java.getResourceAsStream("/oracle/Splits/$name")
            ?: throw IllegalStateException("missing oracle fixture $name")
        return stream.use { it.readBytes() }
    }

    val manifest: JsonObject by lazy { resource("manifest.json") }

    fun cases(fileName: String): Map<String, JsonObject> {
        val map = LinkedHashMap<String, JsonObject>()
        for (entry in resource(fileName)["cases"]!!.jsonArray) {
            val id = entry.jsonObject["id"]!!.jsonPrimitive.content
            check(map[id] == null) { "$fileName has a duplicate case id $id" }
            map[id] = entry.jsonObject
        }
        return map
    }

    /** The manifest entry for one fixture file. */
    fun manifestEntry(fileName: String): JsonObject =
        manifest["fixtures"]!!.jsonArray.map { it.jsonObject }
            .first { it["path"]!!.jsonPrimitive.content.endsWith("/$fileName") }

    fun manifestCaseIds(fileName: String): Set<String> =
        manifestEntry(fileName)["caseIDs"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    /** Oracle-only case ids with their reasons. */
    fun oracleOnly(fileName: String): Map<String, String> =
        (manifestEntry(fileName)["oracleOnly"] as? JsonArray ?: JsonArray(emptyList())).associate {
            it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["reason"]!!.jsonPrimitive.content
        }
}

/** Deterministic generated ids (`gen-1`, `gen-2`, ...); the comparison binds `<placeholder>` tokens to them. */
class SplitIdSequence {
    private var count = 0

    fun next(): String {
        count += 1
        return "gen-$count"
    }
}

/**
 * Replays one Splits oracle input through the production split family or rule-split executor,
 * then projects the outcome into the oracle's JSON shape. Rows use camelCase keys; diffs use the
 * snake_case keys the fixture records. Port of Actualist `SplitOracleReplay`.
 */
object SplitOracleReplay {
    fun replay(input: JsonObject): OracleValue {
        val operation = (input["operation"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return replayRule(input)
        val ids = SplitIdSequence()
        when (operation) {
            "makeChild" -> {
                val parent = record(obj(input["parent"], "parent"))
                val patches = objects(input["data"], "data").map { patch(it) }
                return OracleValue.Arr(patches.map { data -> row(SplitTransactionFamilyOps.makeChild(parent, data) { ids.next() }) })
            }
            "recalculateSplit" -> {
                val groups = LinkedHashMap<String, OracleValue>()
                for (key in listOf("exact", "mismatch", "mixedSign")) {
                    val transaction = record(obj(input[key], key))
                    val result = SplitTransactionFamilyOps.recalculateSplit(transaction)
                    groups[key] = OracleValue.Obj(
                        mapOf(
                            "parent" to row(result.selected()),
                            "children" to OracleValue.Arr(result.subtransactions.map { row(it) }),
                        ),
                    )
                }
                return OracleValue.Obj(groups)
            }
            "splitTransaction" -> {
                val id = string(input, "id")
                val children = records(input["children"], "children")
                val changes = SplitTransactionFamilyOps.splitTransaction(
                    flatten(input),
                    id,
                    createSubtransactions = { _: SplitTransactionRecord -> children },
                    idGenerator = { ids.next() },
                )
                return rowsAndFamily(changes.data, id)
            }
            "addSplitTransaction" -> {
                val id = string(input, "id")
                val changes = SplitTransactionFamilyOps.addSplitTransaction(flatten(input), id) { ids.next() }
                return rowsAndFamily(changes.data, id)
            }
            "updateTransaction" -> {
                val rows = flatten(input)
                val edited = editedObject(input)
                val transaction = record(edited)
                // JSON key presence decides whether a child inherits the parent's payee.
                val conversionChildren: List<SplitTransactionPatch>? =
                    if (edited["subtransactions"] == null) null else objects(edited["subtransactions"], "subtransactions").map { patch(it) }
                val changes = SplitTransactionFamilyOps.updateTransaction(rows, transaction, conversionChildren) { ids.next() }
                return changeSet(changes, effectiveParentId(rows, transaction.id))
            }
            "deleteTransaction" -> {
                val rows = flatten(input)
                val id = string(input, "id")
                val changes = SplitTransactionFamilyOps.deleteTransaction(rows, id)
                return changeSet(changes, effectiveParentId(rows, id))
            }
            // makeAsNonChildTransactions has no Actualist counterpart; it is oracle-only in the manifest.
            else -> throw IllegalStateException("no replay or fixture case named $operation")
        }
    }

    /**
     * Rule fixtures carry a transaction and actions but no `operation`. Conditions are not evaluated;
     * the fixture asserts only the action outcome for a matching transaction. The executor writes its
     * result into a [RuleEvaluationContext]; the parent row is rebuilt from that context and each
     * child from its recorded split, with account, date, cleared, reconciled and starting-balance
     * inherited from the parent as the family's `makeChild` does.
     */
    private fun replayRule(input: JsonObject): OracleValue {
        val transactionObject = obj(input["transaction"], "transaction")
        val transaction = record(transactionObject)
        val actionsJson = input["actions"] as? JsonArray ?: throw IllegalStateException("actions is not an array")
        val actions = RuleJson.decodeActions(actionsJson.toString()) ?: throw IllegalStateException("actions do not decode")
        val ids = SplitIdSequence()
        val metadata = RuleMetadata(
            accountNames = emptyMap(),
            offBudgetAccountIds = emptySet(),
            categoryNames = emptyMap(),
            categoryGroupsByCategoryId = emptyMap(),
            categoryGroupNames = emptyMap(),
            payeeNames = emptyMap(),
            transferPayeeIds = emptySet(),
        )
        val context = RuleEvaluationContext(
            metadata = metadata,
            accountId = transaction.account ?: "",
            accountName = "",
            accountIsOffBudget = false,
            amount = transaction.amount,
            categoryId = transaction.category,
            categoryName = null,
            categoryGroupId = null,
            categoryGroupName = null,
            date = LocalDate.parse(transaction.date ?: throw IllegalStateException("transaction has no date")),
            notes = transaction.notes,
            payeeId = transaction.payee,
            payeeName = "",
            importedPayee = str(transactionObject, "imported_payee"),
            cleared = transaction.cleared ?: false,
            reconciled = transaction.reconciled ?: false,
            isTransfer = false,
            isParent = transaction.isParent,
            sortOrder = transaction.sortOrder,
            evaluationId = transaction.id,
            isChild = transaction.isChild,
            parentId = transaction.parentId,
            startingBalance = transaction.startingBalance ?: false,
        )
        if (!RuleSplitActionExecutor.apply(actions, context) { ids.next() }) return OracleValue.Str("failedClosed")
        val parent = SplitTransactionRecord(
            id = context.evaluationId,
            amount = context.amount,
            account = context.accountId,
            date = context.date.toString(),
            category = context.categoryId,
            payee = context.payeeId,
            notes = context.notes,
            cleared = context.cleared,
            reconciled = context.reconciled,
            startingBalance = context.startingBalance,
            sortOrder = context.sortOrder,
            isParent = context.isParent,
            isChild = context.isChild,
            parentId = context.parentId,
            deleted = context.deletesTransaction,
        )
        val children = context.splits.map { split ->
            SplitTransactionFamilyOps.makeChild(
                parent,
                SplitTransactionPatch(
                    id = split.id,
                    amount = split.amount,
                    category = SplitOptionalField.Value(split.categoryId),
                    payee = SplitOptionalField.Value(split.payeeId),
                    notes = SplitOptionalField.Value(split.notes),
                    sortOrder = SplitOptionalField.Value(split.sortOrder),
                ),
            )
        }
        return OracleValue.Obj(mapOf("parent" to row(parent), "children" to OracleValue.Arr(children.map { row(it) })))
    }

    /**
     * Fixture rows are the flat transaction list Actual stores. A parent's embedded `subtransactions`
     * are not rows of their own, so a child counts only where it is listed at top level.
     */
    private fun flatten(input: JsonObject): List<SplitTransactionRecord> =
        records(input["transactions"], "transactions").map { it.selected() }

    /**
     * Actual merges an update payload over the stored row, so omitted keys keep their stored values.
     * Stored embedded children are dropped: the payload's own `subtransactions` decide.
     */
    private fun editedObject(input: JsonObject): JsonObject {
        val payload = obj(input["transaction"], "transaction")
        val id = string(payload, "id")
        val stored = objects(input["transactions"], "transactions").firstOrNull { str(it, "id") == id } ?: return payload
        val merged = LinkedHashMap<String, JsonElement>()
        for ((key, value) in stored) {
            if (key != "subtransactions") merged[key] = value
        }
        for ((key, value) in payload) merged[key] = value
        return JsonObject(merged)
    }

    private fun effectiveParentId(rows: List<SplitTransactionRecord>, id: String): String =
        rows.firstOrNull { it.id == id }?.effectiveParentId ?: id

    // region Input decoding

    private fun obj(value: JsonElement?, name: String): JsonObject =
        value as? JsonObject ?: throw IllegalStateException("$name is not an object")

    private fun objects(value: JsonElement?, name: String): List<JsonObject> {
        val array = value as? JsonArray ?: throw IllegalStateException("$name is not an array of objects")
        return array.map { it as? JsonObject ?: throw IllegalStateException("$name is not an array of objects") }
    }

    private fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun string(o: JsonObject, key: String): String = str(o, key) ?: throw IllegalStateException("$key is not a string")

    private fun long(o: JsonObject, key: String): Long? {
        val primitive = (o[key] as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
        return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
    }

    private fun double(o: JsonObject, key: String): Double? =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun boolean(o: JsonObject, key: String): Boolean? =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun records(value: JsonElement?, name: String): List<SplitTransactionRecord> = objects(value, name).map { record(it) }

    fun record(o: JsonObject): SplitTransactionRecord = SplitTransactionRecord(
        id = string(o, "id"),
        amount = long(o, "amount") ?: 0L,
        account = str(o, "account"),
        date = str(o, "date"),
        category = str(o, "category"),
        payee = str(o, "payee"),
        notes = str(o, "notes"),
        cleared = boolean(o, "cleared"),
        reconciled = boolean(o, "reconciled"),
        startingBalance = boolean(o, "starting_balance_flag"),
        sortOrder = double(o, "sort_order"),
        isParent = boolean(o, "is_parent") ?: false,
        isChild = boolean(o, "is_child") ?: false,
        parentId = str(o, "parent_id"),
        error = splitError(o["error"]),
        deleted = boolean(o, "deleted") ?: false,
        subtransactions = if (o["subtransactions"] == null) emptyList() else records(o["subtransactions"], "subtransactions"),
    )

    /** Omitted keys stay omitted (inherit from the parent); explicit nulls are `Value(null)`. */
    private fun patch(o: JsonObject): SplitTransactionPatch = SplitTransactionPatch(
        id = str(o, "id"),
        amount = long(o, "amount"),
        category = optionalText(o, "category"),
        payee = optionalText(o, "payee"),
        notes = optionalText(o, "notes"),
        sortOrder = optionalNumber(o, "sort_order"),
    )

    private fun optionalText(o: JsonObject, key: String): SplitOptionalField<String> =
        if (o[key] == null) SplitOptionalField.Omitted else SplitOptionalField.Value(str(o, key))

    private fun optionalNumber(o: JsonObject, key: String): SplitOptionalField<Double> =
        if (o[key] == null) SplitOptionalField.Omitted else SplitOptionalField.Value(double(o, key))

    private fun splitError(value: JsonElement?): SplitTransactionError? {
        val o = value as? JsonObject ?: return null
        return SplitTransactionError(
            type = string(o, "type"),
            version = (long(o, "version") ?: 1L).toInt(),
            difference = long(o, "difference") ?: 0L,
        )
    }

    // endregion

    // region Output projection

    private fun rowsAndFamily(rows: List<SplitTransactionRecord>, parentId: String): OracleValue {
        val result = SplitTransactionFamilyOps.family(rows, parentId)
        return OracleValue.Obj(
            mapOf(
                "rows" to OracleValue.Arr(result.rows.map { row(it) }),
                "family" to family(result.family),
            ),
        )
    }

    private fun changeSet(changes: SplitTransactionChangeSet, parentId: String): OracleValue =
        OracleValue.Obj(mapOf("result" to rowsAndFamily(changes.data, parentId), "diff" to diff(changes.diff)))

    private fun family(family: SplitTransactionFamily?): OracleValue {
        if (family == null) return OracleValue.Null
        return OracleValue.Obj(
            mapOf(
                "parent" to row(family.parent),
                "children" to OracleValue.Arr(family.children.map { row(it) }),
            ),
        )
    }

    /** Every row cell, camelCase. Used for output rows, the family, and the rule result. */
    fun row(record: SplitTransactionRecord): OracleValue = OracleValue.Obj(
        mapOf(
            "id" to OracleValue.Str(record.id),
            "amount" to OracleValue.Num(record.amount.toDouble()),
            "account" to cell(record.account),
            "date" to cell(record.date),
            "category" to cell(record.category),
            "payee" to cell(record.payee),
            "notes" to cell(record.notes),
            "cleared" to cell(record.cleared),
            "reconciled" to cell(record.reconciled),
            "startingBalance" to cell(record.startingBalance),
            "sortOrder" to cell(record.sortOrder),
            "isParent" to bit(record.isParent),
            "isChild" to bit(record.isChild),
            "parentID" to cell(record.parentId),
            "error" to errorValue(record.error),
            "deleted" to bit(record.deleted),
        ),
    )

    /** `diffItems` reports the whole record for additions, and only changed cells for updates. */
    private fun diff(diff: SplitTransactionDiff): OracleValue = OracleValue.Obj(
        mapOf(
            "added" to OracleValue.Arr(diff.added.map { addedRecord(it) }),
            "updated" to OracleValue.Arr(diff.updated.map { sparseUpdate(it) }),
            "deleted" to OracleValue.Arr(diff.deleted.map { OracleValue.Obj(mapOf("id" to OracleValue.Str(it))) }),
        ),
    )

    private fun addedRecord(record: SplitTransactionRecord): OracleValue = OracleValue.Obj(
        mapOf(
            "id" to OracleValue.Str(record.id),
            "amount" to OracleValue.Num(record.amount.toDouble()),
            "account" to cell(record.account),
            "date" to cell(record.date),
            "category" to cell(record.category),
            "payee" to cell(record.payee),
            "notes" to cell(record.notes),
            "cleared" to cell(record.cleared),
            "reconciled" to cell(record.reconciled),
            "starting_balance_flag" to cell(record.startingBalance),
            "sort_order" to cell(record.sortOrder),
            "is_parent" to bit(record.isParent),
            "is_child" to bit(record.isChild),
            "parent_id" to cell(record.parentId),
            "error" to errorValue(record.error),
            "deleted" to bit(record.deleted),
        ),
    )

    /** Changed cells only, as `getChangedValues` reports them: a cell changed to null, 0, or false is present. */
    private fun sparseUpdate(update: SplitTransactionUpdate): OracleValue {
        val record = update.record
        val fields = LinkedHashMap<String, OracleValue>()
        fields["id"] = OracleValue.Str(record.id)
        for (field in update.changedFields) {
            when (field) {
                SplitTransactionField.AMOUNT -> fields["amount"] = OracleValue.Num(record.amount.toDouble())
                SplitTransactionField.ACCOUNT -> fields["account"] = cell(record.account)
                SplitTransactionField.DATE -> fields["date"] = cell(record.date)
                SplitTransactionField.CATEGORY -> fields["category"] = cell(record.category)
                SplitTransactionField.PAYEE -> fields["payee"] = cell(record.payee)
                SplitTransactionField.NOTES -> fields["notes"] = cell(record.notes)
                SplitTransactionField.CLEARED -> fields["cleared"] = cell(record.cleared)
                SplitTransactionField.RECONCILED -> fields["reconciled"] = cell(record.reconciled)
                SplitTransactionField.STARTING_BALANCE -> fields["starting_balance_flag"] = cell(record.startingBalance)
                SplitTransactionField.SORT_ORDER -> fields["sort_order"] = cell(record.sortOrder)
                SplitTransactionField.IS_PARENT -> fields["is_parent"] = bit(record.isParent)
                SplitTransactionField.IS_CHILD -> fields["is_child"] = bit(record.isChild)
                SplitTransactionField.PARENT_ID -> fields["parent_id"] = cell(record.parentId)
                SplitTransactionField.TRANSFER_ID -> fields["transfer_id"] = cell(record.transferId)
                SplitTransactionField.ERROR -> fields["error"] = errorValue(record.error)
                SplitTransactionField.DELETED -> fields["deleted"] = bit(record.deleted)
            }
        }
        return OracleValue.Obj(fields)
    }

    // endregion

    // region Cells and normalization

    /**
     * Test-side normalization, applied to expected and actual alike before comparison. Within each
     * diff, arrays are sorted by id because Actual leaves diff order unspecified. In `added` records
     * only, null cells and false `is_parent`, `is_child`, and `deleted` flags count as absent, because
     * Actual's fixture omits undefined keys while production records always carry every cell. Amounts,
     * non-null cells, and `updated` entries are not altered.
     */
    fun normalized(value: OracleValue): OracleValue = when (value) {
        is OracleValue.Obj -> {
            val added = value.fields["added"]
            val updated = value.fields["updated"]
            val deleted = value.fields["deleted"]
            if (added is OracleValue.Arr && updated is OracleValue.Arr && deleted is OracleValue.Arr) {
                val result = LinkedHashMap<String, OracleValue>()
                for ((key, field) in value.fields) result[key] = normalized(field)
                result["added"] = sortedById(OracleValue.Arr(added.values.map { dropAbsentCells(it) }))
                result["updated"] = sortedById(updated)
                result["deleted"] = sortedById(deleted)
                OracleValue.Obj(result)
            } else {
                val result = LinkedHashMap<String, OracleValue>()
                for ((key, field) in value.fields) result[key] = normalized(field)
                OracleValue.Obj(result)
            }
        }
        is OracleValue.Arr -> OracleValue.Arr(value.values.map { normalized(it) })
        else -> value
    }

    private fun dropAbsentCells(value: OracleValue): OracleValue {
        if (value !is OracleValue.Obj) return value
        val kept = LinkedHashMap<String, OracleValue>()
        for ((key, field) in value.fields) {
            if (field == OracleValue.Null) continue
            if (key in listOf("is_parent", "is_child", "deleted") && field == OracleValue.Num(0.0)) continue
            kept[key] = field
        }
        return OracleValue.Obj(kept)
    }

    private fun sortedById(value: OracleValue): OracleValue =
        if (value is OracleValue.Arr) OracleValue.Arr(value.values.sortedBy { idText(it) }) else value

    private fun idText(value: OracleValue): String = value.rowId ?: ""

    private fun errorValue(error: SplitTransactionError?): OracleValue {
        if (error == null) return OracleValue.Null
        return OracleValue.Obj(
            mapOf(
                "type" to OracleValue.Str(error.type),
                "version" to OracleValue.Num(error.version.toDouble()),
                "difference" to OracleValue.Num(error.difference.toDouble()),
            ),
        )
    }

    private fun cell(value: String?): OracleValue = if (value == null) OracleValue.Null else OracleValue.Str(value)

    private fun cell(value: Boolean?): OracleValue = if (value == null) OracleValue.Null else bit(value)

    private fun cell(value: Double?): OracleValue = if (value == null) OracleValue.Null else OracleValue.Num(value)

    /** Booleans compare as 1 and 0, matching how the oracle JSON reads `true` and `false`. */
    private fun bit(value: Boolean): OracleValue = OracleValue.Num(if (value) 1.0 else 0.0)

    // endregion
}

/**
 * Two-way comparison: every key must be present on both sides, and arrays compare by position.
 * A `<placeholder>` in the expected value binds one-to-one to the first actual value it meets, so
 * generated ids need not match the oracle's numbering. Port of Swift `OracleComparison`.
 */
class SplitComparison {
    private val found = mutableListOf<String>()
    private val bindings = HashMap<String, String>()
    private val claims = HashMap<String, String>()

    val mismatches: List<String> get() = found

    fun compare(expected: OracleValue, actual: OracleValue, path: String = "") {
        if (expected is OracleValue.Str && isPlaceholder(expected.value)) {
            bind(expected.value, actual, path)
            return
        }
        if (expected is OracleValue.Obj && actual is OracleValue.Obj) {
            val keys = (expected.fields.keys + actual.fields.keys).toSortedSet()
            for (key in keys) {
                val childPath = if (path.isEmpty()) key else "$path.$key"
                val expectedField = expected.fields[key]
                val actualField = actual.fields[key]
                if (expectedField != null && actualField != null) {
                    compare(expectedField, actualField, childPath)
                } else if (expectedField != null) {
                    record(childPath, expectedField.canonical, "missing")
                } else if (actualField != null) {
                    record(childPath, "missing", actualField.canonical)
                }
            }
            return
        }
        if (expected is OracleValue.Arr && actual is OracleValue.Arr) {
            if (expected.values.size != actual.values.size) {
                record(path, "${expected.values.size} items", "${actual.values.size} items")
                return
            }
            for (index in expected.values.indices) {
                compare(expected.values[index], actual.values[index], "$path[$index]")
            }
            return
        }
        if (expected != actual) record(path, expected.canonical, actual.canonical)
    }

    private fun isPlaceholder(token: String): Boolean = token.startsWith("<") && token.endsWith(">")

    private fun bind(token: String, actual: OracleValue, path: String) {
        val value = actual.canonical
        val bound = bindings[token]
        if (bound != null) {
            if (bound != value) record(path, "$token = $bound", value)
            return
        }
        val owner = claims[value]
        if (owner != null && owner != token) {
            record(path, token, "$value, already bound to $owner")
            return
        }
        bindings[token] = value
        claims[value] = token
    }

    private fun record(path: String, expected: String, actual: String) {
        found.add("$path: expected $expected, actual $actual")
    }

    companion object {
        fun report(caseId: String, mismatches: List<String>): String =
            mismatches.joinToString("\n") { "$caseId $it" }
    }
}
