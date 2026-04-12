package com.tradingplatform.app

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.security.BiometricLockManager
import com.tradingplatform.app.security.RootDetector
import com.tradingplatform.app.ui.navigation.AppNavGraph
import com.tradingplatform.app.ui.theme.TradingPlatformTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var rootDetector: RootDetector
    @Inject lateinit var biometricLockManager: BiometricLockManager
    @Inject lateinit var sessionManager: SessionManager

    private var inactivityJob: Job? = null
    private var isBiometricLocked = false

    companion object {
        private const val INACTIVITY_TIMEOUT_MS = 5 * 60 * 1000L  // 5 min
        const val EXTRA_NAVIGATE_TO = "navigate_to"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screenshots, screen recording, and Recent Apps thumbnail
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        // Edge-to-edge + immersive sticky: draw behind the status/nav bars and
        // hide them unless the user swipes from an edge (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE).
        // Matches the "clean fullscreen" expectation for a trading app; the
        // system bars still appear briefly on demand for navigation / time.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        checkRootStatus()
        handleDeepLinkIntent(intent)

        setContent {
            TradingPlatformTheme {
                AppNavGraph()
            }
        }

        startInactivityTimer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLinkIntent(intent)
    }

    /**
     * Réinitialise le timer d'inactivité à chaque interaction tactile.
     * Mécanisme 2 du verrou biométrique (CLAUDE.md §4).
     */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetInactivityTimer()
        return super.dispatchTouchEvent(ev)
    }

    private fun startInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = lifecycleScope.launch {
            delay(INACTIVITY_TIMEOUT_MS)
            showBiometricLock()
        }
    }

    private fun resetInactivityTimer() {
        if (!isBiometricLocked) {
            inactivityJob?.cancel()
            inactivityJob = lifecycleScope.launch {
                delay(INACTIVITY_TIMEOUT_MS)
                showBiometricLock()
            }
        }
    }

    private fun showBiometricLock() {
        isBiometricLocked = true
        Timber.d("MainActivity: showing biometric lock (inactivity timeout)")
        biometricLockManager.lock()
    }

    fun onBiometricUnlocked() {
        isBiometricLocked = false
        biometricLockManager.unlock()
        resetInactivityTimer()
        Timber.d("MainActivity: biometric unlocked — timer reset")
    }

    private fun checkRootStatus() {
        if (rootDetector.isRooted()) {
            Timber.w("MainActivity: device appears to be rooted — security advisory")
            // Afficher un avertissement non bloquant (app en sideload interne, usage trusted)
            // Ne pas bloquer — RootBeer est bypassable (CLAUDE.md §4 note)
        }
    }

    /**
     * Gère les deep links FCM : intent extra "navigate_to" = "alerts".
     * Notifie [SessionManager] qui réémet l'événement vers [AppNavViewModel].
     * Fonctionne pour onCreate (lancement depuis notification) ET onNewIntent
     * (app déjà en foreground au moment de la notification).
     */
    private fun handleDeepLinkIntent(intent: Intent?) {
        val navigateTo = intent?.getStringExtra(EXTRA_NAVIGATE_TO)
        if (navigateTo == "alerts") {
            Timber.d("MainActivity: FCM deep link → alerts")
            sessionManager.notifyDeepLink("alerts")
        }
    }
}
