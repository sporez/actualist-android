package com.sporez.actualist.core.budget

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
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

    private fun validatedName(name: String, label: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LocalFirstException.InvalidLocalWrite("missing $label name")
        return trimmed
    }
}
