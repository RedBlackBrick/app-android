package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.LocalMaintenanceApi
import com.tradingplatform.app.domain.model.DeviceIdentity
import com.tradingplatform.app.domain.model.DeviceLocalStatus
import com.tradingplatform.app.domain.repository.LocalMaintenanceRepository
import com.tradingplatform.app.security.SealedBoxHelper
import com.tradingplatform.app.security.isLocalNetwork
import com.tradingplatform.app.security.sealLanBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class LocalMaintenanceRepositoryImpl @Inject constructor(
    @Named("lan") private val maintenanceApi: LocalMaintenanceApi,
    private val sealedBoxHelper: SealedBoxHelper,
) : LocalMaintenanceRepository {

    /**
     * Envoie une commande de maintenance à la Radxa via HTTP LAN (payload chiffré libsodium).
     *
     * Règles critiques (CLAUDE.md §8) :
     * - Valide que l'IP est RFC-1918 avant tout appel réseau (anti-DNS-rebinding)
     * - Le local_token n'est jamais loggé — [REDACTED] uniquement
     * - Connexion HTTP uniquement (LAN local — pas de certificate pinning)
     * - Le payload JSON est chiffré avec crypto_box_seal (clé publique Curve25519 de la Radxa)
     */
    override suspend fun sendCommand(
        deviceIp: String,
        devicePort: Int,
        action: String,
        localToken: String,
        radxaWgPubkey: String,
        params: Map<String, String>,
    ): Result<String> = runCatching {
        val payloadJson = JSONObject().apply {
            put("action", action)
            put("local_token", localToken)
            put("params", JSONObject(params))
        }.toString()

        val body = sealedBoxHelper.sealLanBody(
            deviceIp = deviceIp,
            radxaWgPubkeyBase64 = radxaWgPubkey,
            payload = payloadJson.toByteArray(Charsets.UTF_8),
        )

        val url = "https://$deviceIp:$devicePort/command"
        val response = maintenanceApi.sendCommand(url, body)
        if (!response.isSuccessful) error("command failed: HTTP ${response.code()}")
        // ResponseBody doit être consommé dans use { } pour garantir la fermeture du flux
        // même si une exception survient pendant la lecture (body().string() lit le stream).
        response.body()?.use { it.string() } ?: "OK"
    }

    /**
     * Récupère le statut local de la Radxa (wg_status, wifi_ssid, uptime, last_error).
     * Valide que l'IP est RFC-1918 avant chaque appel.
     */
    override suspend fun getStatus(
        deviceIp: String,
        devicePort: Int,
    ): Result<DeviceLocalStatus> = runCatching {
        if (!isLocalNetwork(deviceIp)) error("Refused: $deviceIp is not RFC-1918")
        val url = "https://$deviceIp:$devicePort/status"
        val response = maintenanceApi.getStatus(url)
        if (!response.isSuccessful) error("status failed: HTTP ${response.code()}")
        val body = response.body() ?: error("Empty status response")
        DeviceLocalStatus(
            deviceId = body["device_id"] ?: "",
            wgStatus = body["wg_status"] ?: "unknown",
            wifiSsid = body["wifi_ssid"],
            uptime = body["uptime"] ?: "unknown",
            lastError = body["last_error"],
        )
    }

    /**
     * Récupère l'identité de la Radxa (device_id, wg_pubkey, local_ip).
     * Valide que l'IP est RFC-1918 avant chaque appel.
     */
    override suspend fun getIdentity(
        deviceIp: String,
        devicePort: Int,
    ): Result<DeviceIdentity> = runCatching {
        if (!isLocalNetwork(deviceIp)) error("Refused: $deviceIp is not RFC-1918")
        val url = "https://$deviceIp:$devicePort/identity"
        val response = maintenanceApi.getIdentity(url)
        if (!response.isSuccessful) error("identity failed: HTTP ${response.code()}")
        val body = response.body() ?: error("Empty identity response")
        DeviceIdentity(
            deviceId = body["device_id"] ?: error("Missing device_id"),
            wgPubkey = body["wg_pubkey"] ?: error("Missing wg_pubkey"),
            localIp = body["local_ip"] ?: error("Missing local_ip"),
        )
    }
}
