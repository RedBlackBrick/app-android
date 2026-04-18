package com.tradingplatform.app.components

import com.tradingplatform.app.ui.components.buildPnlDescription
import org.junit.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Tests de la description accessible des valeurs P&L (TalkBack / voiceOver).
 *
 * Règle (CLAUDE.md §9) : les valeurs monétaires doivent avoir un contentDescription
 * verbose — pas uniquement le nombre formaté. Un lecteur d'écran qui lit "moins trois
 * cents euros" en mode rapide peut être interprété comme "trois cents euros" (le signe
 * est souvent omis). "Perte de 300 €" lève l'ambigüité.
 */
class PnlDescriptionTest {

    @Test
    fun `positive value produces gain description without sign prefix`() {
        val desc = buildPnlDescription(BigDecimal("1250.00"), "€")
        assertEquals("Gain de 1 250,00 €", desc.replace('\u00A0', ' ').replace('\u202F', ' '))
    }

    @Test
    fun `negative value produces perte description with absolute value`() {
        val desc = buildPnlDescription(BigDecimal("-300.00"), "€")
        // Pas de "-300" dans la description — utiliser la valeur absolue pour TalkBack
        assertFalse(desc.contains("-"), "Description ne doit pas contenir '-' : $desc")
        assertEquals("Perte de 300,00 €", desc.replace('\u00A0', ' ').replace('\u202F', ' '))
    }

    @Test
    fun `zero value produces neutral description`() {
        val desc = buildPnlDescription(BigDecimal.ZERO, "€")
        assertEquals("Aucun gain ni perte, 0,00 €", desc.replace('\u00A0', ' ').replace('\u202F', ' '))
    }

    @Test
    fun `custom currency symbol is preserved`() {
        val desc = buildPnlDescription(BigDecimal("100.50"), "USD")
        assertEquals("Gain de 100,50 USD", desc.replace('\u00A0', ' ').replace('\u202F', ' '))
    }

    @Test
    fun `very small positive value still produces gain description`() {
        val desc = buildPnlDescription(BigDecimal("0.01"), "€")
        assertEquals("Gain de 0,01 €", desc.replace('\u00A0', ' ').replace('\u202F', ' '))
    }

    @Test
    fun `thousands separator is present in description`() {
        val desc = buildPnlDescription(BigDecimal("12345.67"), "€")
        // Grouping espace insécable (Locale.FRENCH) — vérifie qu'il est bien présent
        assertEquals("Gain de 12 345,67 €", desc.replace('\u00A0', ' ').replace('\u202F', ' '))
    }
}
