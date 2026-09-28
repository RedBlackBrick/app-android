package com.tradingplatform.app.domain.model

/**
 * Valeur lue depuis le cache local ou le réseau, accompagnée de l'instant réel de sa dernière
 * synchronisation (epoch millis). L'UI affiche ainsi l'âge vrai de la donnée plutôt que
 * `System.currentTimeMillis()` au moment de l'affichage.
 */
data class Cached<out T>(
    val value: T,
    val syncedAt: Long,
)
