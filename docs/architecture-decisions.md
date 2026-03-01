# Décisions d'architecture — app-android

Toutes les décisions ont été arrêtées. Ce fichier sert de référence rapide.
Les détails d'implémentation sont dans `CLAUDE.md`.

---

## A — Refresh token httpOnly cookie → EncryptedCookieJar

`CookieJar` OkHttp personnalisé (`EncryptedCookieJar`) qui persiste les cookies dans
`EncryptedDataStore`. Le cookie refresh est envoyé automatiquement sur `/v1/auth/refresh`.
Voir `CLAUDE.md §11`.

## B — Verrou biométrique → deux mécanismes distincts combinés

**Mécanisme 1 — Keystore** : clé créée avec `setUserAuthenticationValidityDurationSeconds(300)`.
Ce timer court depuis la **dernière authentification biométrique réussie** — géré par Android.

**Mécanisme 2 — Inactivity tracker** : timer dans `MainActivity` réinitialisé à chaque
`dispatchTouchEvent`. Au bout de 5 min sans interaction, l'overlay biométrique s'affiche.

Ces deux mécanismes sont indépendants et se complètent : le Keystore protège la clé crypto,
l'inactivity tracker protège l'UI. Ils ne sont pas interchangeables.

Gestion `KeyPermanentlyInvalidatedException` obligatoire (biométrie supprimée → clé invalidée).
Les widgets ne demandent jamais de biométrie. Voir `CLAUDE.md §4`.

## C — LAN Radxa cleartext HTTP → cleartext global permis

`network_security_config.xml` avec `cleartextTrafficPermitted="true"` globalement.
Risque atténué : VPN actif obligatoire + validation `isLocalNetwork()` avant envoi PIN.
Voir `CLAUDE.md §10`.

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

## F2 — Devices → admin uniquement

`GET /v1/edge/devices` existe mais est réservé aux comptes admin (`is_admin == true`).
L'onglet Devices, le pairing et les widgets système sont conditionnels à `is_admin`.
Voir `CLAUDE.md §2` (section Fonctionnalités conditionnelles).

## G — 2FA/TOTP → TotpScreen dédié + persistance session

`TotpScreen` séparé (pas de dialog). Flow : `LoginScreen → TotpScreen → GET /v1/portfolios → Dashboard`.
La session reste active grâce au refresh token transparent — l'utilisateur ne se reconnecte pas.
Voir `CLAUDE.md §11` (flow complet et persistance).

## I — PairingRepository → OkHttpClient dédié LAN

`SendPinToDeviceUseCase` et `ConfirmPairingUseCase` ne font pas d'appels réseau directement
(violation clean architecture). Ils délèguent à `PairingRepository` (interface dans `domain/`,
implémentation dans `data/`).

`PairingRepositoryImpl` utilise un `OkHttpClient` **séparé** (`@Named("lan")`) sans les
interceptors VPS (pas de CsrfInterceptor, pas d'AuthInterceptor, pas de VpnRequiredInterceptor).
La validation `isLocalNetwork()` est faite dans le Repository avant chaque appel.
Voir `CLAUDE.md §8`.

## H — CSRF → CsrfInterceptor + EncryptedCookieJar

Le VPS n'exempte pas les requêtes Bearer du CSRF.
`CsrfInterceptor` obligatoire sur tous les `POST/PUT/DELETE/PATCH`.
Chaîne : `CsrfInterceptor → VpnRequiredInterceptor → AuthInterceptor → TokenAuthenticator`.
Voir `CLAUDE.md §3` et `§11`.
