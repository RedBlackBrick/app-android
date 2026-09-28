package com.tradingplatform.app.ui.common

/**
 * État d'une donnée rafraîchissable affichée à l'écran (audit #21).
 *
 * Contrairement à un `sealed Loading | Success | Error`, la dernière valeur connue
 * n'est **jamais** perdue pendant un refresh ou après un échec : l'écran continue
 * d'afficher [value] (éventuellement périmée) au lieu de repasser en skeleton ou en
 * carte d'erreur à chaque mise à jour en arrière-plan.
 *
 * - [isInitialLoading] : aucun résultat encore (ni valeur ni erreur) et un chargement en
 *   cours → seul cas où l'écran affiche un skeleton.
 * - `value != null && error != null` : valeur périmée (dernier succès) + erreur du dernier
 *   refresh → afficher la valeur avec son horodatage [syncedAt] et signaler l'erreur.
 * - `value == null && error != null` : jamais chargé → carte d'erreur.
 *
 * @property value Dernière valeur connue (dernier succès ou patch temps réel).
 * @property isRefreshing Un chargement est en vol (initial ou en arrière-plan).
 * @property error Message du dernier échec ; remis à `null` au succès suivant.
 * @property syncedAt Epoch millis de la dernière mise à jour de [value] ; `0L` = jamais.
 */
data class DataState<T>(
    val value: T? = null,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val syncedAt: Long = 0L,
) {
    /** Premier chargement en cours : ni valeur ni erreur encore. */
    val isInitialLoading: Boolean
        get() = value == null && error == null && isRefreshing

    /** Démarre un (re)chargement en conservant la valeur et l'erreur courantes. */
    fun loading(): DataState<T> = copy(isRefreshing = true)

    /** Chargement réussi : nouvelle valeur, erreur effacée. */
    fun success(v: T, now: Long): DataState<T> = DataState(v, false, null, now)

    /** Chargement échoué : la valeur précédente est conservée (périmée). */
    fun failure(msg: String): DataState<T> = copy(isRefreshing = false, error = msg)
}
