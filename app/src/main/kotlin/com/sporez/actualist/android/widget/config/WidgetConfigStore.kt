package com.sporez.actualist.android.widget.config

import android.content.Context
import com.sporez.actualist.core.widgets.WidgetChoices

/** Per placed widget (appWidgetId): the ordered ids its configuration screen saved. */
class WidgetConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("widget_config", Context.MODE_PRIVATE)

    fun get(appWidgetId: Int): List<String>? = WidgetChoices.decode(prefs.getString(key(appWidgetId), null))

    fun set(appWidgetId: Int, ids: List<String>) {
        prefs.edit().putString(key(appWidgetId), WidgetChoices.encode(ids)).apply()
    }

    fun clear(appWidgetId: Int) {
        prefs.edit().remove(key(appWidgetId)).apply()
    }

    private fun key(appWidgetId: Int) = "widget_$appWidgetId"
}
