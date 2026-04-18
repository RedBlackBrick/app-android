package com.tradingplatform.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.tradingplatform.app.ui.theme.jetBrainsMonoFamily
import com.tradingplatform.app.ui.theme.pnlColor
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/**
 * Affiche une valeur P&L avec couleur dynamique selon le signe.
 *
 * - Positif : [LocalExtendedColors.current.pnlPositive] (emerald)
 * - Négatif : [LocalExtendedColors.current.pnlNegative] (rose)
 * - Zéro    : [MaterialTheme.colorScheme.onSurface]
 *
 * Police JetBrains Mono avec tabular numbers (tnum), alignée à droite.
 * TalkBack : "Gain non réalisé : +1 250,00 €" ou "Perte : -300,00 €"
 *
 * Usage :
 * ```kotlin
 * PnlText(value = position.unrealizedPnl)
 * PnlText(value = position.realizedPnl, style = MaterialTheme.typography.titleMedium)
 * ```
 */
@Composable
fun PnlText(
    value: BigDecimal,
    modifier: Modifier = Modifier,
    currencySymbol: String = "€",
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val color = pnlColor(value)

    val formatted = formatPnlAmount(value, currencySymbol)
    val verboseDescription = buildPnlDescription(value, currencySymbol)

    Text(
        text = formatted,
        style = style.copy(
            fontFamily = jetBrainsMonoFamily,
            fontFeatureSettings = "tnum",
            textAlign = TextAlign.End,
        ),
        color = color,
        textAlign = TextAlign.End,
        modifier = modifier.semantics {
            contentDescription = verboseDescription
        },
    )
}

// ── Helpers de formatage ──────────────────────────────────────────────────────

/**
 * Formate une valeur P&L avec signe, groupement des milliers, 2 décimales et symbole monétaire.
 * Exemple : "+1 250,00 €", "-300,00 €", "0,00 €"
 */
internal fun formatPnlAmount(value: BigDecimal, currencySymbol: String): String {
    val format = NumberFormat.getNumberInstance(Locale.FRENCH).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    val prefix = when {
        value > BigDecimal.ZERO -> "+"
        else -> ""
    }
    return "$prefix${format.format(value)} $currencySymbol"
}

/**
 * Génère une description accessible pour TalkBack — forme verbose lue par les synthèses
 * vocales sans ambigüité (le signe "+"/"-" est souvent omis par TalkBack en mode rapide,
 * ce qui transforme "-300" en "300" lu comme un gain).
 *
 * Positif : "Gain de 1 250,00 €"
 * Négatif : "Perte de 300,00 €"
 * Zéro    : "Aucun gain ni perte, 0,00 €"
 */
internal fun buildPnlDescription(value: BigDecimal, currencySymbol: String): String {
    // Utilise la valeur absolue pour éviter de lire le signe — le mot "Gain"/"Perte"
    // porte déjà l'information directionnelle et est moins ambigu que "moins trois cents".
    val absolute = value.abs()
    return when {
        value > BigDecimal.ZERO ->
            "Gain de ${formatMoneyAmount(absolute, currencySymbol)}"
        value < BigDecimal.ZERO ->
            "Perte de ${formatMoneyAmount(absolute, currencySymbol)}"
        else ->
            "Aucun gain ni perte, ${formatMoneyAmount(absolute, currencySymbol)}"
    }
}
