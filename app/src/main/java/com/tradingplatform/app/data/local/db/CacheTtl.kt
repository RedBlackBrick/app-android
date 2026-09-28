package com.tradingplatform.app.data.local.db

/**
 * Durées de vie du cache Room — source unique (CLAUDE.md §2 « Stratégie de cache Room »).
 *
 * Deux usages distincts :
 * - **Fraîcheur** (`*_MS`) : au-delà, une donnée est « périmée ». Les lectures unitaires
 *   (`getDeviceStatus`, `getPosition`) ne servent plus le cache sans re-fetch, les widgets
 *   affichent le badge « périmé » (seuil plancher [WIDGET_STALE_GRACE_MS]) et `ui.components.CacheTimestamp` passe en couleur offline.
 * - **Rétention** (cutoff de purge exécutée APRÈS une sync réussie) : égale à la fraîcheur,
 *   sauf pour `pnl_snapshots` et `alerts` (voir [PNL_RETENTION_MS], [ALERTS_RETENTION_MS]).
 */
object CacheTtl {
    /** `positions` — fraîcheur et cutoff de purge. */
    const val POSITIONS_MS: Long = 5 * 60 * 1000L

    /** `pnl_snapshots` — fraîcheur (badge « périmé » du PnlWidget). */
    const val PNL_MS: Long = 5 * 60 * 1000L

    /**
     * `pnl_snapshots` — cutoff de purge. Une ligne par période (`period` = PK) : une purge à
     * [PNL_MS] exécutée après la sync d'UNE période supprimerait la ligne d'une autre période
     * (semaine/mois configurées par une autre instance de widget) dès qu'elle a plus de 5 min.
     * 24 h ne purge que les périodes abandonnées (widget supprimé).
     */
    const val PNL_RETENTION_MS: Long = 24 * 60 * 60 * 1000L

    /** `quotes` — fraîcheur et cutoff de purge. */
    const val QUOTES_MS: Long = 10 * 60 * 1000L

    /** `devices` — fraîcheur et cutoff de purge. */
    const val DEVICES_MS: Long = 60 * 1000L

    /** `alerts` — rétention 30 jours (et [ALERTS_MAX_ROWS] lignes max, cf. `AlertDao`). */
    const val ALERTS_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000L

    /** `alerts` — nombre max de lignes conservées (valeur figée dans `AlertDao.keepOnlyLatest500`). */
    const val ALERTS_MAX_ROWS: Int = 500

    /**
     * Plancher du seuil « périmé » des widgets : période du `WidgetUpdateWorker` (15 min,
     * plancher OS) + 5 min de grâce. Sans ce plancher, un TTL de 5-10 min afficherait le
     * badge pendant la majeure partie de chaque cycle. Seuil widget =
     * `maxOf(TTL entité, WIDGET_STALE_GRACE_MS)` ; l'UI in-app garde les TTL d'entité.
     */
    const val WIDGET_STALE_GRACE_MS: Long = 20 * 60 * 1000L

    /** Seuil par défaut de `ui.components.CacheTimestamp` (« Données du HH:mm » au-delà). */
    const val DEFAULT_UI_MS: Long = 10 * 60 * 1000L

    /** `true` si [syncedAt] a moins de [ttlMs] (un horodatage dans le futur est considéré périmé). */
    fun isFresh(syncedAt: Long, ttlMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        val age = now - syncedAt
        return age in 0 until ttlMs
    }
}
