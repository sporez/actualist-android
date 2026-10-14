package com.sporez.actualist.android.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sporez.actualist.android.ui.MessageState
import com.sporez.actualist.android.ui.reloading
import com.sporez.actualist.core.budget.BudgetMonth
import com.sporez.actualist.core.budget.HoldCommand
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.budget.MoveMoneyCommand
import com.sporez.actualist.core.rules.CategoryLearning
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.transactions.TransactionBatch
import com.sporez.actualist.core.transactions.TransactionQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BudgetScreenState(val month: BudgetMonth, val availableMonths: List<String>, val note: String? = null) {
    val overspent get() = month.categoryGroups.filter { !it.isIncome }.flatMap { it.categories }.filter { it.balance < 0 }
}

class BudgetViewModel(private val session: BudgetSession, initialMonth: String) : ViewModel() {
    private val _month = MutableStateFlow(initialMonth)
    val month: StateFlow<String> = _month.asStateFlow()
    val messages = MessageState()

    val state = reloading(session, _month) { month ->
        BudgetScreenState(budget.fetchBudgetMonth(month), budget.availableMonths(), budget.monthNote(month))
    }

    private val _followingCount = MutableStateFlow(2)

    /** The months after the shown one, for the side-by-side columns on wide screens (two unless the screen says otherwise). */
    val following = reloading(session, combine(_month, _followingCount) { month, count -> month to count }) { (month, count) ->
        (1..count).map { budget.fetchBudgetMonth(Months.shifted(month, it)) }
    }

    /** The shown month's uncategorized on-budget transactions, for the review list and the banner count. */
    val uncategorized = reloading(session, _month) { month ->
        ledger.transactions(TransactionQuery(uncategorizedOnly = true, month = month, limit = 500))
    }

    fun setFollowingCount(count: Int) {
        _followingCount.value = count.coerceIn(0, 4)
    }

    fun showMonth(month: String) {
        _month.value = month
    }

    fun showToday() {
        _month.value = Months.current()
    }

    /** Covers each overspent category from its chosen source (null is To Budget) in one batch: (category, source, amount). */
    fun coverAll(items: List<Triple<String, String?, Long>>) = write(if (items.size == 1) "Covered" else "Covered ${items.size} categories") {
        budgetWrites.move(month.value, items.map { (id, from, amount) -> MoveMoneyCommand(from, id, amount, MoveMoneyCommand.Kind.COVER) })
    }

    suspend fun categoryOptions(): List<com.sporez.actualist.android.ui.components.PickerOption> = try {
        session.read {
            ledger.categories(includeHidden = false).map { com.sporez.actualist.android.ui.components.PickerOption(it.id, it.name, it.groupName) }
        }
    } catch (error: Exception) {
        messages.show(error.message ?: "Categories could not be read.")
        emptyList()
    }

    /** Gives one uncategorized transaction a category (the way a batch categorize does, then category learning). */
    fun categorize(transactionId: String, categoryId: String) = write("Categorized") {
        val result = TransactionBatch(database, clock).categorize(listOf(transactionId), categoryId)
        if (result.changed == 0) error("That transaction cannot be categorized here.")
        CategoryLearning(database).learn(setOf(transactionId), java.time.LocalDate.now())
    }

    fun previousMonth() {
        _month.value = Months.previous(_month.value)
    }

    fun nextMonth() {
        _month.value = Months.shifted(_month.value, 1)
    }

    fun assign(categoryId: String, amount: Long) = write("Assigned") { budgetWrites.assign(month.value, categoryId, amount) }

    /** Assigns in a month other than the shown one: the side-by-side columns edit in place. */
    fun assignIn(month: String, categoryId: String, amount: Long) = write("Assigned in ${monthTitle(month)}") { budgetWrites.assign(month, categoryId, amount) }

    /** A null side is To Budget. */
    fun move(from: String?, to: String?, amount: Long) = write("Moved") {
        budgetWrites.move(month.value, listOf(MoveMoneyCommand(from, to, amount, if (from == null) MoveMoneyCommand.Kind.TRANSFER_AVAILABLE else MoveMoneyCommand.Kind.MOVE)))
    }

    /** Covers a category's overspending from To Budget (null) or another category. */
    fun cover(categoryId: String, from: String?, overspent: Long) = write("Covered") {
        budgetWrites.move(month.value, listOf(MoveMoneyCommand(from, categoryId, overspent, MoveMoneyCommand.Kind.COVER)))
    }

    fun setCarryover(categoryId: String, carryover: Boolean) = write(if (carryover) "Rollover on" else "Rollover off") {
        budgetWrites.setCarryover(categoryId, carryover, month.value, month.value)
    }

    fun addCategory(name: String, groupId: String?, newGroupName: String?) = write("Added $name") {
        val writes = com.sporez.actualist.core.budget.CategoryWrites(database)
        val group = groupId ?: writes.createGroup(newGroupName ?: error("Choose a group")).first
        writes.createCategory(name, group)
    }

    fun hideCategory(categoryId: String) = write("Hidden") { com.sporez.actualist.core.budget.CategoryWrites(database).setCategoryHidden(categoryId, true) }

    fun unhideCategory(categoryId: String) = write("Shown") { com.sporez.actualist.core.budget.CategoryWrites(database).setCategoryHidden(categoryId, false) }

    fun renameCategory(categoryId: String, name: String) = write("Renamed") { com.sporez.actualist.core.budget.CategoryWrites(database).renameCategory(categoryId, name) }

    fun renameGroup(groupId: String, name: String) = write("Renamed") { com.sporez.actualist.core.budget.CategoryWrites(database).renameGroup(groupId, name) }

    /** One step up (-1) or down (+1) inside the category's group. */
    fun moveCategoryBy(categoryId: String, delta: Int) = write("Moved") { com.sporez.actualist.core.budget.CategoryWrites(database).moveCategoryBy(categoryId, delta) }

    /** Moves a category to the end of another group. */
    fun moveCategoryToGroup(categoryId: String, groupId: String) = write("Moved") {
        com.sporez.actualist.core.budget.CategoryWrites(database).moveCategory(categoryId, groupId, null)
    }

    fun moveGroupBy(groupId: String, delta: Int) = write("Moved") { com.sporez.actualist.core.budget.CategoryWrites(database).moveGroupBy(groupId, delta) }

    /** Whether deleting the category (or, for a group, any of its categories) needs a transfer destination; null when unreadable. */
    suspend fun needsTransfer(id: String, isGroup: Boolean): Boolean? = try {
        session.read {
            val writes = com.sporez.actualist.core.budget.CategoryWrites(database)
            if (isGroup) writes.groupNeedsTransfer(id) else writes.categoryNeedsTransfer(id)
        }
    } catch (error: Exception) {
        messages.show(error.message ?: "The category could not be read.")
        null
    }

    fun deleteCategory(categoryId: String, transferId: String?) = write("Deleted") {
        com.sporez.actualist.core.budget.CategoryWrites(database).deleteCategory(categoryId, transferId)
    }

    fun deleteGroup(groupId: String, transferId: String?) = write("Deleted") {
        com.sporez.actualist.core.budget.CategoryWrites(database).deleteGroup(groupId, transferId)
    }

    fun copyPreviousMonth() = write("Copied last month's budget") { budgetWrites.applyBulk(month.value) { copyPreviousMonthDrafts(month.value) } }

    fun setThreeMonthAverage() = write("Set to 3-month averages") {
        budgetWrites.applyBulk(month.value) { setAverageDrafts(month.value, Months.current()) }
    }

    fun setZero() = write("Set all budgets to zero") { budgetWrites.applyBulk(month.value) { setZeroDrafts(month.value) } }

    /** What the budget's templates would assign this month (Actual's "Apply budget template"). */
    suspend fun templatePreview(overwrite: Boolean): com.sporez.actualist.core.templates.TemplatePreview? = try {
        session.read {
            val templates = com.sporez.actualist.core.templates.BudgetTemplates(database, clock)
            if (!templates.hasTemplates()) null
            else templates.preview(month.value, mode = if (overwrite) com.sporez.actualist.core.templates.TemplateApplyMode.OVERWRITE else com.sporez.actualist.core.templates.TemplateApplyMode.FILL_EMPTY)
        }
    } catch (error: Exception) {
        messages.show(error.message ?: "Templates could not be read.")
        null
    }

    fun applyTemplates(overwrite: Boolean) = write("Applied templates") {
        com.sporez.actualist.core.templates.BudgetTemplates(database, clock)
            .apply(month.value, mode = if (overwrite) com.sporez.actualist.core.templates.TemplateApplyMode.OVERWRITE else com.sporez.actualist.core.templates.TemplateApplyMode.FILL_EMPTY)
    }

    fun hold(amount: Long) = write("Held for next month") { budgetWrites.hold(month.value, HoldCommand.Hold(amount)) }

    fun releaseHold() = write("Hold released") { budgetWrites.hold(month.value, HoldCommand.Reset) }

    /** Active schedules a schedule template can budget for: id to name. */
    suspend fun scheduleChoices(): List<Pair<String, String>> = session.read {
        com.sporez.actualist.core.schedules.Schedules(database, clock).list(java.time.LocalDate.now().toString())
            .filter { !it.completed && it.name.isNotBlank() }
            .map { it.id to it.name }
    }

    suspend fun categoryNote(categoryId: String): String? =
        session.read { com.sporez.actualist.core.notes.EntityNotes(database).read(com.sporez.actualist.core.notes.NoteTarget.Category(categoryId)) }

    fun saveCategoryNote(categoryId: String, text: String) = write("Notes saved") {
        com.sporez.actualist.core.notes.EntityNotes(database).write(com.sporez.actualist.core.notes.NoteTarget.Category(categoryId), text)
    }

    /** The category's template as an editor draft; null when it is note-managed or locked (see TemplateEditorWrites). */
    suspend fun templateDraft(categoryId: String): com.sporez.actualist.core.templates.editor.TemplateEditorDraft? =
        session.read { com.sporez.actualist.core.templates.editor.TemplateEditorWrites(database, clock).load(categoryId) }

    /** The editor's snapshot: draft, lock and the schedules and income sources it may reference; null when unreadable. */
    suspend fun templateEditorSnapshot(categoryId: String): com.sporez.actualist.core.templates.editor.TemplateEditorSnapshot? = try {
        session.read { com.sporez.actualist.core.templates.editor.TemplateEditorWrites(database, clock).snapshot(categoryId) }
    } catch (error: Exception) {
        null
    }

    /** Raw `goal_def` per category that has one: what the templates browser lists. */
    suspend fun templateDefinitions(): Map<String, String> = try {
        session.read { com.sporez.actualist.core.templates.TemplateReads(database).goalDefsRaw() }
    } catch (error: Exception) {
        emptyMap()
    }

    fun saveTemplate(categoryId: String, draft: com.sporez.actualist.core.templates.editor.TemplateEditorDraft) =
        write(if (draft.entries.isEmpty()) "Template removed" else "Template saved") {
            com.sporez.actualist.core.templates.editor.TemplateEditorWrites(database, clock).save(categoryId, draft)
        }

    private fun write(done: String, block: BudgetSession.() -> Any?) {
        viewModelScope.launch {
            try {
                session.write { block() }
                messages.show(done)
            } catch (error: Exception) {
                messages.show(error.message ?: "The change could not be saved.")
            }
        }
    }
}
