package com.sayit.watch.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sayit.watch.net.Transport
import com.sayit.watch.net.TransportClient
import com.sayit.watch.recording.AudioCapture
import com.sayit.watch.recording.RecordingSession
import com.sayit.watch.recording.WavWriter
import com.sayit.watch.settings.DestinationValidator
import com.sayit.watch.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pure UI state machine for the 0.2.0-dev.2 Wear OS screens
 * (docs/WATCH-UI-Z-HANDOFF.md). No Android dependencies — unit-testable on the
 * JVM. The ViewModel maps recording/transport callbacks onto its events.
 *
 * Screens: CONFIG -> READY -> RECORDING, with inline overlays
 * (UPLOADING / UPLOAD_FAILED / UPLOADED) and a pending-upload latch that
 * survives a failed upload and blocks a new recording until the user explicitly
 * discards the retained WAV (never silently overwritten).
 */
data class WatchUiState(
    val screen: Screen,
    val overlay: Overlay,
    /** A completed WAV is retained and still needs an upload attempt. */
    val pendingUpload: Boolean,
    /** Null until the first health check; true only when /api/health answered. */
    val transportAvailable: Boolean?,
    val failureReason: String?,
    /** Set while the user must decide what happens to a retained WAV. */
    val discardPrompt: Boolean,
) {
    enum class Screen { CONFIG, READY, RECORDING }
    enum class Overlay { NONE, UPLOADING, UPLOAD_FAILED, UPLOADED }

    val busy: Boolean
        get() = screen == Screen.RECORDING || overlay == Overlay.UPLOADING

    /** MainActivity maps this to WindowManager FLAG_KEEP_SCREEN_ON. */
    val keepScreenOn: Boolean get() = busy

    val showsPendingUploadBadge: Boolean
        get() = pendingUpload && overlay == Overlay.NONE && screen == Screen.READY
}

class WatchUiStateMachine(
    initial: WatchUiState =
        WatchUiState(
            screen = WatchUiState.Screen.CONFIG,
            overlay = WatchUiState.Overlay.NONE,
            pendingUpload = false,
            transportAvailable = null,
            failureReason = null,
            discardPrompt = false,
        ),
) {
    var state: WatchUiState = initial
        private set

    private fun set(state: WatchUiState) {
        this.state = state
    }

    /** Save & Apply on the config screen: valid destination -> READY. */
    fun settingsApplied() {
        if (state.screen == WatchUiState.Screen.CONFIG) {
            set(state.copy(screen = WatchUiState.Screen.READY, discardPrompt = false))
        }
    }

    fun backToConfig() {
        if (state.screen != WatchUiState.Screen.RECORDING && state.overlay == WatchUiState.Overlay.NONE) {
            set(state.copy(screen = WatchUiState.Screen.CONFIG))
        }
    }

    /** Result of the explicit /api/health check on the Ready screen. */
    fun healthChecked(available: Boolean) {
        set(state.copy(transportAvailable = available))
    }

    /** True when pressing Record must first ask the user to discard the WAV. */
    fun recordNeedsDiscardConfirmation(): Boolean =
        state.screen == WatchUiState.Screen.READY &&
            state.overlay == WatchUiState.Overlay.NONE &&
            state.pendingUpload

    fun showDiscardPrompt() {
        if (recordNeedsDiscardConfirmation()) set(state.copy(discardPrompt = true))
    }

    fun dismissDiscardPrompt() {
        set(state.copy(discardPrompt = false))
    }

    /** Explicit user decision: drop the retained WAV, then recording may start. */
    fun pendingDiscarded() {
        set(state.copy(pendingUpload = false, discardPrompt = false, failureReason = null))
    }

    fun recordingStarted() {
        if (state.screen == WatchUiState.Screen.READY && !state.pendingUpload && state.overlay == WatchUiState.Overlay.NONE) {
            set(state.copy(screen = WatchUiState.Screen.RECORDING, failureReason = null))
        }
    }

    /** The completed WAV uploads automatically (Stop -> auto-upload). */
    fun uploadStarted() {
        if (state.screen == WatchUiState.Screen.RECORDING) {
            set(state.copy(screen = WatchUiState.Screen.READY, overlay = WatchUiState.Overlay.UPLOADING))
        }
    }

    /** Cancel pressed on the recording screen: stop and discard, never upload. */
    fun cancelPressed() {
        if (state.screen == WatchUiState.Screen.RECORDING) {
            set(state.copy(screen = WatchUiState.Screen.READY, overlay = WatchUiState.Overlay.NONE))
        }
    }

    fun uploadFailed(reason: String) {
        if (state.overlay == WatchUiState.Overlay.UPLOADING) {
            set(
                state.copy(
                    overlay = WatchUiState.Overlay.UPLOAD_FAILED,
                    pendingUpload = true,
                    failureReason = reason,
                ),
            )
        }
    }

    /** Retry re-uploads the SAME retained WAV — no re-record, no discard. */
    fun retryPressed() {
        if (state.overlay == WatchUiState.Overlay.UPLOAD_FAILED) {
            set(state.copy(overlay = WatchUiState.Overlay.UPLOADING, failureReason = null))
        }
    }

    /** Later: back to Ready, keeping the obvious Pending-upload badge. */
    fun laterPressed() {
        if (state.overlay == WatchUiState.Overlay.UPLOAD_FAILED) {
            set(state.copy(overlay = WatchUiState.Overlay.NONE))
        }
    }

    fun uploadSucceeded() {
        if (state.overlay == WatchUiState.Overlay.UPLOADING) {
            set(
                state.copy(
                    overlay = WatchUiState.Overlay.UPLOADED,
                    pendingUpload = false,
                    failureReason = null,
                ),
            )
        }
    }

    /** The brief "Uploaded to PC" confirmation auto-returns to Ready. */
    fun uploadedDismissed() {
        if (state.overlay == WatchUiState.Overlay.UPLOADED) {
            set(state.copy(overlay = WatchUiState.Overlay.NONE))
        }
    }
}

/**
 * ViewModel owning the Delivery 1A recording/transport lifecycle plus the
 * 0.2.0-dev.2 UI state machine. The HTTP sender is created through
 * [Transport.sender]; in release builds that returns null and sending fails
 * closed. Stop always auto-uploads the completed WAV; a failure retains it
 * (Retry/Later) and a new recording requires an explicit discard first.
 */
class RecordingViewModel(
    private val settings: SettingsStore,
    private val session: RecordingSession = RecordingSession(),
    private val capture: AudioCapture = AudioCapture(),
    private val clientFactory: (() -> TransportClient?) = { Transport.sender() },
) : ViewModel() {

    private val _state = MutableStateFlow(RecordingSession.State.IDLE)
    val state: StateFlow<RecordingSession.State> = _state.asStateFlow()

    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Delivery 1B UI state machine (screens + inline upload states). */
    private val machine = WatchUiStateMachine()
    private val _ui = MutableStateFlow(machine.state)
    val ui: StateFlow<WatchUiState> = _ui.asStateFlow()

    private fun syncUi() {
        _ui.value = machine.state
    }

    private fun uiEvent(event: (WatchUiStateMachine) -> Unit) {
        event(machine)
        syncUi()
    }

    private val _canSend = MutableStateFlow(false)
    val canSend: StateFlow<Boolean> = _canSend.asStateFlow()

    /** Duration in ms derived from the captured sample count (never a wall clock). */
    val durationMs: Long get() = WavWriter.durationMs(_sampleCount.value)

    /** Result of the last 16 kHz capability check, if it succeeded. */
    var initInfo: AudioCapture.InitResult.Ready? = null
        private set

    private fun syncState() {
        _state.value = session.state
        _sampleCount.value = session.sampleCount
        _lastError.value = session.lastError
        _canSend.value = session.canSend()
    }

    /** Verifies 16 kHz capability and moves to READY. */
    fun prepare() {
        val result = capture.verifySupported()
        when (result) {
            is AudioCapture.InitResult.Ready -> {
                initInfo = result
                session.toReady()
            }
            is AudioCapture.InitResult.Failed -> {
                session.recordingFailed(result.reason)
            }
        }
        syncState()
    }

    /** Requires RECORD_AUDIO permission; caller must have requested it. */
    fun hasRecordPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Save & Apply on the config screen: persist, validate, then Ready. */
    fun applySettings(ip: String, port: String, token: String) {
        settings.receiverIp = ip.trim()
        settings.receiverPort = port.trim()
        settings.devToken = token.trim()
        if (settings.isValidDestination() && settings.hasValidToken()) {
            prepare()
            uiEvent(WatchUiStateMachine::settingsApplied)
        }
    }

    /** Open the developer configuration screen from Ready. */
    fun openConfig() {
        uiEvent(WatchUiStateMachine::backToConfig)
    }

    /** Explicit health check: /api/health reachable means transport available
     *  only — never Provider/ASR readiness. Release builds fail closed. */
    fun checkHealth() {
        viewModelScope.launch {
            val available = withContext(Dispatchers.IO) {
                if (!Transport.isDebug() || Transport.sender() == null) {
                    return@withContext false
                }
                try {
                    val dest = DestinationValidator.validate(settings.receiverIp, settings.receiverPort)
                    if (dest !is DestinationValidator.ValidationResult.Valid) {
                        return@withContext false
                    }
                    val url = java.net.URL("http://${dest.ip}:${dest.port}/api/health")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 4_000
                    connection.readTimeout = 4_000
                    connection.requestMethod = "GET"
                    val status = connection.responseCode
                    val body = connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    status == 200 && body.contains("sayit-watch-debug-receiver")
                } catch (e: Exception) {
                    false
                }
            }
            uiEvent { it.healthChecked(available) }
        }
    }

    /**
     * Record button entry point. A retained WAV from a failed upload is never
     * silently overwritten: the UI state machine raises the explicit discard
     * prompt instead.
     */
    fun recordButtonPressed() {
        if (machine.recordNeedsDiscardConfirmation()) {
            uiEvent(WatchUiStateMachine::showDiscardPrompt)
            return
        }
        startRecording()
    }

    /** User confirmed: drop the retained WAV, then start the new recording. */
    fun discardConfirmed() {
        uiEvent(WatchUiStateMachine::pendingDiscarded)
        session.reset()
        prepare()
        syncState()
        startRecording()
    }

    fun discardPromptDismissed() {
        uiEvent(WatchUiStateMachine::dismissDiscardPrompt)
    }

    /** Starts recording on a dedicated I/O coroutine. */
    fun startRecording(maxDurationSec: Int = 15) {
        prepare()
        if (session.state != RecordingSession.State.READY) return
        session.startRecording()
        recordingActive = true
        syncState()
        uiEvent(WatchUiStateMachine::recordingStarted)
        vibrate(RecordingSession.State.RECORDING)
        viewModelScope.launch {
            val samples = withContext(Dispatchers.IO) {
                val pcm = java.io.ByteArrayOutputStream()
                try {
                    val count = capture.record(
                        maxDurationSec * 1000,
                        { recordingActive },
                        { bytes, _ -> pcm.write(bytes) },
                        // Publish the cumulative captured sample count live so the
                        // UI can render a sample-derived duration while recording.
                        { cumulative -> _sampleCount.value = cumulative },
                    )
                    val wav = WavWriter.buildWav(pcm.toByteArray(), pcm.size())
                    session.recordingCompleted(count, wav)
                    count
                } catch (e: Exception) {
                    session.recordingFailed(e.message ?: "recording failed")
                    -1
                }
            }
            syncState()
            if (samples >= 0) {
                vibrate(RecordingSession.State.RECORDED)
                // Stop always auto-uploads the completed WAV (no separate Send page).
                send()
            }
        }
    }

    /** Stops the running recording (idempotent). The capture coroutine then
     *  completes the WAV and auto-uploads it — no separate Send step. */
    fun stopRecording() {
        recordingActive = false
    }

    /**
     * Cancel on the recording screen: stop capture, discard the partial
     * recording, never upload, back to Ready.
     */
    fun cancelRecording() {
        recordingActive = false
        session.reset()
        prepare()
        syncState()
        uiEvent(WatchUiStateMachine::cancelPressed)
    }

    private var recordingActive = false

    /** Sends the retained WAV; retryable without re-recording after failure. */
    fun send() {
        if (!session.canSend()) return
        val client = clientFactory()
        if (client == null) {
            session.transportFailed("cleartext HTTP sender is unavailable in this build")
            syncState()
            uiEvent { it.uploadFailed("sender unavailable") }
            vibrate(RecordingSession.State.FAILURE)
            return
        }
        session.beginUpload()
        syncState()
        uiEvent(WatchUiStateMachine::uploadStarted)
        viewModelScope.launch {
            val wav = session.wavBytes ?: return@launch
            val result = withContext(Dispatchers.IO) {
                val dest = DestinationValidator.validate(
                    settings.receiverIp,
                    settings.receiverPort,
                )
                if (dest !is DestinationValidator.ValidationResult.Valid) {
                    val reason = (dest as DestinationValidator.ValidationResult.Invalid).reason
                    TransportClient.UploadResult.Failure("invalid destination: $reason")
                } else {
                    client.upload(dest.ip, dest.port, settings.devToken, wav)
                }
            }
            when (result) {
                is TransportClient.UploadResult.Success -> {
                    session.transportSucceeded()
                    syncState()
                    uiEvent(WatchUiStateMachine::uploadSucceeded)
                    vibrate(RecordingSession.State.TRANSPORT_SUCCESS)
                    // Brief transport-only confirmation, then back to Ready.
                    viewModelScope.launch {
                        delay(UPLOADED_BRIEF_MS)
                        uiEvent(WatchUiStateMachine::uploadedDismissed)
                    }
                }
                is TransportClient.UploadResult.Failure -> {
                    session.transportFailed(result.reason)
                    syncState()
                    uiEvent { it.uploadFailed(result.reason) }
                    vibrate(RecordingSession.State.FAILURE)
                }
            }
        }
    }

    /** Retry after failure: uploads the SAME retained WAV, never re-records. */
    fun retryUpload() {
        uiEvent(WatchUiStateMachine::retryPressed)
        send()
    }

    /** Later: back to Ready with the obvious Pending-upload badge. */
    fun laterPressed() {
        uiEvent(WatchUiStateMachine::laterPressed)
    }

    fun uploadedBriefDismissed() {
        uiEvent(WatchUiStateMachine::uploadedDismissed)
    }

    fun reset() {
        session.reset()
        syncState()
    }

    private fun vibrate(forState: RecordingSession.State) {
        // Short vibration feedback for recording start/stop and upload success/failure.
        val pattern = when (forState) {
            RecordingSession.State.RECORDING -> longArrayOf(0, 60)
            RecordingSession.State.RECORDED -> longArrayOf(0, 40, 60, 40)
            RecordingSession.State.TRANSPORT_SUCCESS -> longArrayOf(0, 30, 40, 30, 40, 30)
            else -> longArrayOf(0, 120)
        }
        // Vibrator is resolved lazily by the UI host (needs Context); see MainActivity.
        onVibrate?.invoke(pattern)
    }

    /** Set by MainActivity with a Context-bound vibrator. */
    var onVibrate: ((LongArray) -> Unit)? = null

    class Factory(private val settings: SettingsStore) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RecordingViewModel(settings) as T
    }

    companion object {
        /** How long the brief "Uploaded to PC" confirmation stays visible. */
        const val UPLOADED_BRIEF_MS = 2_000L
    }
}

/** Vibrates using the modern VibratorManager API (API 31+; minSdk 30 fallback below). */
fun vibratePattern(context: Context, pattern: LongArray) {
    val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
}
