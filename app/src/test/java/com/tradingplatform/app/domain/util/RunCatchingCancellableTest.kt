package com.tradingplatform.app.domain.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * PR 3.3 (audit #10 / B-auth-conc-1 + B-misc-1) — [runCatchingCancellable] must behave exactly
 * like `kotlin.runCatching` for ordinary exceptions, but must never swallow coroutine
 * cancellation.
 */
class RunCatchingCancellableTest {

    @Test
    fun `wraps a successful block into Result success`() {
        val result = runCatchingCancellable { 42 }

        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `wraps a regular exception into Result failure`() {
        val boom = IOException("network down")

        val result = runCatchingCancellable<Int> { throw boom }

        assertTrue(result.isFailure)
        assertEquals(boom, result.exceptionOrNull())
    }

    @Test
    fun `wraps an IllegalStateException into Result failure`() {
        val result = runCatchingCancellable<Unit> { error("bad state") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `rethrows CancellationException instead of wrapping it`() {
        val cancellation = CancellationException("cancelled")

        try {
            runCatchingCancellable<Unit> { throw cancellation }
            fail("Expected CancellationException to propagate, but it was swallowed")
        } catch (e: CancellationException) {
            assertEquals(cancellation, e)
        }
    }

    @Test
    fun `rethrows a real TimeoutCancellationException instead of wrapping it`() = runTest {
        // TimeoutCancellationException's constructor is internal to kotlinx-coroutines-core, so
        // we obtain a real instance the only way external code can: let withTimeout throw one.
        val captured = try {
            withTimeout(1) { delay(1_000) }
            error("expected withTimeout to throw TimeoutCancellationException")
        } catch (e: TimeoutCancellationException) {
            e
        }

        // TimeoutCancellationException is a CancellationException subclass — re-throwing it
        // through runCatchingCancellable must propagate it, not wrap it into Result.failure.
        try {
            runCatchingCancellable<Unit> { throw captured }
            fail("Expected TimeoutCancellationException to propagate, but it was swallowed")
        } catch (e: TimeoutCancellationException) {
            assertEquals(captured, e)
        }
    }

    @Test
    fun `a real withTimeout cancellation propagates out of runCatchingCancellable`() = runTest {
        var sawTimeoutCancellationException = false
        try {
            withTimeout(10) {
                runCatchingCancellable {
                    delay(1_000)
                }
            }
        } catch (e: TimeoutCancellationException) {
            sawTimeoutCancellationException = true
        }
        assertTrue(
            "withTimeout's TimeoutCancellationException must escape runCatchingCancellable",
            sawTimeoutCancellationException,
        )
    }

    @Test
    fun `a cancelled coroutine's job is actually cancelled, not swallowed as Result failure`() = runTest {
        var reachedAfterCancellation = false
        var resultOutsideCatch: Result<Unit>? = null

        val job = launch {
            resultOutsideCatch = runCatchingCancellable {
                delay(1_000)
            }
            // Must never run — cancellation must abort the coroutine here, not fall through
            // with a Result.failure(CancellationException) like plain runCatching would.
            reachedAfterCancellation = true
        }

        job.cancel()
        job.join()

        assertFalse("Coroutine body must not resume after cancellation", reachedAfterCancellation)
        assertEquals(null, resultOutsideCatch)
    }

    @Test
    fun `does not affect parent scope cancellation propagation`() = runTest {
        var innerCompleted = false
        val job = launch {
            launch {
                runCatchingCancellable {
                    delay(1_000)
                }
                innerCompleted = true
            }
            delay(1_000)
        }
        job.cancel()
        job.join()
        assertFalse(innerCompleted)
    }
}
