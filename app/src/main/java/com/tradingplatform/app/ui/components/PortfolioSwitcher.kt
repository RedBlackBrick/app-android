package com.tradingplatform.app.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.TradingPlatformTheme

// ── Logique pure (testée en JVM : PortfolioSwitcherLogicTest) ─────────────────────────────────

/** Libellé du bouton tant que le portefeuille actif n'est pas identifié dans la liste. */
internal const val DEFAULT_SWITCHER_LABEL = "Portefeuille"

/** Nom affiché d'un portefeuille dont le nom serait vide. */
internal const val UNNAMED_PORTFOLIO_LABEL = "Portefeuille sans nom"

/** Le sélecteur n'a de sens qu'à partir de 2 portefeuilles ; sinon il ne rend rien. */
internal fun shouldShowPortfolioSwitcher(portfolios: List<Portfolio>): Boolean = portfolios.size >= 2

/** Nom à afficher pour [portfolio] (jamais vide). */
internal fun portfolioDisplayName(portfolio: Portfolio): String =
    portfolio.name.trim().ifEmpty { UNNAMED_PORTFOLIO_LABEL }

/** Portefeuille actif de la liste, `null` si [activeId] est inconnu ou absent de la liste. */
internal fun activePortfolioOrNull(portfolios: List<Portfolio>, activeId: String?): Portfolio? =
    if (activeId == null) null else portfolios.firstOrNull { it.id == activeId }

/** Texte du bouton compact : nom du portefeuille actif, ou [DEFAULT_SWITCHER_LABEL]. */
internal fun activePortfolioLabel(portfolios: List<Portfolio>, activeId: String?): String =
    activePortfolioOrNull(portfolios, activeId)?.let(::portfolioDisplayName) ?: DEFAULT_SWITCHER_LABEL

/** Description TalkBack du bouton : le nom complet (le libellé visible peut être tronqué). */
internal fun switcherButtonDescription(portfolios: List<Portfolio>, activeId: String?): String {
    val active = activePortfolioOrNull(portfolios, activeId)
        ?: return "Changer de portefeuille"
    return "Portefeuille actif : ${portfolioDisplayName(active)}. Changer de portefeuille"
}

/** Description TalkBack d'une ligne du menu : nom, devise et état actif. */
internal fun portfolioItemDescription(portfolio: Portfolio, isActive: Boolean): String {
    val currency = portfolio.currency.trim()
    val base = if (currency.isEmpty()) {
        portfolioDisplayName(portfolio)
    } else {
        "${portfolioDisplayName(portfolio)}, devise $currency"
    }
    return if (isActive) "$base, portefeuille actif" else base
}

// ── Composables ───────────────────────────────────────────────────────────────────────────────

/** Cible tactile minimale (Material / TalkBack). */
private val MinTouchTarget = 48.dp

/**
 * Largeur maximale du bouton dans une `TopAppBar` : à 360 dp et police 130 %, il faut laisser
 * la place au titre (« Portefeuille » ≈ 150 dp) et à la roue Réglages (48 dp).
 */
private val SwitcherMaxWidth = 128.dp

/**
 * Sélecteur du portefeuille actif, à placer dans les `actions` d'une `TopAppBar`.
 *
 * Rend **rien** si le compte a moins de 2 portefeuilles. Sinon : un bouton compact (nom du
 * portefeuille actif tronqué proprement + flèche, cible ≥ 48 dp) qui ouvre un [DropdownMenu]
 * (nom + devise, coche sur l'actif). Choisir un autre portefeuille appelle
 * [PortfolioSwitcherViewModel.select] ; les écrans reliés à `ObserveActivePortfolioUseCase`
 * se remettent à zéro puis se rechargent.
 */
@Composable
fun PortfolioSwitcher(
    modifier: Modifier = Modifier,
    viewModel: PortfolioSwitcherViewModel = hiltViewModel(),
) {
    val portfolios by viewModel.portfolios.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()

    PortfolioSwitcherContent(
        portfolios = portfolios,
        activeId = activeId,
        onSelect = viewModel::select,
        modifier = modifier,
    )
}

/** Rendu sans ViewModel — testable et prévisualisable seul. */
@Composable
internal fun PortfolioSwitcherContent(
    portfolios: List<Portfolio>,
    activeId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (shouldShowPortfolioSwitcher(portfolios)) {
        var expanded by remember { mutableStateOf(false) }
        val buttonDescription = switcherButtonDescription(portfolios, activeId)

        Box(modifier = modifier) {
            TextButton(
                onClick = { expanded = true },
                contentPadding = PaddingValues(horizontal = Spacing.sm),
                modifier = Modifier
                    .widthIn(max = SwitcherMaxWidth)
                    .heightIn(min = MinTouchTarget)
                    .semantics { contentDescription = buttonDescription },
            ) {
                Text(
                    text = activePortfolioLabel(portfolios, activeId),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.md),
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                portfolios.forEach { portfolio ->
                    val isActive = portfolio.id == activeId
                    val itemDescription = portfolioItemDescription(portfolio, isActive)
                    DropdownMenuItem(
                        text = { PortfolioMenuLabel(portfolio = portfolio) },
                        onClick = {
                            expanded = false
                            if (!isActive) onSelect(portfolio.id)
                        },
                        // Emplacement toujours réservé : les noms restent alignés, la coche
                        // n'apparaît que sur le portefeuille actif.
                        trailingIcon = {
                            if (isActive) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        modifier = Modifier.semantics {
                            selected = isActive
                            contentDescription = itemDescription
                        },
                    )
                }
            }
        }
    }
}

/** Nom (jusqu'à 2 lignes) et devise d'un portefeuille dans le menu. */
@Composable
private fun PortfolioMenuLabel(portfolio: Portfolio, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = portfolioDisplayName(portfolio),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val currency = portfolio.currency.trim()
        if (currency.isNotEmpty()) {
            Text(
                text = currency,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────────────────────

private val previewPortfolios = listOf(
    Portfolio(id = "p1", name = "Compte principal", currency = "EUR"),
    Portfolio(id = "p2", name = "PEA", currency = "EUR"),
)

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun PortfolioSwitcherPreviewLight() {
    TradingPlatformTheme(darkTheme = false) {
        PortfolioSwitcherContent(portfolios = previewPortfolios, activeId = "p1", onSelect = {})
    }
}

@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PortfolioSwitcherPreviewDark() {
    TradingPlatformTheme(darkTheme = true) {
        PortfolioSwitcherContent(portfolios = previewPortfolios, activeId = "p2", onSelect = {})
    }
}
