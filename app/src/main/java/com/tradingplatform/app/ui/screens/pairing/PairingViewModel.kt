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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /** Device info captured as soon as the Radxa QR is scanned — persists through the pairing flow. */
    private val _deviceInfo = MutableStateFlow<DevicePairingInfo?>(null)
    val deviceInfo: StateFlow<DevicePairingInfo?> = _deviceInfo.asStateFlow()

    /**
     * VPS session captured as soon as the VPS QR is scanned — persists through the pairing
     * flow so [PairingProgressScreen] can display [PairingSession.deviceWgIp] for confirmation
     * (CLAUDE.md §8). Never sent anywhere — display only.
     */
    private val _sessionInfo = MutableStateFlow<PairingSession?>(null)
    val sessionInfo: StateFlow<PairingSession?> = _sessionInfo.asStateFlow()

    /**
     * One-shot QR scan errors (misread while a QR was already scanned) — a snackbar + haptic
     * event, distinct from [PairingStep.Error] which is reserved for the very first misread
     * (nothing scanned yet). [MutableSharedFlow] with no replay: a screen not currently
     * collecting (e.g. recreated mid-emission) simply misses it, which is fine for a transient
     * "try again" hint.
     */
    private val _scanErrors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val scanErrors: SharedFlow<String> = _scanErrors.asSharedFlow()

    /** Tracks the in-flight pairing coroutine so [reset] can abort a 120 s WaitingConfirmation. */
    private var pairingJob: Job? = null

    // ── QR scan handlers ─────────────────────────────────────────────────────

    /**
     * Called when a QR code is detected in [ScanVpsQrScreen].
     * Tries the VPS parser first, then the Device parser as a fallback — this matches the
     * CLAUDE.md contract "L'app scanne les deux QR dans n'importe quel ordre". It also
     * prevents the camera from looping on a misread when one QR is still in frame after
     * a screen transition.
     * session_pin is NEVER logged.
     */
    fun onVpsQrScanned(raw: String) {
        viewModelScope.launch {
            val vpsResult = parseVpsQrUseCase(raw)
            if (vpsResult.isSuccess) {
                applyVpsScan(vpsResult.getOrThrow())
                return@launch
            }
            // Fallback: the user may have scanned the Radxa QR by mistake on this screen
            val deviceResult = scanDeviceQrUseCase(raw)
            if (deviceResult.isSuccess) {
                applyDeviceScan(deviceResult.getOrThrow())
                return@launch
            }
            Timber.d("PairingViewModel: VPS QR parse failed — ${vpsResult.exceptionOrNull()?.message}")
            onUnrecognizedQr()
        }
    }

    /**
     * Called when a QR code is detected in [ScanDeviceQrScreen].
     * Tries the Device parser first, then the VPS parser as a fallback.
     */
    fun onDeviceQrScanned(raw: String) {
        viewModelScope.launch {
            val deviceResult = scanDeviceQrUseCase(raw)
            if (deviceResult.isSuccess) {
                applyDeviceScan(deviceResult.getOrThrow())
                return@launch
            }
            val vpsResult = parseVpsQrUseCase(raw)
            if (vpsResult.isSuccess) {
                applyVpsScan(vpsResult.getOrThrow())
                return@launch
            }
            Timber.d("PairingViewModel: Device QR parse failed — ${deviceResult.exceptionOrNull()?.message}")
            onUnrecognizedQr()
        }
    }

    /**
     * Neither parser recognized the scanned QR.
     *
     * If nothing has been scanned yet ([PairingStep.Idle]), this is the very first attempt —
     * transition to [PairingStep.Error] as documented in CLAUDE.md §8. If one QR is already
     * scanned (or both), a misread must NOT discard that progress: keep [_step] as-is and
     * emit a one-shot [_scanErrors] event so the screen can show a snackbar + haptic without
     * losing the already-scanned QR.
     */
    private suspend fun onUnrecognizedQr() {
        if (_step.value is PairingStep.Idle) {
            _step.value = PairingStep.Error(
                message = "QR non reconnu, réessayez",
                retryable = true,
            )
        } else {
            _scanErrors.emit("QR non reconnu, réessayez")
        }
    }

    private fun applyVpsScan(session: PairingSession) {
        Timber.d("PairingViewModel: VPS QR parsed — sessionId=${session.sessionId} pin=[REDACTED]")
        _sessionInfo.value = session
        val current = _step.value
        _step.value = when (current) {
            is PairingStep.DeviceScanned ->
                PairingStep.BothScanned(session = session, device = current.device)
            is PairingStep.BothScanned ->
                PairingStep.BothScanned(session = session, device = current.device)
            else -> PairingStep.VpsScanned(session = session)
        }
    }

    private fun applyDeviceScan(device: DevicePairingInfo) {
        Timber.d("PairingViewModel: Device QR parsed — deviceId=${device.deviceId} ip=${device.localIp}")
        _deviceInfo.value = device
        val current = _step.value
        _step.value = when (current) {
            is PairingStep.VpsScanned ->
                PairingStep.BothScanned(session = current.session, device = device)
            is PairingStep.BothScanned ->
                PairingStep.BothScanned(session = current.session, device = device)
            else -> PairingStep.DeviceScanned(device = device)
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

        pairingJob = viewModelScope.launch {
            try {
                _step.value = PairingStep.SendingPin

                // L'id lu dans le QR Radxa est provisoire (`radxa-pending-…`) : le VPS alloue
                // l'id définitif (`radxa-<12hex>`) que la Radxa adopte et renvoie dans le 200 de
                // /pin. Les clés locales (local_token, pubkey, IP) sont stockées sous celui-là ;
                // repli sur l'id du QR si le device ne le fournit pas.
                var deviceId = current.device.deviceId

                // Step 1 — send encrypted PIN + nonce to Radxa device over LAN
                sendPinToDeviceUseCase(
                    deviceIp = current.device.localIp,
                    devicePort = current.device.port,
                    sessionId = current.session.sessionId,
                    sessionPin = current.session.sessionPin,   // never logged by the UseCase ([REDACTED])
                    localToken = current.session.localToken,   // never logged ([REDACTED])
                    nonce = current.session.nonce,              // never logged ([REDACTED])
                    radxaWgPubkey = current.device.wgPubkey,
                ).onSuccess { definitiveId ->
                    definitiveId?.takeIf { it.isNotBlank() }?.let { deviceId = it }
                }.onFailure { e ->
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
                            deviceId = deviceId,
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
            } catch (e: CancellationException) {
                // Annulation normale (reset() / navigation back) — OkHttp propage cancel()
                // à toute Call en vol, ce qui ferme immédiatement la socket TCP LAN côté client.
                // Re-throw pour respecter la structured concurrency.
                Timber.d("PairingViewModel: pairing job cancelled — LAN socket closed via coroutine cancel")
                throw e
            } finally {
                // Garantir que pairingJob ne retient plus la coroutine terminée.
                // Pas d'IO ici — la cleanup réseau est gérée par OkHttp sur cancel().
                pairingJob = null
            }
        }
    }

    // ── Navigation / lifecycle helpers ────────────────────────────────────────

    /**
     * Resets the state machine to [PairingStep.Idle].
     * Called from [PairingDoneScreen] retry button — allows re-scanning both QR codes.
     */
    fun retry() {
        pairingJob?.cancel()
        pairingJob = null
        _step.value = PairingStep.Idle
        _deviceInfo.value = null
        _sessionInfo.value = null
    }

    /**
     * Resets the state machine to [PairingStep.Idle] and cancels any in-flight pairing job.
     * Called on back / cancel — session is abandoned on VPS at TTL expiry (120 s).
     */
    fun reset() {
        pairingJob?.cancel()
        pairingJob = null
        _step.value = PairingStep.Idle
        _deviceInfo.value = null
        _sessionInfo.value = null
    }
}
