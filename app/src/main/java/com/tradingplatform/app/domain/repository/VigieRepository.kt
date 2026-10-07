package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.Cached
import com.tradingplatform.app.domain.model.Vigie

/** Vigie « Refuge » (`GET /v1/vigie`) avec cache Room à ligne unique. */
interface VigieRepository {

    /**
     * Lit la Vigie. Le cache n'est servi que s'il est frais (`CacheTtl.VIGIE_MS`) et que
     * [forceRefresh] vaut `false` ; sinon réseau → Room. Si le réseau échoue et qu'une ligne
     * périmée existe, elle est renvoyée avec son VRAI `syncedAt` (affichage « hors ligne », pas
     * d'erreur bloquante) ; sans cache, c'est un `failure`.
     *
     * Une réponse `available = false` n'est pas une erreur : `success(Cached(Vigie.UNAVAILABLE))`,
     * rien n'est persisté.
     */
    suspend fun getVigie(forceRefresh: Boolean): Result<Cached<Vigie>>
}
