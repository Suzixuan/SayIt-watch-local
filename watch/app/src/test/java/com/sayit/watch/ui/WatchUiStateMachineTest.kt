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
