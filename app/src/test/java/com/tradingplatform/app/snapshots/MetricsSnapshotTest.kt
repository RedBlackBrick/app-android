package com.tradingplatform.app.snapshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.tradingplatform.app.ui.components.CPU_THRESHOLDS
import com.tradingplatform.app.ui.components.CompactHealthBar
import com.tradingplatform.app.ui.components.MEMORY_THRESHOLDS
import com.tradingplatform.app.ui.components.MetricRow
import com.tradingplatform.app.ui.components.TEMPERATURE_THRESHOLDS
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.TradingPlatformTheme
import org.junit.Rule
import org.junit.Test

/**
 * Snapshots des composants de métriques device (CPU, RAM, température) aux trois niveaux
 * de seuil (OK / warn / crit) en light + dark.
 *
 * Ces composants dictent la lisibilité du Dashboard admin — toute régression de couleur
 * sur les badges impacte la réactivité aux alertes hardware Radxa.
 */
class MetricsSnapshotTest {

    @get:Rule
    val paparazzi: Paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        renderingMode = SessionParams.RenderingMode.SHRINK,
    )

    @Test
    fun metrics_ok_light() {
        paparazzi.snapshot {
            TradingPlatformTheme(darkTheme = false) { MetricsContent(level = Level.OK) }
        }
    }

    @Test
    fun metrics_ok_dark() {
        paparazzi.snapshot {
            TradingPlatformTheme(darkTheme = true) { MetricsContent(level = Level.OK) }
        }
    }

    @Test
    fun metrics_warn_light() {
        paparazzi.snapshot {
            TradingPlatformTheme(darkTheme = false) { MetricsContent(level = Level.WARN) }
        }
    }

    @Test
    fun metrics_crit_dark() {
        paparazzi.snapshot {
            TradingPlatformTheme(darkTheme = true) { MetricsContent(level = Level.CRIT) }
        }
    }

    private enum class Level { OK, WARN, CRIT }

    @Composable
    private fun MetricsContent(level: Level) {
        val (cpu, mem, temp) = when (level) {
            Level.OK -> Triple(25f, 40f, 45f)
            Level.WARN -> Triple(75f, 78f, 65f)
            Level.CRIT -> Triple(95f, 92f, 80f)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            MetricRow(label = "CPU", value = cpu, unit = "%", thresholds = CPU_THRESHOLDS, progressMax = 100f)
            MetricRow(label = "Mémoire", value = mem, unit = "%", thresholds = MEMORY_THRESHOLDS, progressMax = 100f)
            MetricRow(label = "Température", value = temp, unit = " C", thresholds = TEMPERATURE_THRESHOLDS, progressMax = 100f)
            CompactHealthBar(cpuPct = cpu, memoryPct = mem, temperature = temp)
        }
    }
}
