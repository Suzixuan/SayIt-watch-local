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
 * ViewModel owning the Delivery 1A recording/transport lifecycle.
 * The HTTP sender is created through [Transport.sender]; in release builds
 * that returns null and sending fails closed.
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

    private val _canSend = MutableStateFlow(false)
    val canSend: StateFlow<Boolean> = _canSend.asStateFlow()

    /** Duration in ms derived from the captured sample count. */
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

    /** Starts recording on a dedicated I/O coroutine. */
    fun startRecording(maxDurationSec: Int = 15) {
        prepare()
        if (session.state != RecordingSession.State.READY) return
        session.startRecording()
        recordingActive = true
        syncState()
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
            if (samples >= 0) vibrate(RecordingSession.State.RECORDED)
        }
    }

    /** Stops the running recording (idempotent). */
    fun stopRecording() {
        recordingActive = false
    }

    private var recordingActive = false

    /** Sends the retained WAV; retryable without re-recording after failure. */
    fun send() {
        if (!session.canSend()) return
        val client = clientFactory()
        if (client == null) {
            session.transportFailed("cleartext HTTP sender is unavailable in this build")
            syncState()
            vibrate(RecordingSession.State.FAILURE)
            return
        }
        session.beginUpload()
        syncState()
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
                    vibrate(RecordingSession.State.TRANSPORT_SUCCESS)
                }
                is TransportClient.UploadResult.Failure -> {
                    session.transportFailed(result.reason)
                    syncState()
                    vibrate(RecordingSession.State.FAILURE)
                }
            }
        }
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
