package com.tradingplatform.app.domain.model

/**
 * Connexion broker d'un portefeuille (`GET /v1/portfolios/{id}/broker-connection`).
 *
 * [connectionStatus] est un statut de **configuration** côté backend (`active`, `inactive`,
 * `error`, `maintenance`, `revoked`, `pending`), pas une santé en direct. Le repository renvoie
 * `null` (et non ce type) quand le portefeuille n'a aucune connexion broker.
 */
data class PortfolioBrokerStatus(val brokerCode: String?, val connectionStatus: String?)
