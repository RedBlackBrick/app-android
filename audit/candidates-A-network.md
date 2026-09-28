# Candidates — lead A (network-session) — status: candidate

CRITICAL (as reported)
- A-corr-1 | AuthInterceptor.kt:42-47,74-86 | /v1/auth/2fa/verify missing from PUBLIC_PATHS (and CsrfInterceptor exempt) → no token yet → synthetic 401 + forced logout → 2FA login broken. conf high
- A-sec-1 | EncryptedDataStore.kt:73-82,251-255 | BIOMETRIC_LOCKED not in criticalKeys → apply() not commit() → lock state lost if process killed right after. conf high (ORCH: likely medium)

HIGH
- A-conc-1 | TokenAuthenticator.kt:79-98 | Mutex held across deferred.await() → "reuse in-flight refresh" branch unreachable; concurrent 401s serialize N refreshes. conf high
- A-corr-2 | TradingApplication.kt:67-107, AuthInterceptor.kt:50-87, TokenHolder.kt:10-12 | TokenHolder preloaded fire-and-forget at cold start; AuthInterceptor has no DataStore fallback (doc claims one) → early request → spurious forced logout. conf high
- A-err-1 | EncryptedDataStore.kt:51-70 | sharedPreferences by lazy caches null forever on first GeneralSecurityException; no remediation path (clearAll no-op) → install bricked. conf high
- A-sec-2 | SystemVpnMonitor.kt:46-70, VpnRequiredInterceptor.kt:53-55 | any TRANSPORT_VPN satisfies VPN gate (main + lan clients). conf high (ORCH: check design intent commit 7da1974)
- A-sec-3 | NetworkModule.kt:119-128, CsrfInterceptor.kt:118-124 | debug HttpLoggingInterceptor BODY logs password/tokens; Cookie header (csrf_token) not redacted. conf high (debug only; CLAUDE.md forbids even in debug)
- A-conc-2 | NetworkModule.kt:106-151, TokenAuthenticator.kt:78-99 | refresh uses same OkHttpClient (maxRequestsPerHost=5) → self-starvation with ≥5 concurrent 401; orphaned doRefresh after 8s timeout. conf medium
- A-cov-1..5 | TokenAuthenticator, CsrfInterceptor, EncryptedDataStore, KeystoreManager untested; SealedBoxHelperTest never instantiates SealedBoxHelper. (→ test-gap section, not ranked defects)

MEDIUM
- A-sec-4 | VpnRequiredInterceptor.kt:36-42 | login + 2fa/verify VPN-excluded; only RFC1918 base URL convention protects. conf medium
- A-di-1 | EncryptedDataStore.kt:273-287 | clearSession fallback on enumeration failure = clear() all → wipes WG keys. conf medium
- A-di-2 | EncryptedDataStore.kt:73-82 | IS_ADMIN/PORTFOLIO_ID apply() while ACCESS_TOKEN commit() → partial persisted login state. conf medium
- A-corr-3 | CsrfInterceptor.kt:75-79,216 | csrf-token path inconsistent: fetch hits /csrf-token, exempt sets list /v1/auth/csrf-token. conf medium (→ check backend csrf.py)
- A-cov-6,7 | no test pinning interceptor order; CertificatePinner DEV_MODE untested. (test-gap)

LOW
- A-sec-5 | LanTrustManager.kt:35-46 | trust-all TLS for LAN; /status poll unauthenticated (documented TOFU not implemented).
- A-di-3 | EncryptedCookieJar.kt:59-77 | cookie persisted fire-and-forget.
- A-conc-3 | TokenAuthenticator.kt:139-142 | catch(Exception) swallows CancellationException (latent).
- A-sec-6 | RootDetector.kt:20-25 | fails open on exception (advisory only, by design).
- A-cov-8 | TimeoutInterceptor/UpgradeRequiredInterceptor/LanTrustManager untested.

Healthy: DEV_MODE hard false in release + gradle guard; pinning requires 2 pins; TokenHolder @Volatile; cookie jar exact-path matching; KeyPermanentlyInvalidatedException handled; seal() uses crypto_box_seal; PIN/local_token redacted; lan client has no logging. network_security_config stricter than CLAUDE.md says (doc drift).
