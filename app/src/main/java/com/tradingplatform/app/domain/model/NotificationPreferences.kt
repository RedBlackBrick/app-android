package com.tradingplatform.app.domain.model

/** Canaux d'une catégorie de notification. Un canal absent côté serveur vaut « activé ». */
data class ChannelPreferences(
    val inApp: Boolean = true,
    val push: Boolean = true,
    val email: Boolean = true,
)

/**
 * Heures calmes (`HH:MM`, interprétées dans le fuseau `ui.appearance.timezone`, UTC par défaut).
 * Elles coupent push / e-mail / webhook / slack, jamais l'in-app, et jamais les notifications
 * urgentes ou système.
 */
data class QuietHours(
    val enabled: Boolean = false,
    val start: String = "22:00",
    val end: String = "08:00",
)

/**
 * Seuils d'alerte de risque (`null` = seuil désactivé). Les champs `*Pct` sont des FRACTIONS comme
 * partout dans le domaine (0.10 = 10 %), convertis depuis les pourcentages du backend (0..100).
 * Lecture seule sur mobile : la configuration se fait sur le web.
 */
data class RiskAlertThresholds(
    val varPctOfMax: Double? = null,
    val suppressedSignalsPerDay: Int? = null,
    val drawdownWarnPct: Double? = null,
    val positionConcentrationPct: Double? = null,
)

/**
 * Préférences de notification du compte (`GET /v1/auth/preferences`, clé `ui.notifications`).
 *
 * [categories] contient toujours les 3 [NotifCategory] (défaut « tout activé » quand le compte n'a
 * jamais enregistré de préférences). Le JSON brut n'est PAS porté par ce modèle : c'est le
 * repository qui relit puis réécrit l'objet complet à chaque changement (voir
 * `NotificationPreferencesRepository.setPushEnabled`).
 */
data class NotificationPreferences(
    val categories: Map<NotifCategory, ChannelPreferences>,
    val quietHours: QuietHours = QuietHours(),
    val riskAlertThresholds: RiskAlertThresholds = RiskAlertThresholds(),
) {
    fun channelsFor(category: NotifCategory): ChannelPreferences =
        categories[category] ?: ChannelPreferences()

    fun isPushEnabled(category: NotifCategory): Boolean = channelsFor(category).push
}
