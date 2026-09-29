package com.tradingplatform.app.data.repository

import com.tradingplatform.app.domain.model.ChannelPreferences
import com.tradingplatform.app.domain.model.NotifCategory
import com.tradingplatform.app.domain.model.NotificationPreferences
import com.tradingplatform.app.domain.model.QuietHours
import com.tradingplatform.app.domain.model.RiskAlertThresholds
import kotlin.math.abs

/**
 * Lecture / réécriture de `ui.notifications` et `ui.appearance` sur l'arbre JSON BRUT
 * (`Map<String, Any?>` / `List<Any?>` / `String` / `Boolean` / `Number` / `null`, tel que Moshi le
 * produit) — fonctions pures, sans réseau.
 *
 * Pourquoi un arbre brut : le backend fusionne `ui` de façon SUPERFICIELLE (`existing_ui.update(body.ui)`),
 * donc chaque clé de premier niveau envoyée (`notifications`, `appearance`) remplace intégralement
 * l'objet stocké. Pour ne perdre aucune clé (y compris celles que le mobile ne connaît pas, écrites
 * par le web), on modifie le seul booléen `push` de la catégorie visée et on renvoie tout le reste
 * tel quel, dans le même ordre.
 *
 * Nombres : Moshi lit tout nombre JSON en `Double`. Un entier (`25`) redeviendrait `25.0` à
 * l'écriture, que certains champs backend entiers (`suppressedSignalsPerDay`) pourraient refuser ;
 * [normalizeNumbers] rend donc les `Double` sans partie fractionnaire en `Long`.
 */
internal object NotificationPreferencesJson {

    private const val KEY_NOTIFICATIONS = "notifications"
    private const val KEY_APPEARANCE = "appearance"
    private const val KEY_NOTIF_TYPES = "notifTypes"
    private const val KEY_QUIET_HOURS = "quietHours"
    private const val KEY_THRESHOLDS = "riskAlertThresholds"
    private const val KEY_CATEGORY = "key"

    /** Plus grand entier exactement représentable en `Double` (2^53). */
    private const val MAX_EXACT_DOUBLE_INTEGER = 9_007_199_254_740_992.0

    private const val PERCENT = 100.0

    /**
     * Préférences lisibles depuis `ui` (peut être `null` / vide pour un compte neuf : tout activé).
     * Tolérant : une valeur du mauvais type retombe sur le défaut du champ.
     */
    fun parse(ui: Map<String, Any?>?): NotificationPreferences {
        val notifications = asObject(ui?.get(KEY_NOTIFICATIONS))
        val types = (notifications?.get(KEY_NOTIF_TYPES) as? List<*>).orEmpty()

        val categories = NotifCategory.entries.associateWith { category ->
            val entry = types.asSequence()
                .mapNotNull { asObject(it) }
                .firstOrNull { it[KEY_CATEGORY] == category.wireKey }
            ChannelPreferences(
                inApp = entry?.get("inApp") as? Boolean ?: true,
                push = entry?.get("push") as? Boolean ?: true,
                email = entry?.get("email") as? Boolean ?: true,
            )
        }

        val quiet = asObject(notifications?.get(KEY_QUIET_HOURS))
        val quietDefaults = QuietHours()
        val quietHours = QuietHours(
            enabled = quiet?.get("enabled") as? Boolean ?: quietDefaults.enabled,
            start = quiet?.get("start") as? String ?: quietDefaults.start,
            end = quiet?.get("end") as? String ?: quietDefaults.end,
        )

        val thresholds = asObject(notifications?.get(KEY_THRESHOLDS))
        val riskAlertThresholds = RiskAlertThresholds(
            varPctOfMax = (thresholds?.get("varPctOfMax") as? Number)?.toDouble()?.div(PERCENT),
            suppressedSignalsPerDay = (thresholds?.get("suppressedSignalsPerDay") as? Number)?.toInt(),
            drawdownWarnPct = (thresholds?.get("drawdownWarnPct") as? Number)?.toDouble()?.div(PERCENT),
            positionConcentrationPct =
                (thresholds?.get("positionConcentrationPct") as? Number)?.toDouble()?.div(PERCENT),
        )

        return NotificationPreferences(categories, quietHours, riskAlertThresholds)
    }

    /**
     * Corps du PATCH (valeur de la clé `ui`) : `notifications` COMPLET (seul `push` de [category]
     * change) puis `appearance` COMPLET (inchangé) quand il existe. Les autres clés de premier
     * niveau de `ui` ne sont pas envoyées : elles survivent côté serveur.
     *
     * - Catégorie absente de `notifTypes` (ou `notifTypes` absent) : une entrée est ajoutée avec
     *   `inApp` / `email` à `true` (défaut backend) et `push = enabled`. Les autres catégories
     *   absentes ne sont pas matérialisées (absente = tout autorisé côté backend, inchangé).
     * - Entrées et clés inconnues (dans `notifTypes[]`, `notifications`, `appearance`) : conservées.
     * - `appearance` absent : non envoyé (rien à préserver).
     */
    fun withPushEnabled(
        ui: Map<String, Any?>?,
        category: NotifCategory,
        enabled: Boolean,
    ): Map<String, Any?> {
        val notifications = asObject(normalizeNumbers(ui?.get(KEY_NOTIFICATIONS)))
            ?: LinkedHashMap<String, Any?>()
        val types = ArrayList<Any?>((notifications[KEY_NOTIF_TYPES] as? List<*>).orEmpty())

        var found = false
        for (index in types.indices) {
            val entry = asObject(types[index]) ?: continue
            if (entry[KEY_CATEGORY] == category.wireKey) {
                entry["push"] = enabled
                types[index] = entry
                found = true
            }
        }
        if (!found) {
            types.add(
                linkedMapOf<String, Any?>(
                    KEY_CATEGORY to category.wireKey,
                    "inApp" to true,
                    "push" to enabled,
                    "email" to true,
                ),
            )
        }
        notifications[KEY_NOTIF_TYPES] = types

        val body = LinkedHashMap<String, Any?>()
        body[KEY_NOTIFICATIONS] = notifications
        asObject(normalizeNumbers(ui?.get(KEY_APPEARANCE)))?.let { body[KEY_APPEARANCE] = it }
        return body
    }

    /** Copie profonde de [value] où tout `Double` entier devient `Long` (ordre des clés conservé). */
    fun normalizeNumbers(value: Any?): Any? = when (value) {
        is Map<*, *> -> {
            val copy = LinkedHashMap<String, Any?>(value.size)
            for ((key, item) in value) {
                if (key is String) copy[key] = normalizeNumbers(item)
            }
            copy
        }
        is List<*> -> value.mapTo(ArrayList<Any?>(value.size)) { normalizeNumbers(it) }
        is Double ->
            if (value % 1.0 == 0.0 && abs(value) < MAX_EXACT_DOUBLE_INTEGER) value.toLong() else value
        else -> value
    }

    /** Copie mutable d'un objet JSON (clés `String` seulement) ; `null` si [value] n'est pas un objet. */
    private fun asObject(value: Any?): LinkedHashMap<String, Any?>? {
        if (value !is Map<*, *>) return null
        val copy = LinkedHashMap<String, Any?>(value.size)
        for ((key, item) in value) {
            if (key is String) copy[key] = item
        }
        return copy
    }
}
