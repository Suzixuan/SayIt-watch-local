package com.sayit.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Z3 Repair 2 必修 1: Cancel must invalidate the in-flight recording generation
 * so a capture that completes after the cancel is dropped — final state stays
 * READY with no WAV, no upload, no FAILURE.
 */
class RecordingRequestLatchTest {

    @Test
    fun `a live generation is current until cancelled`() {
        val latch = RecordingRequestLatch()
        val gen = latch.begin()
        assertTrue(latch.isCurrent(gen))
        latch.cancel()
        assertFalse(latch.isCurrent(gen))
    }

    @Test
    fun `cancel followed by a late capture completion drops the recording`() {
        val latch = RecordingRequestLatch()
        val session = com.sayit.watch.recording.RecordingSession()
        session.toReady()
        val gen = latch.begin()
        session.startRecording()

        // The user presses Cancel.
        latch.cancel()
        session.reset()
        session.toReady()

        // The capture coroutine completes LATE; the latch gates the call away.
        val lateSamples = 15_000
        val lateWav = ByteArray(lateSamples * 2)
        var handled = false
        if (latch.isCurrent(gen)) {
            session.recordingCompleted(lateSamples, lateWav)
            handled = true
        }
        assertFalse(handled)

        // Final state: READY, no WAV, no failure, nothing to upload.
        assertEquals(com.sayit.watch.recording.RecordingSession.State.READY, session.state)
        assertEquals(null, session.wavBytes)
        assertEquals(0, session.sampleCount)
        assertFalse(session.canSend())
        assertEquals(null, session.lastError)
    }

    @Test
    fun `a new begin after cancel is current again`() {
        val latch = RecordingRequestLatch()
        val first = latch.begin()
        latch.cancel()
        assertFalse(latch.isCurrent(first))

        val second = latch.begin()
        assertTrue(second != first)
        assertTrue(latch.isCurrent(second))
    }

    @Test
    fun `generation zero is never current`() {
        val latch = RecordingRequestLatch()
        assertFalse(latch.isCurrent(0))
    }
}
