package com.tradingplatform.app.usecase.pairing

import androidx.datastore.preferences.core.Preferences
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.model.MobileProvisioningResult
import com.tradingplatform.app.domain.model.SetupQrData
import com.tradingplatform.app.domain.repository.MobileProvisioningRepository
import com.tradingplatform.app.domain.usecase.pairing.ProvisionMobileVpnUseCase
import com.tradingplatform.app.vpn.WireGuardConfig
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * JVM tests for [ProvisionMobileVpnUseCase] — focus on what is persisted for later tunnel
 * rebuilds (audit REPORT §10: provisioned `allowed_ips` must survive to reconnect()).
 */
class ProvisionMobileVpnUseCaseTest {

    /** In-memory stand-in for EncryptedDataStore (typed String keys only). */
    private val store = mutableMapOf<String, String>()
    private val dataStore = mockk<EncryptedDataStore> {
        coEvery { readString(any<Preferences.Key<String>>()) } answers {
            store[firstArg<Preferences.Key<String>>().name]
        }
        coEvery { writeString(any<Preferences.Key<String>>(), any()) } answers {
            store[firstArg<Preferences.Key<String>>().name] = secondArg()
        }
    }
    private val repository = mockk<MobileProvisioningRepository>()
    private val wireGuardManager = mockk<WireGuardManager>(relaxed = true)
    private val useCase = ProvisionMobileVpnUseCase(repository, wireGuardManager, dataStore)

    private val setupData = SetupQrData(
        version = 1,
        provisioningId = "prov-uuid-123",
        claimToken = "a".repeat(43),
        nonce = "b".repeat(64),
        vpsEndpoint = "vps.example.com:51820",
        vpsPubkey = "c".repeat(44),
        dns = "",
        expiresAt = Instant.now().plusSeconds(300),
    )

    private val provisioned = MobileProvisioningResult(
        vpnPeerId = 7L,
        tunnelIp = "10.42.0.12",
        dns = "10.42.0.1",
        allowedIps = "10.42.0.0/24",
        serverPubkey = "d".repeat(43) + "=",
        endpoint = "vps.example.com:51820",
    )

    private fun givenRegisterReturns(result: Result<MobileProvisioningResult>) {
        coEvery {
            repository.register(any(), any(), any(), any(), any(), any(), any())
        } returns result
    }

    @Test
    fun `provisioned allowed_ips are persisted and used for the initial connect`() = runTest {
        givenRegisterReturns(Result.success(provisioned))
        val config = slot<WireGuardConfig>()
        every { wireGuardManager.connect(capture(config)) } returns Unit

        val result = useCase(setupData)

        assertTrue(result.isSuccess)
        assertEquals("10.42.0.0/24", store[DataStoreKeys.WG_ALLOWED_IPS.name])
        assertEquals("10.42.0.0/24", config.captured.peer.allowedIPs)
        // The other wg_* keys reconnect() needs are persisted alongside.
        assertEquals("10.42.0.12", store[DataStoreKeys.WG_TUNNEL_IP.name])
        assertEquals("vps.example.com:51820", store[DataStoreKeys.WG_ENDPOINT.name])
        assertEquals(provisioned.serverPubkey, store[DataStoreKeys.WG_SERVER_PUBKEY.name])
        assertEquals("10.42.0.1", store[DataStoreKeys.WG_DNS.name])
        assertTrue(store[DataStoreKeys.WG_PRIVATE_KEY.name]!!.isNotBlank())
    }

    @Test
    fun `register failure persists nothing and never connects`() = runTest {
        givenRegisterReturns(Result.failure(RuntimeException("410 Gone")))

        val result = useCase(setupData)

        assertTrue(result.isFailure)
        assertNull(store[DataStoreKeys.WG_ALLOWED_IPS.name])
        assertNull(store[DataStoreKeys.WG_PRIVATE_KEY.name])
        verify(exactly = 0) { wireGuardManager.connect(any()) }
    }
}
