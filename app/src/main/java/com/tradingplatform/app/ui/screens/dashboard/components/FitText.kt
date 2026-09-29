package com.tradingplatform.app.ui.screens.dashboard.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** Facteur de réduction appliqué à chaque passe de mise en page qui déborde encore. */
private const val SHRINK_FACTOR = 0.92f

/**
 * Texte d'UNE seule ligne qui rétrécit jusqu'à tenir dans la largeur disponible (plancher
 * [minFontSize]) au lieu d'être tronqué ou coupé au milieu d'un nombre. Sert aux montants (NAV,
 * KPI, valeurs de portefeuille) : à 360 dp avec une police à 130 %, « 103 475,29 € » en 32 sp mono
 * dépasse 296 dp ; un montant tronqué (« 103 47… ») serait trompeur.
 *
 * Le [style] doit déjà être numérique (`asNumeric()`) quand il s'agit d'un montant. La taille
 * repart de celle du style quand le nombre de caractères change (nouvelle valeur plus longue).
 */
@Composable
internal fun FitText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    minFontSize: TextUnit = 12.sp,
) {
    var fontSize by remember(text.length, style) { mutableStateOf(style.fontSize) }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { layout ->
            if (layout.didOverflowWidth && fontSize.value > minFontSize.value) {
                fontSize = maxOf(minFontSize.value, fontSize.value * SHRINK_FACTOR).sp
            }
        },
        style = style,
    )
}
