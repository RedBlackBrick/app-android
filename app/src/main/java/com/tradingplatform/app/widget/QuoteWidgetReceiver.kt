package com.tradingplatform.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class QuoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = QuoteWidget()

    /**
     * Supprime le ticker configuré des instances retirées de l'écran d'accueil — sinon
     * [WidgetUpdateWorker] continuerait à synchroniser leur symbole à chaque cycle
     * ([QuoteWidget.configuredSymbols]).
     */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { QuoteWidget.clearConfiguredSymbol(context, it) }
    }
}
