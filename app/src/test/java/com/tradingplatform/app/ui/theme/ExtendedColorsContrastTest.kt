package com.tradingplatform.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Verrouille l'accessibilité des couleurs de P&L : texte ≥ 4,5:1 (WCAG AA) sur les surfaces réelles
 * de l'app, en clair et en sombre. Avant : emerald-600 sur blanc = 3,77:1 et le flash de changement
 * de valeur (emerald-200 / rose-100) = 1,2:1, ce qui faisait disparaître le chiffre à chaque tick.
 */
class ExtendedColorsContrastTest {

    private fun channel(c: Float): Double =
        if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> maxOf(la, lb) to minOf(la, lb) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertAa(name: String, foreground: Color, vararg backgrounds: Pair<String, Color>) {
        backgrounds.forEach { (bgName, bg) ->
            val ratio = contrast(foreground, bg)
            assertTrue("$name sur $bgName : %.2f:1 < 4,5:1".format(ratio), ratio >= 4.5)
        }
    }

    @Test
    fun `light theme P&L text and flash reach AA on the card and the background`() {
        val bgs = arrayOf("cardSurface" to lightExtendedColors.cardSurface, "background" to Slate50)
        assertAa("light pnlPositive", lightExtendedColors.pnlPositive, *bgs)
        assertAa("light pnlNegative", lightExtendedColors.pnlNegative, *bgs)
        assertAa("light pnlPositiveFlash", lightExtendedColors.pnlPositiveFlash, *bgs)
        assertAa("light pnlNegativeFlash", lightExtendedColors.pnlNegativeFlash, *bgs)
    }

    @Test
    fun `dark theme P&L text and flash reach AA on the card and the background`() {
        val bgs = arrayOf("cardSurface" to darkExtendedColors.cardSurface, "background" to Slate950)
        assertAa("dark pnlPositive", darkExtendedColors.pnlPositive, *bgs)
        assertAa("dark pnlNegative", darkExtendedColors.pnlNegative, *bgs)
        assertAa("dark pnlPositiveFlash", darkExtendedColors.pnlPositiveFlash, *bgs)
        assertAa("dark pnlNegativeFlash", darkExtendedColors.pnlNegativeFlash, *bgs)
    }

    @Test
    fun `the flash is visibly different from the resting colour`() {
        // Un flash quasi identique à la couleur de repos ne se voit pas.
        assertTrue(contrast(lightExtendedColors.pnlPositiveFlash, lightExtendedColors.pnlPositive) >= 1.3)
        assertTrue(contrast(lightExtendedColors.pnlNegativeFlash, lightExtendedColors.pnlNegative) >= 1.3)
        assertTrue(contrast(darkExtendedColors.pnlPositiveFlash, darkExtendedColors.pnlPositive) >= 1.3)
        assertTrue(contrast(darkExtendedColors.pnlNegativeFlash, darkExtendedColors.pnlNegative) >= 1.3)
    }
}
