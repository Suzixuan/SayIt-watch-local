package com.sayit.watch.recording

/**
 * Delivery 1A recording state machine.
 *
 * States: Idle -> Ready (16 kHz verified) -> Recording -> Recorded ->
 * Uploading -> TransportSuccess | Failure.
 *
 * The completed WAV is retained after an upload failure so Send can be
 * retried without re-recording.
 */
class RecordingSession {

    enum class State { IDLE, READY, RECORDING, RECORDED, UPLOADING, TRANSPORT_SUCCESS, FAILURE }

    var state: State = State.IDLE
        private set

    /** Captured sample count of the current WAV, or 0. */
    var sampleCount: Int = 0
        private set

    /** Full WAV bytes retained for retry. Null until recording completes. */
    var wavBytes: ByteArray? = null
        private set

    /** True when the last failure was a transport failure (retryable without re-record). */
    var lastFailureIsTransport: Boolean = false
        private set

    var lastError: String? = null
        private set

    fun toReady() {
        state = State.READY
    }

    fun startRecording() {
        require(state == State.READY || state == State.RECORDED || state == State.FAILURE) {
            "cannot start recording from $state"
        }
        // A new recording replaces any previous WAV.
        wavBytes = null
        sampleCount = 0
        lastError = null
        lastFailureIsTransport = false
        state = State.RECORDING
    }

    /** Called with the sample count captured by the I/O thread. */
    fun recordingCompleted(samples: Int, wav: ByteArray) {
        require(state == State.RECORDING) { "recordingCompleted from $state" }
        sampleCount = samples
        wavBytes = wav
        state = State.RECORDED
    }

    fun beginUpload() {
        require(state == State.RECORDED || state == State.FAILURE) {
            "cannot upload from $state"
        }
        state = State.UPLOADING
    }

    fun transportSucceeded() {
        require(state == State.UPLOADING) { "transportSucceeded from $state" }
        state = State.TRANSPORT_SUCCESS
    }

    fun transportFailed(error: String) {
        require(state == State.UPLOADING) { "transportFailed from $state" }
        lastError = error
        lastFailureIsTransport = true
        // Retain wavBytes so Send can be retried without re-recording.
        state = State.FAILURE
    }

    fun recordingFailed(error: String) {
        lastError = error
        lastFailureIsTransport = false
        wavBytes = null
        state = State.FAILURE
    }

    /** True when the recorded WAV is available for (re)upload. */
    fun canSend(): Boolean =
        (state == State.RECORDED || state == State.FAILURE) && wavBytes != null

    fun reset() {
        state = State.IDLE
        wavBytes = null
        sampleCount = 0
        lastError = null
        lastFailureIsTransport = false
    }
}
