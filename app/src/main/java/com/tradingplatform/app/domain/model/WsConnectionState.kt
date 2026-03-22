package com.tradingplatform.app.domain.model

/**
 * Etat de la connexion WebSocket privee, expose a l'UI (F5).
 *
 * L'UI affiche un indicateur discret (dot colore) dans le TopAppBar du Dashboard :
 * - [Connected]     : dot vert — donnees en temps reel
 * - [Connecting]    : dot orange — tentative de connexion en cours
 * - [Disconnected]  : dot rouge — pas de donnees temps reel (polling REST actif)
 * - [Degraded]      : dot orange — connexion instable (reconnexions frequentes)
 *
 * Pour eviter le "flicker" lors de transitions rapides (Connected -> Reconnecting -> Connected),
 * le ViewModel applique un debounce de 2s avant de propager [Disconnected] a l'UI.
 */
enum class WsConnectionState {
    /** WebSocket connecte et authentifie — donnees temps reel. */
    Connected,

    /** Tentative de connexion en cours (backoff ou premier connect). */
    Connecting,

    /** Deconnecte — pas de donnees temps reel, fallback polling REST. */
    Disconnected,

    /**
     * Connexion instable — reconnexions frequentes observees.
     * L'UI peut afficher "Connexion instable" au lieu de faire clignoter vert/rouge.
     */
    Degraded,
}
