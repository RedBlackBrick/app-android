package com.tradingplatform.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `style.merge(TradingNumbers.bodyLarge)` laissait l'argument gagner : le P&L « hero » (titleLarge,
 * gras) sortait en bodyLarge 16 sp normal. `asNumeric()` ne doit changer QUE la police et `tnum`.
 */
class AsNumericTest {

    private val callerStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 34.sp,
        letterSpacing = 0.5.sp,
    )

    @Test
    fun `forces the mono family and tabular figures`() {
        val out = callerStyle.asNumeric()

        assertEquals(jetBrainsMonoFamily, out.fontFamily)
        assertEquals("tnum", out.fontFeatureSettings)
    }

    @Test
    fun `keeps the size, weight and spacing chosen by the caller`() {
        val out = callerStyle.asNumeric()

        assertEquals(28.sp, out.fontSize)
        assertEquals(FontWeight.Bold, out.fontWeight)
        assertEquals(34.sp, out.lineHeight)
        assertEquals(0.5.sp, out.letterSpacing)
    }

    @Test
    fun `a proportional theme style loses its family but keeps its size`() {
        val out = TradingTypography.headlineMedium.asNumeric()

        assertEquals(TradingTypography.headlineMedium.fontSize, out.fontSize)
        assertEquals(jetBrainsMonoFamily, out.fontFamily)
    }
}
