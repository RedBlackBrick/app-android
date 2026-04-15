package com.tradingplatform.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.tradingplatform.app.ui.theme.TradingNumbers
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * Affiche un montant monétaire neutre (sans signe positif/négatif coloré).
 *
 * Toujours en couleur [MaterialTheme.colorScheme.onSurface], police JetBrains Mono,
 * aligné à droite avec tabular numbers (tnum).
 *
 * Pour les P&L avec couleur dynamique selon le signe, utiliser [PnlText].
 *
 * TalkBack : "Valeur : 50 000,00 €"
 *
 * Usage :
 * ```kotlin
 * MoneyText(amount = portfolio.totalValue)
 * MoneyText(amount = position.entryPrice, decimals = 4)
 * MoneyText(
 *     amount = account.balance,
 *     style = MaterialTheme.typography.titleLarge,
 * )
 * ```
 */
@Composable
fun MoneyText(
    amount: BigDecimal,
    modifier: Modifier = Modifier,
    currencySymbol: String = "€",
    decimals: Int = 2,
    style: TextStyle = TradingNumbers.bodyLarge,
) {
    val formatted = formatMoneyAmount(amount, currencySymbol, decimals)

    Text(
        text = formatted,
        style = style.copy(
            textAlign = TextAlign.End,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.End,
        modifier = modifier.semantics {
            contentDescription = "Valeur : $formatted"
        },
    )
}

// ── Helper de formatage ────────────────────────────────────────────────────────

/**
 * Formate un montant monétaire avec groupement des milliers, [decimals] décimales
 * et symbole monétaire.
 *
 * Exemple : 50000.00 → "50 000,00 €", 1234.5678 (decimals=4) → "1 234,5678 €"
 */
internal fun formatMoneyAmount(
    amount: BigDecimal,
    currencySymbol: String,
    decimals: Int = 2,
): String {
    val format = NumberFormat.getNumberInstance(Locale.FRENCH).apply {
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
    }
    return "${format.format(amount)} $currencySymbol"
}
