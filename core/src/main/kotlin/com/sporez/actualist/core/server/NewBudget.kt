package com.sporez.actualist.core.server

import androidx.sqlite.SQLiteDriver
import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Sql
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A brand-new Actual budget file, as Actualist's `makeNewBudgetStarterDatabase` builds it: the
 * v26.10.0 starter schema, every migration Actual has applied by then (so Actual does not run
 * them again), the default dashboard, and the starter category groups. Rows are written directly,
 * as Actual's `createBudget` does; there are no CRDT messages until the first edit.
 */
object NewBudget {
    private val starterGroups = listOf(
        Triple("Usual Expenses", false, listOf("Food", "General", "Bills", "Bills (Flexible)")),
        Triple("Investments and Savings", false, listOf("Savings")),
        Triple("Income", true, listOf("Income", "Starting Balances")),
    )

    private const val INCREMENT = 16_384.0

    /** Creates the file at [path] and returns it closed; open it with [BudgetDatabase.open]. */
    fun createFile(driver: SQLiteDriver, path: String, newId: () -> String = { UUID.randomUUID().toString() }) {
        val file = File(path)
        if (file.exists()) throw LocalFirstException.InvalidLocalWrite("a budget file already exists here")
        file.parentFile?.mkdirs()
        val sql = Sql(driver.open(path))
        try {
            sql.execute("PRAGMA trusted_schema = OFF")
            sql.transaction {
                sql.executeScript(BudgetDatabase.starterSchemaSql())
                for (id in migrationIds()) sql.execute("INSERT INTO __migrations__ (id) VALUES (?)", id)
                val pageId = newId()
                sql.execute("INSERT INTO dashboard_pages (id, name) VALUES (?, ?)", pageId, "Main")
                for (widget in dashboardWidgets) {
                    sql.execute(
                        "INSERT INTO dashboard (id, type, width, height, x, y, meta, dashboard_page_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        newId(), widget.type, widget.width, widget.height, widget.x, widget.y, widget.meta, pageId,
                    )
                }
                var groupOrder = 0.0
                var categoryOrder = 0.0
                for ((name, isIncome, categories) in starterGroups) {
                    val groupId = newId()
                    groupOrder += INCREMENT
                    sql.execute(
                        "INSERT INTO category_groups (id, name, is_income, hidden, tombstone, sort_order) VALUES (?, ?, ?, 0, 0, ?)",
                        groupId, name, isIncome, groupOrder,
                    )
                    for (category in categories) {
                        val categoryId = newId()
                        categoryOrder += INCREMENT
                        sql.execute(
                            "INSERT INTO categories (id, name, cat_group, is_income, hidden, tombstone, sort_order) VALUES (?, ?, ?, ?, 0, 0, ?)",
                            categoryId, category, groupId, isIncome, categoryOrder,
                        )
                        sql.execute("INSERT INTO category_mapping (id, transferId) VALUES (?, ?)", categoryId, categoryId)
                    }
                }
            }
        } finally {
            sql.close()
        }
    }

    fun migrationIds(): List<Long> {
        val stream = NewBudget::class.java.getResourceAsStream("/actual/migrations.txt")
            ?: throw LocalFirstException.SchemaUnavailable("migration list missing")
        return stream.bufferedReader().use { it.readText() }.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toLong() }.sorted()
    }

    /**
     * The upload archive: `db.sqlite` plus Actualist's allowlisted `metadata.json`
     * (`id`, `budgetName`, `resetClock: true`), both at the archive root.
     */
    fun archive(databaseFile: File, budgetId: String, budgetName: String): ByteArray {
        val metadata = buildJsonObject {
            put("id", budgetId)
            put("budgetName", budgetName)
            put("resetClock", JsonPrimitive(true))
        }.toString()
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("db.sqlite"))
            zip.write(databaseFile.readBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("metadata.json"))
            zip.write(metadata.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    private class Widget(val type: String, val width: Int, val height: Int, val x: Int, val y: Int, val meta: String?)

    /** Actual v26.10.0's seeded dashboard, verbatim from Actualist `ActualStarterSchema.dashboardWidgets`. */
    private val dashboardWidgets = listOf(
        Widget("summary-card", 3, 2, 0, 0, """{"name":"Total Income (YTD)","content":"{\"type\":\"sum\",\"fontSize\":20}","timeFrame":{"start":"2024-01-01","end":"2024-12-31","mode":"yearToDate"},"conditions":[{"field":"amount","op":"gt","value":0},{"field":"account","op":"onBudget","value":""},{"field":"transfer","op":"is","value":false}],"conditionsOp":"and"}"""),
        Widget("summary-card", 3, 2, 3, 0, """{"name":"Total Expenses (YTD)","content":"{\"type\":\"sum\",\"fontSize\":20}","timeFrame":{"start":"2024-01-01","end":"2024-12-31","mode":"yearToDate"},"conditions":[{"field":"amount","op":"lt","value":0},{"field":"account","op":"onBudget","value":""},{"field":"transfer","op":"is","value":false}],"conditionsOp":"and"}"""),
        Widget("summary-card", 3, 2, 6, 0, """{"name":"Avg Per Month","content":"{\"type\":\"avgPerMonth\",\"fontSize\":20}","timeFrame":{"start":"2024-01-01","end":"2024-12-31","mode":"yearToDate"},"conditions":[{"field":"amount","op":"lt","value":0},{"field":"account","op":"onBudget","value":""},{"field":"transfer","op":"is","value":false}],"conditionsOp":"and"}"""),
        Widget("summary-card", 3, 2, 9, 0, """{"name":"Avg Per Transaction","content":"{\"type\":\"avgPerTransact\",\"fontSize\":20}","timeFrame":{"start":"2024-01-01","end":"2024-12-31","mode":"yearToDate"},"conditions":[{"field":"amount","op":"lt","value":0},{"field":"account","op":"onBudget","value":""},{"field":"transfer","op":"is","value":false}],"conditionsOp":"and"}"""),
        Widget("net-worth-card", 6, 2, 0, 2, null),
        Widget("cash-flow-card", 6, 2, 6, 2, null),
        Widget("spending-card", 4, 2, 0, 5, """{"name":"This Month","mode":"single-month"}"""),
        Widget("spending-card", 4, 2, 4, 5, """{"name":"Budget Overview","mode":"budget"}"""),
        Widget("spending-card", 4, 2, 8, 5, """{"name":"3-Month Average","mode":"average"}"""),
        Widget("calendar-card", 8, 4, 0, 8, """{"name":"Transaction Calendar","timeFrame":{"start":"2024-01-01","end":"2024-03-31","mode":"sliding-window"},"conditions":[{"field":"transfer","op":"is","value":false}],"conditionsOp":"and"}"""),
        Widget("summary-card", 4, 2, 8, 8, """{"name":"Recent Net Worth Change","content":"{\"type\":\"sum\",\"fontSize\":32}","timeFrame":{"start":"2024-01-01","end":"2024-03-31","mode":"sliding-window"},"conditions":[],"conditionsOp":"and"}"""),
        Widget("markdown-card", 4, 2, 8, 10, """{"content":"## Dashboard Tips\n\nYou can add new widgets or edit existing widgets by using the buttons at the top of the page. Choose a widget type and customize it to fit your needs.\n\n**Moving cards:** Drag any card by its header to reposition it.\n\n**Deleting cards:** Click the three-dot menu on any card and select \"Remove\"."}"""),
    )
}
