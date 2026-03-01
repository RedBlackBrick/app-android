package com.tradingplatform.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.tradingplatform.app.ui.theme.TradingPlatformTheme
import kotlinx.coroutines.launch

/**
 * Activité de configuration du QuoteWidget.
 *
 * Affichée automatiquement par Android lors de l'ajout du widget sur l'écran d'accueil.
 * L'utilisateur saisit le ticker (ex: AAPL, TSLA) à afficher pour cette instance.
 *
 * Le ticker est persisté dans SharedPreferences (keyed par appWidgetId) :
 * ```
 * prefs.edit().putString("ticker_$appWidgetId", symbol).apply()
 * ```
 *
 * SharedPreferences (pas GlanceStateDefinition) car c'est une valeur scalaire configurée
 * une seule fois — plus simple et sans overhead de sérialisation (CLAUDE.md §11).
 *
 * Termine avec RESULT_OK pour que le widget s'affiche. Si l'utilisateur annule,
 * RESULT_CANCELED est retourné et Android ne crée pas le widget.
 */
class QuoteWidgetConfigureActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Résultat par défaut : CANCELED (au cas où l'utilisateur quitte sans confirmer)
        setResult(RESULT_CANCELED)

        // Récupérer l'appWidgetId depuis l'intent
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Si appWidgetId invalide, terminer immédiatement
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
                    var ticker by remember {
                        mutableStateOf(
                            QuoteWidget.readConfiguredSymbol(this@QuoteWidgetConfigureActivity, appWidgetId)
                                ?: ""
                        )
                    }
                    var isError by remember { mutableStateOf(false) }
                    val keyboardController = LocalSoftwareKeyboardController.current
                    val coroutineScope = rememberCoroutineScope()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Configurer le widget Quote",
                            style = MaterialTheme.typography.titleLarge,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Saisissez le symbole boursier à afficher (ex : AAPL, TSLA, BTC-USD)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = ticker,
                            onValueChange = { input ->
                                // Convertir en majuscules, supprimer les espaces
                                ticker = input.uppercase().replace(" ", "")
                                isError = false
                            },
                            label = { Text("Symbole (ex: AAPL)") },
                            isError = isError,
                            supportingText = if (isError) {
                                { Text("Veuillez saisir un symbole valide") }
                            } else null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { keyboardController?.hide() }
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = { finish() },
                            ) {
                                Text("Annuler")
                            }

                            Button(
                                onClick = {
                                    val symbol = ticker.trim()
                                    if (symbol.isBlank()) {
                                        isError = true
                                        return@Button
                                    }

                                    // Persister le symbole dans SharedPreferences
                                    QuoteWidget.saveConfiguredSymbol(
                                        context = this@QuoteWidgetConfigureActivity,
                                        appWidgetId = appWidgetId,
                                        symbol = symbol,
                                    )

                                    // Déclencher la première mise à jour du widget
                                    coroutineScope.launch {
                                        try {
                                            val manager = GlanceAppWidgetManager(
                                                this@QuoteWidgetConfigureActivity
                                            )
                                            val glanceId = manager.getGlanceIdBy(appWidgetId)
                                            QuoteWidget().update(
                                                this@QuoteWidgetConfigureActivity,
                                                glanceId,
                                            )
                                        } catch (_: Exception) {
                                            // Non-bloquant — le Worker mettra à jour le widget
                                        }
                                    }

                                    // Retourner RESULT_OK avec l'appWidgetId
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

    override fun onDestroy() {
        super.onDestroy()
        // Nettoyer la config si le widget n'a pas été confirmé
        // (RESULT_CANCELED → Android n'ajoute pas le widget, on nettoie la clé orpheline)
    }
}
