package com.tradingplatform.app.usecase.vpn

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.usecase.vpn.HasVpnConfigUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HasVpnConfigUseCaseTest {

    private val dataStore = mockk<EncryptedDataStore>()
    private val useCase = HasVpnConfigUseCase(dataStore)

    private fun stub(
        privateKey: String? = "cHJpdmF0ZQ==",
        endpoint: String? = "vps.example.com:51820",
        serverPubKey: String? = "c2VydmVy",
        tunnelIp: String? = "10.42.0.7/32",
    ) {
        coEvery { dataStore.readString(DataStoreKeys.WG_PRIVATE_KEY) } returns privateKey
        coEvery { dataStore.readString(DataStoreKeys.WG_ENDPOINT) } returns endpoint
        coEvery { dataStore.readString(DataStoreKeys.WG_SERVER_PUBKEY) } returns serverPubKey
        coEvery { dataStore.readString(DataStoreKeys.WG_TUNNEL_IP) } returns tunnelIp
    }

    @Test
    fun `true when the four wg keys are present`() = runTest {
        stub()

        assertTrue(useCase())
    }

    @Test
    fun `false when the private key is missing`() = runTest {
        stub(privateKey = null)

        assertFalse(useCase())
    }

    @Test
    fun `false when the endpoint is missing`() = runTest {
        stub(endpoint = null)

        assertFalse(useCase())
    }

    @Test
    fun `false when the server public key is missing`() = runTest {
        stub(serverPubKey = null)

        assertFalse(useCase())
    }

    @Test
    fun `false when the tunnel ip is missing`() = runTest {
        stub(tunnelIp = null)

        assertFalse(useCase())
    }

    @Test
    fun `false when a value is blank`() = runTest {
        stub(endpoint = "  ")

        assertFalse(useCase())
    }
}
