# Plan partiel — auth / réseau (#2, #10, #12, #15, #16, logging, TokenHolder)
Source : agent planificateur, 2026-09-28. B = app/src/main/java/com/tradingplatform/app/. Tests : app/src/test/... (MockWebServer + mockk + turbine présents).

Deux défauts supplémentaires trouvés :
- `EncryptedCookieJar.AUTH_SAVE_PATHS` = {login, refresh} (EncryptedCookieJar.kt:37) mais le backend pose le cookie refresh_token sur /2fa/verify aussi (router.py:457-463). Après un login 2FA le cookie n'est jamais persisté → premier refresh en 401 → logout forcé à l'expiration de l'access token. **Vérifié par l'orchestrateur.**
- TradingApplication.kt:47-48 prétend que LoginViewModel appelle PrivateWsClient.connect() : personne ne le fait → latence WS jusqu'à 300 s après login.

## Ordre / effort
1. A AuthPaths + flux TOTP (S). 2. E logging (S). 3. F cold start (S). 4. D helper seul + CLAUDE.md (S). 5. B TokenAuthenticator + client refresh (M). 6. C cycle de vie WS (M). 7. D remplacement mécanique + dedup MarketData (M, en dernier : diff large).

## A. #2 — `AuthPaths` source unique + retry TOTP — S
Nouveau B/data/api/AuthPaths.kt (dérivé de backend auth.py:60-97, csrf.py:38-72) :
```kotlin
object AuthPaths {
    const val LOGIN = "/v1/auth/login"; const val REFRESH = "/v1/auth/refresh"
    const val TOTP_VERIFY = "/v1/auth/2fa/verify"; const val TOTP_VERIFY_ALIAS = "/v1/auth/verify-2fa"
    const val CSRF = "/v1/auth/csrf-token"; const val CSRF_LEGACY = "/csrf-token"
    val PUBLIC = setOf(LOGIN, REFRESH, TOTP_VERIFY, TOTP_VERIFY_ALIAS, CSRF, CSRF_LEGACY)   // pas de Bearer requis
    val CSRF_EXEMPT = setOf(LOGIN, REFRESH, CSRF)            // 2fa/verify N'EST PAS exempt (token CSRF anonyme OK)
    val VPN_EXCLUDED = PUBLIC
    val COOKIE_SAVE = setOf(LOGIN, REFRESH, TOTP_VERIFY, TOTP_VERIFY_ALIAS)   // réponses portant Set-Cookie refresh_token
    fun isSensitive(path: String) = path.startsWith("/v1/auth/") || path == CSRF_LEGACY || path == "/v1/notifications/fcm-token"
}
```
Remplacer les 4 sets privés : AuthInterceptor.kt:42-47, CsrfInterceptor.kt:75-79, VpnRequiredInterceptor.kt, EncryptedCookieJar.kt:37-38, TokenAuthenticator.kt:154. Retirer /v1/auth/register (admin backend, pas d'endpoint app).
Retry TOTP — faits backend (service.py:786-795, :193) : temp token TTL 300 s, **supprimé à la première lecture avant la vérification du code** → un mauvais code le brûle ; 401 pour « mauvais code » et « token expiré » ; 429 après 3/min. Donc : TotpViewModel.verify() lit `pendingTotpToken` (peek) et ne `consume` que sur succès ou sur InvalidTotpCodeException ; timeout/IOException → garder le token, état Error avec retry. Sur InvalidTotpCodeException : message « Code incorrect ou session 2FA expirée — reconnectez-vous » + `TotpUiState.BackToLogin`. AuthRepositoryImpl.verify2fa (:112-118) : 429 → AccountLockedException(Retry-After). Suivi backend optionnel : ne supprimer le temp token que sur succès TOTP.
Tests : AuthPathsTest ; AuthInterceptorTest (TokenHolder vide + 2fa/verify → la requête atteint MockWebServer, pas de notifyForcedLogout) ; EncryptedCookieJarTest (Set-Cookie sur 2fa/verify persisté) ; TotpViewModelTest (IOException garde le token, 401 le consomme).

## B. #15/#16 — TokenAuthenticator + client `@Named("refresh")` — M
NetworkModule.kt : `provideRefreshOkHttpClient` (Dispatcher maxRequests=2/maxRequestsPerHost=2, TimeoutInterceptor, VpnRequiredInterceptor, logger headers-only, cookieJar, timeouts 5 s, pinning ; **sans** Csrf/Auth/Authenticator) + `provideRefreshAuthApi`. Pas de cycle Hilt. TokenAuthenticator prend `@Named("refresh") authApi: AuthApi` (plus de dagger.Lazy). CSRF inutile : /refresh est exempt côté backend (csrf.py:45).
```kotlin
override fun authenticate(route: Route?, response: Response): Request? {
    if (response.priorResponse?.code == 401) return null                                   // 1 retry max
    if (response.request.url.encodedPath == AuthPaths.REFRESH) { handleLogout(); return null }
    val failed = response.request.header("Authorization")?.removePrefix("Bearer ")
    tokenHolder.accessToken?.let { if (it != failed) return retryWith(response, it) }      // bearer périmé → pas de refresh
    val token = runBlocking { withTimeoutOrNull(AUTHENTICATE_TIMEOUT_MS) { refreshOnce().await() } } ?: return null
    return retryWith(response, token)
}
private suspend fun refreshOnce(): Deferred<String?> = mutex.withLock {   // le lock ne garde que le champ
    refreshDeferred?.takeIf { !it.isCompleted } ?: applicationScope.async { doRefresh() }.also { refreshDeferred = it }
}
```
withTimeoutOrNull n'annule que l'attente, jamais le Deferred partagé ; le refresh finit dans applicationScope (borné par les timeouts 5 s) et alimente TokenHolder → une requête relancée par l'UI prend le chemin rapide « bearer périmé ». doRefresh : relancer CancellationException. Le pré-fetch CSRF post-login existe déjà (CsrfInterceptor.preFetch appelé AuthRepositoryImpl.kt:82,133, TradingApplication.kt:98) → CLAUDE.md §12 à reformuler.
Tests TokenAuthenticatorTest (MockWebServer Dispatcher comptant les chemins) : 8 appels 401 parallèles → 1 POST /refresh, 8 retries ; serveur toujours 401 → 1 refresh, 2 hits ; bearer ≠ holder → 0 refresh ; refresh 401 → handleLogout + notifyForcedLogout ; refresh plus lent que le timeout → null, holder mis à jour ensuite.

## C. #12 — cycle de vie du WS privé — M
Réalité des dépendances : PrivateWsClient → AuthRepository → AuthApi → OkHttpClient → TokenAuthenticator, donc ni AuthRepositoryImpl.logout ni TokenAuthenticator.handleLogout ne peuvent injecter PrivateWsClient. Passer par SessionManager (feuille, déjà un bus) :
- SessionManager : `sessionStartedEvents` (MutableSharedFlow, extraBufferCapacity=1) + `notifySessionStarted()`.
- AuthRepositoryImpl : injecter SessionManager ; `notifySessionStarted()` juste après `tokenHolder.setToken` dans login (:76) et verify2fa (:127).
- PrivateWsClient : injecter TokenHolder + SessionManager ; dans init : collecter sessionStartedEvents → `reconnectJob?.cancel(); reconnectAttempts.set(0); connect()` ; collecter forcedLogoutEvents → `disconnect()`. Garde dans connect()/scheduleReconnect()/openWebSocket() : `if (tokenHolder.accessToken == null) { Disconnected; return }`. `generation = AtomicInteger()` incrémenté dans disconnect(), capturé par le WsListener ; onOpen avec génération périmée ferme le socket (un getWsToken en vol pendant le logout rouvrirait sinon un socket authentifié). Corriger la KDoc TradingApplication.kt:47-48.
Tests PrivateWsClientTest (MockWebServer withWebSocketUpgrade) : forced-logout → close 1000 + Disconnected, pas de reconnexion ; TokenHolder vide → connect() no-op, pas de getWsToken ; sessionStarted après backoff 5 → connexion immédiate, attempts=0.

## D. #10 — `runCatchingCancellable` + dedup MarketData — M
Helper B/domain/util/RunCatchingCancellable.kt :
```kotlin
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> = try { Result.success(block()) }
    catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(e) }
```
Inventaire : 44 sites, tous en forme `runCatching {` (31 data/repository, 6 domain/usecase). Mécanique : `grep -rl 'runCatching {' data/repository domain/usecase | xargs sed -i 's/\brunCatching {/runCatchingCancellable {/'` + insertion de l'import. ConfirmPairingUseCase.kt:38, AuthRepositoryImpl.kt:91, TokenAuthenticator.doRefresh : `catch (e: CancellationException) { throw e }` avant le générique. CLAUDE.md §2 : « runCatchingCancellable {} (domain/util) — jamais runCatching nu dans une suspend fun ». Garde-fou sans detekt : petit test JVM qui scanne src/main pour `\brunCatching {` hors du helper.
MarketDataRepositoryImpl.getQuote (:37-82) : supervisorScope tourne dans le job de l'appelant → l'annulation du gagnant devient Result.failure pour tous. Détacher via applicationScope :
```kotlin
private val inFlight = ConcurrentHashMap<String, Deferred<Result<Quote>>>()
override suspend fun getQuote(symbol: String): Result<Quote> { val key = symbol.uppercase()
    val job = inFlight.computeIfAbsent(key) { applicationScope.async { runCatchingCancellable { fetchAndCache(key) } } }
    job.invokeOnCompletion { inFlight.remove(key, job) }
    return job.await() }   // l'annulation de l'appelant n'annule que son await
```
Tests : runCatchingCancellableTest ; MarketDataRepositoryImplTest (2 appelants, annuler le 1er → le 2e reçoit success, 1 seul appel API).

## E. Expurgation des logs — S
NetworkModule.kt:119-128 : helper `debugLogger(headersOnly)` → deux HttpLoggingInterceptor (BODY / HEADERS) avec redactHeader Authorization, X-CSRF-Token, Cookie, Set-Cookie ; wrapper `if (AuthPaths.isSensitive(path)) headers else body` ; ajouté seulement en DEBUG. Réutilisé par le client refresh (headers-only). Test : Logger capturant les lignes ; `password`/`access_token` absents pour /login, body présent pour /portfolios.

## F. Fenêtre de démarrage à froid — S
GetAuthContextUseCase.kt:167-193 : injecter TokenHolder ; après lecture du token, `if (tokenHolder.accessToken == null) tokenHolder.setToken(it)`. La porte de navigation implique alors un holder peuplé. Réécrire les KDoc TokenHolder.kt:10-12 et AuthInterceptor.kt:20-26. Test GetAuthContextUseCaseTest.
