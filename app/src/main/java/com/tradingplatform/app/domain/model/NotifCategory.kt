package com.tradingplatform.app.domain.model

/**
 * Les 3 catégories de préférences de notification du backend (`ui.notifications.notifTypes[].key`).
 * Le backend n'en connaît pas d'autre : chaque `type` de notification est rattaché à l'une d'elles
 * (`strategy_signal` : signaux et dégradations de stratégie ; `risk_alert` : alertes de risque ;
 * `system` : système, devices, réseau).
 */
enum class NotifCategory(val wireKey: String) {
    STRATEGY_SIGNAL("strategy_signal"),
    RISK_ALERT("risk_alert"),
    SYSTEM("system"),
    ;

    companion object {
        /** `null` si [key] n'est pas une catégorie connue (clé inconnue : à ignorer, jamais à supprimer). */
        fun fromWireKey(key: String): NotifCategory? = entries.firstOrNull { it.wireKey == key }
    }
}
