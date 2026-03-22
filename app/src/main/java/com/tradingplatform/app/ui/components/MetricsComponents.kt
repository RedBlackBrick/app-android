package com.tradingplatform.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import kotlin.math.roundToInt

// ── Thresholds ──────────────────────────────────────────────────────────────────

/**
 * Seuils d'alerte pour une métrique device.
 *
 * @param warn valeur >= warn affiche un avertissement (orange)
 * @param crit valeur >= crit affiche une alerte critique (rouge)
 */
data class MetricThresholds(
    val warn: Float,
    val crit: Float,
)

/** Seuils CPU : warn 70%, crit 90%. */
val CPU_THRESHOLDS = MetricThresholds(warn = 70f, crit = 90f)

/** Seuils mémoire : warn 70%, crit 90%. */
val MEMORY_THRESHOLDS = MetricThresholds(warn = 70f, crit = 90f)

/** Seuils température : warn 60 C, crit 75 C. */
val TEMPERATURE_THRESHOLDS = MetricThresholds(warn = 60f, crit = 75f)

/** Seuils disque : warn 70%, crit 85%. */
val DISK_THRESHOLDS = MetricThresholds(warn = 70f, crit = 85f)

// ── metricColor ─────────────────────────────────────────────────────────────────

/**
 * Retourne la couleur appropriée pour une valeur de métrique en fonction des seuils.
 *
 * - value >= crit : pnlNegative (rouge)
 * - value >= warn : warning (orange/ambre)
 * - value < warn  : pnlPositive (vert)
 * - null           : onSurfaceVariant (gris neutre)
 *
 * Utilise [LocalExtendedColors] pour respecter light/dark theme automatiquement.
 */
@Composable
fun metricColor(value: Float?, thresholds: MetricThresholds): Color {
    val extendedColors = LocalExtendedColors.current
    if (value == null) return MaterialTheme.colorScheme.onSurfaceVariant
    return when {
        value >= thresholds.crit -> extendedColors.pnlNegative
        value >= thresholds.warn -> extendedColors.warning
        else -> extendedColors.pnlPositive
    }
}

// ── MetricRow ───────────────────────────────────────────────────────────────────

/**
 * Ligne de métrique avec label, valeur textuelle et barre de progression colorée.
 *
 * Utilisé dans [EdgeDeviceDashboardScreen] pour la card "Ressources".
 *
 * @param label nom de la métrique (ex: "CPU", "Mémoire")
 * @param value valeur numérique (nullable si indisponible)
 * @param unit unité d'affichage (ex: "%", " C")
 * @param thresholds seuils warn/crit pour la couleur
 * @param progressMax valeur max pour la barre de progression (100 pour un pourcentage)
 */
@Composable
fun MetricRow(
    label: String,
    value: Float?,
    unit: String,
    thresholds: MetricThresholds,
    progressMax: Float,
    modifier: Modifier = Modifier,
) {
    val valueText = if (value != null) "${value.roundToInt()}$unit" else "—"
    val color = metricColor(value, thresholds)
    val progress = if (value != null) (value / progressMax).coerceIn(0f, 1f) else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$label : $valueText"
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = if (value != null) color else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (value != null) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = color,
                trackColor = color.copy(alpha = 0.15f),
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

// ── CompactHealthBar ────────────────────────────────────────────────────────────

/**
 * Trois mini barres de progression horizontales (CPU, RAM, Temp) en une Row compacte.
 *
 * Concu pour les cartes de la liste de devices — affiche un apercu rapide de la sante
 * du device sans prendre trop de place verticale.
 *
 * N'affiche rien si les trois valeurs sont null.
 *
 * @param cpuPct pourcentage CPU (0-100, nullable)
 * @param memoryPct pourcentage memoire (0-100, nullable)
 * @param temperature temperature en degres C (nullable)
 */
@Composable
fun CompactHealthBar(
    cpuPct: Float?,
    memoryPct: Float?,
    temperature: Float?,
    modifier: Modifier = Modifier,
) {
    // Ne rien afficher si aucune metrique n'est disponible
    if (cpuPct == null && memoryPct == null && temperature == null) return

    val cpuColor = metricColor(cpuPct, CPU_THRESHOLDS)
    val memColor = metricColor(memoryPct, MEMORY_THRESHOLDS)
    val tempColor = metricColor(temperature, TEMPERATURE_THRESHOLDS)

    val descriptionParts = buildList {
        if (cpuPct != null) add("CPU ${cpuPct.roundToInt()}%")
        if (memoryPct != null) add("RAM ${memoryPct.roundToInt()}%")
        if (temperature != null) add("Temp ${temperature.roundToInt()} degres")
    }
    val fullDescription = "Metriques : ${descriptionParts.joinToString(", ")}"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = fullDescription },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // CPU
        if (cpuPct != null) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CPU",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                LinearProgressIndicator(
                    progress = { (cpuPct / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = cpuColor,
                    trackColor = cpuColor.copy(alpha = 0.15f),
                    strokeCap = StrokeCap.Round,
                )
            }
        }

        // RAM
        if (memoryPct != null) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "RAM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                LinearProgressIndicator(
                    progress = { (memoryPct / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = memColor,
                    trackColor = memColor.copy(alpha = 0.15f),
                    strokeCap = StrokeCap.Round,
                )
            }
        }

        // Temperature
        if (temperature != null) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Temp",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                LinearProgressIndicator(
                    progress = { (temperature / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = tempColor,
                    trackColor = tempColor.copy(alpha = 0.15f),
                    strokeCap = StrokeCap.Round,
                )
            }
        }
    }
}

// ── HealthStatusBadge ───────────────────────────────────────────────────────────

/**
 * Badge intelligent indiquant l'etat de sante global du device.
 *
 * Logique :
 * - Si au moins une metrique depasse le seuil critique : affiche le nom de la metrique
 *   critique (ex: "CPU critique", "Temp critique"). Si plusieurs sont critiques, priorise
 *   dans l'ordre CPU > Memoire > Temperature > Disque.
 * - Si au moins une metrique depasse le seuil warn (sans critique) : "Avertissement"
 * - Sinon : "Sain"
 * - Si aucune metrique n'est disponible (toutes null) : ne rien afficher.
 *
 * @param cpuPct pourcentage CPU (nullable)
 * @param memoryPct pourcentage memoire (nullable)
 * @param temperature temperature en degres C (nullable)
 * @param diskPct pourcentage disque (nullable)
 */
@Composable
fun HealthStatusBadge(
    cpuPct: Float?,
    memoryPct: Float?,
    temperature: Float?,
    diskPct: Float?,
    modifier: Modifier = Modifier,
) {
    // Ne rien afficher si aucune metrique n'est disponible
    if (cpuPct == null && memoryPct == null && temperature == null && diskPct == null) return

    val extendedColors = LocalExtendedColors.current

    // Verifier les metriques critiques dans l'ordre de priorite
    val criticalLabel = when {
        cpuPct != null && cpuPct >= CPU_THRESHOLDS.crit -> "CPU critique"
        memoryPct != null && memoryPct >= MEMORY_THRESHOLDS.crit -> "RAM critique"
        temperature != null && temperature >= TEMPERATURE_THRESHOLDS.crit -> "Temp critique"
        diskPct != null && diskPct >= DISK_THRESHOLDS.crit -> "Disque critique"
        else -> null
    }

    // Verifier les metriques en avertissement
    val hasWarning = (cpuPct != null && cpuPct >= CPU_THRESHOLDS.warn) ||
        (memoryPct != null && memoryPct >= MEMORY_THRESHOLDS.warn) ||
        (temperature != null && temperature >= TEMPERATURE_THRESHOLDS.warn) ||
        (diskPct != null && diskPct >= DISK_THRESHOLDS.warn)

    val (text, color) = when {
        criticalLabel != null -> criticalLabel to extendedColors.pnlNegative
        hasWarning -> "Avertissement" to extendedColors.warning
        else -> "Sain" to extendedColors.pnlPositive
    }

    StatusBadge(
        text = text,
        color = color,
        modifier = modifier,
    )
}
