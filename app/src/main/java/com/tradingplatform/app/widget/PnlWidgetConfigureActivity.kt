package com.tradingplatform.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.TradingPlatformTheme
import kotlinx.coroutines.launch

/**
 * Configuration activity for PnlWidget.
 *
 * Allows the user to select the P&L period to display:
 * - Jour (day)
 * - Semaine (week)
 * - Mois (month)
 *
 * Shows a visual preview of what the widget will look like.
 */
class PnlWidgetConfigureActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            TradingPlatformTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    var selectedPeriod by remember {
                        mutableStateOf(
                            PnlWidget.readConfiguredPeriod(
                                this@PnlWidgetConfigureActivity,
                                appWidgetId,
                            )
                        )
                    }
                    val coroutineScope = rememberCoroutineScope()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(Spacing.xl),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Configurer le widget P&L",
                            style = MaterialTheme.typography.titleLarge,
                        )

                        Spacer(modifier = Modifier.height(Spacing.sm))

                        Text(
                            text = "Choisissez la période à afficher",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(Spacing.xl))

                        // Period preview card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Spacing.lg),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = "P&L ${PnlWidget.periodDisplayLabel(selectedPeriod)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(Spacing.sm))
                                Text(
                                    text = "+1 250,00 €",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "+2,35%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.height(Spacing.xs))
                                Text(
                                    text = "Aperçu",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(Spacing.xl))

                        // Period selection
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            PnlWidget.AVAILABLE_PERIODS.forEach { period ->
                                val isSelected = period == selectedPeriod
                                Card(
                                    onClick = { selectedPeriod = period },
                                    modifier = Modifier.weight(1f),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                                    border = if (isSelected)
                                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                    else
                                        null,
                                ) {
                                    Text(
                                        text = PnlWidget.periodDisplayLabel(period),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                horizontal = Spacing.sm,
                                                vertical = Spacing.md,
                                            ),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(Spacing.xxl))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { finish() }) {
                                Text("Annuler")
                            }

                            Button(
                                onClick = {
                                    PnlWidget.saveConfiguredPeriod(
                                        context = this@PnlWidgetConfigureActivity,
                                        appWidgetId = appWidgetId,
                                        period = selectedPeriod,
                                    )

                                    coroutineScope.launch {
                                        try {
                                            val manager = GlanceAppWidgetManager(
                                                this@PnlWidgetConfigureActivity
                                            )
                                            val glanceId = manager.getGlanceIdBy(appWidgetId)
                                            PnlWidget().update(
                                                this@PnlWidgetConfigureActivity,
                                                glanceId,
                                            )
                                        } catch (_: Exception) {
                                            // Non-bloquant
                                        }
                                    }

                                    val resultIntent = Intent().apply {
                                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                    }
                                    setResult(RESULT_OK, resultIntent)
                                    finish()
                                },
                            ) {
                                Text("Confirmer")
                            }
                        }
                    }
                }
            }
        }
    }
}
