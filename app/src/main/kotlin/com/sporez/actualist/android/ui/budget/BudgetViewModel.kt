package com.sporez.actualist.android.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sporez.actualist.android.ui.MessageState
import com.sporez.actualist.android.ui.reloading
import com.sporez.actualist.core.budget.BudgetMonth
import com.sporez.actualist.core.budget.HoldCommand
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.budget.MoveMoneyCommand
import com.sporez.actualist.core.privacy.SampleValues
import com.sporez.actualist.core.rules.CategoryLearning
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.transactions.TransactionBatch
import com.sporez.actualist.core.transactions.TransactionQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One month for the screen. With [includeRollover] off (Actualist's default for "Include Rollover in
 * Alerts"), a category that rolls its overspending over is not counted as overspent.
 */
data class BudgetScreenState(
    val month: BudgetMonth,
    val availableMonths: List<String>,
    val note: String? = null,
    val includeRollover: Boolean = false,
) {
    val overspent get() = month.categoryGroups.filter { !it.isIncome }.flatMap { it.categories }.filter { it.balance < 0 && (includeRollover || !it.carryover) }
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
    fun categorize(transactionId: String, categoryId: String, unlockReconciled: Boolean = false) = write("Categorized") {
        val mutations = com.sporez.actualist.core.transactions.ReconciledMutations(database)
        val locked = if (unlockReconciled) mutations.unlock(listOf(transactionId)) else emptyList()
        try {
            val result = TransactionBatch(database, clock).categorize(listOf(transactionId), categoryId)
            if (result.changed == 0) error("That transaction cannot be categorized here.")
            CategoryLearning(database).learn(setOf(transactionId), java.time.LocalDate.now())
        } finally {
            if (locked.isNotEmpty()) mutations.relock(locked)
        }
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

    /**
     * Moves money between the focused category and several others in ONE write (one undo step):
     * (from, to, amount) per leg, a null side being To Budget.
     */
    fun moveAll(legs: List<Triple<String?, String?, Long>>) = write(if (legs.size == 1) "Moved" else "Moved in ${legs.size} parts") {
        budgetWrites.move(
            month.value,
            legs.map { (from, to, amount) -> MoveMoneyCommand(from, to, amount, if (from == null) MoveMoneyCommand.Kind.TRANSFER_AVAILABLE else MoveMoneyCommand.Kind.MOVE) },
        )
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

    /** Actualist's visibility guards: an income group cannot be hidden and a hidden group's categories wait for the group. */
    fun changeVisibility(categoryId: String, hide: Boolean, isIncome: Boolean, groupHidden: Boolean) {
        when {
            hide && isIncome -> messages.show("Income groups cannot be hidden.")
            groupHidden -> messages.show("Show the group before changing a category.")
            hide -> hideCategory(categoryId)
            else -> unhideCategory(categoryId)
        }
    }

    fun hideCategory(categoryId: String) = write("Hidden") { com.sporez.actualist.core.budget.CategoryWrites(database).setCategoryHidden(categoryId, true) }

    fun unhideCategory(categoryId: String) = write("Shown") { com.sporez.actualist.core.budget.CategoryWrites(database).setCategoryHidden(categoryId, false) }

    fun renameCategory(categoryId: String, name: String) = guardedWrite("Renamed") { com.sporez.actualist.core.budget.CategoryWrites(database).renameCategory(categoryId, name) }

    fun renameGroup(groupId: String, name: String) = guardedWrite("Renamed") { com.sporez.actualist.core.budget.CategoryWrites(database).renameGroup(groupId, name) }

    /** One step up (-1) or down (+1) inside the category's group. */
    fun moveCategoryBy(categoryId: String, delta: Int) = guardedWrite("Moved") { com.sporez.actualist.core.budget.CategoryWrites(database).moveCategoryBy(categoryId, delta) }

    /** Moves a category to the end of another group. */
    fun moveCategoryToGroup(categoryId: String, groupId: String) = guardedWrite("Moved") {
        com.sporez.actualist.core.budget.CategoryWrites(database).moveCategory(categoryId, groupId, null)
    }

    fun moveGroupBy(groupId: String, delta: Int) = guardedWrite("Moved") { com.sporez.actualist.core.budget.CategoryWrites(database).moveGroupBy(groupId, delta) }

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

    /** Holdable money and the hold in force for the shown month (Actualist's hold review); null when unreadable. */
    suspend fun holdReview(): com.sporez.actualist.core.budget.HoldReview? = try {
        session.read { budget.holdReview(month.value) }
    } catch (error: Exception) {
        messages.show(error.message ?: "The hold could not be read.")
        null
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

    fun saveCategoryNote(categoryId: String, text: String) = guardedWrite("Notes saved") {
        com.sporez.actualist.core.notes.EntityNotes(database).write(com.sporez.actualist.core.notes.NoteTarget.Category(categoryId), text)
    }

    fun saveMonthNote(month: String, text: String) = guardedWrite("Notes saved") {
        com.sporez.actualist.core.notes.EntityNotes(database).write(com.sporez.actualist.core.notes.NoteTarget.BudgetMonth(month), text)
    }

    /** Sync progress of the open budget: a failure about a missing password means its key must be re-entered. */
    val syncStatus: StateFlow<com.sporez.actualist.core.store.SyncStatus> get() = session.syncStatus

    /** Saves the encrypted budget's key from [password] and syncs again; false (with a message) when it did not work. */
    suspend fun unlock(server: com.sporez.actualist.android.sync.ServerConnection, password: String): Boolean = try {
        server.unlockBudget(session, password)
        true
    } catch (error: Exception) {
        messages.show(error.message ?: "The budget could not be unlocked.")
        false
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

    /** A write Actualist disables while Sample Values is on (rename, reorder, notes): refused with a message instead. */
    private fun guardedWrite(done: String, block: BudgetSession.() -> Any?) {
        if (SampleValues.active) messages.show(if (done == "Notes saved") SampleValues.NOTES_HIDDEN_TITLE else SampleValues.CATEGORY_WRITE_BLOCKED) else write(done, block)
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
