package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.local.db.dao.AlertDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Bouton « Tout lire » : `markAllRead()` délègue à la requête DAO unique
 * (`UPDATE alerts SET read = 1 WHERE read = 0`) et n'avale jamais une annulation.
 */
class AlertRepositoryImplTest {

    private val alertDao = mockk<AlertDao>()
    private val repository = AlertRepositoryImpl(alertDao)

    @Test
    fun `markAllRead calls the DAO once and returns success`() = runTest {
        coEvery { alertDao.markAllRead() } returns Unit

        val result = repository.markAllRead()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { alertDao.markAllRead() }
    }

    @Test
    fun `markAllRead wraps a DAO failure in Result failure`() = runTest {
        val error = IOException("disk full")
        coEvery { alertDao.markAllRead() } throws error

        val result = repository.markAllRead()

        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
    }

    @Test
    fun `markAllRead rethrows CancellationException instead of wrapping it`() = runTest {
        coEvery { alertDao.markAllRead() } throws CancellationException("cancelled")

        try {
            repository.markAllRead()
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }
}
