package com.tradingplatform.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * Durées et easing standards pour les animations — à respecter pour la cohérence
 * entre les screens.
 *
 * Référence : docs/design-system.md § Animations
 *
 * Règles AnimatedContent vs AnimatedVisibility :
 * - AnimatedVisibility : apparition/disparition d'un élément (show/hide)
 * - AnimatedContent : transition entre deux contenus différents (Loading → Success, valeur qui change)
 *
 * Exemple correct :
 * ```kotlin
 * AnimatedContent(
 *     targetState = uiState,
 *     transitionSpec = {
 *         fadeIn(tween(Motion.EnterDuration)) togetherWith fadeOut(tween(Motion.ExitDuration))
 *     }
 * ) { state -> ... }
 * ```
 */
object Motion {
    // ── Durées (millisecondes) ────────────────────────────────────────────────
    /** Entrée d'un élément : screen entrant, card apparaissant */
    const val EnterDuration = 300

    /** Sortie d'un élément : screen sortant, card disparaissant */
    const val ExitDuration = 200

    /** Transition de valeur P&L en polling : mise à jour chiffre en direct */
    const val ValueUpdateDuration = 500

    /** Feedback immédiat : ripple, état pressed */
    const val ShortDuration = 100

    /** Durée standard intermédiaire */
    const val MediumDuration = 200

    // ── Easing ────────────────────────────────────────────────────────────────

    /**
     * EmphasizedDecelerate — pour les éléments qui entrent à l'écran.
     * Commence vite, ralentit vers la fin.
     */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /**
     * EmphasizedAccelerate — pour les éléments qui quittent l'écran.
     * Commence lentement, accélère vers la fin.
     */
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /**
     * Emphasized — courbe générale M3 pour les transitions importantes.
     */
    val EmphasizedEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /**
     * Standard — transitions de contenu, crossfade.
     * Material 3 standard curve : accélération douce, décélération symétrique.
     */
    val StandardEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /**
     * Decelerate — entrée depuis l'extérieur.
     */
    val DecelerateEasing: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

    /**
     * Accelerate — sortie vers l'extérieur.
     */
    val AccelerateEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)
}
