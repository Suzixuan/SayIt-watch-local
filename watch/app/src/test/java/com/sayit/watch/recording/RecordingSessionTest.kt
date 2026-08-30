package com.sayit.watch.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionTest {

    private fun wavOf(n: Int) = ByteArray(n) { 1 }

    @Test
    fun `initial state is idle with no wav`() {
        val s = RecordingSession()
        assertEquals(RecordingSession.State.IDLE, s.state)
        assertFalse(s.canSend())
        assertNull(s.wavBytes)
    }

    @Test
    fun `happy path transitions ready - recording - recorded - uploading - success`() {
        val s = RecordingSession()
        s.toReady()
        assertSame(RecordingSession.State.READY, s.state)

        s.startRecording()
        assertSame(RecordingSession.State.RECORDING, s.state)
        assertNull(s.wavBytes)

        s.recordingCompleted(16000, wavOf(32042))
        assertSame(RecordingSession.State.RECORDED, s.state)
        assertEquals(16000, s.sampleCount)
        assertTrue(s.canSend())

        s.beginUpload()
        assertSame(RecordingSession.State.UPLOADING, s.state)

        s.transportSucceeded()
        assertSame(RecordingSession.State.TRANSPORT_SUCCESS, s.state)
        assertFalse(s.canSend())
    }

    @Test
    fun `upload failure retains wav for retry without re-recording`() {
        val s = RecordingSession()
        s.toReady()
        s.startRecording()
        s.recordingCompleted(8000, wavOf(16042))
        s.beginUpload()
        s.transportFailed("HTTP 500")
        assertSame(RecordingSession.State.FAILURE, s.state)
        assertTrue(s.lastFailureIsTransport)
        assertEquals("HTTP 500", s.lastError)
        // WAV retained -> Send retryable without re-recording.
        assertTrue(s.canSend())
        assertEquals(16042, s.wavBytes!!.size)

        // Retry directly from FAILURE.
        s.beginUpload()
        assertSame(RecordingSession.State.UPLOADING, s.state)
        s.transportSucceeded()
        assertSame(RecordingSession.State.TRANSPORT_SUCCESS, s.state)
    }

    @Test
    fun `new recording replaces previous wav`() {
        val s = RecordingSession()
        s.toReady()
        s.startRecording()
        s.recordingCompleted(100, wavOf(244))
        assertEquals(100, s.sampleCount)

        s.startRecording()
        assertNull(s.wavBytes)
        assertEquals(0, s.sampleCount)
    }

    @Test
    fun `recording failure clears wav and marks non-transport failure`() {
        val s = RecordingSession()
        s.toReady()
        s.startRecording()
        s.recordingFailed("16 kHz unsupported: minBufferSize=-1")
        assertSame(RecordingSession.State.FAILURE, s.state)
        assertFalse(s.lastFailureIsTransport)
        assertFalse(s.canSend())
        assertNull(s.wavBytes)
    }

    @Test
    fun `start from recorded or failure is allowed for retry`() {
        val s = RecordingSession()
        s.toReady()
        s.startRecording()
        s.recordingCompleted(10, wavOf(64))
        s.startRecording() // from RECORDED
        assertSame(RecordingSession.State.RECORDING, s.state)

        s.recordingCompleted(5, wavOf(54))
        s.beginUpload()
        s.transportFailed("x")
        s.startRecording() // from FAILURE
        assertSame(RecordingSession.State.RECORDING, s.state)
    }

    @Test
    fun `invalid transitions are rejected`() {
        val s = RecordingSession()
        // upload before recording
        var threw = false
        try { s.beginUpload() } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)

        s.toReady()
        s.startRecording()
        threw = false
        try { s.transportSucceeded() } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `reset returns to idle`() {
        val s = RecordingSession()
        s.toReady()
        s.startRecording()
        s.recordingCompleted(9, wavOf(62))
        s.reset()
        assertSame(RecordingSession.State.IDLE, s.state)
        assertNull(s.wavBytes)
        assertEquals(0, s.sampleCount)
        assertFalse(s.canSend())
    }
}
