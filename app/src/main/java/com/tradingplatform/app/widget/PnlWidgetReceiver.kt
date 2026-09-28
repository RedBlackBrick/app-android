package com.tradingplatform.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class PnlWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = PnlWidget()

    /**
     * Supprime la période configurée des instances retirées de l'écran d'accueil — sinon
     * [WidgetUpdateWorker] continuerait à synchroniser leur période à chaque cycle.
     */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { PnlWidget.clearConfiguredPeriod(context, it) }
    }
}
