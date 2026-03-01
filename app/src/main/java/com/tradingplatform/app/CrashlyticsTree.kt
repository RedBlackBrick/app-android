package com.tradingplatform.app

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * Timber tree that forwards warnings and errors to Firebase Crashlytics.
 *
 * Planted in release builds only. Debug/verbose/info logs are silently dropped
 * (Timber strip rules in proguard-rules.pro remove d/v/i at build time,
 * but this tree also ignores them as a safety net).
 */
class CrashlyticsTree : Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean =
        priority >= Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.log("${tag ?: "---"}: $message")
        if (t != null) {
            crashlytics.recordException(t)
        }
    }
}
