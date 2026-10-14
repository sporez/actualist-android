package com.sporez.actualist.android.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sporez.actualist.android.ui.MessageState
import com.sporez.actualist.android.ui.reloading
import com.sporez.actualist.core.budget.BudgetMonth
import com.sporez.actualist.core.budget.HoldCommand
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.budget.MoveMoneyCommand
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.coroutines.flow.MutableStateFlow
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

    fun showMonth(month: String) {
        _month.value = month
    }

    fun previousMonth() {
        _month.value = Months.previous(_month.value)
    }

    fun nextMonth() {
        _month.value = Months.shifted(_month.value, 1)
    }

    fun assign(categoryId: String, amount: Long) = write("Assigned") { budgetWrites.assign(month.value, categoryId, amount) }

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

    /** The category's template as an editor draft; null when it is note-managed or locked (see TemplateEditorWrites). */
    suspend fun templateDraft(categoryId: String): com.sporez.actualist.core.templates.editor.TemplateEditorDraft? =
        session.read { com.sporez.actualist.core.templates.editor.TemplateEditorWrites(database, clock).load(categoryId) }

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
