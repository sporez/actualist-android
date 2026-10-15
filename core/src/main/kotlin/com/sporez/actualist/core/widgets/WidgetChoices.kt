package com.sporez.actualist.core.widgets

/** Actualist's widget families, from a launcher cell size in dp. */
enum class WidgetSizeClass(val categoryRows: Int, val accountRows: Int, val transactionRows: Int) {
    SMALL(1, 2, 2),
    MEDIUM(3, 4, 3),
    LARGE(8, 8, 8),
    ;

    companion object {
        fun of(widthDp: Int, heightDp: Int): WidgetSizeClass = when {
            heightDp >= 200 -> LARGE
            widthDp >= 200 -> MEDIUM
            else -> SMALL
        }
    }
}

/** What a user picked for one widget: an ordered list of ids, stored as text and shaped against what still exists. */
object WidgetChoices {
    /** Quick Actions holds four (WidgetQuickActions.capacity); Category Balances up to sixteen. */
    const val QUICK_ACTION_CAPACITY = 4
    const val CATEGORY_LIMIT = 16
    const val ACCOUNT_LIMIT = 16

    fun encode(ids: List<String>): String = ids.joinToString("\n")

    fun decode(text: String?): List<String>? =
        text?.split('\n')?.filter { it.isNotBlank() }?.distinct()?.takeIf { it.isNotEmpty() }

    /** The chosen items still present, in the chosen order; [fallback] when nothing chosen survives. */
    fun <T> pick(chosen: List<String>?, available: List<T>, limit: Int, fallback: List<T>, id: (T) -> String): List<T> {
        val byId = available.associateBy(id)
        val picked = chosen.orEmpty().mapNotNull { byId[it] }.distinct().take(limit)
        return picked.ifEmpty { fallback.take(limit) }
    }

    /** Quick action ids: known chosen ones in order, topped up with [defaults] to [capacity] (as Actualist does). */
    fun quickActions(chosen: List<String>?, known: Set<String>, defaults: List<String>, capacity: Int = QUICK_ACTION_CAPACITY): List<String> =
        (chosen.orEmpty().filter { it in known } + defaults).distinct().take(capacity)

    /** [selection] with [id] toggled: removed when present, else appended unless [limit] is reached. */
    fun toggle(selection: List<String>, id: String, limit: Int): List<String> = when {
        id in selection -> selection - id
        selection.size >= limit -> selection
        else -> selection + id
    }
}

/** A text sparkline for the Net Worth trend (Glance has no canvas). */
object Sparkline {
    private const val BLOCKS = "▁▂▃▄▅▆▇█"

    fun render(values: List<Long>): String {
        if (values.isEmpty()) return ""
        val low = values.min()
        val high = values.max()
        if (low == high) return BLOCKS[3].toString().repeat(values.size)
        val span = (high - low).toDouble()
        return values.map { BLOCKS[(((it - low) / span) * (BLOCKS.length - 1)).toInt().coerceIn(0, BLOCKS.length - 1)] }.joinToString("")
    }
}
