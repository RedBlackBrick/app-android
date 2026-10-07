package com.tradingplatform.app.usecase.vigie

import com.tradingplatform.app.domain.model.Cached
import com.tradingplatform.app.domain.model.HomeVigie
import com.tradingplatform.app.domain.model.NotificationPreferences
import com.tradingplatform.app.domain.model.Vigie
import com.tradingplatform.app.domain.repository.NotificationPreferencesRepository
import com.tradingplatform.app.domain.repository.VigieRepository
import com.tradingplatform.app.domain.usecase.vigie.GetHomeVigieUseCase
import com.tradingplatform.app.domain.usecase.vigie.GetShowVigieUseCase
import com.tradingplatform.app.domain.usecase.vigie.GetVigieUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * UseCases Vigie : le réglage de compte `showVigie` conditionne la carte Accueil (jamais un appel
 * `v1/vigie` quand il est faux), `forceRefresh` est transmis tel quel, le cache daté est propagé.
 */
class VigieUseCasesTest {

    private val vigieRepository = mockk<VigieRepository>()
    private val preferencesRepository = mockk<NotificationPreferencesRepository>()

    private val getVigie = GetVigieUseCase(vigieRepository)
    private val getShowVigie = GetShowVigieUseCase(preferencesRepository)
    private val getHomeVigie = GetHomeVigieUseCase(getShowVigie, getVigie)

    private val cached = Cached(Vigie.UNAVAILABLE.copy(available = true), syncedAt = 1234L)

    private fun prefs(showVigie: Boolean) = NotificationPreferences(categories = emptyMap(), showVigie = showVigie)

    // ── GetVigieUseCase ──────────────────────────────────────────────────────

    @Test
    fun `GetVigieUseCase defaults to forceRefresh false`() = runTest {
        coEvery { vigieRepository.getVigie(any()) } returns Result.success(cached)

        assertSame(cached, getVigie().getOrThrow())

        coVerify(exactly = 1) { vigieRepository.getVigie(false) }
    }

    @Test
    fun `GetVigieUseCase forwards forceRefresh true and the real syncedAt`() = runTest {
        coEvery { vigieRepository.getVigie(true) } returns Result.success(cached)

        val result = getVigie(forceRefresh = true).getOrThrow()

        assertEquals(1234L, result.syncedAt)
        coVerify(exactly = 1) { vigieRepository.getVigie(true) }
    }

    @Test
    fun `GetVigieUseCase propagates a failure`() = runTest {
        coEvery { vigieRepository.getVigie(any()) } returns Result.failure(IOException("VPN down"))

        assertTrue(getVigie().isFailure)
    }

    // ── GetShowVigieUseCase ──────────────────────────────────────────────────

    @Test
    fun `GetShowVigieUseCase reads the account setting`() = runTest {
        coEvery { preferencesRepository.get() } returns Result.success(prefs(showVigie = true))
        assertTrue(getShowVigie().getOrThrow())

        coEvery { preferencesRepository.get() } returns Result.success(prefs(showVigie = false))
        assertFalse(getShowVigie().getOrThrow())
    }

    @Test
    fun `GetShowVigieUseCase propagates an unreadable setting as a failure, never as false`() = runTest {
        coEvery { preferencesRepository.get() } returns Result.failure(IOException("VPN down"))

        assertTrue(getShowVigie().isFailure)
    }

    // ── GetHomeVigieUseCase ──────────────────────────────────────────────────

    @Test
    fun `setting false hides the card and makes NO Vigie call`() = runTest {
        coEvery { preferencesRepository.get() } returns Result.success(prefs(showVigie = false))

        val result = getHomeVigie(forceRefresh = true).getOrThrow()

        assertEquals(HomeVigie.Hidden, result)
        coVerify(exactly = 0) { vigieRepository.getVigie(any()) }
    }

    @Test
    fun `setting true shows the Vigie and forwards forceRefresh`() = runTest {
        coEvery { preferencesRepository.get() } returns Result.success(prefs(showVigie = true))
        coEvery { vigieRepository.getVigie(any()) } returns Result.success(cached)

        val result = getHomeVigie(forceRefresh = true).getOrThrow()

        assertEquals(HomeVigie.Shown(cached), result)
        coVerify(exactly = 1) { vigieRepository.getVigie(true) }
    }

    @Test
    fun `an unreadable setting is a failure and makes NO Vigie call`() = runTest {
        coEvery { preferencesRepository.get() } returns Result.failure(IOException("VPN down"))

        assertTrue(getHomeVigie().isFailure)

        coVerify(exactly = 0) { vigieRepository.getVigie(any()) }
    }

    @Test
    fun `setting true with a failing Vigie read is a failure`() = runTest {
        coEvery { preferencesRepository.get() } returns Result.success(prefs(showVigie = true))
        coEvery { vigieRepository.getVigie(any()) } returns Result.failure(IOException("VPN down"))

        assertTrue(getHomeVigie().isFailure)
    }

    @Test
    fun `setting true serves the stale cached value as a success (offline = dated cache)`() = runTest {
        val stale = Cached(Vigie.UNAVAILABLE.copy(available = true), syncedAt = 1L)
        coEvery { preferencesRepository.get() } returns Result.success(prefs(showVigie = true))
        coEvery { vigieRepository.getVigie(false) } returns Result.success(stale)

        val shown = getHomeVigie().getOrThrow() as HomeVigie.Shown

        assertEquals(1L, shown.vigie.syncedAt)
    }
}
