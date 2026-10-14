package com.sporez.actualist.core.schedules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneId

/** JSON-shaped value compared between the pinned oracle and the production replay. */
sealed class OracleValue {
    object Null : OracleValue()
    data class Num(val value: Double) : OracleValue()
    data class Str(val value: String) : OracleValue()
    data class Arr(val values: List<OracleValue>) : OracleValue()
    data class Obj(val fields: Map<String, OracleValue>) : OracleValue()

    val canonical: String
        get() = when (this) {
            is Null -> "null"
            is Num -> if (Math.rint(value) == value && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()
            is Str -> "\"" + value + "\""
            is Arr -> values.joinToString(",", "[", "]") { it.canonical }
            is Obj -> fields.keys.sorted().joinToString(",", "{", "}") { "\"" + it + "\":" + fields.getValue(it).canonical }
        }

    /** Row identity when the value is a row object. */
    val rowId: String?
        get() = ((this as? Obj)?.fields?.get("id") as? Str)?.value

    companion object {
        fun string(value: String?): OracleValue = if (value == null) Null else Str(value)

        fun number(value: Long): OracleValue = Num(value.toDouble())

        /** Booleans read as 1 and 0, like `NSNumber.doubleValue` in the Swift replay. */
        fun fromJson(element: JsonElement?): OracleValue = when (element) {
            null, is JsonNull -> Null
            is JsonPrimitive -> {
                if (element.isString) {
                    Str(element.content)
                } else {
                    val flag = element.booleanOrNull
                    val number = element.doubleOrNull
                    when {
                        flag != null -> Num(if (flag) 1.0 else 0.0)
                        number != null -> Num(number)
                        else -> Str(element.content)
                    }
                }
            }
            is JsonArray -> Arr(element.map { fromJson(it) })
            is JsonObject -> Obj(element.entries.associate { entry -> entry.key to fromJson(entry.value) })
        }

        /** A raw SQLite value as the Swift replay's `OracleValue.databaseValue`. */
        fun fromDatabase(value: Any?): OracleValue = when (value) {
            null -> Null
            is Long -> Num(value.toDouble())
            is Double -> Num(value)
            is String -> Str(value)
            is ByteArray -> Str("<blob>")
            else -> Str(value.toString())
        }
    }
}

data class ReplayMismatch(val path: String, val expected: String, val actual: String) {
    override fun toString(): String = "$path: expected $expected, actual $actual"
}

/**
 * Object fields are compared by every expected key. Arrays are paired after sorting by row id or
 * canonical form, so order never decides a result. Numbers are compared exactly.
 */
object ReplayComparison {
    fun mismatches(expected: OracleValue, actual: OracleValue, path: String): List<ReplayMismatch> {
        if (expected is OracleValue.Obj && actual is OracleValue.Obj) {
            val result = mutableListOf<ReplayMismatch>()
            for (key in expected.fields.keys.sorted()) {
                val childPath = if (path.isEmpty()) key else "$path.$key"
                val expectedField = expected.fields.getValue(key)
                val actualField = actual.fields[key]
                if (actualField == null) {
                    result.add(ReplayMismatch(childPath, expectedField.canonical, "missing"))
                    continue
                }
                result.addAll(mismatches(expectedField, actualField, childPath))
            }
            return result
        }
        if (expected is OracleValue.Arr && actual is OracleValue.Arr) return arrayMismatches(expected.values, actual.values, path)
        if (expected == actual) return emptyList()
        return listOf(ReplayMismatch(path, expected.canonical, actual.canonical))
    }

    private fun arrayMismatches(expected: List<OracleValue>, actual: List<OracleValue>, path: String): List<ReplayMismatch> {
        if (expected.size != actual.size) {
            val pending = actual.map { it.canonical }.toMutableList()
            val missing = mutableListOf<String>()
            for (item in expected.map { it.canonical }) {
                val index = pending.indexOf(item)
                if (index >= 0) pending.removeAt(index) else missing.add(item)
            }
            return listOf(
                ReplayMismatch(
                    path,
                    "${expected.size} items",
                    "${actual.size} items; missing [${missing.joinToString(" ")}]; unexpected [${pending.joinToString(" ")}]",
                ),
            )
        }
        val expectedSorted = expected.sortedBy { sortKey(it) }
        val actualSorted = actual.sortedBy { sortKey(it) }
        val result = mutableListOf<ReplayMismatch>()
        for (index in expectedSorted.indices) {
            val label = expectedSorted[index].rowId ?: index.toString()
            result.addAll(mismatches(expectedSorted[index], actualSorted[index], "$path[$label]"))
        }
        return result
    }

    private fun sortKey(value: OracleValue): String = value.rowId ?: value.canonical

    fun report(caseId: String, mismatches: List<ReplayMismatch>): String = buildString {
        appendLine("$caseId: ${mismatches.size} mismatch(es)")
        mismatches.take(40).forEach { appendLine(it) }
        if (mismatches.size > 40) appendLine("... ${mismatches.size - 40} more")
    }
}

/** The Schedules oracle contract and manifest, and the fixture's explicit time zone and "today". */
object ScheduleOracleFixture {
    /** The fixture's `timeZone`, used for every instant the replays inject; never the machine zone. */
    val timeZone: ZoneId = ZoneId.of("America/New_York")
    const val FIXTURE_TODAY = "2026-10-11"

    private fun resource(name: String): JsonObject {
        val stream = ScheduleOracleFixture::class.java.getResourceAsStream("/oracle/Schedules/$name")
            ?: throw IllegalStateException("missing oracle fixture $name")
        return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    val contract: JsonObject by lazy { resource("schedules-contract.json") }
    val manifest: JsonObject by lazy { resource("schedules-manifest.json") }

    val cases: Map<String, JsonObject> by lazy {
        val map = LinkedHashMap<String, JsonObject>()
        for (entry in contract["cases"]!!.jsonArray) map[entry.jsonObject["id"]!!.jsonPrimitive.content] = entry.jsonObject
        map
    }

    fun entry(caseId: String): JsonObject = cases[caseId] ?: throw IllegalStateException("no fixture case $caseId")

    fun idsOfFamily(family: String): List<String> =
        cases.filter { it.value["family"]!!.jsonPrimitive.content == family }.keys.toList()

    /** Cases only the upstream oracle can run, from the manifest's `oracleOnly` list. */
    val oracleOnlyIds: Set<String> by lazy {
        manifest["oracleOnly"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
    }

    /**
     * Cases not replayed yet. Empty: every recurrence, status and stateful case is replayed, and the
     * only cases excluded are the manifest's oracle-only ones.
     */
    val notYetPorted: Set<String> = emptySet()

    val recurrenceIds: List<String> by lazy { idsOfFamily("recurrence").filter { it !in oracleOnlyIds && it !in notYetPorted } }
    val statusIds: List<String> by lazy { idsOfFamily("status").filter { it !in oracleOnlyIds && it !in notYetPorted } }
    val statefulIds: List<String> by lazy { idsOfFamily("stateful").filter { it !in oracleOnlyIds && it !in notYetPorted } }

    /** Local noon of [dayId] in the fixture zone, the `now` the replays inject. */
    fun noonMillis(dayId: String): Long {
        val date = LocalDate.parse(dayId)
        return date.atTime(12, 0).atZone(timeZone).toInstant().toEpochMilli()
    }

    fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
