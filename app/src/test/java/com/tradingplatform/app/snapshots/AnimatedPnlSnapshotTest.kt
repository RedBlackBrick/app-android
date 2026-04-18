package com.tradingplatform.app.snapshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.tradingplatform.app.ui.components.AnimatedPnlText
import com.tradingplatform.app.ui.theme.TradingPlatformTheme
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

/**
 * Snapshots de [AnimatedPnlText] dans son état de repos (pas de flash actif).
 *
 * Important : [AnimatedPnlText] contient des animations de couleur. Paparazzi capture
 * le frame initial (pré-animation), donc le snapshot reflète la couleur cible stable.
 *
 * Couvre aussi le cas "valeur hors-limite" (`isDisplayable() == false`) qui affiche "—"
 * pour prévenir un layout corrompu par une valeur astronomique (CLAUDE.md §5).
 */
class AnimatedPnlSnapshotTest {

    @get:Rule
    val paparazzi: Paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        renderingMode = SessionParams.RenderingMode.SHRINK,
    )

    @Test
    fun animatedPnl_light() {
        paparazzi.snapshot {
            TradingPlatformTheme(darkTheme = false) { Content() }
        }
    }

    @Test
    fun animatedPnl_dark() {
        paparazzi.snapshot {
            TradingPlatformTheme(darkTheme = true) { Content() }
        }
    }

    @Composable
    private fun Content() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
        ) {
            AnimatedPnlText(value = BigDecimal("42.75"))
            Spacer(Modifier.height(8.dp))
            AnimatedPnlText(value = BigDecimal("-128.33"))
            Spacer(Modifier.height(8.dp))
            AnimatedPnlText(value = BigDecimal.ZERO)
            Spacer(Modifier.height(8.dp))
            // Valeur hors limite — fallback "—" pour éviter layout cassé (CLAUDE.md §5)
            AnimatedPnlText(value = BigDecimal("999999999999999999"))
        }
    }
}
