package com.sayit.watch.ui

import com.sayit.watch.recording.RecordingSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minimal dev.3 Watch flow: Config, Ready and Recording only. */
class WatchUiStateMachineTest {

    private fun readyMachine(): WatchUiStateMachine = WatchUiStateMachine().also { it.settingsApplied() }

    @Test
    fun `save and apply moves from config to ready`() {
        val machine = WatchUiStateMachine()
        assertEquals(WatchUiState.Screen.CONFIG, machine.state.screen)
        machine.settingsApplied()
        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
        assertNull(machine.state.transportAvailable)
    }

    @Test
    fun `valid saved config starts directly on ready`() {
        val machine = WatchUiStateMachine()
        machine.startupWith(configValid = true)
        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
        assertFalse(machine.state.isUploading)
    }

    @Test
    fun `invalid or missing config starts on config and stays there`() {
        val machine = WatchUiStateMachine()
        machine.startupWith(configValid = false)
        assertEquals(WatchUiState.Screen.CONFIG, machine.state.screen)
        // A later valid save moves to Ready.
        machine.settingsApplied()
        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
    }

    @Test
    fun `startup decision is idempotent and does not move a recording screen`() {
        val machine = WatchUiStateMachine()
        machine.startupWith(configValid = true)
        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
        machine.startupWith(configValid = false)
        // Already on Ready: invalid startup signal must not push back to Config.
        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
    }

    @Test
    fun `stop starts silent upload and immediately returns the visible screen to ready`() {
        val machine = readyMachine()
        machine.recordingStarted()
        machine.uploadStarted()

        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
        assertTrue(machine.state.isUploading)
        assertTrue(machine.state.keepScreenOn)
    }

    @Test
    fun `success and failure share the same silent completion state`() {
        repeat(2) {
            val machine = readyMachine()
            machine.recordingStarted()
            machine.uploadStarted()
            machine.uploadFinished()

            assertEquals(WatchUiState.Screen.READY, machine.state.screen)
            assertFalse(machine.state.isUploading)
            assertFalse(machine.state.keepScreenOn)
            assertTrue(machine.canStartRecording())
        }
    }

    @Test
    fun `record is a no op during silent upload and works when upload finishes`() {
        val machine = readyMachine()
        machine.recordingStarted()
        machine.uploadStarted()
        machine.recordingStarted()

        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
        assertTrue(machine.state.isUploading)

        machine.uploadFinished()
        machine.recordingStarted()
        assertEquals(WatchUiState.Screen.RECORDING, machine.state.screen)
    }

    @Test
    fun `silent upload cleanup removes wav after either transport result`() {
        fun uploadingSession(): RecordingSession = RecordingSession().also {
            it.toReady()
            it.startRecording()
            it.recordingCompleted(4, byteArrayOf(1, 2, 3, 4))
            it.beginUpload()
        }

        val success = uploadingSession()
        success.transportSucceeded()
        resetSessionAfterSilentUpload(success)
        assertEquals(RecordingSession.State.READY, success.state)
        assertNull(success.wavBytes)
        assertFalse(success.canSend())

        val failure = uploadingSession()
        failure.transportFailed("network unreachable")
        resetSessionAfterSilentUpload(failure)
        assertEquals(RecordingSession.State.READY, failure.state)
        assertNull(failure.wavBytes)
        assertFalse(failure.canSend())
    }

    @Test
    fun `only recording start and stop retain haptics`() {
        assertArrayEquals(longArrayOf(0, 60), recordingHapticPattern(RecordingSession.State.RECORDING))
        assertArrayEquals(longArrayOf(0, 40, 60, 40), recordingHapticPattern(RecordingSession.State.RECORDED))
        assertNull(recordingHapticPattern(RecordingSession.State.TRANSPORT_SUCCESS))
        assertNull(recordingHapticPattern(RecordingSession.State.FAILURE))
    }
}
