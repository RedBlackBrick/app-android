package com.tradingplatform.app.domain.usecase.write

import com.tradingplatform.app.BuildConfig
import com.tradingplatform.app.data.session.TokenHolder
import com.tradingplatform.app.vpn.SystemVpnMonitor
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import javax.inject.Inject

/** Résultat de la garde d'écriture : l'UI n'ouvre la confirmation que si [Allowed]. */
sealed interface WriteGate {
    data object Allowed : WriteGate

    /** [message] : texte FR prêt à afficher (bouton désactivé + explication). */
    data class Blocked(val reason: WriteBlockReason, val message: String) : WriteGate
}

enum class WriteBlockReason { VPN_NOT_CONNECTED, DATA_STALE, NOT_LOGGED_IN }

/**
 * Garde d'écriture : « a-t-on le droit de PROPOSER une action d'écriture maintenant ? ».
 *
 * Trois conditions, toutes nécessaires :
 * 1. **Session** : un access token est présent en mémoire ([TokenHolder]).
 * 2. **VPN** : tunnel WireGuard intégré `Connected` OU VPN système tiers actif (décision D6) —
 *    même politique que `VpnRequiredInterceptor`, qui reste le vrai garde réseau.
 * 3. **Fraîcheur** : la donnée sur laquelle l'utilisateur va agir (ex. l'ordre à annuler) a été
 *    lue il y a moins de `maxAgeMs`. Sinon l'écran est probablement en retard sur le serveur : on
 *    n'agit pas sur un état périmé.
 *
 * **Priorité quand plusieurs conditions échouent** (une seule raison est renvoyée, la plus
 * fondamentale d'abord) : [WriteBlockReason.NOT_LOGGED_IN] > [WriteBlockReason.VPN_NOT_CONNECTED]
 * > [WriteBlockReason.DATA_STALE]. Sans session rien d'autre ne sert ; sans VPN, « actualisez les
 * données » serait un conseil impossible à suivre (le rafraîchissement exige le tunnel).
 *
 * Cette garde est une aide UI (bouton désactivé, message clair) : elle ne remplace ni la
 * confirmation biométrique ni les intercepteurs. Sur un `DEV_MODE` (émulateur, jamais en release),
 * le VPN n'est pas exigé — comme dans `VpnRequiredInterceptor`.
 *
 * Non-suspend : [SystemVpnMonitor.isActiveNow] est une lecture synchrone de `ConnectivityManager`
 * (aucun accès réseau).
 */
class EvaluateWriteGateUseCase internal constructor(
    private val wireGuardManager: WireGuardManager,
    private val systemVpnMonitor: SystemVpnMonitor,
    private val tokenHolder: TokenHolder,
    private val clock: () -> Long,
    private val vpnRequired: Boolean,
) {

    @Inject
    constructor(
        wireGuardManager: WireGuardManager,
        systemVpnMonitor: SystemVpnMonitor,
        tokenHolder: TokenHolder,
    ) : this(
        wireGuardManager = wireGuardManager,
        systemVpnMonitor = systemVpnMonitor,
        tokenHolder = tokenHolder,
        clock = System::currentTimeMillis,
        vpnRequired = !BuildConfig.DEV_MODE,
    )

    /**
     * @param dataSyncedAt horodatage (epoch ms) de la lecture de la donnée visée ; `null` = jamais
     *   lue / origine inconnue → [WriteBlockReason.DATA_STALE].
     * @param maxAgeMs âge maximal toléré (inclus) ; au-delà, ou pour un horodatage dans le futur
     *   (horloge reculée), la donnée est considérée périmée.
     */
    operator fun invoke(dataSyncedAt: Long?, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): WriteGate {
        if (tokenHolder.accessToken.isNullOrBlank()) {
            return WriteGate.Blocked(WriteBlockReason.NOT_LOGGED_IN, MESSAGE_NOT_LOGGED_IN)
        }
        if (vpnRequired && !isTunnelActive()) {
            return WriteGate.Blocked(WriteBlockReason.VPN_NOT_CONNECTED, MESSAGE_VPN_NOT_CONNECTED)
        }
        if (!isDataFresh(dataSyncedAt, clock(), maxAgeMs)) {
            return WriteGate.Blocked(WriteBlockReason.DATA_STALE, MESSAGE_DATA_STALE)
        }
        return WriteGate.Allowed
    }

    private fun isTunnelActive(): Boolean {
        val inApp = wireGuardManager.state.value
        if (inApp is VpnState.Connected || inApp is VpnState.SystemVpnActive) return true
        // Même relecture de secours que VpnRequiredInterceptor : les callbacks du moniteur peuvent
        // être en retard sur un VPN monté depuis une autre app.
        return systemVpnMonitor.active.value || systemVpnMonitor.isActiveNow()
    }

    companion object {
        /** Âge maximal par défaut de la donnée visée par une écriture. */
        const val DEFAULT_MAX_AGE_MS: Long = 60_000L

        const val MESSAGE_NOT_LOGGED_IN = "Session expirée — reconnectez-vous."
        const val MESSAGE_VPN_NOT_CONNECTED = "VPN requis — activez le tunnel puis réessayez."
        const val MESSAGE_DATA_STALE = "Données trop anciennes — actualisez l'écran avant d'agir."

        /** `true` si [syncedAt] est connu et son âge est dans `[0, maxAgeMs]`. */
        internal fun isDataFresh(syncedAt: Long?, now: Long, maxAgeMs: Long): Boolean {
            if (syncedAt == null) return false
            val age = now - syncedAt
            return age in 0L..maxAgeMs
        }
    }
}
