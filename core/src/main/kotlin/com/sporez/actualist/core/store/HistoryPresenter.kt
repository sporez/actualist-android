package com.sporez.actualist.core.store

import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.UndoBatch
import com.sporez.actualist.core.money.Money

/** What kind of change a History row shows; the screen maps it to an icon. */
enum class HistoryKind { Assign, Move, Transaction, Transfer, Category, Account, Payee, Rule, Schedule, Other }

/** One cell-level difference, as display text. [from] is null when there was no earlier value. */
data class HistoryChange(val label: String, val from: String?, val to: String?)

data class HistoryRow(
    val title: String,
    val detail: String,
    val amountText: String?,
    /** Negative for an outflow, positive for an inflow, null when not a money row. */
    val amountSign: Int?,
    val kind: HistoryKind,
    val changes: List<HistoryChange>,
)

/** One line of the undo review: the value now and the value after the undo. */
data class HistoryReviewEntry(val label: String, val current: String, val proposed: String)

data class HistoryReview(val title: String, val entries: List<HistoryReviewEntry>)

/**
 * Turns a recorded gesture's cell changes into Actualist-style History text (the port of
 * `HistoryRowPresentation`): the kind of change, the entities by name and amounts, before to
 * after. Names are read from the database at display time, so a renamed or deleted entity shows
 * its current name. Reads only.
 */
class HistoryPresenter(private val database: BudgetDatabase, private val symbol: String = "$") {
    private class Cells(val before: Map<Triple<String, String, String>, String?>, val after: Map<Triple<String, String, String>, String?>) {
        fun rows(dataset: String): List<String> = (after.keys + before.keys).filter { it.first == dataset }.map { it.second }.distinct()
        fun before(dataset: String, row: String, column: String): String? = before[Triple(dataset, row, column)]
        fun after(dataset: String, row: String, column: String): String? = after[Triple(dataset, row, column)]
        fun columns(dataset: String, row: String): List<String> =
            (after.keys + before.keys).filter { it.first == dataset && it.second == row }.map { it.third }.distinct()
    }

    private fun cells(batches: List<UndoBatch>): Cells {
        val before = LinkedHashMap<Triple<String, String, String>, String?>()
        val after = LinkedHashMap<Triple<String, String, String>, String?>()
        for (batch in batches) {
            for (cell in batch.undoCells) before.putIfAbsent(Triple(cell.dataset, cell.row, cell.column), cell.value.serialized)
            for (message in batch.messages) after[Triple(message.dataset, message.row, message.column)] = message.serializedValue
        }
        return Cells(before, after)
    }

    fun row(batches: List<UndoBatch>): HistoryRow {
        val cells = cells(batches)
        val created = batches.flatMap { it.createdRows }.toSet()
        val deleted = batches.flatMap { it.deletedRows }.toSet() - created
        return budgetRow(cells) ?: transactionRow(cells, created, deleted) ?: entityRow(cells, created, deleted) ?: otherRow(cells)
    }

    /** The undo (or redo) review: the current value of each touched cell and what it becomes. */
    fun review(batches: List<UndoBatch>, undoing: Boolean): HistoryReview {
        val row = row(batches)
        val entries = ArrayList<HistoryReviewEntry>()
        for (change in row.changes) {
            val now = if (undoing) change.to else change.from
            val next = if (undoing) change.from else change.to
            entries += HistoryReviewEntry(change.label, now ?: "None", next ?: "None")
        }
        if (entries.isEmpty()) entries += HistoryReviewEntry(row.title, if (undoing) "Present" else "Removed", if (undoing) "Removed" else "Present")
        return HistoryReview(row.title, entries)
    }

    // --- budget amounts -------------------------------------------------------------------

    private fun budgetRow(cells: Cells): HistoryRow? {
        val amountRows = (cells.rows("zero_budgets") + cells.rows("reflect_budgets")).distinct()
            .filter { id -> (cells.columns("zero_budgets", id) + cells.columns("reflect_budgets", id)).contains("amount") }
        val monthRows = cells.rows("zero_budget_months")
        if (amountRows.isEmpty() && monthRows.isEmpty()) return null
        if (amountRows.isEmpty()) {
            return HistoryRow("Changed a budget month", "Month settings changed", null, null, HistoryKind.Other, emptyList())
        }
        data class Cell(val id: String, val month: String, val name: String, val old: Long, val new: Long)
        val list = amountRows.map { id ->
            val dataset = if (cells.columns("zero_budgets", id).contains("amount")) "zero_budgets" else "reflect_budgets"
            val month = id.take(7)
            val categoryId = id.drop(8)
            Cell(id, month, categoryName(categoryId), number(cells.before(dataset, id, "amount")) ?: 0L, number(cells.after(dataset, id, "amount")) ?: 0L)
        }
        val changes = list.map { HistoryChange("${it.name} · ${monthLabel(it.month)}", money(it.old), money(it.new)) }
        if (list.size == 1) {
            val cell = list.single()
            val delta = cell.new - cell.old
            return HistoryRow(
                title = "Assigned ${money(cell.new)} to ${cell.name}",
                detail = "${monthLabel(cell.month)} · ${money(cell.old)} → ${money(cell.new)}",
                amountText = Money.formatSigned(delta, symbol), amountSign = sign(delta), kind = HistoryKind.Assign, changes = changes,
            )
        }
        val net = list.sumOf { it.new - it.old }
        val gained = list.filter { it.new > it.old }
        val lost = list.filter { it.new < it.old }
        if (net == 0L && gained.size == 1 && lost.size == 1) {
            val amount = gained.single().new - gained.single().old
            return HistoryRow(
                title = "Moved ${money(amount)}",
                detail = "${lost.single().name} → ${gained.single().name} · ${monthLabel(list.first().month)}",
                amountText = money(amount), amountSign = null, kind = HistoryKind.Move, changes = changes,
            )
        }
        return HistoryRow(
            title = "Changed ${list.size} budget amounts",
            detail = list.take(3).joinToString(", ") { it.name } + if (list.size > 3) " and ${list.size - 3} more" else "",
            amountText = null, amountSign = null, kind = HistoryKind.Assign, changes = changes,
        )
    }

    // --- transactions ---------------------------------------------------------------------

    private fun transactionRow(cells: Cells, created: Set<Pair<String, String>>, deleted: Set<Pair<String, String>>): HistoryRow? {
        // Split children are folded into their parent: list parents, or children when only they changed.
        val ids = cells.rows("transactions")
        if (ids.isEmpty()) return null
        val snapshots = ids.associateWith { database.rowSnapshot("transactions", it) }
        val parents = ids.filter { snapshots[it]?.get("isChild") != "N:1" }.ifEmpty { ids }
        val first = parents.first()
        val snap = snapshots[first]
        val isTransfer = snap?.get("transferred_id")?.let { it != "0:" } == true
        val key = "transactions" to first
        val verb = when {
            key in deleted -> "Deleted"
            key in created -> "Added"
            else -> "Edited"
        }
        val noun = when {
            parents.size > 1 -> "${parents.size} transactions"
            isTransfer -> "transfer"
            else -> "transaction"
        }
        val amount = number(snap?.get("amount"))
        val payee = payeeName(text(snap?.get("description")))
        val account = accountName(text(snap?.get("acct")))
        val category = text(snap?.get("category"))?.let { categoryName(it) }
        val detail = if (parents.size > 1) {
            "Across ${parents.mapNotNull { snapshots[it]?.get("acct")?.let { a -> accountName(text(a)) } }.distinct().joinToString(", ")}"
        } else {
            listOfNotNull(payee, account, category.takeIf { !isTransfer }).joinToString(" · ").ifEmpty { "Transaction" }
        }
        val changes = ArrayList<HistoryChange>()
        for (id in parents) {
            val label = if (parents.size > 1) (payeeName(text(snapshots[id]?.get("description"))) ?: "Transaction") + " · " else ""
            val isNew = ("transactions" to id) in created
            for (column in cells.columns("transactions", id)) {
                if (column == "tombstone" || column == "id" || column in structural) continue
                val from = if (isNew) null else cells.before("transactions", id, column)
                val to = cells.after("transactions", id, column)
                if (from == to) continue
                changes += HistoryChange(label + fieldLabel(column), displayField(column, from), displayField(column, to))
            }
            if (("transactions" to id) in deleted) changes += HistoryChange(label + "Transaction", "Present", "Deleted")
        }
        return HistoryRow(
            title = "$verb $noun",
            detail = detail,
            amountText = amount?.let { Money.format(it, symbol) }.takeIf { parents.size == 1 },
            amountSign = amount?.let { sign(it) }.takeIf { parents.size == 1 },
            kind = if (isTransfer) HistoryKind.Transfer else HistoryKind.Transaction,
            changes = changes,
        )
    }

    private val structural = setOf("isParent", "isChild", "parent_id", "sort_order", "starting_balance_flag", "reconciled", "pending", "error")

    private fun fieldLabel(column: String) = when (column) {
        "acct" -> "Account"
        "category" -> "Category"
        "amount" -> "Amount"
        "description" -> "Payee"
        "notes" -> "Notes"
        "date" -> "Date"
        "cleared" -> "Cleared"
        "transferred_id" -> "Transfer"
        else -> column
    }

    private fun displayField(column: String, serialized: String?): String? {
        if (serialized == null || serialized == "0:") return null
        return when (column) {
            "acct" -> accountName(text(serialized)) ?: "Unknown account"
            "category" -> categoryName(text(serialized) ?: "")
            "description" -> payeeName(text(serialized)) ?: "No payee"
            "amount" -> number(serialized)?.let { Money.format(it, symbol) }
            "date" -> number(serialized)?.let { dateLabel(it) }
            "cleared" -> if (number(serialized) == 1L) "Cleared" else "Uncleared"
            "transferred_id" -> "Linked"
            else -> text(serialized)
        }
    }

    // --- entity rows ----------------------------------------------------------------------

    private val entityKinds = listOf(
        Triple("categories", "category", HistoryKind.Category),
        Triple("category_groups", "category group", HistoryKind.Category),
        Triple("accounts", "account", HistoryKind.Account),
        Triple("payees", "payee", HistoryKind.Payee),
        Triple("rules", "rule", HistoryKind.Rule),
        Triple("schedules", "schedule", HistoryKind.Schedule),
        Triple("transaction_filters", "saved filter", HistoryKind.Other),
    )

    private fun entityRow(cells: Cells, created: Set<Pair<String, String>>, deleted: Set<Pair<String, String>>): HistoryRow? {
        val (dataset, noun, kind) = entityKinds.firstOrNull { cells.rows(it.first).isNotEmpty() } ?: return null
        val ids = cells.rows(dataset)
        val first = ids.first()
        val key = dataset to first
        val name = text(database.rowSnapshot(dataset, first)?.get("name")) ?: text(cells.after(dataset, first, "name")) ?: ""
        val verb = when {
            key in deleted -> "Deleted"
            key in created -> "Added"
            else -> "Changed"
        }
        val changes = ArrayList<HistoryChange>()
        for (id in ids) {
            val isNew = (dataset to id) in created
            for (column in cells.columns(dataset, id)) {
                if (column == "tombstone" || column == "id" || column == "sort_order") continue
                val from = if (isNew) null else cells.before(dataset, id, column)
                val to = cells.after(dataset, id, column)
                if (from == to) continue
                changes += HistoryChange(column.replace('_', ' ').replaceFirstChar { it.uppercase() }, plain(from), plain(to))
            }
            if ((dataset to id) in deleted) changes += HistoryChange(noun.replaceFirstChar { it.uppercase() }, "Present", "Deleted")
        }
        val title = if (ids.size > 1) "$verb ${ids.size} ${noun}s" else "$verb $noun" + if (name.isNotEmpty()) " $name" else ""
        val renamed = changes.firstOrNull { it.label == "Name" && it.from != null && it.to != null }
        val detail = renamed?.let { "${it.from} → ${it.to}" } ?: name.ifEmpty { noun.replaceFirstChar { it.uppercase() } }
        return HistoryRow(title, detail, null, null, kind, changes)
    }

    private fun otherRow(cells: Cells): HistoryRow {
        val datasets = (cells.after.keys + cells.before.keys).map { it.first }.distinct()
        val detail = if (datasets.contains("notes")) "Notes" else "Budget data"
        return HistoryRow("Changed the budget", detail, null, null, HistoryKind.Other, emptyList())
    }

    // --- lookups and formatting -----------------------------------------------------------

    private fun categoryName(id: String): String =
        text(database.rowSnapshot("categories", id)?.get("name"))?.takeIf { it.isNotBlank() } ?: if (id.isEmpty()) "Uncategorized" else "Deleted category"

    private fun accountName(id: String?): String? = id?.let { text(database.rowSnapshot("accounts", it)?.get("name")) }

    private fun payeeName(id: String?): String? = id?.let { text(database.rowSnapshot("payees", it)?.get("name")) }

    private fun money(minor: Long) = Money.format(minor, symbol)

    private fun sign(value: Long) = if (value > 0) 1 else if (value < 0) -1 else 0

    private fun number(serialized: String?): Long? = serialized?.takeIf { it.startsWith("N:") }?.drop(2)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

    private fun text(serialized: String?): String? = serialized?.takeIf { it.startsWith("S:") }?.drop(2)

    private fun plain(serialized: String?): String? {
        if (serialized == null || serialized == "0:") return null
        return when {
            serialized.startsWith("S:") -> serialized.drop(2)
            serialized.startsWith("N:") -> serialized.drop(2)
            else -> serialized
        }
    }

    private fun monthLabel(month: String): String {
        val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val index = month.drop(5).toIntOrNull()?.minus(1)
        return if (index != null && index in 0..11) "${names[index]} ${month.take(4)}" else month
    }

    private fun dateLabel(yyyymmdd: Long): String {
        val text = yyyymmdd.toString()
        if (text.length != 8) return text
        return "${text.substring(0, 4)}-${text.substring(4, 6)}-${text.substring(6, 8)}"
    }
}
