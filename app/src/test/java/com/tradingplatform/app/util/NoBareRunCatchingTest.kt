package com.tradingplatform.app.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard-rail for PR 3.3 (audit #10 / B-auth-conc-1 + B-misc-1) — every `runCatching {}` in
 * `app/src/main` that can wrap a suspend call must be `runCatchingCancellable {}` instead
 * (see [com.tradingplatform.app.domain.util.runCatchingCancellable] for why: plain
 * `runCatching` swallows [kotlinx.coroutines.CancellationException] and breaks structured
 * concurrency).
 *
 * Scans every `.kt` file under `app/src/main/java` for a bare `runCatching {` call, skipping:
 *  - the helper's own file (its KDoc mentions `runCatching {}` as prose);
 *  - a hard-coded allow-list of files where the match is either purely synchronous,
 *    non-suspending code (cancellation cannot occur inside the block) or, for
 *    [QUOTE_FALLBACK_CONTROLLER], a comment that happens to contain the substring.
 *
 * If this test fails, either convert the new `runCatching {` to `runCatchingCancellable {`,
 * or — if it is genuinely synchronous/non-suspending — add it to [ALLOWED_FILES] with a
 * one-line justification next to the entry.
 */
class NoBareRunCatchingTest {

    companion object {
        private val BARE_RUN_CATCHING = Regex("""\brunCatching\s*\{""")

        private const val HELPER_FILE =
            "com/tradingplatform/app/domain/util/RunCatchingCancellable.kt"

        /**
         * Relative to `app/src/main/java`. Every entry here must be justified: either the
         * `runCatching {` block contains no suspend calls (cancellation cannot occur), or — for
         * [QuoteFallbackController] — the match is inside a comment, not code.
         */
        private val ALLOWED_FILES = setOf(
            // Synchronous JSON / URI parsing — no suspend calls in the block. Explicitly
            // called out in PR 3.3's brief (audit/plan-auth-network.md §D).
            "com/tradingplatform/app/domain/usecase/pairing/ParseVpsQrUseCase.kt",
            "com/tradingplatform/app/domain/usecase/pairing/ParseSetupQrUseCase.kt",
            "com/tradingplatform/app/domain/usecase/pairing/ScanDeviceQrUseCase.kt",
            // Synchronous BigDecimal parsing inside Glance @Composable functions — no suspend
            // calls, cannot be cancelled mid-parse.
            "com/tradingplatform/app/widget/PnlWidget.kt",
            "com/tradingplatform/app/widget/PositionsWidget.kt",
            "com/tradingplatform/app/widget/QuoteWidget.kt",
            // Synchronous SharedPreferences file deletion + MasterKey alias deletion inside a
            // plain `synchronized {}` block (not coroutine-suspending); also outside the
            // PR 3.3 conversion scope (data/local/datastore is not in the replacement list).
            "com/tradingplatform/app/data/local/datastore/EncryptedDataStore.kt",
            // Synchronous Intent#startActivity — no suspend calls. Also outside scope: not a
            // ViewModel (worktree rules restrict ui/ changes to ViewModels only).
            "com/tradingplatform/app/ui/navigation/AppNavGraph.kt",
            // Not real code — a KDoc/comment mentioning "runCatching{}" as prose, matched by the
            // regex because there's no space before '{'. Also outside scope: not a ViewModel.
            "com/tradingplatform/app/ui/common/QuoteFallbackController.kt",
        )

        /** Candidate roots — resolved relative to the Gradle test working directory. */
        private val CANDIDATE_ROOTS = listOf(
            "src/main/java",
            "app/src/main/java",
        )
    }

    private fun findMainSourceRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        for (candidate in CANDIDATE_ROOTS) {
            val dir = File(userDir, candidate)
            if (dir.isDirectory) return dir
        }
        error(
            "NoBareRunCatchingTest: could not locate app/src/main/java from user.dir=" +
                "${userDir.absolutePath} — tried $CANDIDATE_ROOTS"
        )
    }

    @Test
    fun `no bare runCatching outside the helper and the allow-list`() {
        val root = findMainSourceRoot()

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                relative != HELPER_FILE && relative !in ALLOWED_FILES
            }
            .filter { file -> BARE_RUN_CATCHING.containsMatchIn(file.readText()) }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .sorted()
            .toList()

        assertTrue(
            "Found bare `runCatching {` outside the helper/allow-list — use " +
                "runCatchingCancellable {} instead (see RunCatchingCancellable.kt), or add the " +
                "file to ALLOWED_FILES with a justification if it is genuinely synchronous:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
