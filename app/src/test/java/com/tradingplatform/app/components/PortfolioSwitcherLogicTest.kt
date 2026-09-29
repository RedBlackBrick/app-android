package com.tradingplatform.app.components

import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.ui.components.activePortfolioLabel
import com.tradingplatform.app.ui.components.portfolioItemDescription
import com.tradingplatform.app.ui.components.shouldShowPortfolioSwitcher
import com.tradingplatform.app.ui.components.switcherButtonDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logique pure du sélecteur de portefeuille (aucune dépendance Compose/Android). */
class PortfolioSwitcherLogicTest {

    private val main = Portfolio(id = "p1", name = "Compte principal", currency = "EUR")
    private val pea = Portfolio(id = "p2", name = "PEA", currency = "USD")

    @Test
    fun `the switcher is shown only from 2 portfolios`() {
        assertFalse(shouldShowPortfolioSwitcher(emptyList()))
        assertFalse(shouldShowPortfolioSwitcher(listOf(main)))
        assertTrue(shouldShowPortfolioSwitcher(listOf(main, pea)))
        assertTrue(shouldShowPortfolioSwitcher(listOf(main, pea, main.copy(id = "p3"))))
    }

    @Test
    fun `the button shows the name of the active portfolio`() {
        assertEquals("Compte principal", activePortfolioLabel(listOf(main, pea), "p1"))
        assertEquals("PEA", activePortfolioLabel(listOf(main, pea), "p2"))
    }

    @Test
    fun `the button falls back to a generic label when the active portfolio is unknown`() {
        assertEquals("Portefeuille", activePortfolioLabel(listOf(main, pea), null))
        assertEquals("Portefeuille", activePortfolioLabel(listOf(main, pea), "p9"))
    }

    @Test
    fun `a blank portfolio name is replaced by a readable label`() {
        val unnamed = main.copy(name = "   ")

        assertEquals("Portefeuille sans nom", activePortfolioLabel(listOf(unnamed, pea), "p1"))
        assertEquals(
            "Portefeuille sans nom, devise EUR",
            portfolioItemDescription(unnamed, isActive = false),
        )
    }

    @Test
    fun `TalkBack reads the full active name on the button`() {
        assertEquals(
            "Portefeuille actif : Compte principal. Changer de portefeuille",
            switcherButtonDescription(listOf(main, pea), "p1"),
        )
        assertEquals("Changer de portefeuille", switcherButtonDescription(listOf(main, pea), null))
    }

    @Test
    fun `menu rows describe name, currency and active state`() {
        assertEquals("PEA, devise USD", portfolioItemDescription(pea, isActive = false))
        assertEquals(
            "Compte principal, devise EUR, portefeuille actif",
            portfolioItemDescription(main, isActive = true),
        )
        assertEquals("PEA", portfolioItemDescription(pea.copy(currency = " "), isActive = false))
    }
}
