package com.tradingplatform.app.data.websocket

import com.tradingplatform.app.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Valeur de l'en-tête `Origin` du handshake WebSocket.
 *
 * Le backend (`app/websocket/manager.py:_check_origin`, `/ws/public` et `/v1/ws/private`) rejette
 * en 403 tout handshake sans `Origin` dès que `WS_ALLOWED_ORIGINS` est renseigné (imposé en prod),
 * puis compare la valeur **exactement** (minuscules, sans `/` final, port par défaut omis) à sa
 * liste. OkHttp n'en envoie jamais : sans ce header le temps réel (positions, P&L, cours) restait
 * mort, en reconnexions infinies.
 *
 * Ordre de résolution :
 * 1. [override] non vide (`WS_ORIGIN` dans `local.properties`, à aligner sur `WS_ALLOWED_ORIGINS`) ;
 * 2. dérivé de [baseUrl] : `scheme://host` + port seulement s'il n'est pas celui du schéma
 *    (`https://10.42.0.1:443` → `https://10.42.0.1`, comme un navigateur).
 *
 * Retourne null si aucune origine ne peut être déterminée (URL invalide) : le header n'est alors
 * pas posé, ce qui reproduit l'ancien comportement.
 */
internal fun wsOrigin(baseUrl: String, override: String = BuildConfig.WS_ORIGIN): String? {
    override.trim().trimEnd('/').lowercase().takeIf { it.isNotEmpty() }?.let { return it }
    val url: HttpUrl = baseUrl.toHttpUrlOrNull() ?: return null
    val host = if (':' in url.host) "[${url.host}]" else url.host
    val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
    return "${url.scheme}://$host$port".lowercase()
}
