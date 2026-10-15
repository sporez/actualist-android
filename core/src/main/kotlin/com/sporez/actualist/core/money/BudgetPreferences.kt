package com.sporez.actualist.core.money

import com.sporez.actualist.core.db.BudgetDatabase

/** Reads the budget's synced `preferences` table. */
class BudgetPreferences(private val database: BudgetDatabase) {
    fun value(id: String): String? {
        if (!database.tableExists("preferences")) return null
        val columns = database.columnSet("preferences")
        if ("id" !in columns || "value" !in columns) return null
        val tombstone = if ("tombstone" in columns) "AND (tombstone = 0 OR tombstone IS NULL)" else ""
        return database.sql.queryString("SELECT value FROM preferences WHERE id = ? $tombstone LIMIT 1", id)
    }

    /** The amount format the budget's preferences ask for. */
    fun format(): MoneyFormat = MoneyFormat.fromPreferences(::value)
}
