package com.tradingplatform.app.widget

import androidx.glance.appwidget.GlanceAppWidgetReceiver

class PnlWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = PnlWidget()
}
