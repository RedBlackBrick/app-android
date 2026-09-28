# Décisions d'architecture — app-android

Toutes les décisions ont été arrêtées. Ce fichier sert de référence rapide.
Les détails d'implémentation sont dans `CLAUDE.md`.

---

## A — Refresh token httpOnly cookie → EncryptedCookieJar

`CookieJar` OkHttp personnalisé (`EncryptedCookieJar`) qui persiste les cookies dans
`EncryptedDataStore`. Le cookie refresh est envoyé automatiquement sur `/v1/auth/refresh`.
Voir `CLAUDE.md §11`.

## B — Verrou biométrique → deux mécanismes distincts combinés

**Mécanisme 1 — Keystore** : clé créée avec une validité de 300 s (`setUserAuthenticationParameters`
API 30+, `setUserAuthenticationValidityDurationSeconds` sur 28-29). Ce timer court depuis la
**dernière authentification biométrique forte réussie** — géré par Android.

**Mécanisme 2 — Inactivity tracker** : possédé par `BiometricLockManager` (singleton, observe
`ProcessLifecycleOwner`), pas par `MainActivity` — celle-ci ne fait que relayer les interactions
tactiles (`onUserInteraction()`), pour que la recréation d'Activity (rotation) ne remette pas
l'horloge à zéro. Au bout de 5 min sans interaction, l'overlay biométrique s'affiche.

Ces deux mécanismes sont indépendants et se complètent : le Keystore protège la clé crypto,
l'inactivity tracker protège l'UI. Ils ne sont pas interchangeables.

Gestion `KeyPermanentlyInvalidatedException` obligatoire (biométrie supprimée → clé invalidée).
Les widgets ne demandent jamais de biométrie. Voir `CLAUDE.md §4`.

## C — LAN Radxa → HTTPS + LanTrustManager (revu depuis « cleartext global permis »)

Décision d'origine (cleartext HTTP global via `network_security_config.xml`) supersédée : le
pairing-server Radxa exige désormais TLS. Les appels `sendPin`/`pollStatus` sont en HTTPS avec
un certificat auto-signé accepté par un `X509TrustManager` permissif scopé au client
`@Named("lan")` (`security/LanTrustManager.kt`), pas par le certificate pinning Root CA du VPS.
Risque atténué : VPN actif obligatoire (`VpnRequiredInterceptor`, inclus sur le client LAN) +
validation `isLocalNetwork()` avant tout appel + garde anti-fuite HTTPS/RFC-1918
(`lanOnlyHttpsGuard`) + chiffrement applicatif `crypto_box_seal` du payload (session_pin,
local_token). `network_security_config.xml` conserve un `<domain-config>` cleartext pour les
préfixes LAN hérité de la décision d'origine — non retiré, plus le mécanisme de protection
principal (drift documenté, non résolu). Voir `CLAUDE.md §8/§11` et `docs/security-model.md`.

## D — Glance widgets + Hilt → EntryPointAccessors

Solution unique : `@EntryPoint` interface + `EntryPointAccessors.fromApplication()`.
Voir `CLAUDE.md §2` (section Glance + Hilt).

## E — Feature Alertes → FCM → Room

Les alertes arrivent via FCM et sont persistées dans une table Room `alerts`.
`AlertListScreen` et `AlertsWidget` lisent Room localement (offline-first).
Pas d'endpoint VPS nécessaire. Voir `CLAUDE.md §2` (section Alertes).

## F1 — Portfolio ID → GET /v1/portfolios après login

Appel `GET /v1/portfolios` immédiatement après login pour récupérer `portfolio_id`.
Stocké dans `EncryptedDataStore` clé `auth_portfolio_id`. Voir `CLAUDE.md §11`.

## F2 — Devices (flotte admin) → admin uniquement ; pairing → tout utilisateur (voir D5)

`GET /v1/edge/devices` existe mais est réservé aux comptes admin (`is_admin == true`).
L'onglet Devices (flotte) et les widgets système sont conditionnels à `is_admin`. Le pairing
d'un device n'est **plus** conditionnel à `is_admin` depuis la décision D5 — voir cette entrée.
Voir `CLAUDE.md §2` (section Fonctionnalités conditionnelles).

## G — 2FA/TOTP → TotpScreen dédié + persistance session

`TotpScreen` séparé (pas de dialog). Flow : `LoginScreen → TotpScreen → GET /v1/portfolios → Dashboard`.
La session reste active grâce au refresh token transparent — l'utilisateur ne se reconnecte pas.
Voir `CLAUDE.md §11` (flow complet et persistance).

## I — PairingRepository → OkHttpClient dédié LAN

`SendPinToDeviceUseCase` et `ConfirmPairingUseCase` ne font pas d'appels réseau directement
(violation clean architecture). Ils délèguent à `PairingRepository` (interface dans `domain/`,
implémentation dans `data/`).

`PairingRepositoryImpl` utilise un `OkHttpClient` **séparé** (`@Named("lan")`, HTTPS +
`LanTrustManager` — voir décision C) sans les interceptors VPS (pas de CsrfInterceptor, pas
d'AuthInterceptor) mais **avec** `VpnRequiredInterceptor` — le pairing LAN exige lui aussi un
VPN actif, comme le reste de l'app. La validation `isLocalNetwork()` est faite dans le
Repository avant chaque appel. Voir `CLAUDE.md §8`.

## H — CSRF → CsrfInterceptor + EncryptedCookieJar

Le VPS n'exempte pas les requêtes Bearer du CSRF.
`CsrfInterceptor` obligatoire sur tous les `POST/PUT/DELETE/PATCH`.
Chaîne : `CsrfInterceptor → VpnRequiredInterceptor → AuthInterceptor → TokenAuthenticator`.
Voir `CLAUDE.md §3` et `§11`.

## J — Chiffrement LAN libsodium (crypto_box_seal)

Toute donnée sensible transitant en HTTP sur le LAN (session_pin, local_token, nonce, commandes
maintenance) est chiffrée avec `crypto_box_seal(radxa_wg_pubkey)` via lazysodium-android.
Le HTTP reste en clair mais les secrets sont illisibles pour le réseau. Le Radxa déchiffre
avec sa `wg_private_key` via PyNaCl. Voir `security/SealedBoxHelper.kt`.

Alternatives considérées :
- **TLS/mTLS sur le Radxa** : rejeté — certificats complexes à gérer sur un device embarqué,
  pas de CA disponible dans ce contexte.
- **Symmetric key exchange** : rejeté — nécessite un secret partagé pré-existant,
  problème du chicken-and-egg au pairing.
- **crypto_box_seal (choisi)** : utilise la clé publique WireGuard déjà connue (QR e-ink),
  zéro infrastructure additionnelle — la clé existe déjà dans le système.

## K — Onboarding mobile via QR

L'App scanne un QR affiché sur le panel web PC. Le QR contient la clé privée WireGuard, la
clé publique serveur, l'endpoint, l'IP tunnel et le DNS. L'App stocke la clé privée dans le
Keystore (via `EncryptedDataStore`), configure le tunnel (`WireGuardManager.configureFromSetupQr()`),
puis enchaîne avec le login classique. La clé privée ne transite jamais par le réseau —
uniquement par le canal visuel (écran PC → caméra mobile).

Flux : `SetupScreen → scan QR → configureFromSetupQr() → VPN connect → LoginScreen`

Voir `ui/screens/setup/SetupScreen.kt` et `domain/usecase/pairing/ParseSetupQrUseCase.kt`.

## L — Watchlist → persistance Room locale

La watchlist des symboles suivis est persistée dans une table Room `watchlist(symbol TEXT PK, added_at LONG)`.
Pas de synchronisation avec le backend — la watchlist est locale à l'appareil. Les cours temps réel
sont obtenus via le WebSocket public (`wss://vps/ws/public`) avec souscription par symbole et
throttle `Flow.sample(250ms)`. Fallback REST toutes les 30s si le WS est indisponible.

## M — Activity feed → merge de flows WS privé

Le feed d'activité temps réel du Dashboard est construit par `GetActivityFeedUseCase` qui merge
4 flows du `WsRepository` : `orderUpdates`, `strategySignals`, `notifications`, `portfolioUpdates`.
Chaque événement est mappé vers un `ActivityItem` (sealed class domaine). Le ViewModel maintient
un buffer de 12 items max (prepend + drop). Pas de persistance Room — le feed est éphémère
(reconstruit à chaque ouverture du Dashboard).

## N — Positions live → WS privé position_update

Les `position_update` du WebSocket privé sont collectés dans `PositionsViewModel` via
`GetPositionWsUpdatesUseCase` et mergés dans la liste de positions existante (matching par
`symbol`). L'affichage utilise `AnimatedPnlText` (flash 500ms) pour signaler visuellement
les changements de prix.

## O — Métriques device → composants partagés MetricsComponents

Les seuils de santé device (CPU, RAM, température, disque) sont centralisés dans
`ui/components/MetricsComponents.kt` avec des composants réutilisables : `MetricRow`,
`CompactHealthBar`, `HealthStatusBadge`, `metricColor()`. Utilisés par `DeviceListScreen`
(vue compacte) et `EdgeDeviceDashboardScreen` (vue détaillée).

## P — Filtrage alertes → query Room SQL

Le filtrage des alertes par type utilise une query Room `WHERE type IN (:types)` plutôt qu'un
filtrage en mémoire. Le `AlertsViewModel` expose un `selectedTypes: StateFlow<Set<AlertType>>`
et utilise `flatMapLatest` pour basculer entre la query filtrée et la query complète.

---

## Décisions du plan de remédiation (`audit/PLAN.md`)

Prises pendant la remédiation post-audit (2026-09-28). Référence rapide ; détails dans
`CLAUDE.md` et le document cité par chaque entrée.

### D1 — minSdk 28 (au lieu d'un thème AppCompat)

`BiometricPrompt` plante sur API 26-27 avec le thème framework de l'app (pas AppCompat).
Alternative rejetée : basculer sur `Theme.AppCompat.DayNight.NoActionBar` (+ dépendances
appcompat/fragment-ktx supplémentaires). Choisi : relever `minSdk` à 28, aucun device API 26-27
visé. Voir `CLAUDE.md §4`, décision B ci-dessus.

### D2 — Room baseline v7 (pas de migrations 1→6)

L'app n'a jamais été livrée avant le schéma v7 (versionCode 1, aucun utilisateur en v1-6).
Les schémas `1.json`…`6.json` et les `MIGRATION_*` correspondants ont été supprimés ; `7.json`
est la seule baseline. Le premier changement de schéma post-release (v8+) redevient soumis à la
règle générale de migration explicite. Voir `CLAUDE.md §2` (section Migration Room).

### D5 — Pairing ouvert à tout utilisateur authentifié

Le pairing depuis `Settings > Mes appareils` (`MyDevicesScreen`) n'est **pas** réservé aux
comptes admin — état du code et du backend (qui applique la règle par propriétaire du device,
pas par rôle). Seuls la flotte admin (`Screen.Devices`) et le pairing lancé depuis cet écran
restent réservés aux admins. Voir `CLAUDE.md §2`, `docs/pairing-flow.md §9`, décision F2
ci-dessus.

### D6 — VPN système tiers accepté

Si un VPN monté par une autre app (client WireGuard officiel, OpenVPN, WARP…) est actif —
détecté par `SystemVpnMonitor` — les requêtes sont autorisées même tunnel intégré coupé, exposé
distinctement comme `VpnState.SystemVpnActive`. Limite assumée : l'app ne peut pas vérifier que
ce VPN tiers route vers le VPS ; la confidentialité repose sur TLS + certificate pinning, qui
restent obligatoires quel que soit le tunnel. Voir `CLAUDE.md §3`, `docs/security-model.md §1`.

### D7 — Démarrage à froid verrouillé par défaut (fail-closed)

`BiometricLockManager.isLocked` vaut `true` jusqu'à la décision explicite de
`TradingApplication` : session présente → verrouillé sauf état persisté « déverrouillé » avec
interaction < 5 min ; pas de session (Setup/Login) → déverrouillé silencieusement ; Keystore
corrompu → reste verrouillé. Voir `CLAUDE.md §4`.

### Règle transversale — `runCatchingCancellable` obligatoire (finding #10)

`runCatching {}` catche `Throwable`, donc avale silencieusement `CancellationException` et
casse la concurrence structurée (une coroutine annulée ressort comme un `Result.failure`
« normal »). Toutes les méthodes `Repository`/`UseCase` qui wrappent un appel suspendu utilisent
`domain/util/runCatchingCancellable` (relance `CancellationException` et sa sous-classe
`TimeoutCancellationException`) au lieu de `runCatching {}` nu. Un test de garde
(`NoBareRunCatchingTest`) fait échouer le build sur tout nouveau `runCatching {` nu hors d'une
liste blanche de code synchrone (parsing JSON/URI, widgets Glance). Voir `CLAUDE.md §2`
(section Pattern Result<T>).
