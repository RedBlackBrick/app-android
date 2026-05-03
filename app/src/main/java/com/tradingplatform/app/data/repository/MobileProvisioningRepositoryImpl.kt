package com.tradingplatform.app.data.repository

import com.tradingplatform.app.BuildConfig
import com.tradingplatform.app.domain.model.MobileProvisioningResult
import com.tradingplatform.app.domain.repository.MobileProvisioningRepository
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
 */
@Singleton
class MobileProvisioningRepositoryImpl @Inject constructor() :
    MobileProvisioningRepository {

    override suspend fun register(
        host: String,
        provisioningId: String,
        wgPubkey: String,
        pinProof: String,
        nonce: String,
        deviceLabel: String?,
        fcmToken: String?,
    ): Result<MobileProvisioningResult> = runCatching {
        val pinner = CertificatePinner.Builder()
            .add(host, BuildConfig.CERT_PIN_SHA256)
            .add(host, BuildConfig.CERT_PIN_SHA256_BACKUP)
            .build()

        val client = OkHttpClient.Builder()
            .certificatePinner(pinner)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        val payload = JSONObject().apply {
            put("wg_pubkey", wgPubkey)
            put("pin_proof", pinProof)
            put("nonce", nonce)
            if (!deviceLabel.isNullOrBlank()) put("device_label", deviceLabel)
            if (!fcmToken.isNullOrBlank()) put("fcm_token", fcmToken)
        }.toString()

        val request = Request.Builder()
            .url("https://$host:$REGISTER_PORT/v1/me/mobile-provisioning/$provisioningId/register")
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
