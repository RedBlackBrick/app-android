package com.tradingplatform.app.widget

import androidx.glance.appwidget.GlanceAppWidgetReceiver

class SystemStatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = SystemStatusWidget()
}
