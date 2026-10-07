package com.tradingplatform.app.data.repository

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.tradingplatform.app.data.api.VigieApi
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.dao.VigieDao
import com.tradingplatform.app.data.local.db.entity.VigieSnapshotEntity
import com.tradingplatform.app.data.model.VigieDto
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.Cached
import com.tradingplatform.app.domain.model.Vigie
import com.tradingplatform.app.domain.model.VigieRefugeState
import com.tradingplatform.app.domain.repository.VigieRepository
import com.tradingplatform.app.domain.util.runCatchingCancellable
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Vigie : réseau → Room (ligne unique `vigie_snapshot`), fraîcheur `CacheTtl.VIGIE_MS`.
 *
 * Unique écrivain de `vigie_snapshot` (carte Accueil, écran détail et `WidgetUpdateWorker` passent
 * tous par [getVigie]). Upsert + purge dans UNE transaction, après une réponse réussie seulement ;
 * une réponse `available = false` supprime la ligne (le serveur n'a plus de donnée : le widget
 * affiche « Pas encore de donnée », jamais un ancien niveau).
 */
@Singleton
class VigieRepositoryImpl @Inject constructor(
    private val api: VigieApi,
    private val dao: VigieDao,
    moshi: Moshi,
) : VigieRepository {

    private val payloadAdapter: JsonAdapter<VigieDto> = moshi.adapter(VigieDto::class.java)

    override suspend fun getVigie(forceRefresh: Boolean): Result<Cached<Vigie>> = runCatchingCancellable {
        val now = System.currentTimeMillis()
        val cachedRow = dao.get()
        // Une ligne illisible (payload corrompu) vaut « pas de cache » : jamais une valeur inventée.
        val cached: Cached<Vigie>? = cachedRow?.let { row ->
            decode(row)?.let { Cached(it, row.syncedAt) }
        }

        if (cached != null && !forceRefresh && CacheTtl.isFresh(cached.syncedAt, CacheTtl.VIGIE_MS, now)) {
            return@runCatchingCancellable cached
        }

        try {
            fetchAndCache(now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Réseau / VPN indisponible : la ligne périmée est servie avec son vrai horodatage
            // (CacheTimestamp la marque « hors ligne ») plutôt qu'une erreur bloquante.
            if (cached != null) return@runCatchingCancellable cached
            throw e
        }
    }

    private suspend fun fetchAndCache(now: Long): Cached<Vigie> {
        val response = api.getVigie()
        if (!response.isSuccessful) throw HttpStatusException(response.code(), ENDPOINT)
        val dto = response.body() ?: error("Empty vigie response")
        val vigie = dto.toDomain()

        if (!vigie.available) {
            dao.deleteAll()
            return Cached(Vigie.UNAVAILABLE, now)
        }

        dao.upsertAndPurge(
            snapshot = vigie.toEntity(payloadAdapter.toJson(dto), syncedAt = now),
            cutoffMillis = now - CacheTtl.VIGIE_MS,
        )
        return Cached(vigie, now)
    }

    private fun decode(row: VigieSnapshotEntity): Vigie? = try {
        payloadAdapter.fromJson(row.payloadJson)?.toDomain()?.takeIf { it.available }
    } catch (e: Exception) {
        Timber.w("VigieRepositoryImpl: cached payload unreadable — ignored (${e.javaClass.simpleName})")
        null
    }

    private companion object {
        const val ENDPOINT = "v1/vigie"
    }
}

/**
 * Projection scalaire d'une Vigie disponible vers la ligne Room (lue par `VigieWidget` sans parser
 * de JSON). [payloadJson] = la réponse complète sérialisée.
 */
internal fun Vigie.toEntity(payloadJson: String, syncedAt: Long): VigieSnapshotEntity {
    val stressed = mostStressedFamily
    return VigieSnapshotEntity(
        asOf = requireNotNull(asOf) { "available Vigie without as_of" }.toString(),
        computedAt = computedAt?.toEpochMilli(),
        score = score,
        level = level.wire,
        levelRaw = levelRaw.wire,
        refugeState = (refuge?.state ?: VigieRefugeState.UNKNOWN).wire,
        coverage = coverage,
        liveIndicators = liveIndicators,
        expectedIndicators = expectedIndicators,
        mostStressedFamilyCode = stressed?.code,
        mostStressedFamilyLabel = stressed?.label,
        mostStressedFamilyScore = stressed?.score,
        isStale = isStale,
        payloadJson = payloadJson,
        syncedAt = syncedAt,
    )
}
