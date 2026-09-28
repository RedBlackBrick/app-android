package com.tradingplatform.app.data.repository

import com.tradingplatform.app.BuildConfig
import com.tradingplatform.app.di.IoDispatcher
import com.tradingplatform.app.domain.model.MobileProvisioningResult
import com.tradingplatform.app.domain.repository.MobileProvisioningRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [MobileProvisioningRepository].
 *
 * Builds a *bare* OkHttpClient per call: no auth interceptor (the call is
 * HMAC-authenticated, not Bearer), no VPN-required interceptor (the tunnel is
 * not yet up), no CSRF interceptor (no session cookie). Cert pinning is the
 * one thing we keep — pinned to the same Caddy Root CA the rest of the app
 * uses.
 *
 * Construction is per-call rather than via Hilt-provided client because the
 * pinned host is dynamic (comes from the QR), and this codepath only runs
 * once in the user's lifetime per phone.
 *
 * The blocking `client.newCall(request).execute()` call below is wrapped in
 * `withContext(io)` (`@IoDispatcher` = `Dispatchers.IO`) — unlike the rest of
 * the app's network calls, this one is a hand-built OkHttp call, not a
 * Retrofit suspend function, so it does not dispatch off the caller by
 * itself. Without this, `register()` throws `NetworkOnMainThreadException`
 * whenever it is invoked from `viewModelScope.launch` (which runs on
 * `Dispatchers.Main.immediate`), which is exactly how `SetupViewModel` calls
 * it via `ProvisionMobileVpnUseCase` — see CLAUDE.md §2 "Accès réseau sur le
 * thread principal".
 */
@Singleton
class MobileProvisioningRepositoryImpl @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) : MobileProvisioningRepository {

    /**
     * Test-only seam. When set, [register] uses this client/base URL instead
     * of building the pinned production HTTPS client against `host:8443`.
     * Certificate pinning is never disabled for the production path — these
     * are only read here so a JVM unit test can point `register()` at a
     * plain-http `MockWebServer` instance and assert on the real
     * request/response mapping without a TLS handshake.
     */
    internal var testClientOverride: OkHttpClient? = null
    internal var testBaseUrlOverride: String? = null

    override suspend fun register(
        host: String,
        provisioningId: String,
        wgPubkey: String,
        pinProof: String,
        nonce: String,
        deviceLabel: String?,
        fcmToken: String?,
    ): Result<MobileProvisioningResult> = withContext(io) {
        runCatching {
            val client = testClientOverride ?: run {
                val pinner = CertificatePinner.Builder()
                    .add(host, BuildConfig.CERT_PIN_SHA256)
                    .add(host, BuildConfig.CERT_PIN_SHA256_BACKUP)
                    .build()

                OkHttpClient.Builder()
                    .certificatePinner(pinner)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .build()
            }

            val payload = JSONObject().apply {
                put("wg_pubkey", wgPubkey)
                put("pin_proof", pinProof)
                put("nonce", nonce)
                if (!deviceLabel.isNullOrBlank()) put("device_label", deviceLabel)
                if (!fcmToken.isNullOrBlank()) put("fcm_token", fcmToken)
            }.toString()

            val url = testBaseUrlOverride
                ?.let { base -> "$base/v1/me/mobile-provisioning/$provisioningId/register" }
                ?: "https://$host:$REGISTER_PORT/v1/me/mobile-provisioning/$provisioningId/register"

            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw MobileProvisioningHttpException(response.code, body)
                }
                val json = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    throw IOException("Réponse server invalide (non-JSON)")
                }
                MobileProvisioningResult(
                    vpnPeerId = json.getLong("vpn_peer_id"),
                    tunnelIp = json.getString("tunnel_ip"),
                    dns = json.optString("dns"),
                    allowedIps = json.getString("allowed_ips"),
                    serverPubkey = json.getString("server_pubkey"),
                    endpoint = json.getString("endpoint"),
                )
            }
        }
    }

    private companion object {
        const val REGISTER_PORT = 8443
    }
}

/**
 * Wraps non-2xx responses from the register endpoint so the use case can map
 * common server-side errors to user-facing messages without parsing the body
 * itself.
 */
class MobileProvisioningHttpException(
    val code: Int,
    val body: String,
) : Exception("Mobile provisioning failed (HTTP $code): $body")
