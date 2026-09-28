package com.tradingplatform.app.vpn

import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.wireguard.android.backend.Tunnel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [WireGuardManager] concurrency (audit C-vpn-conc-1, C-vpn-err-1).
 * GoBackend is replaced by [FakeTunnelBackend]; the notification service by
 * [FakeServiceController].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WireGuardManagerTest {

    private val config = WireGuardConfig(
        privateKey = "cHJpdmF0ZWtleWZvcndpcmVndWFyZHRlc3Rpbmc=",
        address = "10.42.0.5/32",
        peer = WireGuardPeer(
            publicKey = "dGVzdHB1YmxpY2tleWZvcndpcmVndWFyZA==",
            endpoint = "vps.example.com:51820",
        ),
    )

    /** Mimics GoBackend: synchronous onStateChange for each transition it performs. */
    private class FakeTunnelBackend : TunnelBackend {
        var state: Tunnel.State = Tunnel.State.DOWN
        var lastTunnel: Tunnel? = null
        val calls = mutableListOf<Tunnel.State>()
        var lastUpConfig: WireGuardConfig? = null

        /** When set, setState(UP) suspends until completed (simulates a slow handshake). */
        var upGate: CompletableDeferred<Unit>? = null

        override suspend fun setState(
            tunnel: Tunnel,
            state: Tunnel.State,
            config: WireGuardConfig?,
        ): Tunnel.State {
            calls += state
            lastTunnel = tunnel
            if (state == Tunnel.State.UP) lastUpConfig = config
            if (state == Tunnel.State.UP) upGate?.await()
            if (state != this.state) {
                this.state = state
                tunnel.onStateChange(state)
            }
            return this.state
        }

        override suspend fun getState(tunnel: Tunnel): Tunnel.State = state

        /** OS revoked the VPN: GoBackend$VpnService.onDestroy → onStateChange(DOWN). */
        fun simulateRevocation() {
            state = Tunnel.State.DOWN
            lastTunnel!!.onStateChange(Tunnel.State.DOWN)
        }
    }

    private class FakeServiceController : VpnServiceController {
        var startCount = 0
        var stopCount = 0
        override fun startForeground() { startCount++ }
        override fun stop() { stopCount++ }
    }

    /** `VpnService.prepare` stand-in: [intent] non-null = consent missing. */
    private class FakeConsentChecker : VpnConsentChecker {
        var intent: Intent? = null
        var calls = 0
        override fun prepareIntent(): Intent? { calls++; return intent }
    }

    private val backend = FakeTunnelBackend()
    private val serviceController = FakeServiceController()
    private val consentChecker = FakeConsentChecker()
    private var backendCreations = 0

    // applicationScope = the TestScope itself, NOT backgroundScope: work launched from
    // backgroundScope is flagged as background and advanceUntilIdle() stops as soon as only
    // background tasks remain — the manager's coroutines would never run. Every job launched
    // here completes within each test, so runTest's leak check is satisfied.
    private fun TestScope.createManager(): WireGuardManager = WireGuardManager(
        applicationScope = this,
        dataStore = mockk<EncryptedDataStore>(relaxed = true),
        backendFactory = { backendCreations++; backend },
        serviceController = serviceController,
        consentChecker = consentChecker,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `connect brings tunnel UP and starts the notification service`() = runTest {
        val manager = createManager()

        manager.connect(config)
        advanceUntilIdle()

        assertEquals(VpnState.Connected(), manager.state.value)
        assertEquals(Tunnel.State.UP, backend.state)
        assertEquals(1, serviceController.startCount)
        assertEquals(0, serviceController.stopCount)
    }

    @Test
    fun `disconnect while connect is suspended ends Disconnected with tunnel DOWN`() = runTest {
        val manager = createManager()
        val gate = CompletableDeferred<Unit>()
        backend.upGate = gate

        manager.connect(config)
        runCurrent()
        // connect is blocked inside setState(UP), holding the lock
        assertEquals(VpnState.Connecting, manager.state.value)

        manager.disconnect()
        runCurrent()
        // disconnect is queued on the mutex, not lost
        assertEquals(listOf(Tunnel.State.UP), backend.calls)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(VpnState.Disconnected, manager.state.value)
        assertEquals(Tunnel.State.DOWN, backend.state)
        assertEquals(listOf(Tunnel.State.UP, Tunnel.State.DOWN), backend.calls)
        assertTrue("notification service must be stopped", serviceController.stopCount >= 1)
    }

    @Test
    fun `connect superseded by disconnect before it runs never brings the tunnel UP`() = runTest {
        val manager = createManager()

        manager.connect(config)
        manager.disconnect()
        advanceUntilIdle()

        assertEquals(VpnState.Disconnected, manager.state.value)
        assertTrue("setState(UP) must not be called", Tunnel.State.UP !in backend.calls)
        assertEquals(0, serviceController.startCount)
    }

    @Test
    fun `external DOWN callback stops the notification service`() = runTest {
        val manager = createManager()
        manager.connect(config)
        advanceUntilIdle()
        assertEquals(VpnState.Connected(), manager.state.value)

        backend.simulateRevocation()

        assertEquals(VpnState.Disconnected, manager.state.value)
        assertEquals(1, serviceController.stopCount)

        // A later disconnect() is a no-op on the backend (currentTunnel was cleared)
        backend.calls.clear()
        manager.disconnect()
        advanceUntilIdle()
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `backend is created once across connect cycles`() = runTest {
        val manager = createManager()

        manager.connect(config)
        advanceUntilIdle()
        manager.disconnect()
        advanceUntilIdle()
        manager.connect(config)
        advanceUntilIdle()

        assertEquals(1, backendCreations)
        assertEquals(VpnState.Connected(), manager.state.value)
    }

    @Test
    fun `connect failure publishes Error and stops the notification service`() = runTest {
        val failing = object : TunnelBackend {
            override suspend fun setState(
                tunnel: Tunnel,
                state: Tunnel.State,
                config: WireGuardConfig?,
            ): Tunnel.State = throw IllegalStateException("VPN_NOT_AUTHORIZED")

            override suspend fun getState(tunnel: Tunnel): Tunnel.State = Tunnel.State.DOWN
        }
        val manager = WireGuardManager(
            applicationScope = this,
            dataStore = mockk<EncryptedDataStore>(relaxed = true),
            backendFactory = { failing },
            serviceController = serviceController,
            consentChecker = consentChecker,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        manager.connect(config)
        advanceUntilIdle()

        assertEquals(VpnState.Error("VPN_NOT_AUTHORIZED"), manager.state.value)
        assertEquals(1, serviceController.stopCount)
    }

    // ── VPN consent (VpnService.prepare) ─────────────────────────────────────

    @Test
    fun `connect without VPN consent publishes ConsentRequired and never calls the backend`() = runTest {
        consentChecker.intent = mockk<Intent>(relaxed = true)
        val manager = createManager()

        manager.connect(config)
        advanceUntilIdle()

        assertEquals(VpnState.ConsentRequired, manager.state.value)
        assertTrue("backend must not be called", backend.calls.isEmpty())
        assertEquals("backend must not even be created", 0, backendCreations)
        assertEquals("notification service must not start", 0, serviceController.startCount)
        assertEquals(1, consentChecker.calls)
    }

    @Test
    fun `prepareIntent delegates to VpnService prepare seam`() = runTest {
        val manager = createManager()
        assertNull(manager.prepareIntent())

        val intent = mockk<Intent>(relaxed = true)
        consentChecker.intent = intent
        assertSame(intent, manager.prepareIntent())
    }

    @Test
    fun `retryAfterConsent replays the pending connect once consent is granted`() = runTest {
        consentChecker.intent = mockk<Intent>(relaxed = true)
        val manager = createManager()
        manager.connect(config)
        advanceUntilIdle()
        assertEquals(VpnState.ConsentRequired, manager.state.value)

        consentChecker.intent = null // user accepted the system dialog
        manager.retryAfterConsent()
        // Connecting is published synchronously — observers never re-read the stale state.
        assertEquals(VpnState.Connecting, manager.state.value)
        advanceUntilIdle()

        assertEquals(VpnState.Connected(), manager.state.value)
        assertEquals(listOf(Tunnel.State.UP), backend.calls)
        assertEquals(1, serviceController.startCount)
    }

    @Test
    fun `retryAfterConsent without granted consent goes back to ConsentRequired`() = runTest {
        consentChecker.intent = mockk<Intent>(relaxed = true)
        val manager = createManager()
        manager.connect(config)
        advanceUntilIdle()

        manager.retryAfterConsent()
        advanceUntilIdle()

        assertEquals(VpnState.ConsentRequired, manager.state.value)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `disconnect while consent is pending ends Disconnected and drops the pending config`() = runTest {
        consentChecker.intent = mockk<Intent>(relaxed = true)
        val emptyStore = mockk<EncryptedDataStore> {
            coEvery { readString(any<Preferences.Key<String>>()) } returns null
        }
        val manager = WireGuardManager(
            applicationScope = this,
            dataStore = emptyStore,
            backendFactory = { backendCreations++; backend },
            serviceController = serviceController,
            consentChecker = consentChecker,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        manager.connect(config)
        advanceUntilIdle()

        manager.disconnect()
        advanceUntilIdle()
        assertEquals(VpnState.Disconnected, manager.state.value)

        // No pending config any more: retryAfterConsent falls back to reconnect() from the
        // store, which is empty here (relaxed mock → null keys) → nothing is brought UP.
        consentChecker.intent = null
        manager.retryAfterConsent()
        advanceUntilIdle()
        assertEquals(VpnState.Disconnected, manager.state.value)
        assertTrue(Tunnel.State.UP !in backend.calls)
    }

    // ── reconnect() from the wg_* keys (audit REPORT §10: provisioned allowed_ips) ──

    private fun TestScope.createManager(store: Map<String, String>): WireGuardManager =
        WireGuardManager(
            applicationScope = this,
            dataStore = mockk<EncryptedDataStore> {
                coEvery { readString(any<Preferences.Key<String>>()) } answers {
                    store[firstArg<Preferences.Key<String>>().name]
                }
            },
            backendFactory = { backendCreations++; backend },
            serviceController = serviceController,
            consentChecker = consentChecker,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

    private val provisionedStore = mapOf(
        DataStoreKeys.WG_PRIVATE_KEY.name to "cHJpdmF0ZWtleWZvcndpcmVndWFyZHRlc3Rpbmc=",
        DataStoreKeys.WG_ENDPOINT.name to "vps.example.com:51820",
        DataStoreKeys.WG_SERVER_PUBKEY.name to "dGVzdHB1YmxpY2tleWZvcndpcmVndWFyZA==",
        DataStoreKeys.WG_TUNNEL_IP.name to "10.42.0.12",
        DataStoreKeys.WG_DNS.name to "10.42.0.1",
    )

    @Test
    fun `reconnect rebuilds the tunnel with the persisted provisioned allowed IPs`() = runTest {
        val manager = createManager(
            provisionedStore + (DataStoreKeys.WG_ALLOWED_IPS.name to "10.42.0.0/24"),
        )

        manager.reconnect()
        advanceUntilIdle()

        assertEquals(VpnState.Connected(), manager.state.value)
        val up = backend.lastUpConfig!!
        assertEquals("10.42.0.0/24", up.peer.allowedIPs)
        assertEquals("10.42.0.12", up.address)
        assertEquals("10.42.0.1", up.dns)
        assertEquals("vps.example.com:51820", up.peer.endpoint)
    }

    @Test
    fun `reconnect falls back to full tunnel when no allowed IPs were persisted`() = runTest {
        // Install provisioned before wg_allowed_ips existed.
        val manager = createManager(provisionedStore)

        manager.reconnect()
        advanceUntilIdle()

        assertEquals(VpnState.Connected(), manager.state.value)
        assertEquals(WireGuardPeer.DEFAULT_ALLOWED_IPS, backend.lastUpConfig!!.peer.allowedIPs)
    }

    @Test
    fun `reconnect treats blank persisted allowed IPs as absent`() = runTest {
        val manager = createManager(provisionedStore + (DataStoreKeys.WG_ALLOWED_IPS.name to " "))

        manager.reconnect()
        advanceUntilIdle()

        assertEquals(WireGuardPeer.DEFAULT_ALLOWED_IPS, backend.lastUpConfig!!.peer.allowedIPs)
    }
}
