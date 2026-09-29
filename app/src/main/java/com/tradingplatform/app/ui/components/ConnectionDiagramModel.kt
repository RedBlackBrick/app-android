package com.tradingplatform.app.ui.components

import com.tradingplatform.app.vpn.VpnState

/*
 * Logique pure des schémas de connexion ([VpnTunnelDiagram], [DeviceLinkDiagram]) — aucune
 * dépendance Compose/Android pour rester testable en JVM. Les composables résolvent la teinte en
 * couleur du thème (jamais de couleur en dur) et combinent [ConnectionDiagramSpec.animated] avec
 * le réglage système « réduire les animations » ([isMotionAllowed]).
 */

/** Teinte sémantique : ACTIVE → `success`, PENDING → `warning`, INACTIVE → gris (`onSurfaceVariant`). */
enum class DiagramTone { ACTIVE, PENDING, INACTIVE }

/** Aspect de la liaison entre les deux extrémités. */
enum class LinkStyle {
    /** Tube plein avec des points qui circulent dans les deux sens. */
    FLOWING,

    /** Tunnel en pointillés qui pulse (établissement en cours). */
    DASHED,

    /** Ligne grise interrompue par une croix. */
    CUT,
}

/**
 * @property animated vrai si le schéma bouge quand les animations sont autorisées (points
 *   circulants, pulsation) ; faux pour une liaison coupée — rendu toujours statique.
 * @property label libellé affiché sous le schéma.
 * @property description texte lu par TalkBack (le schéma est une seule unité sémantique).
 */
data class ConnectionDiagramSpec(
    val tone: DiagramTone,
    val link: LinkStyle,
    val animated: Boolean,
    val label: String,
    val description: String,
)

/** Schéma « téléphone — tunnel — serveur » selon l'état VPN affiché. */
fun vpnTunnelSpec(state: VpnState): ConnectionDiagramSpec = when (state) {
    is VpnState.Connected -> ConnectionDiagramSpec(
        tone = DiagramTone.ACTIVE,
        link = LinkStyle.FLOWING,
        animated = true,
        label = "Tunnel chiffré actif",
        description = "Schéma du tunnel VPN : le téléphone est relié au serveur par un tunnel chiffré actif",
    )
    is VpnState.SystemVpnActive -> ConnectionDiagramSpec(
        tone = DiagramTone.ACTIVE,
        link = LinkStyle.FLOWING,
        animated = true,
        label = "VPN externe actif",
        description = "Schéma du tunnel VPN : le téléphone est relié au serveur par un VPN externe actif",
    )
    is VpnState.Connecting -> ConnectionDiagramSpec(
        tone = DiagramTone.PENDING,
        link = LinkStyle.DASHED,
        animated = true,
        label = "Connexion…",
        description = "Schéma du tunnel VPN : connexion au serveur en cours",
    )
    is VpnState.Disconnected, is VpnState.ConsentRequired, is VpnState.Error -> ConnectionDiagramSpec(
        tone = DiagramTone.INACTIVE,
        link = LinkStyle.CUT,
        animated = false,
        label = "Non connecté",
        description = "Schéma du tunnel VPN : téléphone non connecté au serveur, liaison coupée",
    )
}

/** Schéma « Radxa ↔ serveur via WireGuard » selon que le device est en ligne. */
fun deviceLinkSpec(online: Boolean): ConnectionDiagramSpec =
    if (online) {
        ConnectionDiagramSpec(
            tone = DiagramTone.ACTIVE,
            link = LinkStyle.FLOWING,
            animated = true,
            label = "Liaison WireGuard active",
            description = "Liaison de l'appareil Radxa au serveur par WireGuard : en ligne",
        )
    } else {
        ConnectionDiagramSpec(
            tone = DiagramTone.INACTIVE,
            link = LinkStyle.CUT,
            animated = false,
            label = "Hors ligne — liaison interrompue",
            description = "Liaison de l'appareil Radxa au serveur par WireGuard : hors ligne, liaison interrompue",
        )
    }

/**
 * Les animations ne tournent que si le réglage système « durée des animations »
 * (`Settings.Global.ANIMATOR_DURATION_SCALE`) n'est pas à zéro et hors aperçu Android Studio
 * (`LocalInspectionMode`) : sinon rendu statique.
 */
fun isMotionAllowed(animatorDurationScale: Float, inspectionMode: Boolean): Boolean =
    !inspectionMode && animatorDurationScale != 0f
