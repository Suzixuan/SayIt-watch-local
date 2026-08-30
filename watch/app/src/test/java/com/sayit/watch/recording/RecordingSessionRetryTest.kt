package com.sayit.watch.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delivery 1B UI-handoff rule: after an upload failure the retained WAV is
 * retried byte-for-byte — Retry never re-records.
 */
class RecordingSessionRetryTest {

    @Test
    fun `failed upload retains the exact wav bytes for retry`() {
        val session = RecordingSession()
        session.toReady()
        session.startRecording()

        val wav = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        session.recordingCompleted(samples = 4, wav = wav)
        session.beginUpload()
        session.transportFailed("HTTP 409 (PC busy)")

        assertEquals(RecordingSession.State.FAILURE, session.state)
        assertTrue(session.lastFailureIsTransport)
        assertTrue(session.canSend())
        // The exact same bytes are retained for the retry.
        assertArrayEquals(wav, session.wavBytes)
    }

    @Test
    fun `retry does not clear the retained bytes`() {
        val session = RecordingSession()
        session.toReady()
        session.startRecording()
        val wav = ByteArray(64) { (it * 3).toByte() }
        session.recordingCompleted(samples = 32, wav = wav)

        session.beginUpload()
        session.transportFailed("timeout")
        session.beginUpload() // the Retry press: same session bytes go out again
        session.transportSucceeded()

        assertEquals(RecordingSession.State.TRANSPORT_SUCCESS, session.state)
        assertArrayEquals(wav, session.wavBytes)
    }

    @Test
    fun `starting a new recording replaces the retained wav only after an explicit decision`() {
        val session = RecordingSession()
        session.toReady()
        session.startRecording()
        val wav = byteArrayOf(9, 9, 9)
        session.recordingCompleted(samples = 3, wav = wav)
        session.beginUpload()
        session.transportFailed("HTTP 409 (PC busy)")

        // The retained bytes survive every non-recording transition (the UI
        // state machine gates the replacement behind the explicit discard).
        assertEquals(RecordingSession.State.FAILURE, session.state)
        assertArrayEquals(wav, session.wavBytes)

        session.startRecording() // the explicit "Discard & record" decision
        assertEquals(RecordingSession.State.RECORDING, session.state)
        assertEquals(null, session.wavBytes)
    }
}
