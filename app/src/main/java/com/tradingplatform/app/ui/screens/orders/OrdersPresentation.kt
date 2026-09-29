package com.tradingplatform.app.ui.screens.orders

import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.model.OrderSide
import com.tradingplatform.app.domain.model.OrderStatus
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.usecase.write.EvaluateWriteGateUseCase
import com.tradingplatform.app.ui.components.ConfirmAction
import com.tradingplatform.app.vpn.VpnNotConnectedException

// Règles pures de l'écran Ordres (annulation) — testées en JVM (OrdersPresentationTest).
// Wording : une annulation acceptée ne garantit RIEN côté broker (contrat backend §5.4, §9.5) →
// on dit « demandée », jamais « annulé », et l'état final n'est affiché qu'après relecture.

/** Libellé de la ligne d'un ordre dont l'annulation a été demandée et pas encore résolue. */
internal const val CANCEL_REQUESTED_LABEL = "Annulation demandée"

internal const val CANCEL_NOT_CANCELLABLE_MESSAGE = "Cet ordre n'est plus annulable — liste actualisée."

internal const val CANCEL_VERIFICATION_FAILED_MESSAGE =
    "Annulation demandée — impossible de vérifier l'état, actualisez la liste."

/**
 * Le bouton « Annuler » est-il proposé pour un ordre dans cet état ?
 *
 * Annulables côté backend : `pending`, `submitted`, `partial`, `pending_cancel`, `rollover_pending`,
 * `pending_retry`. Non annulables (409) : `pending_approval` et les 5 états terminaux. Nous
 * n'affichons pas non plus le bouton pour `pending_cancel` : l'annulation est déjà en cours (la
 * ligne le dit) et redemander n'apporterait rien. Statut inconnu ou absent : pas de bouton, par
 * prudence.
 */
internal fun OrderStatus?.isCancellable(): Boolean = when (this) {
    OrderStatus.PENDING,
    OrderStatus.SUBMITTED,
    OrderStatus.PARTIAL,
    OrderStatus.ROLLOVER_PENDING,
    OrderStatus.PENDING_RETRY,
    -> true

    OrderStatus.PENDING_APPROVAL,
    OrderStatus.PENDING_CANCEL,
    OrderStatus.FILLED,
    OrderStatus.CANCELLED,
    OrderStatus.REJECTED,
    OrderStatus.EXPIRED,
    OrderStatus.ERROR,
    OrderStatus.UNKNOWN,
    null,
    -> false
}

internal fun orderSideLabel(side: OrderSide): String = when (side) {
    OrderSide.BUY -> "Achat"
    OrderSide.SELL -> "Vente"
}

/** Quantité lisible pour le récapitulatif (« 10 » et non « 10.000 ») ; « — » si inconnue. */
internal fun cancelQuantityText(order: Order): String =
    order.quantity?.stripTrailingZeros()?.toPlainString() ?: "—"

/**
 * Récapitulatif de la feuille de confirmation : symbole, sens, quantité, et l'avertissement que le
 * broker peut refuser ou ignorer l'annulation. Bouton final destructif (couleur `error`).
 */
internal fun cancelConfirmAction(order: Order): ConfirmAction = ConfirmAction(
    title = "Demander l'annulation de l'ordre ?",
    summaryLines = listOf(
        "Symbole" to order.symbol,
        "Sens" to orderSideLabel(order.side),
        "Quantité" to cancelQuantityText(order),
    ),
    confirmLabel = "Demander l'annulation",
    destructive = true,
    message = "Le broker peut refuser ou ignorer l'annulation.",
)

/** Message juste après l'envoi de la demande (avant la relecture). */
internal fun cancelRequestedMessage(outcome: WriteOutcome): String = when (outcome) {
    WriteOutcome.CONFIRMED -> "Annulation demandée — vérification en cours"
    WriteOutcome.REQUESTED_UNCONFIRMED ->
        "Annulation demandée — état non confirmé, vérification en cours"
}

/**
 * Message après la relecture des ordres actifs : ce que dit le serveur, sans en dire plus. Un
 * ordre absent de la liste n'est « plus dans les ordres actifs » (pas « annulé » : le broker a pu
 * l'exécuter ou ignorer l'annulation — l'état final est dans « Terminés »).
 */
internal fun cancelRereadMessage(orderId: Long, activeOrders: List<Order>): String {
    val order = activeOrders.firstOrNull { it.id == orderId }
    return when {
        order == null -> "Annulation demandée — l'ordre n'est plus dans les ordres actifs"
        order.status == OrderStatus.PENDING_CANCEL -> "Annulation demandée — en cours de traitement"
        else -> "Annulation demandée — l'ordre est toujours actif"
    }
}

/**
 * Message d'un échec CERTAIN de la demande (le use case ne rejoue jamais ; un 5xx ou un timeout
 * après envoi ne passe pas ici : c'est `REQUESTED_UNCONFIRMED`).
 */
internal fun cancelFailureMessage(error: Throwable): String = when (error) {
    is VpnNotConnectedException -> EvaluateWriteGateUseCase.MESSAGE_VPN_NOT_CONNECTED
    is HttpStatusException -> when (error.code) {
        409 -> CANCEL_NOT_CANCELLABLE_MESSAGE
        400 -> "Annulation refusée par le serveur (requête invalide ou broker injoignable)."
        401, 403 -> "Annulation refusée — session expirée ou droits insuffisants."
        429 -> "Trop de demandes — réessayez dans un instant."
        else -> "Annulation refusée par le serveur (HTTP ${error.code})."
    }
    else -> "Annulation impossible — vérifiez la connexion puis réessayez."
}

/** Description TalkBack du bouton « Annuler » : sans le symbole, plusieurs boutons seraient identiques. */
internal fun cancelButtonDescription(order: Order): String =
    "Demander l'annulation de l'ordre ${orderSideLabel(order.side)} ${order.symbol}"
