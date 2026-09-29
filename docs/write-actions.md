# Actions d'écriture (mobile) — cadre de sécurité

L'app est une vue concise du compte plus quelques actions simples et sûres. Toute requête
mutante passe par ce cadre : client sans retry, garde, confirmation biométrique, relecture.

## Périmètre

- **Autorisé** : annuler un ordre, pause/reprise d'un lien stratégie-portefeuille, activer le
  kill switch d'un portefeuille, préférences push.
- **Jamais sur mobile** : nouvel ordre, clôture de position, dépôt/retrait, rebalance, édition
  ou pause au niveau stratégie, kill switch global, levée d'un kill switch, admin, firmware.

## 1. Client réseau `@Named("write")` — `di/WriteNetworkModule.kt`

- `OkHttpClient` = client principal `.newBuilder().retryOnConnectionFailure(false)` : mêmes
  intercepteurs (Timeout, UpgradeRequired, CSRF, VPN, Auth, logger debug), `Authenticator`,
  `CookieJar`, pinning, timeouts. `Retrofit` `@Named("write")` : mêmes `baseUrl` et Moshi.
- Les API d'écriture (`OrdersWriteApi`, `StrategiesWriteApi`, `RiskWriteApi`,
  `PreferencesWriteApi`) se créent **depuis ce Retrofit**, jamais depuis le Retrofit non qualifié.
- Pourquoi : OkHttp rejoue par défaut une requête (POST compris) sur échec de connexion, alors que
  le serveur a peut-être déjà agi. Restent actifs, sans risque de doublon car refus AVANT
  exécution : le refresh sur 401 (`TokenAuthenticator`) et le retry CSRF sur 403.
- Aucun autre retry : ni WorkManager, ni boucle dans un Repository ou ViewModel. **Une écriture
  n'est jamais rejouée après un timeout ou une réponse 5xx.**

## 2. Garde — `EvaluateWriteGateUseCase`

`invoke(dataSyncedAt: Long?, maxAgeMs = 60_000): WriteGate` → `Allowed` ou
`Blocked(reason, message)` (message FR prêt à afficher).

| Condition | Raison si non remplie |
|-----------|----------------------|
| Access token présent | `NOT_LOGGED_IN` |
| VPN intégré `Connected` ou VPN système actif (comme `VpnRequiredInterceptor`) | `VPN_NOT_CONNECTED` |
| Donnée visée lue il y a ≤ `maxAgeMs` (jamais lue ou horodatage futur = périmée) | `DATA_STALE` |

Priorité : `NOT_LOGGED_IN` > `VPN_NOT_CONNECTED` > `DATA_STALE` (sans VPN, « actualisez » est
irréalisable). C'est une aide UI (bouton désactivé + message) : les intercepteurs restent le vrai
garde réseau. L'évaluer à l'ouverture de la feuille ET juste avant l'envoi (la biométrie prend du temps).

## 3. Confirmation — `ConfirmActionSheet` (`ui/components/`)

- Feuille modale Material 3 en 2 étapes : (1) récapitulatif + motif si `requireReason` (bouton actif
  seulement si le motif n'est pas blanc ; 500 caractères max) ; (2) bouton final → `BiometricPrompt`.
- **Biométrie à chaque action** : `BIOMETRIC_STRONG` seul via `BiometricManager.authenticate`, jamais
  de fenêtre réutilisée, pas de `CryptoObject`, aucun repli PIN/schéma/mot de passe.
- **Fail-closed** : `onConfirmed` n'est appelé que depuis le succès du prompt. Sans
  `FragmentActivity`, sans biométrie forte, sur erreur, annulation ou clé invalidée : message
  d'erreur, feuille ouverte. Un succès tardif (feuille fermée ou remplacée) est ignoré.
- Le parent met `action` à `null` dans `onConfirmed` (jamais rappelé pour la même instance).

```kotlin
var pending by remember { mutableStateOf<ConfirmAction?>(null) }   // ou un StateFlow du ViewModel
ConfirmActionSheet(
    action = pending, onDismiss = { pending = null },
    onConfirmed = { reason -> pending = null; viewModel.cancelOrder(orderId) },
)
```

## 4. Vocabulaire « demandé » et relecture

- Les Repository d'écriture retournent `Result<WriteOutcome>` : `CONFIRMED` (2xx) ou
  `REQUESTED_UNCONFIRMED` (5xx ou timeout après envoi ; le backend convertit plusieurs erreurs en 500).
- Wording : « Annulation **demandée** », jamais « annulé » avant relecture. Sur
  `REQUESTED_UNCONFIRMED` : « Demande envoyée — état non confirmé, vérification en cours ».
- Après toute écriture (même `CONFIRMED`) : relire l'état par GET et afficher ce que dit le serveur.
  L'utilisateur ne relance qu'après avoir vu l'état relu. Un 4xx explicite (409, 403, 422) est un échec certain.

## 5. Ajouter une action — checklist

1. API Retrofit d'écriture sur `@Named("write")`, module Hilt propre (pas de `NetworkModule`).
2. Repository : `Result<WriteOutcome>` via `runCatchingCancellable`, aucun retry.
3. UseCase : règles métier (ex. motif non vide) ; le ViewModel évalue la garde et expose `ConfirmAction?`.
4. Écran : `ConfirmActionSheet` → `onConfirmed` → UseCase → relecture.
5. Tests : garde, UseCase (aucun second appel après un échec), wording. Ne jamais logger montant ni motif.
