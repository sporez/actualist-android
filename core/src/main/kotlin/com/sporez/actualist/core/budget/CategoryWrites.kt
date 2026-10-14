package com.sporez.actualist.core.budget

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Sql
import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncValue
import java.util.UUID

/** Actual's sort-order shove (`ActualSortOrder`): insert before a target, renumbering when gaps run out. */
object SortOrder {
    const val INCREMENT = 16_384.0

    data class Item(val id: String, val sortOrder: Double)
    data class Result(val sortOrder: Double, val updates: List<Item>)

    fun shove(items: List<Item>, targetId: String?): Result {
        val targetIndex = items.indexOfFirst { it.id == targetId }
        if (targetId == null || targetIndex < 0) return Result((items.lastOrNull()?.sortOrder ?: 0.0) + INCREMENT, emptyList())
        val target = items[targetIndex]
        val before = items.getOrNull(targetIndex - 1)
        val updates = mutableListOf<Item>()
        if (target.sortOrder - (before?.sortOrder ?: 0.0) <= 2) {
            var next = targetIndex
            var order = Math.floor(items[next].sortOrder) + INCREMENT
            while (next < items.size) {
                if (order <= items[next].sortOrder) break
                updates += Item(items[next].id, order)
                next++
                order += INCREMENT
            }
        }
        val sortOrder = if (targetIndex == 0) target.sortOrder / 2 else (items[targetIndex - 1].sortOrder + target.sortOrder) / 2
        return Result(sortOrder, updates)
    }
}

/**
 * Category and group lifecycle writes, ported from Actualist `BudgetDatabase+CategoryLifecycle`:
 * a new category goes to the top of its group with a `category_mapping` identity row, names
 * are unique (case-insensitively) within their scope, and hiding writes one cell.
 */
class CategoryWrites(private val database: BudgetDatabase, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private val sql get() = database.sql

    fun createGroup(name: String): Pair<String, List<SyncMessage>> {
        val id = newId()
        val messages = database.commitLocal { createGroupDrafts(id, name) to Unit }.messages
        return id to messages
    }

    fun createCategory(name: String, groupId: String): Pair<String, List<SyncMessage>> {
        val id = newId()
        val messages = database.commitLocal { createCategoryDrafts(id, name, groupId) to Unit }.messages
        return id to messages
    }

    fun renameCategory(categoryId: String, name: String) = database.commitLocal {
        val trimmed = validatedName(name, "category")
        val row = sql.queryOne("SELECT name, cat_group FROM categories WHERE id = ? AND (tombstone IS NULL OR tombstone = 0)", categoryId)
            ?: throw LocalFirstException.InvalidLocalWrite("missing category")
        if (row.string("name") == trimmed) return@commitLocal emptyList<CellDraft>() to Unit
        rejectDuplicateCategory(trimmed, row.string("cat_group") ?: "", categoryId)
        listOf(CellDraft("categories", categoryId, "name", SyncValue.text(trimmed))) to Unit
    }.messages

    fun setCategoryHidden(categoryId: String, hidden: Boolean) = database.commitLocal {
        if (!database.liveRowExists("categories", categoryId)) throw LocalFirstException.InvalidLocalWrite("missing category")
        listOf(CellDraft("categories", categoryId, "hidden", SyncValue.bool(hidden))) to Unit
    }.messages

    fun renameGroup(groupId: String, name: String) = database.commitLocal {
        val trimmed = validatedName(name, "category group")
        val row = sql.queryOne("SELECT name FROM category_groups WHERE id = ? AND (tombstone IS NULL OR tombstone = 0)", groupId)
            ?: throw LocalFirstException.InvalidLocalWrite("missing category group")
        if (row.string("name") == trimmed) return@commitLocal emptyList<CellDraft>() to Unit
        val clash = sql.query("SELECT id, name FROM category_groups WHERE (tombstone IS NULL OR tombstone = 0)")
            .any { it.string("id") != groupId && it.string("name")?.equals(trimmed, ignoreCase = true) == true }
        if (clash) throw LocalFirstException.InvalidLocalWrite("a category group with that name already exists")
        listOf(CellDraft("category_groups", groupId, "name", SyncValue.text(trimmed))) to Unit
    }.messages

    // region Reordering (Actual's moveCategory and moveCategoryGroup)

    /** Moves a category into [groupId], before [beforeCategoryId] (null for the end of the group). */
    fun moveCategory(categoryId: String, groupId: String, beforeCategoryId: String?) = database.commitLocal {
        moveCategoryDrafts(categoryId, groupId, beforeCategoryId) to Unit
    }.messages

    /** Moves a group before [beforeGroupId] (null for the end). */
    fun moveGroup(groupId: String, beforeGroupId: String?) = database.commitLocal {
        moveGroupDrafts(groupId, beforeGroupId) to Unit
    }.messages

    /** One step up (delta -1) or down (+1) within the category's group; nothing at the edge. */
    fun moveCategoryBy(categoryId: String, delta: Int) = database.commitLocal {
        val row = sql.queryOne("SELECT cat_group FROM categories WHERE id = ? AND (tombstone IS NULL OR tombstone = 0)", categoryId)
            ?: throw LocalFirstException.InvalidLocalWrite("missing category")
        val groupId = row.string("cat_group") ?: throw LocalFirstException.InvalidLocalWrite("category has no group")
        val ids = liveCategoryIds(groupId)
        val index = ids.indexOf(categoryId)
        val target = reorderTarget(ids, index, delta)
        if (target == NO_MOVE) return@commitLocal emptyList<CellDraft>() to Unit
        moveCategoryDrafts(categoryId, groupId, target) to Unit
    }.messages

    fun moveGroupBy(groupId: String, delta: Int) = database.commitLocal {
        if (!database.liveRowExists("category_groups", groupId)) throw LocalFirstException.InvalidLocalWrite("missing category group")
        val ids = sql.query("SELECT id FROM category_groups WHERE (tombstone IS NULL OR tombstone = 0) ORDER BY sort_order, id").mapNotNull { it.string("id") }
        val target = reorderTarget(ids, ids.indexOf(groupId), delta)
        if (target == NO_MOVE) return@commitLocal emptyList<CellDraft>() to Unit
        moveGroupDrafts(groupId, target) to Unit
    }.messages

    /** The item to insert before so that the item at [index] moves by [delta]; [NO_MOVE] at an edge. */
    private fun reorderTarget(ids: List<String>, index: Int, delta: Int): String? {
        if (index < 0) return NO_MOVE
        val to = index + delta
        if (to < 0 || to >= ids.size) return NO_MOVE
        return if (delta < 0) ids[to] else ids.getOrNull(to + 1)
    }

    private fun liveCategoryIds(groupId: String): List<String> =
        sql.query("SELECT id FROM categories WHERE cat_group = ? AND (tombstone IS NULL OR tombstone = 0) ORDER BY sort_order, id", groupId).mapNotNull { it.string("id") }

    fun moveCategoryDrafts(categoryId: String, groupId: String, beforeCategoryId: String?): List<CellDraft> {
        val category = sql.queryOne("SELECT id, name, cat_group, is_income FROM categories WHERE id = ? AND (tombstone IS NULL OR tombstone = 0)", categoryId)
            ?: throw LocalFirstException.InvalidLocalWrite("missing category")
        val group = sql.queryOne("SELECT id, is_income FROM category_groups WHERE id = ? AND (tombstone IS NULL OR tombstone = 0)", groupId)
            ?: throw LocalFirstException.InvalidLocalWrite("missing category group")
        if (beforeCategoryId == categoryId) throw LocalFirstException.InvalidLocalWrite("a category cannot move before itself")
        if (category.bool("is_income") != group.bool("is_income")) {
            throw LocalFirstException.InvalidLocalWrite("income and expense categories cannot be mixed")
        }
        if (category.string("cat_group") != groupId) rejectDuplicateCategory(category.string("name") ?: "", groupId, categoryId)
        val items = sql.query("SELECT id, sort_order FROM categories WHERE cat_group = ? AND (tombstone IS NULL OR tombstone = 0) ORDER BY sort_order, id", groupId)
            .map { SortOrder.Item(it.string("id")!!, it.double("sort_order") ?: 0.0) }
        val shove = SortOrder.shove(items, beforeCategoryId)
        return shove.updates.map { CellDraft("categories", it.id, "sort_order", SyncValue.Real(it.sortOrder)) } + listOf(
            CellDraft("categories", categoryId, "sort_order", SyncValue.Real(shove.sortOrder)),
            CellDraft("categories", categoryId, "cat_group", SyncValue.text(groupId)),
        )
    }

    fun moveGroupDrafts(groupId: String, beforeGroupId: String?): List<CellDraft> {
        if (!database.liveRowExists("category_groups", groupId)) throw LocalFirstException.InvalidLocalWrite("missing category group")
        if (beforeGroupId == groupId) throw LocalFirstException.InvalidLocalWrite("a group cannot move before itself")
        val items = sql.query("SELECT id, sort_order FROM category_groups WHERE (tombstone IS NULL OR tombstone = 0) ORDER BY sort_order, id")
            .map { SortOrder.Item(it.string("id")!!, it.double("sort_order") ?: 0.0) }
        val shove = SortOrder.shove(items, beforeGroupId)
        return shove.updates.map { CellDraft("category_groups", it.id, "sort_order", SyncValue.Real(it.sortOrder)) } +
            CellDraft("category_groups", groupId, "sort_order", SyncValue.Real(shove.sortOrder))
    }

    // endregion

    // region Deletion (Actual's deleteCategory and deleteCategoryGroup with a transfer)

    /** Whether deleting the category must name a category to receive its transactions and budget. */
    fun categoryNeedsTransfer(categoryId: String): Boolean = sql.locked {
        val hasTransaction = if (database.tableExists("category_mapping")) {
            sql.queryOne(
                "SELECT 1 AS found FROM (SELECT category FROM transactions WHERE ${BudgetDatabase.livePredicate(database.columnSet("transactions"))}) t " +
                    "LEFT JOIN category_mapping cm ON cm.id = t.category WHERE COALESCE(cm.transferId, t.category) = ? LIMIT 1",
                categoryId,
            ) != null
        } else {
            sql.queryOne("SELECT 1 AS found FROM transactions WHERE ${BudgetDatabase.livePredicate(database.columnSet("transactions"))} AND category = ? LIMIT 1", categoryId) != null
        }
        if (hasTransaction) return@locked true
        val table = BudgetReads(database).budgetTable().tableName
        sql.queryOne(
            "SELECT 1 AS found FROM ${Sql.quoted(table)} WHERE category = ? AND COALESCE(amount, 0) != 0 LIMIT 1",
            categoryId,
        ) != null
    }

    fun groupNeedsTransfer(groupId: String): Boolean = sql.locked {
        liveCategoryIds(groupId).any { categoryNeedsTransfer(it) }
    }

    fun deleteCategory(categoryId: String, transferCategoryId: String?) = database.commitLocal {
        val category = sql.queryOne("SELECT id, is_income FROM categories WHERE id = ? AND (tombstone IS NULL OR tombstone = 0)", categoryId)
            ?: throw LocalFirstException.InvalidLocalWrite("missing category")
        requireManageable(category.bool("is_income"), "categories")
        val drafts = mutableListOf<CellDraft>()
        if (transferCategoryId == null) {
            if (categoryNeedsTransfer(categoryId)) throw LocalFirstException.InvalidLocalWrite("category requires a transfer destination")
        } else {
            val destination = sql.queryOne("SELECT id, is_income FROM categories WHERE id = ? AND (tombstone IS NULL OR tombstone = 0)", transferCategoryId)
                ?: throw LocalFirstException.InvalidLocalWrite("missing transfer category")
            if (transferCategoryId == categoryId) throw LocalFirstException.InvalidLocalWrite("a category cannot transfer to itself")
            if (destination.bool("is_income") != category.bool("is_income")) {
                throw LocalFirstException.InvalidLocalWrite("income and expense categories cannot be mixed")
            }
            if (!category.bool("is_income")) drafts += budgetTransferDrafts(listOf(categoryId), transferCategoryId)
            drafts += mappingTransferDrafts(listOf(categoryId), transferCategoryId)
        }
        drafts += CellDraft("categories", categoryId, "tombstone", SyncValue.bool(true))
        drafts to Unit
    }.messages

    fun deleteGroup(groupId: String, transferCategoryId: String?) = database.commitLocal {
        val group = sql.queryOne("SELECT id, is_income FROM category_groups WHERE id = ? AND (tombstone IS NULL OR tombstone = 0)", groupId)
            ?: throw LocalFirstException.InvalidLocalWrite("missing category group")
        requireManageable(group.bool("is_income"), "groups")
        val allChildren = sql.query("SELECT id, is_income FROM categories WHERE cat_group = ?", groupId)
        val liveChildren = liveCategoryIds(groupId)
        val drafts = mutableListOf<CellDraft>()
        if (transferCategoryId == null) {
            if (liveChildren.any { categoryNeedsTransfer(it) }) throw LocalFirstException.InvalidLocalWrite("category group requires a transfer destination")
        } else {
            val destination = sql.queryOne("SELECT id, cat_group, is_income FROM categories WHERE id = ? AND (tombstone IS NULL OR tombstone = 0)", transferCategoryId)
                ?: throw LocalFirstException.InvalidLocalWrite("missing transfer category")
            if (destination.string("cat_group") == groupId) throw LocalFirstException.InvalidLocalWrite("a category group cannot transfer within itself")
            if (destination.bool("is_income") != group.bool("is_income") || allChildren.any { it.bool("is_income") != destination.bool("is_income") }) {
                throw LocalFirstException.InvalidLocalWrite("income and expense categories cannot be mixed")
            }
            drafts += budgetTransferDrafts(liveChildren, transferCategoryId)
            drafts += mappingTransferDrafts(allChildren.mapNotNull { it.string("id") }, transferCategoryId)
        }
        for (child in allChildren) drafts += CellDraft("categories", child.string("id")!!, "tombstone", SyncValue.bool(true))
        drafts += CellDraft("category_groups", groupId, "tombstone", SyncValue.bool(true))
        drafts to Unit
    }.messages

    private fun requireManageable(isIncome: Boolean, kind: String) {
        if (isIncome && BudgetReads(database).budgetTable() == BudgetTable.ENVELOPE) {
            throw LocalFirstException.InvalidLocalWrite("income $kind cannot be managed in an envelope budget")
        }
    }

    /** Adds each month's budgeted amount of the sources to the destination's, through the normal assign path. */
    private fun budgetTransferDrafts(sources: List<String>, destination: String): List<CellDraft> {
        if (sources.isEmpty()) return emptyList()
        val table = BudgetReads(database).budgetTable().tableName
        val sourceSet = sources.toSet()
        val sourceAmounts = java.util.TreeMap<String, Long>()
        val destinationAmounts = HashMap<String, Long>()
        val rows = sql.query("SELECT ${normalizedMonthSql("month")} AS month, category, amount FROM ${Sql.quoted(table)}")
        for (row in rows) {
            val month = row.string("month") ?: continue
            val category = row.string("category") ?: continue
            val amount = row.longOr0("amount")
            if (category in sourceSet) sourceAmounts[month] = (sourceAmounts[month] ?: 0L) + amount
            if (category == destination) destinationAmounts[month] = (destinationAmounts[month] ?: 0L) + amount
        }
        val writes = BudgetWrites(database)
        val drafts = mutableListOf<CellDraft>()
        for ((month, amount) in sourceAmounts) {
            if (amount == 0L) continue
            drafts += writes.assignDrafts(month, destination, (destinationAmounts[month] ?: 0L) + amount)
        }
        return drafts
    }

    /** Points the sources, and every category already forwarded to them, at the destination. */
    private fun mappingTransferDrafts(sources: List<String>, destination: String): List<CellDraft> {
        if (sources.isEmpty()) return emptyList()
        val ids = sortedSetOf<String>()
        ids += sources
        for (source in sources) {
            sql.query("SELECT id FROM category_mapping WHERE transferId = ?", source).forEach { row -> row.string("id")?.let { ids += it } }
        }
        return ids.map { CellDraft("category_mapping", it, "transferId", SyncValue.text(destination)) }
    }

    // endregion

    fun createGroupDrafts(id: String, name: String): List<CellDraft> {
        val trimmed = validatedName(name, "category group")
        if (database.rowExists("category_groups", id)) throw LocalFirstException.InvalidLocalWrite("category group already exists")
        val groups = sql.query("SELECT id, name, sort_order FROM category_groups WHERE (tombstone IS NULL OR tombstone = 0) ORDER BY sort_order, id")
        if (groups.any { it.string("name")?.equals(trimmed, ignoreCase = true) == true }) {
            throw LocalFirstException.InvalidLocalWrite("a category group with that name already exists")
        }
        val sortOrder = SortOrder.shove(groups.map { SortOrder.Item(it.string("id")!!, it.double("sort_order") ?: 0.0) }, null).sortOrder
        return listOf(
            CellDraft("category_groups", id, "name", SyncValue.text(trimmed)),
            CellDraft("category_groups", id, "is_income", SyncValue.bool(false)),
            CellDraft("category_groups", id, "sort_order", SyncValue.Real(sortOrder)),
            CellDraft("category_groups", id, "hidden", SyncValue.bool(false)),
            CellDraft("category_groups", id, "tombstone", SyncValue.bool(false)),
        )
    }

    fun createCategoryDrafts(id: String, name: String, groupId: String): List<CellDraft> {
        val trimmed = validatedName(name, "category")
        val group = sql.queryOne("SELECT id, is_income FROM category_groups WHERE id = ? AND (tombstone IS NULL OR tombstone = 0)", groupId)
            ?: throw LocalFirstException.InvalidLocalWrite("missing category group")
        if (database.rowExists("categories", id) || database.rowExists("category_mapping", id)) {
            throw LocalFirstException.InvalidLocalWrite("category already exists")
        }
        rejectDuplicateCategory(trimmed, groupId, null)
        val items = sql.query("SELECT id, sort_order FROM categories WHERE cat_group = ? AND (tombstone IS NULL OR tombstone = 0) ORDER BY sort_order, id", groupId)
            .map { SortOrder.Item(it.string("id")!!, it.double("sort_order") ?: 0.0) }
        val shove = SortOrder.shove(items, items.firstOrNull()?.id)
        return shove.updates.map { CellDraft("categories", it.id, "sort_order", SyncValue.Real(it.sortOrder)) } + listOf(
            CellDraft("categories", id, "name", SyncValue.text(trimmed)),
            CellDraft("categories", id, "cat_group", SyncValue.text(groupId)),
            CellDraft("categories", id, "is_income", SyncValue.bool(group.bool("is_income"))),
            CellDraft("categories", id, "sort_order", SyncValue.Real(shove.sortOrder)),
            CellDraft("categories", id, "hidden", SyncValue.bool(false)),
            CellDraft("categories", id, "tombstone", SyncValue.bool(false)),
            CellDraft("category_mapping", id, "transferId", SyncValue.text(id)),
        )
    }

    private fun rejectDuplicateCategory(name: String, groupId: String, excluding: String?) {
        val clash = sql.query("SELECT id, name FROM categories WHERE cat_group = ? AND (tombstone IS NULL OR tombstone = 0)", groupId)
            .any { it.string("id") != excluding && it.string("name")?.equals(name, ignoreCase = true) == true }
        if (clash) throw LocalFirstException.InvalidLocalWrite("a category with that name already exists in this group")
    }

    private companion object {
        /** Sentinel for "no reorder"; a real target id is never this. */
        const val NO_MOVE = "\u0000no-move"
    }

    private fun validatedName(name: String, label: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LocalFirstException.InvalidLocalWrite("missing $label name")
        return trimmed
    }
}
