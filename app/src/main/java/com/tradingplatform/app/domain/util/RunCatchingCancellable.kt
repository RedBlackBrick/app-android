package com.tradingplatform.app.domain.util

import kotlinx.coroutines.CancellationException

/**
 * Cancellation-safe replacement for `kotlin.runCatching` (audit #10 / B-auth-conc-1 + B-misc-1).
 *
 * Plain `runCatching {}` catches `Throwable`, which silently swallows
 * [kotlinx.coroutines.CancellationException] and turns coroutine cancellation into a
 * `Result.failure(...)` instead of letting it propagate — breaking structured concurrency
 * (the coroutine looks "successful" to callers, parent scopes never learn the child was
 * cancelled, and cleanup/finally-based cancellation signalling is defeated).
 *
 * `runCatchingCancellable` rethrows [CancellationException] immediately and only wraps other
 * [Throwable]s into a [Result]. Every `Repository`/`UseCase`/interceptor/websocket/widget call
 * site that wraps a `suspend` call in `app/src/main` should use this instead of `runCatching`.
 *
 * **[kotlinx.coroutines.TimeoutCancellationException] is a subclass of [CancellationException]**,
 * so it is rethrown here too, not wrapped into `Result.failure`. Callers that want to map a
 * timeout to a `Result.failure` (e.g. treat "confirmation timed out" as a business failure
 * rather than propagate it as coroutine cancellation) must catch
 * `TimeoutCancellationException` explicitly *before* calling `runCatchingCancellable`, the way
 * `ConfirmPairingUseCase` wraps its `withTimeout(...)` block in its own try/catch that maps the
 * timeout to `Result.failure(PairingTimeoutException)` ahead of the generic catch.
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
