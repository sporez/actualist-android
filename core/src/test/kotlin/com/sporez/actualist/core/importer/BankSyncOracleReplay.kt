package com.sporez.actualist.core.importer

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Sql
import com.sporez.actualist.core.importer.BankSyncReconciliation.Candidate
import com.sporez.actualist.core.schedules.OracleValue
import com.sporez.actualist.core.schedules.ReplayMismatch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate

/**
 * Replays BankSync oracle cases through the production import reconcile that CSV import and Bank Sync share:
 * rule projection, the matcher, the cell writes and the Bank Sync opening balance, committed to a seeded
 * starter database. Port of Actualist `BankSyncOracleReplay`.
 *
 * The provider-side mapping that produced a bank or simplefin case's candidates (`prepareDownload`) is not
 * reproduced: those cases replay the recorded post-mapping transactions. CSV cases build candidates through
 * [ImportCandidates] and compare them with the recorded ones.
 */
object BankSyncOracleReplay {
    val today: LocalDate = LocalDate.parse(BankSyncOracleFixture.FIXTURE_TODAY)

    /** Sort order base the replay gives inserted rows, so file order survives (`sortBase - index`). */
    private const val SORT_BASE = 1_000_000.0

    private val seedTables = listOf(
        "accounts", "category_groups", "categories", "category_mapping", "payees", "payee_mapping",
        "rules", "schedules", "schedules_next_date", "transactions",
    )

    class Comparison(
        val expected: OracleValue,
        val actual: OracleValue,
        /** Candidates production built (CSV) that differ from the post-mapping transaction the oracle recorded. */
        val candidateMismatches: List<ReplayMismatch>,
    ) {
        val mismatches: List<ReplayMismatch>
            get() = candidateMismatches + BankSyncComparison.mismatches(expected, actual)
    }

    fun replay(entry: JsonObject): Comparison {
        OracleJson.string(entry["error"])?.let { throw IllegalStateException("replayed case recorded an error: $it") }
        val call = entry["call"]!!.jsonObject
        val input = entry["input"]!!.jsonObject
        val kind = OracleJson.string(call["kind"])!!
        val accountId = OracleJson.string(call["accountId"])!!
        val options = call["options"]!!.jsonObject

        return TestDatabases.starter(nodeId = "node1", clock = TestClock()).use { database ->
            val tables = LinkedHashMap<String, JsonElement>()
            tables.putAll(BankSyncOracleFixture.worldTables)
            tables.putAll(input)
            seed(database, tables, entry["setup"]!!.jsonObject["prefs"]!!.jsonObject)

            var counter = 0
            val reconciler = ImportReconciler(database, TestClock()) { "uuid-${++counter}" }
            val reconcileOptions = reconcileOptions(kind, options)
            val recorded = OracleJson.objects(entry["candidates"])
            val afterRecord = entry["after"]!!.jsonObject
            val newPayeeNames = newPayeeNames(afterRecord)

            var candidateMismatches: List<ReplayMismatch> = emptyList()
            val candidates: List<Candidate>
            if (kind == "csv") {
                val rows = csvRows(database, OracleJson.objects(call["transactions"]))
                candidates = reconciler.candidates(rows, reconcileOptions)
                candidateMismatches = candidateMismatches(candidates, recorded)
            } else {
                candidates = recordedCandidates(recorded, newPayeeNames)
            }

            val isPreview = OracleJson.bool(options["isPreview"]) == true
            val plan: ImportPlan = if (isPreview) {
                reconciler.previewCandidates(accountId, candidates, reconcileOptions, today)
            } else {
                // Bank Sync's first-sync step: the account's first sync writes a Starting Balance row.
                val opening = if (kind == "simplefin") {
                    OpeningBalanceRequest(OracleJson.long(options["startingBalance"]) ?: 0L, SORT_BASE + 1)
                } else {
                    null
                }
                reconciler.applyCandidates(accountId, candidates, reconcileOptions, today, SORT_BASE, opening).plan
            }

            val actual = actualValue(database, plan, input)
            val expected = expectedValue(entry, input)
            Comparison(expected, actual, candidateMismatches)
        }
    }

    // region Options

    /**
     * The production defaults for the kind, with the call's upstream options laid over them. A `simplefin`
     * call reads the seeded `sync-update-dates-<account>` preference, as production does; a `bank` or `csv`
     * call passes the option (production's file import never sets it).
     */
    private fun reconcileOptions(kind: String, options: JsonObject): ImportReconcileOptions {
        var result = if (kind == "csv") ImportReconcileOptions.CSV else ImportReconcileOptions.BANK_SYNC
        // A direct `bank` call is a provider whose accounts use upstream's default strict id checking. Actualist
        // has only SimpleFIN, whose accounts turn it off, so `simplefin` keeps the Bank Sync defaults.
        if (kind == "bank") result = result.copy(strictIdChecking = true, updateDates = false)
        OracleJson.bool(options["updateDates"])?.let { result = result.copy(updateDates = it) }
        OracleJson.bool(options["strictIdChecking"])?.let { result = result.copy(strictIdChecking = it) }
        OracleJson.bool(options["reimportDeleted"])?.let { result = result.copy(reimportDeleted = it) }
        OracleJson.bool(options["defaultCleared"])?.let { result = result.copy(defaultCleared = it) }
        when (OracleJson.string(options["payeeNameNormalization"])) {
            "original" -> result = result.copy(payeeNameNormalization = ImportPayeeNameNormalization.ORIGINAL)
            "title-case" -> result = result.copy(payeeNameNormalization = ImportPayeeNameNormalization.TITLE_CASE)
            else -> Unit
        }
        return result
    }

    // endregion

    // region Candidates

    /**
     * A `transactions-import` row as a CSV row. Upstream names a category by id; the file names it by its name,
     * so a known id is given as that category's name and an unknown one stays as typed.
     */
    private fun csvRows(database: BudgetDatabase, transactions: List<JsonObject>): List<ImportRow> {
        val namesById = HashMap<String, String>()
        val columns = database.columnSet("categories")
        for (row in database.sql.query("SELECT id, name FROM categories WHERE ${BudgetDatabase.livePredicate(columns)}")) {
            val id = row.string("id") ?: continue
            namesById[id] = row.string("name") ?: ""
        }
        return transactions.mapIndexed { index, transaction ->
            val day = OracleJson.string(transaction["date"]) ?: throw IllegalStateException("bad date")
            val category = OracleJson.string(transaction["category"])
            val payee = OracleJson.string(transaction["payee_name"])
            ImportRow(
                date = LocalDate.parse(day),
                amount = OracleJson.long(transaction["amount"]) ?: 0L,
                payeeName = payee ?: "",
                payeeCellWasEmpty = payee == "",
                notes = OracleJson.string(transaction["notes"]),
                categoryName = category?.let { namesById[it] ?: it },
                cleared = OracleJson.bool(transaction["cleared"]),
                importedId = OracleJson.string(transaction["imported_id"]),
                sourceLine = index + 1,
            )
        }
    }

    /**
     * A recorded post-mapping transaction (the input of upstream's rules step) as a candidate. The
     * provider-side mapping that produced it (`prepareDownload`) is not reproduced.
     */
    private fun recordedCandidates(recorded: List<JsonObject>, newPayeeNames: Map<String, String>): List<Candidate> =
        recorded.map { transaction ->
            val day = OracleJson.string(transaction["date"])!!
            val payee = OracleJson.string(transaction["payee"])
            val isNewPayee = payee?.startsWith("uuid-") == true
            val importedPayee = OracleJson.string(transaction["imported_payee"])
            Candidate(
                financialId = OracleJson.string(transaction["imported_id"]),
                dayId = day.replace("-", ""),
                amount = OracleJson.long(transaction["amount"]) ?: 0L,
                payeeId = if (isNewPayee) null else payee,
                payeeName = if (isNewPayee) (payee?.let { newPayeeNames[it] } ?: importedPayee) else null,
                notes = OracleJson.string(transaction["notes"]),
                categoryId = OracleJson.string(transaction["category"]),
                cleared = OracleJson.bool(transaction["cleared"]) ?: false,
                importedPayee = importedPayee,
            )
        }

    /** What production's CSV mapping built, against what upstream's normalization handed its rules. */
    private fun candidateMismatches(candidates: List<Candidate>, recorded: List<JsonObject>): List<ReplayMismatch> {
        if (candidates.size != recorded.size) {
            return listOf(ReplayMismatch("candidates", "${recorded.size} items", "${candidates.size} items"))
        }
        val result = ArrayList<ReplayMismatch>()
        for (index in candidates.indices) {
            val candidate = candidates[index]
            val transaction = recorded[index]

            fun check(field: String, expected: OracleValue, actual: OracleValue) {
                if (expected != actual) {
                    result.add(ReplayMismatch("candidate[$index].$field", expected.canonical, actual.canonical))
                }
            }

            fun text(element: JsonElement?): OracleValue = OracleValue.string(OracleJson.string(element))
            val payee = OracleJson.string(transaction["payee"])
            check(
                "payee",
                if (payee?.startsWith("uuid-") == true) OracleValue.Null else OracleValue.string(payee),
                OracleValue.string(candidate.payeeId),
            )
            check("date", text(transaction["date"]), OracleValue.Str(ImportDays.iso(candidate.dayId)))
            check("amount", OracleValue.Num((OracleJson.long(transaction["amount"]) ?: 0L).toDouble()), OracleValue.Num(candidate.amount.toDouble()))
            check("imported_payee", text(transaction["imported_payee"]), OracleValue.string(candidate.importedPayee))
            check("notes", text(transaction["notes"]), OracleValue.string(candidate.notes))
            check("category", text(transaction["category"]), OracleValue.string(candidate.categoryId))
            check("imported_id", text(transaction["imported_id"]), OracleValue.string(candidate.financialId))
            val cleared = OracleJson.bool(transaction["cleared"])
            if (cleared != null) {
                check(
                    "cleared",
                    OracleValue.Num(if (cleared) 1.0 else 0.0),
                    OracleValue.Num(if (candidate.clearedIsExplicit && candidate.cleared) 1.0 else 0.0),
                )
            } else {
                check("cleared (explicit)", OracleValue.Num(0.0), OracleValue.Num(if (candidate.clearedIsExplicit) 1.0 else 0.0))
            }
        }
        return result
    }

    /** Minted payee id (`uuid-N`) to its name, for the new payees the oracle created. */
    private fun newPayeeNames(after: JsonObject): Map<String, String> {
        val names = HashMap<String, String>()
        for (payee in OracleJson.objects(after["payees"])) {
            val id = OracleJson.string(payee["id"])
            val name = OracleJson.string(payee["name"])
            if (id != null && id.startsWith("uuid-") && name != null) names[id] = name
        }
        return names
    }

    // endregion

    // region Values

    private fun expectedValue(entry: JsonObject, input: JsonObject): OracleValue {
        val result = expectedResult(entry)
        val after = entry["after"]!!.jsonObject
        val state = BankSyncOracleState.canonical(
            transactions = OracleJson.rows(after["transactions"]),
            payees = OracleJson.rows(after["payees"]),
            rules = OracleJson.rows(after["rules"]),
            baselineTransactionIds = OracleJson.ids(input["transactions"]),
            baselinePayeeIds = OracleJson.ids(BankSyncOracleFixture.worldTables["payees"]),
        )
        return OracleValue.Obj(state.fields + ("result" to result))
    }

    /**
     * Classification of the oracle's result: the rows it updated, the matches it left alone, and how many rows
     * it added (a first-sync Starting Balance row is not an imported row).
     */
    private fun expectedResult(entry: JsonObject): OracleValue {
        var raw: JsonElement? = entry["result"]
        if (raw is JsonArray && raw.isNotEmpty()) raw = raw.first().jsonObject["res"]
        val result = (raw as? JsonObject) ?: throw IllegalStateException("result is not an object")
        val after = entry["after"]!!.jsonObject
        val added = (result["added"] as? JsonArray)?.mapNotNull { OracleJson.string(it) } ?: emptyList()
        val startingRows = OracleJson.objects(after["transactions"])
            .filter { OracleJson.long(it["starting_balance_flag"]) == 1L }
            .mapNotNull { OracleJson.string(it["id"]) }
            .toSet()
        // The first-sync Starting Balance row can itself be matched by a later download.
        val updated = ((result["updated"] as? JsonArray)?.mapNotNull { OracleJson.string(it) } ?: emptyList())
            .filter { it !in startingRows }
            .sorted()
        val ignored = OracleJson.objects(result["updatedPreview"]).count { OracleJson.bool(it["ignored"]) == true }
        return OracleValue.Obj(
            mapOf(
                "updated" to OracleValue.Arr(updated.map { OracleValue.Str(it) }),
                "ignored" to OracleValue.Num(ignored.toDouble()),
                "skippedDeleted" to OracleValue.Num(0.0),
                "addedCount" to OracleValue.Num(added.count { it !in startingRows }.toDouble()),
            ),
        )
    }

    private fun actualValue(database: BudgetDatabase, plan: ImportPlan, input: JsonObject): OracleValue {
        val updated = ArrayList<String>()
        var ignored = 0
        var skipped = 0
        for (entry in plan.reconciliation.entries) {
            when (entry) {
                is BankSyncReconciliation.Entry.Update -> {
                    updated.add(entry.update.existingId)
                    updated.addAll(entry.update.childIds)
                }
                is BankSyncReconciliation.Entry.Unchanged -> ignored++
                is BankSyncReconciliation.Entry.SkippedDeleted -> skipped++
                is BankSyncReconciliation.Entry.Insert -> Unit
            }
        }
        val addedCount = plan.reconciliation.inserts.sumOf { 1 + it.splits.size }
        val result = OracleValue.Obj(
            mapOf(
                "updated" to OracleValue.Arr(updated.sorted().map { OracleValue.Str(it) }),
                "ignored" to OracleValue.Num(ignored.toDouble()),
                "skippedDeleted" to OracleValue.Num(skipped.toDouble()),
                "addedCount" to OracleValue.Num(addedCount.toDouble()),
            ),
        )
        val state = readState(database, input)
        return OracleValue.Obj(state.fields + ("result" to result))
    }

    private val transactionAliases = listOf(
        "id", "is_parent", "is_child", "parent_id", "account", "category", "amount", "payee", "notes", "date",
        "imported_id", "error", "imported_payee", "starting_balance_flag", "transfer_id", "cleared", "reconciled",
        "tombstone", "schedule",
    )

    private fun readState(database: BudgetDatabase, input: JsonObject): OracleValue.Obj {
        fun table(sql: String, aliases: List<String>): List<Map<String, OracleValue>> =
            database.sql.query(sql).map { row ->
                val fields = LinkedHashMap<String, OracleValue>()
                for (alias in aliases) fields[alias] = OracleValue.fromDatabase(row[alias])
                fields
            }

        val transactions = table(
            """
            SELECT id, COALESCE(isParent, 0) AS is_parent, COALESCE(isChild, 0) AS is_child,
                   CASE WHEN COALESCE(isChild, 0) = 0 THEN NULL ELSE parent_id END AS parent_id,
                   acct AS account, category, amount, description AS payee, notes, date,
                   financial_id AS imported_id, error, imported_description AS imported_payee,
                   COALESCE(starting_balance_flag, 0) AS starting_balance_flag,
                   transferred_id AS transfer_id, cleared, COALESCE(reconciled, 0) AS reconciled,
                   COALESCE(tombstone, 0) AS tombstone, schedule
            FROM transactions
            """.trimIndent(),
            transactionAliases,
        ).map { fields ->
            val day = fields["date"]
            if (day is OracleValue.Num) {
                val text = day.value.toLong().toString()
                fields + ("date" to OracleValue.Str("${text.take(4)}-${text.drop(4).take(2)}-${text.takeLast(2)}"))
            } else {
                fields
            }
        }
        val payees = table(
            "SELECT id, name, transfer_acct, COALESCE(tombstone, 0) AS tombstone FROM payees",
            listOf("id", "name", "transfer_acct", "tombstone"),
        )
        val rules = table("SELECT id, COALESCE(tombstone, 0) AS tombstone FROM rules", listOf("id", "tombstone"))
        return BankSyncOracleState.canonical(
            transactions = transactions,
            payees = payees,
            rules = rules,
            baselineTransactionIds = OracleJson.ids(input["transactions"]),
            baselinePayeeIds = OracleJson.ids(BankSyncOracleFixture.worldTables["payees"]),
        )
    }

    // endregion

    // region Seeding

    /** Clears the starter rows of the seeded tables, then inserts the upstream rows as dumped. */
    private fun seed(database: BudgetDatabase, tables: Map<String, JsonElement>, prefs: JsonObject) {
        database.sql.transaction {
            for (table in seedTables.reversed()) database.sql.execute("DELETE FROM $table")
            for ((id, value) in prefs) {
                database.sql.execute(
                    "INSERT OR REPLACE INTO preferences (id, value) VALUES (?, ?)",
                    id,
                    OracleJson.string(value) ?: throw IllegalStateException("preference $id is not a string"),
                )
            }
            for (table in seedTables) {
                for ((index, row) in OracleJson.objects(tables[table]).withIndex()) {
                    val values = java.util.TreeMap<String, Any?>()
                    for ((column, element) in row) values[column] = sqlValue(element, "$table.$column")
                    // The dump leaves out sort_order; keep the dumped order.
                    if (table == "transactions") values["sort_order"] = (1000 - index).toDouble()
                    val columns = values.keys.toList()
                    database.sql.execute(
                        "INSERT INTO $table (${columns.joinToString(", ") { Sql.quoted(it) }}) VALUES (${columns.joinToString(", ") { "?" }})",
                        *columns.map { values[it] }.toTypedArray(),
                    )
                }
            }
        }
    }

    private fun sqlValue(element: JsonElement, column: String): Any? {
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw IllegalStateException("unsupported value in $column")
        if (primitive.isString) return primitive.content
        primitive.booleanOrNull?.let { return if (it) 1L else 0L }
        val text = primitive.content
        return if (text.contains('.') || text.contains('e') || text.contains('E')) text.toDouble() else text.toLong()
    }

    // endregion
}
