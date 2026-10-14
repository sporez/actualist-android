package com.sporez.actualist.core.templates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The note-managed template staleness guard. Port of Actualist `BudgetDatabase+TemplateNoteSync`
 * and `BudgetTemplateCategoryLock.isNoteManaged`.
 *
 * Actual regenerates `categories.goal_def` from the category note's `#template` / `#goal`
 * directives before applying templates. Actualist applies the stored `goal_def` directly, so a
 * note-managed definition can be stale relative to a synced note. Before applying, any
 * note-managed `goal_def` whose note no longer matches it is refused. UI-managed templates
 * (`template_settings.source == "ui"`) are never stale.
 */
object TemplateNoteGuard {
    class Stale(val categoryId: String, val reason: String)

    /** `template_settings.source`, trimmed, or null when absent or unreadable. */
    fun templateSource(raw: String?): String? {
        if (raw == null) return null
        val parsed = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (error: Exception) {
            null
        }
        val obj = parsed ?: return null
        val source = obj["source"] as? JsonPrimitive ?: return null
        if (!source.isString) return null
        return source.content.trim().takeIf { it.isNotEmpty() }
    }

    fun isNoteManaged(source: String?, noteHasDirectives: Boolean): Boolean {
        val normalized = source?.trim()?.lowercase()
        if (normalized == "ui") return false
        if (normalized == "note" || normalized == "notes") return true
        return noteHasDirectives
    }

    /** Categories in [goalDefsRaw] whose note-managed `goal_def` is stale against the current category note. */
    fun staleCategories(reads: TemplateReads, goalDefsRaw: Map<String, String>): List<Stale> {
        if (goalDefsRaw.isEmpty()) return emptyList()
        val notes = reads.categoryNotes()
        val sources = reads.categoryTemplateSources()
        val stale = mutableListOf<Stale>()
        for ((categoryId, json) in goalDefsRaw) {
            val entries = try {
                TemplateEntryDecoder.decode(json)
            } catch (error: UnsupportedTemplateException) {
                // An undecodable goal_def cannot be applied safely; the apply path reports it as an
                // unsupported template, so it is not stale here.
                continue
            }
            val noteDirectives = TemplateNoteParser.directives(notes[categoryId] ?: "")
            if (!isNoteManaged(sources[categoryId], noteDirectives.isNotEmpty())) continue
            TemplateNoteParser.stalenessReason(noteDirectives, entries)?.let { stale.add(Stale(categoryId, it)) }
        }
        return stale
    }
}
