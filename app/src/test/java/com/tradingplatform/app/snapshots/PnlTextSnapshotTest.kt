package com.tradingplatform.app.snapshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.tradingplatform.app.ui.components.PnlText
import com.tradingplatform.app.ui.theme.TradingPlatformTheme
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

/**
 * Snapshots visuels des valeurs P&L dans les deux modes (light / dark) pour les
 * trois états : gain, perte, neutre.
 *
 * Exécuter avec `./gradlew recordPaparazziDebug -PenablePaparazzi=true` pour régénérer
 * les PNG de référence après un changement UI volontaire, puis `verifyPaparazziDebug`
 * en CI pour détecter les régressions.
 */
class PnlTextSnapshotTest {

    @get:Rule
    val paparazzi: Paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        renderingMode = SessionParams.RenderingMode.SHRINK,
    )

    @Test
    fun pnlText_positive_light() {
        paparazzi.snapshot {
            TradingPlatformTheme(darkTheme = false) {
                PnlSamples()
            }
        }
    }

    @Test
    fun pnlText_positive_dark() {
        paparazzi.snapshot {
            TradingPlatformTheme(darkTheme = true) {
                PnlSamples()
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PnlSamples() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        // Gain substantiel — teste la couleur positive + thousands separator
        PnlText(value = BigDecimal("1250.00"))
        Spacer(Modifier.height(8.dp))
        // Perte — teste la couleur négative + signe préservé
        PnlText(value = BigDecimal("-300.50"))
        Spacer(Modifier.height(8.dp))
        // Neutre — teste le fallback onSurfaceVariant
        PnlText(value = BigDecimal.ZERO)
        Spacer(Modifier.height(8.dp))
        // Gros nombre — teste l'alignement tabular + non-wrap
        PnlText(value = BigDecimal("98765432.10"))
    }
}
