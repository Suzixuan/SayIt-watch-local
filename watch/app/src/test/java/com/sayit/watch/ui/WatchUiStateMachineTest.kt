package com.sayit.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the 0.2.0-dev.2 UI state machine
 * (docs/WATCH-UI-Z-HANDOFF.md): Ready -> Recording -> Stop -> Uploading ->
 * Success/Failure; Retry, Later, Pending upload, and the explicit discard rule.
 */
class WatchUiStateMachineTest {

    private fun machine(): WatchUiStateMachine {
        val m = WatchUiStateMachine()
        m.settingsApplied() // CONFIG -> READY
        return m
    }

    @Test
    fun `save and apply moves from config to ready`() {
        val m = WatchUiStateMachine()
        assertEquals(WatchUiState.Screen.CONFIG, m.state.screen)
        m.settingsApplied()
        assertEquals(WatchUiState.Screen.READY, m.state.screen)
        assertNull(m.state.transportAvailable)
    }

    @Test
    fun `ready to recording to stop auto-uploads then success returns to ready`() {
        val m = machine()
        m.recordingStarted()
        assertEquals(WatchUiState.Screen.RECORDING, m.state.screen)
        assertTrue(m.state.keepScreenOn)

        m.uploadStarted()
        assertEquals(WatchUiState.Overlay.UPLOADING, m.state.overlay)
        assertEquals(WatchUiState.Screen.READY, m.state.screen)
        assertTrue(m.state.keepScreenOn)

        m.uploadSucceeded()
        assertEquals(WatchUiState.Overlay.UPLOADED, m.state.overlay)
        assertFalse(m.state.pendingUpload)

        m.uploadedDismissed()
        assertEquals(WatchUiState.Overlay.NONE, m.state.overlay)
        assertFalse(m.state.keepScreenOn)
    }

    @Test
    fun `upload failure retains the wav as pending upload with a reason`() {
        val m = machine()
        m.recordingStarted()
        m.uploadStarted()
        m.uploadFailed("HTTP 409 (PC busy)")

        assertEquals(WatchUiState.Overlay.UPLOAD_FAILED, m.state.overlay)
        assertTrue(m.state.pendingUpload)
        assertEquals("HTTP 409 (PC busy)", m.state.failureReason)
    }

    @Test
    fun `retry re-uploads the same wav without re-recording or discarding`() {
        val m = machine()
        m.recordingStarted()
        m.uploadStarted()
        m.uploadFailed("network unreachable")

        m.retryPressed()
        assertEquals(WatchUiState.Overlay.UPLOADING, m.state.overlay)
        assertTrue("the retained WAV must stay pending during retry", m.state.pendingUpload)

        m.uploadFailed("network unreachable again")
        assertEquals(WatchUiState.Overlay.UPLOAD_FAILED, m.state.overlay)
        assertTrue(m.state.pendingUpload)
    }

    @Test
    fun `later returns to ready with the obvious pending upload badge`() {
        val m = machine()
        m.recordingStarted()
        m.uploadStarted()
        m.uploadFailed("network unreachable")

        m.laterPressed()
        assertEquals(WatchUiState.Overlay.NONE, m.state.overlay)
        assertEquals(WatchUiState.Screen.READY, m.state.screen)
        assertTrue(m.state.showsPendingUploadBadge)
        assertFalse(m.state.keepScreenOn)
    }

    @Test
    fun `a new recording with a pending wav requires the explicit discard prompt`() {
        val m = machine()
        m.recordingStarted()
        m.uploadStarted()
        m.uploadFailed("network unreachable")
        m.laterPressed()

        // Record must NOT silently overwrite the retained WAV.
        assertTrue(m.recordNeedsDiscardConfirmation())
        m.showDiscardPrompt()
        assertTrue(m.state.discardPrompt)

        // Keep it: prompt closes, WAV stays pending, still no recording.
        m.dismissDiscardPrompt()
        assertFalse(m.state.discardPrompt)
        assertTrue(m.state.pendingUpload)
        m.recordingStarted()
        assertEquals("recording blocked while pending", WatchUiState.Screen.READY, m.state.screen)

        // Discard & record: the explicit decision clears the latch and records.
        m.showDiscardPrompt()
        m.pendingDiscarded()
        assertFalse(m.state.pendingUpload)
        m.recordingStarted()
        assertEquals(WatchUiState.Screen.RECORDING, m.state.screen)
    }

    @Test
    fun `cancel on the recording screen discards without upload`() {
        val m = machine()
        m.recordingStarted()
        m.cancelPressed()

        assertEquals(WatchUiState.Screen.READY, m.state.screen)
        assertEquals(WatchUiState.Overlay.NONE, m.state.overlay)
        assertFalse(m.state.pendingUpload)
    }

    @Test
    fun `failure then later then retry from pending-ready reaches uploading with the same bytes`() {
        // Z3 Repair 2 必修 2: Later must not strand the retained WAV — the
        // Pending-ready state exposes a reachable Retry. The session is driven in
        // lockstep (exactly as the ViewModel does) so the uploaded bytes can be
        // asserted against the original WAV.
        val m = machine()
        val session = com.sayit.watch.recording.RecordingSession()
        session.toReady()

        m.recordingStarted()
        session.startRecording()
        val originalWav = byteArrayOf(7, 5, 3, 1, 9, 8, 2, 6)
        session.recordingCompleted(samples = 4, wav = originalWav)

        m.uploadStarted()
        session.beginUpload()
        session.transportFailed("HTTP 409 (PC busy)")
        m.uploadFailed("HTTP 409 (PC busy)")
        assertEquals(WatchUiState.Overlay.UPLOAD_FAILED, m.state.overlay)

        m.laterPressed()
        assertEquals(WatchUiState.Overlay.NONE, m.state.overlay)
        assertTrue(m.state.showsPendingUploadBadge)

        // Retry from Pending-ready: reachable, and the machine accepts it.
        m.retryPressed()
        assertEquals(WatchUiState.Overlay.UPLOADING, m.state.overlay)
        // The retried upload consumes the SAME retained bytes.
        session.beginUpload()
        org.junit.Assert.assertArrayEquals(originalWav, session.wavBytes)
        session.transportSucceeded()
        m.uploadSucceeded()
        assertEquals(WatchUiState.Overlay.UPLOADED, m.state.overlay)
        assertFalse(m.state.pendingUpload)
    }

    @Test
    fun `retry is not offered without a pending upload`() {
        val m = machine()
        // No failure, no pending WAV: retry has nothing to send and is a no-op.
        m.retryPressed()
        assertEquals(WatchUiState.Overlay.NONE, m.state.overlay)
        assertFalse(m.state.pendingUpload)
    }

    @Test
    fun `health check only records transport availability`() {
        val m = machine()
        m.healthChecked(true)
        assertEquals(true, m.state.transportAvailable)
        m.healthChecked(false)
        assertEquals(false, m.state.transportAvailable)
        // Transport availability says nothing about Provider/ASR readiness — the
        // wording stays transport-only.
        assertEquals(false, m.state.transportAvailable)
    }

    @Test
    fun `a success clears the pending latch so no discard is required afterwards`() {
        val m = machine()
        m.recordingStarted()
        m.uploadStarted()
        m.uploadSucceeded()
        m.uploadedDismissed()

        assertFalse(m.recordNeedsDiscardConfirmation())
        m.recordingStarted()
        assertEquals(WatchUiState.Screen.RECORDING, m.state.screen)
    }
}
