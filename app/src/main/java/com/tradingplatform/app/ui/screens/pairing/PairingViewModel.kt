package com.tradingplatform.app.ui.screens.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.exception.PairingTimeoutException
import com.tradingplatform.app.domain.model.DevicePairingInfo
import com.tradingplatform.app.domain.model.PairingSession
import com.tradingplatform.app.domain.model.PairingStatus
import com.tradingplatform.app.domain.usecase.pairing.ConfirmPairingUseCase
import com.tradingplatform.app.domain.usecase.pairing.ParseVpsQrUseCase
import com.tradingplatform.app.domain.usecase.pairing.ScanDeviceQrUseCase
import com.tradingplatform.app.domain.usecase.pairing.SendPinToDeviceUseCase
import com.tradingplatform.app.domain.usecase.pairing.StoreDevicePairingResultUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ── PairingStep state machine ─────────────────────────────────────────────────

sealed interface PairingStep {
    /** Initial state — no QR scanned yet. */
    data object Idle : PairingStep

    /** VPS QR scanned, waiting for Radxa QR. */
    data class VpsScanned(val session: PairingSession) : PairingStep

    /** Radxa QR scanned, waiting for VPS QR. */
    data class DeviceScanned(val device: DevicePairingInfo) : PairingStep

    /** Both QR codes scanned — ready to start pairing. */
    data class BothScanned(
        val session: PairingSession,
        val device: DevicePairingInfo,
    ) : PairingStep

    /** PIN is being sent to the Radxa device over LAN. */
    data object SendingPin : PairingStep

    /** PIN sent — polling Radxa for confirmation. */
    data object WaitingConfirmation : PairingStep

    /** Pairing completed successfully. */
    data object Success : PairingStep

    /**
     * Pairing failed or QR is invalid.
     * [retryable] indicates whether the user can retry from the current scan screen.
     */
    data class Error(val message: String, val retryable: Boolean) : PairingStep
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * Shared ViewModel for the 4 pairing screens.
 *
 * The state machine handles both QR codes in any order:
 * - Idle + VPS QR  → VpsScanned
 * - Idle + Device QR → DeviceScanned
 * - VpsScanned + Device QR → BothScanned
 * - DeviceScanned + VPS QR → BothScanned
 * - BothScanned → startPairing() → SendingPin → WaitingConfirmation → Success | Error
 *
 * Security rules:
 * - session_pin is NEVER logged — [REDACTED] if debug output needed.
 * - Mutable StateFlow is private; only immutable StateFlow is exposed.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val parseVpsQrUseCase: ParseVpsQrUseCase,
    private val scanDeviceQrUseCase: ScanDeviceQrUseCase,
    private val sendPinToDeviceUseCase: SendPinToDeviceUseCase,
    private val confirmPairingUseCase: ConfirmPairingUseCase,
    private val storeDevicePairingResultUseCase: StoreDevicePairingResultUseCase,
) : ViewModel() {

    private val _step = MutableStateFlow<PairingStep>(PairingStep.Idle)
    val step: StateFlow<PairingStep> = _step.asStateFlow()

    /** Device info captured when BothScanned is reached — persists through the pairing flow. */
    private val _deviceInfo = MutableStateFlow<DevicePairingInfo?>(null)
    val deviceInfo: StateFlow<DevicePairingInfo?> = _deviceInfo.asStateFlow()

    // ── QR scan handlers ─────────────────────────────────────────────────────

    /**
     * Called when a QR code is detected in [ScanVpsQrScreen].
     * Parses the raw value and transitions state accordingly.
     * session_pin is NEVER logged.
     */
    fun onVpsQrScanned(raw: String) {
        viewModelScope.launch {
            parseVpsQrUseCase(raw)
                .onSuccess { session ->
                    Timber.d("PairingViewModel: VPS QR parsed — sessionId=${session.sessionId} pin=[REDACTED]")
                    val current = _step.value
                    _step.value = when (current) {
                        is PairingStep.DeviceScanned -> {
                            _deviceInfo.value = current.device
                            PairingStep.BothScanned(
                                session = session,
                                device = current.device,
                            )
                        }
                        else -> PairingStep.VpsScanned(session = session)
                    }
                }
                .onFailure { e ->
                    Timber.d("PairingViewModel: VPS QR parse failed — ${e.message}")
                    _step.value = PairingStep.Error(
                        message = "QR non reconnu, réessayez",
                        retryable = true,
                    )
                }
        }
    }

    /**
     * Called when a QR code is detected in [ScanDeviceQrScreen].
     * Parses the raw value and transitions state accordingly.
     */
    fun onDeviceQrScanned(raw: String) {
        viewModelScope.launch {
            scanDeviceQrUseCase(raw)
                .onSuccess { device ->
                    Timber.d("PairingViewModel: Device QR parsed — deviceId=${device.deviceId} ip=${device.localIp}")
                    val current = _step.value
                    _step.value = when (current) {
                        is PairingStep.VpsScanned -> {
                            _deviceInfo.value = device
                            PairingStep.BothScanned(
                                session = current.session,
                                device = device,
                            )
                        }
                        else -> PairingStep.DeviceScanned(device = device)
                    }
                }
                .onFailure { e ->
                    Timber.d("PairingViewModel: Device QR parse failed — ${e.message}")
                    _step.value = PairingStep.Error(
                        message = "QR non reconnu, réessayez",
                        retryable = true,
                    )
                }
        }
    }

    // ── Pairing execution ─────────────────────────────────────────────────────

    /**
     * Starts the pairing sequence. Must only be called when [step] is [PairingStep.BothScanned].
     *
     * Flow:
     * BothScanned → SendingPin → (sendPin ok) → WaitingConfirmation → Success | Error
     *
     * The session_pin is NEVER logged — [REDACTED] in all debug output.
     * SendPinToDeviceUseCase must not be retried after ConfirmPairingUseCase succeeds
     * (VPS invalidates the PIN after first use).
     */
    fun startPairing() {
        val current = _step.value
        if (current !is PairingStep.BothScanned) {
            Timber.w("PairingViewModel: startPairing() called but state is not BothScanned: $current")
            return
        }

        viewModelScope.launch {
            _step.value = PairingStep.SendingPin

            // Step 1 — send encrypted PIN + nonce to Radxa device over LAN
            sendPinToDeviceUseCase(
                deviceIp = current.device.localIp,
                devicePort = current.device.port,
                sessionId = current.session.sessionId,
                sessionPin = current.session.sessionPin,   // never logged by the UseCase ([REDACTED])
                localToken = current.session.localToken,   // never logged ([REDACTED])
                nonce = current.session.nonce,              // never logged ([REDACTED])
                radxaWgPubkey = current.device.wgPubkey,
            ).onFailure { e ->
                Timber.d("PairingViewModel: SendPin failed — ${e.message}")
                _step.value = PairingStep.Error(
                    message = e.localizedMessage ?: "Erreur lors de l'envoi du PIN",
                    retryable = false,
                )
                return@launch
            }

            // Step 2 — poll Radxa for pairing confirmation
            _step.value = PairingStep.WaitingConfirmation

            confirmPairingUseCase(
                deviceIp = current.device.localIp,
                devicePort = current.device.port,
                sessionId = current.session.sessionId,
            ).onSuccess { status ->
                Timber.d("PairingViewModel: ConfirmPairing result — status=$status")
                if (status == PairingStatus.PAIRED) {
                    storeDevicePairingResultUseCase(
                        deviceId = current.device.deviceId,
                        localToken = current.session.localToken,
                        wgPubkey = current.device.wgPubkey,
                        localIp = current.device.localIp,
                    ).onSuccess {
                        _step.value = PairingStep.Success
                    }.onFailure { e ->
                        Timber.e(e, "PairingViewModel: StoreDevicePairingResult failed")
                        _step.value = PairingStep.Error(
                            message = "Échec de la sauvegarde des clés du device",
                            retryable = false,
                        )
                    }
                } else {
                    _step.value = PairingStep.Error(
                        message = "Le device n'a pas pu être appairé",
                        retryable = false,
                    )
                }
            }.onFailure { e ->
                Timber.d("PairingViewModel: ConfirmPairing failed — ${e.message}")
                val message = when (e) {
                    is PairingTimeoutException ->
                        "Session expirée — relancez le pairing depuis le VPS"
                    else ->
                        e.localizedMessage ?: "Erreur de confirmation"
                }
                _step.value = PairingStep.Error(
                    message = message,
                    retryable = false,
                )
            }
        }
    }

    // ── Navigation / lifecycle helpers ────────────────────────────────────────

    /**
     * Resets the state machine to [PairingStep.Idle].
     * Called from [PairingDoneScreen] retry button — allows re-scanning both QR codes.
     */
    fun retry() {
        _step.value = PairingStep.Idle
        _deviceInfo.value = null
    }

    /**
     * Resets the state machine to [PairingStep.Idle].
     * Called on back-button press — session is abandoned on VPS at TTL expiry.
     */
    fun reset() {
        _step.value = PairingStep.Idle
        _deviceInfo.value = null
    }
}
