package com.tradingplatform.app

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.security.BiometricLockManager
import com.tradingplatform.app.vpn.SystemVpnMonitor
import com.tradingplatform.app.security.RootDetector
import com.tradingplatform.app.ui.navigation.AppNavGraph
import com.tradingplatform.app.ui.theme.TradingPlatformTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * FragmentActivity (et non ComponentActivity) : `BiometricPrompt` exige une FragmentActivity
 * pour afficher son dialog. Avec une ComponentActivity, le verrou biométrique ne pouvait pas
 * afficher de prompt (audit #1).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var rootDetector: RootDetector
    @Inject lateinit var biometricLockManager: BiometricLockManager
    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var systemVpnMonitor: SystemVpnMonitor

    companion object {
        const val EXTRA_NAVIGATE_TO = "navigate_to"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screenshots, screen recording, and Recent Apps thumbnail.
        // Seule exception : un build debug émulateur (`-PALLOW_SCREENSHOTS=true`, false en release)
        // pour la vérification visuelle du design.
        if (!BuildConfig.ALLOW_SCREENSHOTS) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

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
    }

    override fun onStart() {
        super.onStart()
        // Retour au premier plan : relit l'état réel des VPN système (un tunnel monté ou coupé
        // pendant que l'app était en arrière-plan ne doit pas laisser une bannière périmée).
        systemVpnMonitor.isActiveNow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLinkIntent(intent)
    }

    /**
     * Transmet chaque interaction tactile au [BiometricLockManager], seul propriétaire du
     * timer d'inactivité (CLAUDE.md §4). Simple écriture d'un AtomicLong — pas d'allocation.
     */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        biometricLockManager.onUserInteraction()
        return super.dispatchTouchEvent(ev)
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
