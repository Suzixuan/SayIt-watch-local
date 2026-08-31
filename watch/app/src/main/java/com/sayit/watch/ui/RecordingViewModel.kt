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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pure UI state machine for the 0.2.0-dev.3 minimal Watch screens
 * (docs/WATCH-UI-Z-HANDOFF.md). No Android dependencies — unit-testable on the
 * JVM. The ViewModel maps recording/transport callbacks onto its events.
 *
 * Screens: CONFIG -> READY -> RECORDING. Uploading remains an internal busy
 * latch only: it returns visually to Ready, blocks another recording, and is
 * cleared silently after either HTTP result.
 *
 * The multi-generation Cancel/completion coordinator lives in
 * RecordingRequestLatch.kt (Z3 Repair 4 必修 1).
 */
data class WatchUiState(
    val screen: Screen,
    /** Null until the first health check; true only when /api/health answered. */
    val transportAvailable: Boolean?,
    /** Internal only: no visible upload state is rendered while this is true. */
    val isUploading: Boolean,
) {
    enum class Screen { CONFIG, READY, RECORDING }

    val busy: Boolean
        get() = screen == Screen.RECORDING || isUploading

    /** MainActivity maps this to WindowManager FLAG_KEEP_SCREEN_ON. */
    val keepScreenOn: Boolean get() = busy
}

class WatchUiStateMachine(
    initial: WatchUiState =
        WatchUiState(
            screen = WatchUiState.Screen.CONFIG,
            transportAvailable = null,
            isUploading = false,
        ),
) {
    var state: WatchUiState = initial
        private set

    private fun set(state: WatchUiState) {
        this.state = state
    }

    /**
     * Startup decision (dev.3 Config rule): a valid saved destination+token
     * starts directly on Ready; missing/invalid configuration starts on Config.
     * Idempotent — later calls do not move a RECORDING screen.
     */
    fun startupWith(configValid: Boolean) {
        if (state.screen == WatchUiState.Screen.CONFIG && !state.isUploading) {
            if (configValid) {
                set(state.copy(screen = WatchUiState.Screen.READY))
            }
            // invalid/missing -> stay CONFIG
        }
    }

    /** Save & Apply on the config screen: valid destination -> READY. */
    fun settingsApplied() {
        if (state.screen == WatchUiState.Screen.CONFIG) {
            set(state.copy(screen = WatchUiState.Screen.READY))
        }
    }

    fun backToConfig() {
        if (state.screen != WatchUiState.Screen.RECORDING && !state.isUploading) {
            set(state.copy(screen = WatchUiState.Screen.CONFIG))
        }
    }

    /** Result of the explicit /api/health check on the Ready screen. */
    fun healthChecked(available: Boolean) {
        set(state.copy(transportAvailable = available))
    }

    /** Ready is visible during a silent upload, but cannot start another capture. */
    fun canStartRecording(): Boolean = state.screen == WatchUiState.Screen.READY && !state.isUploading

    fun recordingStarted() {
        if (canStartRecording()) {
            set(state.copy(screen = WatchUiState.Screen.RECORDING))
        }
    }

    /** Stop uploads automatically but immediately returns the visible screen to Ready. */
    fun uploadStarted() {
        if (state.screen == WatchUiState.Screen.RECORDING) {
            set(state.copy(screen = WatchUiState.Screen.READY, isUploading = true))
        }
    }

    /** Cancel pressed on the recording screen: stop and discard, never upload. */
    fun cancelPressed() {
        if (state.screen == WatchUiState.Screen.RECORDING) {
            set(state.copy(screen = WatchUiState.Screen.READY))
        }
    }

    /** Both success and failure finish with the same silent Ready state. */
    fun uploadFinished() {
        if (state.isUploading) {
            set(state.copy(isUploading = false))
        }
    }
}

/**
 * ViewModel owning the Delivery 1A recording/transport lifecycle plus the
 * 0.2.0-dev.3 UI state machine. The HTTP sender is created through
 * [Transport.sender]; in release builds that returns null and sending fails
 * closed. Stop auto-uploads the completed WAV silently; either HTTP result
 * clears it and makes the Watch recordable again.
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

    init {
        // dev.3 startup rule: a valid saved IP/port/token starts directly on
        // Ready; first install or missing/invalid configuration starts on Config.
        uiEvent {
            it.startupWith(settings.isValidDestination() && settings.hasValidToken())
        }
    }

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

    /**
     * Startup rule (dev.3): a valid saved IP/port/token starts directly on
     * Ready; first install or missing/invalid configuration starts on Config.
     * Decided in the constructor init block; this method only verifies the
     * 16 kHz capture capability.
     */
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

    /** Ready is visible during upload, but the state-machine latch makes this a no-op. */
    fun recordButtonPressed() = startRecording()

    private val requestLatch = RecordingRequestLatch()

    /** Starts recording on a dedicated I/O coroutine. */
    fun startRecording(maxDurationSec: Int = 15) {
        if (!machine.canStartRecording()) return
        prepare()
        if (session.state != RecordingSession.State.READY) return
        session.startRecording()
        val generation = requestLatch.begin()
        recordingActive = true
        syncState()
        uiEvent(WatchUiStateMachine::recordingStarted)
        vibrate(RecordingSession.State.RECORDING)
        viewModelScope.launch {
            // I/O produces ONLY an outcome — it never touches the RecordingSession,
            // the UI, vibration, or upload from the IO dispatcher.
            val outcome = withContext(Dispatchers.IO) {
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
                    RecordingOutcome.Completed(count, wav)
                } catch (e: Exception) {
                    RecordingOutcome.Failed(e.message ?: "recording failed")
                }
            }
            // Back on the main coroutine: the atomic settle gate decides whether
            // this generation may write its outcome. A Cancel (or a superseded /
            // already-settled generation) drops it silently: session untouched,
            // no auto-upload, no stop vibration.
            if (requestLatch.settle(generation)) {
                when (outcome) {
                    is RecordingOutcome.Completed -> {
                        session.recordingCompleted(outcome.samples, outcome.wav)
                        syncState()
                        vibrate(RecordingSession.State.RECORDED)
                        // Stop always auto-uploads the completed WAV (no separate Send page).
                        send()
                    }
                    is RecordingOutcome.Failed -> {
                        session.recordingFailed(outcome.reason)
                        syncState()
                    }
                }
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
        // Invalidate the in-flight generation FIRST: the capture coroutine's late
        // completion is then dropped by the latch (final state stays READY with
        // no WAV — never FAILURE, never an upload).
        requestLatch.cancel()
        session.reset()
        prepare()
        syncState()
        uiEvent(WatchUiStateMachine::cancelPressed)
    }

    /** Cross-thread stop signal (UI thread writes, Dispatchers.IO reads). */
    @Volatile
    private var recordingActive = false

    /** Sends one completed WAV. There is no user-visible upload result or retry path. */
    fun send() {
        if (!session.canSend()) return
        session.beginUpload()
        syncState()
        uiEvent(WatchUiStateMachine::uploadStarted)
        val client = clientFactory()
        if (client == null) {
            session.transportFailed("cleartext HTTP sender is unavailable in this build")
            // A failed send means transport is not actually available — reflect it
            // instead of leaving the Ready state showing "available".
            uiEvent { it.healthChecked(false) }
            finishSilentUpload()
            return
        }
        val wav = session.wavBytes
        if (wav == null) {
            finishSilentUpload()
            return
        }
        viewModelScope.launch {
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
                }
                is TransportClient.UploadResult.Failure -> {
                    session.transportFailed(result.reason)
                    // Failed upload means transport is not actually available — reflect it
                    // instead of leaving the Ready state showing "available".
                    uiEvent { it.healthChecked(false) }
                }
            }
            finishSilentUpload()
        }
    }

    /** Clears audio after either HTTP result, then revalidates capture for the next round. */
    private fun finishSilentUpload() {
        resetSessionAfterSilentUpload(session)
        prepare()
        syncState()
        uiEvent(WatchUiStateMachine::uploadFinished)
    }

    fun reset() {
        session.reset()
        syncState()
    }

    private fun vibrate(forState: RecordingSession.State) {
        val pattern = recordingHapticPattern(forState) ?: return
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

}

/** Product-level cleanup: the Watch never retains audio for a retry. */
internal fun resetSessionAfterSilentUpload(session: RecordingSession) {
    session.reset()
    session.toReady()
}

/** Start and stop retain simple haptics; transport outcomes are intentionally silent. */
fun recordingHapticPattern(forState: RecordingSession.State): LongArray? = when (forState) {
    RecordingSession.State.RECORDING -> longArrayOf(0, 60)
    RecordingSession.State.RECORDED -> longArrayOf(0, 40, 60, 40)
    else -> null
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
