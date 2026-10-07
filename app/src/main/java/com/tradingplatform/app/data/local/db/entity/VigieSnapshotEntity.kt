package com.tradingplatform.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cache Room de la Vigie — **une seule ligne** ([id] = [SINGLE_ROW_ID]), remplacée à chaque sync
 * réussie par `VigieRepositoryImpl` (unique écrivain ; la carte Accueil, l'écran détail et le
 * `WidgetUpdateWorker` passent tous par `GetVigieUseCase`). Lue par `VigieWidget` (colonnes
 * scalaires, sans parser de JSON) et par le repository (payload complet pour l'écran détail hors
 * ligne).
 *
 * Seule une réponse `available = true` est stockée : « pas de donnée » n'est jamais persisté (la
 * ligne est supprimée), le widget affiche alors « Pas encore de donnée », jamais un niveau.
 *
 * - [score] `null` quand [level] vaut `unknown` ; [level] = niveau CONFIRMÉ, [levelRaw] = brut du jour.
 * - [asOf] = date `YYYY-MM-DD` du calcul serveur ; [computedAt] = instant serveur (epoch ms, `null`
 *   si illisible) ; [syncedAt] = instant de la dernière sync réussie CÔTÉ APP (fraîcheur du cache).
 * - [payloadJson] = la réponse complète ([com.tradingplatform.app.data.model.VigieDto] sérialisé :
 *   familles, indicateurs, régime, refuge, courbe 90 j) — l'écran détail doit rester lisible hors
 *   ligne. Les colonnes scalaires en sont une projection (même écrivain, jamais de divergence).
 */
@Entity(
    tableName = "vigie_snapshot",
    indices = [Index(value = ["synced_at"])],
)
data class VigieSnapshotEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = SINGLE_ROW_ID,
    @ColumnInfo(name = "as_of") val asOf: String,
    @ColumnInfo(name = "computed_at") val computedAt: Long?,
    @ColumnInfo(name = "score") val score: Double?,
    @ColumnInfo(name = "level") val level: String,
    @ColumnInfo(name = "level_raw") val levelRaw: String,
    @ColumnInfo(name = "refuge_state") val refugeState: String,
    @ColumnInfo(name = "coverage") val coverage: Double?,
    @ColumnInfo(name = "live_indicators") val liveIndicators: Int,
    @ColumnInfo(name = "expected_indicators") val expectedIndicators: Int,
    @ColumnInfo(name = "most_stressed_family_code") val mostStressedFamilyCode: String?,
    @ColumnInfo(name = "most_stressed_family_label") val mostStressedFamilyLabel: String?,
    @ColumnInfo(name = "most_stressed_family_score") val mostStressedFamilyScore: Double?,
    @ColumnInfo(name = "is_stale") val isStale: Boolean,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
) {
    companion object {
        const val SINGLE_ROW_ID = 1
    }
}
