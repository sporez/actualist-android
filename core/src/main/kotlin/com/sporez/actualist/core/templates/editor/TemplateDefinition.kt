package com.sporez.actualist.core.templates.editor

import com.sporez.actualist.core.templates.TemplateCalendar
import com.sporez.actualist.core.templates.TemplateEngine
import com.sporez.actualist.core.templates.TemplateEntry
import com.sporez.actualist.core.templates.TemplateEntryDecoder
import java.time.LocalDate

/** Why a stored definition cannot be opened in the editor (`BudgetTemplateDefinition.ParseFailure`). */
sealed class TemplateDefinitionParseFailure(message: String) : Exception(message) {
    class Unreadable : TemplateDefinitionParseFailure("unreadable template definition")
    class UnsupportedField(val field: String) : TemplateDefinitionParseFailure("unsupported template field $field")
    class UnsupportedType(val type: String) : TemplateDefinitionParseFailure("unsupported template $type")
}

/**
 * Shared definition facade for the editor and the write path. Port of `BudgetTemplateDefinition`. The apply
 * path decodes `TemplateEntry`; the editor uses its own strict codec ([TemplateEditorCodec]) so authoring
 * metadata and unknown-field safety are not lost at the boundary.
 *
 * Every function that needs "now" takes the civil [LocalDate] (Actualist takes a `Date`).
 */
object TemplateDefinition {
    const val DEFAULT_PRIORITY = TemplateDraftEntry.DEFAULT_PRIORITY

    fun firstDayOfCurrentMonth(today: LocalDate): String =
        TemplateCalendar.monthId(today.year * 100 + today.monthValue) + "-01"

    /** The apply engine's decode of a stored definition; null when it cannot be decoded. A blank or `null` document is an empty list. */
    fun parseEntries(json: String?): List<TemplateEntry>? {
        if (json == null) return emptyList()
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == "null") return emptyList()
        return try {
            TemplateEntryDecoder.decode(trimmed)
        } catch (error: Exception) {
            null
        }
    }

    /**
     * Editor entries use one normalized list. Strictly decodes known fields first; null means the whole
     * definition must remain view-only.
     */
    fun drafts(json: String?, today: LocalDate): List<TemplateDraftEntry>? = TemplateEditorCodec.decodeEditor(json, today)

    /** Strictly decodes and normalizes the complete pinned Actual catalog. */
    fun normalizedDrafts(json: String?, today: LocalDate): List<TemplateDraftEntry> =
        TemplateEditorCodec.decodeNormalized(json, today)

    fun encode(entries: List<TemplateDraftEntry>): String = TemplateEditorCodec.encode(entries)

    fun isEditorEditableJson(json: String?, today: LocalDate = LocalDate.now()): Boolean {
        val entries = TemplateEditorCodec.decodeEditor(json, today) ?: return false
        return entries.size <= TemplateEngine.Bounds.MAXIMUM_ENTRIES_PER_CATEGORY &&
            entries.all { TemplateEditorCodec.isEditorReady(it) }
    }
}
