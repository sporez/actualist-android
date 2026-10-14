package com.sporez.actualist.core.reports

/** The order of the Reports screen's cards: the saved order over the defaults, tolerant of unknown or missing ids. */
object ReportCardOrder {
    val defaults: List<String> = listOf(
        "net-worth",
        "cash-flow",
        "month-comparison",
        "spending",
        "budget-comparison",
        "three-month-average",
        "transaction-calendar",
    )

    /** Known saved ids once each, in saved order, then any card the saved list lacks in its default place. */
    fun resolve(saved: List<String>): List<String> {
        val known = saved.filter { it in defaults }.distinct()
        return known + defaults.filter { it !in known }
    }

    /** [id] moved by [delta] places (negative is up), staying inside the list. */
    fun move(order: List<String>, id: String, delta: Int): List<String> {
        val from = order.indexOf(id)
        if (from < 0) return order
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (to == from) return order
        val result = order.toMutableList()
        result.removeAt(from)
        result.add(to, id)
        return result
    }

    fun encode(order: List<String>): String = order.joinToString(",")

    fun decode(text: String?): List<String> = text.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
