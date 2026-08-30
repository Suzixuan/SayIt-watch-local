package com.sayit.watch.ui

import com.sayit.watch.recording.RecordingSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Z3 Repair 3 必修 1: Cancel must be fail-closed for BOTH late outcomes —
 * normal return AND exception — and the completion decision is the latch's
 * atomic `settle` coordinator (the very method the ViewModel's success and
 * catch branches call), never a hand-written duplicate. Includes real
 * cross-thread visibility tests.
 */
class RecordingRequestLatchTest {

    private fun readySession(): RecordingSession {
        val session = RecordingSession()
        session.toReady()
        return session
    }

    @Test
    fun `a live generation settles exactly once`() {
        val latch = RecordingRequestLatch()
        val gen = latch.begin()
        assertTrue(latch.settle(gen))
        assertFalse("second completion attempt is dropped", latch.settle(gen))
    }

    @Test
    fun `cancel then late NORMAL completion leaves READY with no wav and nothing to upload`() {
        val latch = RecordingRequestLatch()
        val session = readySession()
        val gen = latch.begin()
        session.startRecording()

        // User presses Cancel.
        latch.cancel()
        session.reset()
        session.toReady()

        // The capture coroutine returns LATE and normally.
        val lateSamples = 15_000
        if (latch.settle(gen)) {
            session.recordingCompleted(lateSamples, ByteArray(lateSamples * 2))
        }

        assertEquals(RecordingSession.State.READY, session.state)
        assertNull(session.wavBytes)
        assertEquals(0, session.sampleCount)
        assertFalse(session.canSend())
    }

    @Test
    fun `cancel then late EXCEPTION completion leaves READY with no failure`() {
        val latch = RecordingRequestLatch()
        val session = readySession()
        val gen = latch.begin()
        session.startRecording()

        // User presses Cancel; the session recovers to READY.
        latch.cancel()
        session.reset()
        session.toReady()

        // The capture coroutine throws LATE; the settle gate drops the failure.
        var handled = false
        try {
            throw java.lang.IllegalStateException("late AudioRecord failure")
        } catch (e: Exception) {
            if (latch.settle(gen)) {
                session.recordingFailed(e.message ?: "recording failed")
                handled = true
            }
        }
        assertFalse(handled)

        assertEquals(RecordingSession.State.READY, session.state)
        assertNull(session.wavBytes)
        assertNull(session.lastError)
        assertFalse(session.canSend())
    }

    @Test
    fun `a new begin after cancel settles on the new generation only`() {
        val latch = RecordingRequestLatch()
        val first = latch.begin()
        latch.cancel()
        assertFalse(latch.settle(first))

        val second = latch.begin()
        assertTrue(second != first)
        assertTrue(latch.settle(second))
        assertFalse(latch.settle(second))
    }

    @Test
    fun `generation zero never settles`() {
        val latch = RecordingRequestLatch()
        assertFalse(latch.settle(0))
    }

    @Test
    fun `cancel performed on another thread is visible to the settling thread`() {
        val latch = RecordingRequestLatch()
        val gen = latch.begin()
        val cancelDone = CountDownLatch(1)
        val canceller = Thread {
            latch.cancel()
            cancelDone.countDown()
        }
        canceller.start()
        cancelDone.await() // happens-before: the cancel is visible after this join
        assertFalse(latch.settle(gen))
        canceller.join()
    }

    @Test
    fun `concurrent settles from two threads claim exactly once`() {
        val latch = RecordingRequestLatch()
        val gen = latch.begin()
        val ready = CountDownLatch(1)
        val releases = CountDownLatch(2)
        val winners = AtomicInteger(0)

        val runner = Thread {
            ready.countDown()
            releases.await()
            if (latch.settle(gen)) winners.incrementAndGet()
        }
        val runner2 = Thread {
            ready.countDown()
            releases.await()
            if (latch.settle(gen)) winners.incrementAndGet()
        }
        runner.start()
        runner2.start()
        ready.await()
        releases.countDown()
        releases.countDown()
        runner.join()
        runner2.join()

        assertEquals("settle must be exactly-once across threads", 1, winners.get())
        assertFalse(latch.settle(gen))
    }
}
