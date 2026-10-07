package com.tradingplatform.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tradingplatform.app.MainActivity
import com.tradingplatform.app.di.WidgetEntryPoint
import com.tradingplatform.app.ui.navigation.Screen
import com.tradingplatform.app.ui.screens.vigie.VIGIE_TITLE
import dagger.hilt.android.EntryPointAccessors

/**
 * Widget Vigie « Refuge » (2x1 minimum) — veille coïncidente de la tension de marché, jamais une
 * prévision.
 *
 * Affiche (lu UNIQUEMENT dans Room via `WidgetEntryPoint.vigieDao()`, aucun appel réseau) :
 * score + niveau, famille la plus tendue (abrégée), refuge, et l'horodatage de sync avec le badge
 * « périmé · ». Aucune ligne → « Pas encore de donnée », jamais un niveau ni du vert. Donnée
 * publique : pas de garde admin, indépendant du réglage de compte `showVigie`.
 * Tap → ouvre l'app sur l'écran Vigie ([MainActivity.EXTRA_NAVIGATE_TO]).
 */
class VigieWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        val model = vigieWidgetModel(ep.vigieDao().get())

        val openVigie = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, Screen.Vigie.route)
        }

        provideContent {
            GlanceTheme {
                VigieWidgetContent(model = model, openVigie = openVigie)
            }
        }
    }

    companion object {
        /**
         * `true` si au moins une instance du widget est posée sur un écran d'accueil — le
         * [WidgetUpdateWorker] n'appelle `v1/vigie` que dans ce cas (le cache Room ne sert qu'au widget).
         */
        fun isPlaced(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, VigieWidgetReceiver::class.java))
            return ids.isNotEmpty()
        }
    }
}

@Composable
private fun VigieWidgetContent(model: VigieWidgetModel, openVigie: Intent) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(12.dp)
            .clickable(actionStartActivity(openVigie)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = VIGIE_TITLE,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            model.refugeText?.let {
                Text(
                    text = it,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                )
            }
        }

        if (!model.hasData) {
            Text(
                text = model.levelLabel, // « Pas encore de donnée » — neutre
                style = TextStyle(color = model.tone.widgetTone().provider(), fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
            return@Column
        }

        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = model.scoreText,
                style = TextStyle(
                    color = model.tone.widgetTone().provider(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = GlanceModifier.padding(end = 8.dp),
            )
            Text(
                text = model.levelLabel,
                style = TextStyle(
                    color = model.tone.widgetTone().provider(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }

        model.stressedText?.let {
            Text(
                text = it,
                style = TextStyle(
                    color = if (model.masksStress) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = if (model.masksStress) FontWeight.Bold else FontWeight.Normal,
                ),
                maxLines = 1,
            )
        }

        model.syncText?.let {
            Text(
                text = it,
                style = TextStyle(
                    color = if (model.isStale) {
                        androidx.glance.color.ColorProvider(day = WidgetColors.StaleDay, night = WidgetColors.StaleNight)
                    } else {
                        GlanceTheme.colors.onSurfaceVariant
                    },
                    fontSize = 11.sp,
                ),
                maxLines = 1,
            )
        }
    }
}
