package com.tradingplatform.app.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

/**
 * Provides access to haptic feedback from Composables.
 *
 * Usage:
 * ```kotlin
 * val haptic = rememberHapticFeedback()
 * Button(onClick = { haptic.confirm(); doAction() }) { ... }
 * ```
 */
class HapticFeedbackHelper(private val view: View) {

    /** Short confirmation tick — used on successful scan, action completion. */
    fun confirm() {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    /** Error/reject feedback — used on scan failure, validation error. */
    fun reject() {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    /** Light click — used on toggle, chip selection. */
    fun click() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Long press feedback. */
    fun longPress() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}

/**
 * Remember a [HapticFeedbackHelper] scoped to the current Composable's [View].
 */
@Composable
fun rememberHapticFeedback(): HapticFeedbackHelper {
    val view = LocalView.current
    return HapticFeedbackHelper(view)
}
