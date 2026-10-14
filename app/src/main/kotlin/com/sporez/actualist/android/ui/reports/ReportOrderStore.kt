package com.sporez.actualist.android.ui.reports

import android.content.Context
import com.sporez.actualist.core.reports.ReportCardOrder

/** The Reports screen's card order, kept in its own preferences file apart from the app settings. */
class ReportOrderStore(context: Context) {
    private val prefs = context.getSharedPreferences("report_order", Context.MODE_PRIVATE)

    fun load(): List<String> = ReportCardOrder.resolve(ReportCardOrder.decode(prefs.getString(KEY, null)))

    fun save(order: List<String>) {
        prefs.edit().putString(KEY, ReportCardOrder.encode(order)).apply()
    }

    fun reset() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "order"
    }
}
