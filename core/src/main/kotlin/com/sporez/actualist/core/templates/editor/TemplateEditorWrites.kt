package com.sporez.actualist.core.templates.editor

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncValue
import com.sporez.actualist.core.templates.TemplateNoteGuard
import com.sporez.actualist.core.templates.TemplateNoteParser
import com.sporez.actualist.core.templates.TemplateReads
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Whether a category's templates can be edited by the editor. Unknown fields lock the entire category to
 * prevent data loss; note-managed, unsupported, missing-column and stale-note categories lock whole.
 * Port of `BudgetTemplateCategoryLock`.
 */
sealed class TemplateEditorLock {
    enum class Reason(val testerFacingReason: String) {
        MISSING_COLUMNS("This budget cannot store templates."),
        NOTE_MANAGED("This category's templates come from the category note, so they can't be edited here."),
        STALE_NOTES("This category's templates no longer match the category note."),
        UNSUPPORTED_TYPE("This category uses a template type that can't be edited yet."),
    }

    data object Editable : TemplateEditorLock()

    data class ReadOnly(val reason: Reason) : TemplateEditorLock()

    val isEditable: Boolean get() = this is Editable

    /** User-readable explanation when read-only, null when editable. */
    val reasonText: String? get() = (this as? ReadOnly)?.reason?.testerFacingReason

    companion object {
        /**
         * [source] is Actual's `template_settings.source` (`ui` / `notes`) or the fixture spelling `note`.
         * A missing source falls back to note-directive presence, matching the apply stale-note guard.
         */
        fun evaluate(
            hasGoalDefColumn: Boolean,
            hasTemplateSettingsColumn: Boolean,
            source: String?,
            noteHasDirectives: Boolean,
            isStale: Boolean,
            goalDefJson: String?,
            today: LocalDate,
        ): TemplateEditorLock {
            if (!hasGoalDefColumn || !hasTemplateSettingsColumn) return ReadOnly(Reason.MISSING_COLUMNS)
            val parsed = TemplateDefinition.parseEntries(goalDefJson)
            // Actual defaults even unauthored categories to source=notes.
            if (parsed != null && parsed.isEmpty() && !noteHasDirectives && !isStale) return Editable
            if (TemplateNoteGuard.isNoteManaged(source, noteHasDirectives)) {
                return ReadOnly(if (isStale) Reason.STALE_NOTES else Reason.NOTE_MANAGED)
            }
            if (parsed == null) return ReadOnly(Reason.UNSUPPORTED_TYPE)
            if (!TemplateDefinition.isEditorEditableJson(goalDefJson, today)) return ReadOnly(Reason.UNSUPPORTED_TYPE)
            return Editable
        }
    }
}

/** What the editor opens for one category. Port of `BudgetTemplateEditorSnapshot`. */
data class TemplateEditorSnapshot(
    val categoryId: String,
    val categoryName: String,
    val draft: TemplateEditorDraft,
    val lock: TemplateEditorLock,
    val schedules: List<TemplateScheduleOption>,
    val incomeCategories: List<TemplateIncomeOption>,
)

/**
 * Reads and writes a category's template through the editor. Port of Actualist's
 * `BudgetDatabase+TemplateEditor` and `BudgetDatabase+TemplateWrites` (`setCategoryTemplateMessages`).
 *
 * - `load(categoryId): TemplateEditorDraft?` the category's draft (empty when it has no template), or null when
 *   the category is missing or its templates cannot be edited here (see [lockFor]).
 * - `snapshot(categoryId, today): TemplateEditorSnapshot` the draft with its lock and the picker options;
 *   throws [LocalFirstException.InvalidLocalWrite] for a missing category.
 * - `save(categoryId, draft): List<SyncMessage>` validates, then commits `categories.goal_def` (null for an
 *   empty draft) and `categories.template_settings = {"source":"ui"}` as local CRDT cells. Writes nothing
 *   when neither changed. Throws [LocalFirstException.InvalidLocalWrite] with a user-readable reason for a
 *   locked category or an invalid draft. It does not apply budgets or write `cleanup_def`.
 */
class TemplateEditorWrites(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val reads = TemplateReads(database)
    private val sql get() = database.sql

    /** The calendar day [clock] reports in [zone]. */
    fun today(): LocalDate = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate()

    fun load(categoryId: String): TemplateEditorDraft? {
        val snapshot = try {
            snapshot(categoryId)
        } catch (error: LocalFirstException.InvalidLocalWrite) {
            return null
        }
        return if (snapshot.lock.isEditable) snapshot.draft else null
    }

    fun lockFor(categoryId: String): TemplateEditorLock? = try {
        snapshot(categoryId).lock
    } catch (error: LocalFirstException.InvalidLocalWrite) {
        null
    }

    fun snapshot(categoryId: String, today: LocalDate = today()): TemplateEditorSnapshot = sql.locked {
        val trimmed = categoryId.trim()
        if (trimmed.isEmpty() || !database.tableExists("categories")) throw missingCategory()
        val columns = database.columnSet("categories")
        val hasGoalDef = "goal_def" in columns
        val hasSettings = "template_settings" in columns
        val nameSelection = if ("name" in columns) "name" else "NULL"
        val goalSelection = if (hasGoalDef) "goal_def" else "NULL"
        val settingsSelection = if (hasSettings) "template_settings" else "NULL"
        val row = sql.queryOne(
            "SELECT id, $nameSelection AS name, $goalSelection AS goal_def, $settingsSelection AS template_settings " +
                "FROM categories WHERE id = ? AND ${BudgetDatabase.livePredicate(columns)} LIMIT 1",
            trimmed,
        ) ?: throw missingCategory()
        val name = row.string("name")?.trim() ?: ""
        val goalDef = row.string("goal_def")
        val lock = evaluateLock(trimmed, goalDef, row.string("template_settings"), hasGoalDef, hasSettings, today)
        val entries = TemplateDefinition.drafts(goalDef, today) ?: emptyList()
        val context = authoringContext(today)
        TemplateEditorSnapshot(trimmed, name, TemplateEditorDraft(entries), lock, context.schedules, context.incomeCategories)
    }

    fun authoringContext(today: LocalDate = today()): TemplateAuthoringContext = sql.locked {
        TemplateAuthoringContext(today, schedules(), incomeCategories())
    }

    fun save(categoryId: String, draft: TemplateEditorDraft): List<SyncMessage> {
        val trimmedId = categoryId.trim()
        val today = today()
        val committed = database.commitLocal { prepareSave(trimmedId, draft, today) to Unit }
        return committed.messages
    }

    private fun missingCategory() = LocalFirstException.InvalidLocalWrite("missing category")

    private fun invalid(reason: String) = LocalFirstException.InvalidLocalWrite(reason)

    private fun evaluateLock(
        categoryId: String,
        goalDef: String?,
        templateSettings: String?,
        hasGoalDef: Boolean,
        hasSettings: Boolean,
        today: LocalDate,
    ): TemplateEditorLock {
        val source = TemplateNoteGuard.templateSource(templateSettings)
        val note = reads.categoryNotes()[categoryId] ?: ""
        val noteHasDirectives = TemplateNoteParser.directives(note).isNotEmpty()
        return TemplateEditorLock.evaluate(
            hasGoalDefColumn = hasGoalDef,
            hasTemplateSettingsColumn = hasSettings,
            source = source,
            noteHasDirectives = noteHasDirectives,
            isStale = isStaleNoteManaged(categoryId, goalDef),
            goalDefJson = goalDef,
            today = today,
        )
    }

    private fun isStaleNoteManaged(categoryId: String, goalDef: String?): Boolean {
        if (goalDef == null) return false
        val trimmed = goalDef.trim()
        if (trimmed.isEmpty() || trimmed == "null") return false
        return TemplateNoteGuard.staleCategories(reads, mapOf(categoryId to trimmed)).any { it.categoryId == categoryId }
    }

    private fun prepareSave(categoryId: String, draft: TemplateEditorDraft, today: LocalDate): List<CellDraft> {
        if (categoryId.isEmpty() || !database.tableExists("categories")) throw missingCategory()
        val columns = database.columnSet("categories")
        if ("goal_def" !in columns || "template_settings" !in columns) {
            throw invalid(TemplateEditorLock.Reason.MISSING_COLUMNS.testerFacingReason)
        }
        val row = sql.queryOne(
            "SELECT id, goal_def, template_settings FROM categories WHERE id = ? AND ${BudgetDatabase.livePredicate(columns)} LIMIT 1",
            categoryId,
        ) ?: throw missingCategory()
        val currentGoalDef = row.string("goal_def")
        val currentSettings = row.string("template_settings")
        val source = TemplateNoteGuard.templateSource(currentSettings)
        val lock = evaluateLock(categoryId, currentGoalDef, currentSettings, true, true, today)
        if (lock is TemplateEditorLock.ReadOnly) throw invalid(lock.reason.testerFacingReason)

        val unsupported = TemplateEditorLock.Reason.UNSUPPORTED_TYPE.testerFacingReason
        val context = authoringContext(today)
        val issues = TemplateAuthoringValidation.issues(draft.entries, context)
        if (issues.isNotEmpty()) throw invalid(issues.first().message)

        val encoded = try {
            TemplateDefinition.encode(draft.entries)
        } catch (error: TemplateDefinitionParseFailure) {
            throw invalid(unsupported)
        }
        val incoming = normalizedGoalDef(encoded)
        if (TemplateDefinition.parseEntries(incoming) == null) throw invalid(unsupported)
        val reopened = TemplateDefinition.drafts(incoming, today) ?: throw invalid(unsupported)
        val reopenedIssues = TemplateAuthoringValidation.issues(reopened, context)
        if (reopenedIssues.isNotEmpty()) throw invalid(reopenedIssues.first().message)

        val drafts = mutableListOf<CellDraft>()
        if (normalizedGoalDef(currentGoalDef) != incoming) {
            drafts.add(CellDraft("categories", categoryId, "goal_def", SyncValue.textOrNull(incoming)))
        }
        if (source != "ui") {
            drafts.add(CellDraft("categories", categoryId, "template_settings", SyncValue.text(UI_TEMPLATE_SETTINGS_JSON)))
        }
        return drafts
    }

    /** Null for a missing, blank, `null` or empty-list document; otherwise the trimmed text. */
    private fun normalizedGoalDef(json: String?): String? {
        if (json == null) return null
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == "null") return null
        val parsed = TemplateDefinition.parseEntries(trimmed)
        if (parsed != null && parsed.isEmpty()) return null
        return trimmed
    }

    private fun incomeCategories(): List<TemplateIncomeOption> {
        val catalog = reads.incomeCatalog()
        return catalog.incomeCategoryIdsInOrder.mapNotNull { id ->
            val name = catalog.incomeCategoryNamesById[id] ?: return@mapNotNull null
            TemplateIncomeOption(id, name, id !in catalog.hiddenIncomeCategoryIds)
        }
    }

    private fun schedules(): List<TemplateScheduleOption> {
        if (!database.tableExists("schedules")) return emptyList()
        val columns = database.columnSet("schedules")
        if ("name" !in columns) return emptyList()
        val idSelection = if ("id" in columns) "id" else "NULL"
        val completedSelection = if ("completed" in columns) "completed" else "0"
        val rows = sql.query(
            "SELECT $idSelection AS id, name, $completedSelection AS completed FROM schedules " +
                "WHERE name IS NOT NULL AND ${BudgetDatabase.livePredicate(columns)}",
        )
        val options = mutableListOf<TemplateScheduleOption>()
        for (row in rows) {
            if (row.bool("completed")) continue
            val id = row.string("id")
            if (id == null || id.isEmpty()) continue
            val name = row.string("name")?.trim() ?: ""
            if (name.isEmpty()) continue
            options.add(TemplateScheduleOption(id, name))
        }
        return options.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    companion object {
        const val UI_TEMPLATE_SETTINGS_JSON = "{\"source\":\"ui\"}"
    }
}
