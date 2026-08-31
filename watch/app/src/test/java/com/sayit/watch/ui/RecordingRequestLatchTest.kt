package com.sayit.watch.ui

import com.sayit.watch.recording.RecordingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Z3 Repair 4 必修 1: the completion gate must support consecutive
 * multi-generations AND be atomically mutually exclusive with Cancel.
 *
 * All tests drive the same outcome-application logic the ViewModel uses —
 * [RecordingOutcomeCoordinator.applyOutcome] (which wraps the single-atomic
 * [RecordingRequestLatch]) — never a hand-written duplicate and never a bare
 * Atomic. The coordinator is the single gate: I/O produces only an outcome,
 * and the session is written only when the gate passes on the main coroutine.
 */
class RecordingRequestLatchTest {

    private fun readySession(): RecordingSession {
        val session = RecordingSession()
        session.toReady()
        return session
    }

    private fun completed(samples: Int = 16_000): RecordingOutcome =
        RecordingOutcome.Completed(samples, ByteArray(samples * 2))

    private fun failed(reason: String = "boom"): RecordingOutcome =
        RecordingOutcome.Failed(reason)

    /** The ViewModel's full per-round completion: upload the retained WAV, then
     *  reset to READY for the next generation. Mirrors the real flow. */
    private fun finishRoundAndReset(session: RecordingSession) {
        session.beginUpload()
        session.transportSucceeded()
        session.reset()
        session.toReady()
    }

    // ── multi-generation: gen1 settles, then gen2 settles too ──

    @Test
    fun `gen1 succeeds then gen2 also succeeds — consecutive generations settle independently`() {
        val coordinator = RecordingOutcomeCoordinator()
        val session = readySession()

        // Generation 1: record + settle + apply.
        val gen1 = coordinator.begin()
        session.startRecording()
        assertTrue(coordinator.applyOutcome(gen1, completed(10_000), session))
        assertEquals(RecordingSession.State.RECORDED, session.state)
        assertEquals(10_000, session.sampleCount)
        assertTrue(session.canSend())

        // User completes round 1 (upload consumed the WAV in the real flow;
        // the session returns to READY for the next round).
        finishRoundAndReset(session)

        // Generation 2: must settle even though gen1 already settled.
        val gen2 = coordinator.begin()
        session.startRecording()
        assertTrue("gen2 must settle after gen1 settled", coordinator.applyOutcome(gen2, completed(20_000), session))
        assertEquals(RecordingSession.State.RECORDED, session.state)
        assertEquals(20_000, session.sampleCount)
        assertTrue(session.canSend())
    }

    @Test
    fun `two consecutive normal rounds each complete and can upload once`() {
        val coordinator = RecordingOutcomeCoordinator()
        val uploadCount = AtomicInteger(0)
        val session = readySession()

        for (round in 1..2) {
            val gen = coordinator.begin()
            session.startRecording()
            val accepted = coordinator.applyOutcome(gen, completed(16_000 * round), session)
            assertTrue("round $round accepted", accepted)
            assertEquals(RecordingSession.State.RECORDED, session.state)
            // The ViewModel uploads exactly when the gate accepted the outcome.
            if (accepted && session.canSend()) {
                uploadCount.incrementAndGet()
                finishRoundAndReset(session)
            }
        }

        assertEquals("each round uploads exactly once", 2, uploadCount.get())
    }

    // ── late generation must not occupy the new one ──

    @Test
    fun `gen1 late completion cannot claim gen2`() {
        val coordinator = RecordingOutcomeCoordinator()
        val session = readySession()

        val gen1 = coordinator.begin()
        session.startRecording()
        val gen2 = coordinator.begin() // supersedes gen1 before it finishes

        // gen2 completes and settles normally.
        assertTrue(coordinator.applyOutcome(gen2, completed(5_000), session))
        assertEquals(RecordingSession.State.RECORDED, session.state)

        // gen1 arrives LATE: it must be dropped, and it must NOT overwrite gen2's
        // outcome with a different one.
        assertFalse("gen1 late completion must be dropped", coordinator.applyOutcome(gen1, completed(99_999), session))
        assertEquals(5_000, session.sampleCount)
        assertEquals(5_000 * 2, session.wavBytes!!.size)
    }

    @Test
    fun `generation zero never settles`() {
        val coordinator = RecordingOutcomeCoordinator()
        assertFalse(coordinator.applyOutcome(0, completed(), readySession()))
    }

    // ── Cancel vs completion: exactly one terminal state ──

    @Test
    fun `cancel then late NORMAL completion leaves READY with no wav and nothing to upload`() {
        val coordinator = RecordingOutcomeCoordinator()
        val session = readySession()
        val gen = coordinator.begin()
        session.startRecording()

        coordinator.cancel()
        session.reset()
        session.toReady()

        // Late normal completion arrives; the gate drops it.
        val accepted = coordinator.applyOutcome(gen, completed(15_000), session)
        assertFalse(accepted)

        assertEquals(RecordingSession.State.READY, session.state)
        assertNull(session.wavBytes)
        assertEquals(0, session.sampleCount)
        assertFalse(session.canSend())
    }

    @Test
    fun `cancel then late EXCEPTION completion leaves READY with no failure`() {
        val coordinator = RecordingOutcomeCoordinator()
        val session = readySession()
        val gen = coordinator.begin()
        session.startRecording()

        coordinator.cancel()
        session.reset()
        session.toReady()

        val accepted = coordinator.applyOutcome(gen, failed("late AudioRecord failure"), session)
        assertFalse(accepted)

        assertEquals(RecordingSession.State.READY, session.state)
        assertNull(session.wavBytes)
        assertNull(session.lastError)
        assertFalse(session.canSend())
    }

    @Test
    fun `a new begin after cancel settles on the new generation only`() {
        val coordinator = RecordingOutcomeCoordinator()
        val session = readySession()
        val first = coordinator.begin()
        session.startRecording()
        coordinator.cancel()
        session.reset()
        session.toReady()
        assertFalse(coordinator.applyOutcome(first, completed(), session))

        val second = coordinator.begin()
        session.startRecording()
        assertTrue(second != first)
        assertTrue(coordinator.applyOutcome(second, completed(), session))
        assertFalse(coordinator.applyOutcome(second, completed(), session))
    }

    // ── real concurrency: cancel racing completion has ONE winner ──

    @Test
    fun `cancel performed on another thread is visible to the settling thread`() {
        val coordinator = RecordingOutcomeCoordinator()
        val gen = coordinator.begin()
        val cancelDone = CountDownLatch(1)
        val canceller = Thread {
            coordinator.cancel()
            cancelDone.countDown()
        }
        canceller.start()
        cancelDone.await()
        assertFalse(coordinator.applyOutcome(gen, completed(), readySession()))
        canceller.join()
    }

    @Test
    fun `concurrent settles from two threads claim exactly once`() {
        val coordinator = RecordingOutcomeCoordinator()
        val gen = coordinator.begin()
        val session = readySession()
        session.startRecording()
        val ready = CountDownLatch(1)
        val releases = CountDownLatch(2)
        val winners = AtomicInteger(0)

        val runner = Thread {
            ready.countDown()
            releases.await()
            if (coordinator.applyOutcome(gen, completed(), session)) winners.incrementAndGet()
        }
        val runner2 = Thread {
            ready.countDown()
            releases.await()
            if (coordinator.applyOutcome(gen, completed(), session)) winners.incrementAndGet()
        }
        runner.start()
        runner2.start()
        ready.await()
        releases.countDown()
        releases.countDown()
        runner.join()
        runner2.join()

        assertEquals("settle must be exactly-once across threads", 1, winners.get())
        assertFalse(coordinator.applyOutcome(gen, completed(), session))
    }

    @Test
    fun `cancel racing concurrent completion has exactly one terminal state`() {
        val coordinator = RecordingOutcomeCoordinator()
        val session = readySession()
        val gen = coordinator.begin()
        session.startRecording()

        val ready = CountDownLatch(2)
        val releases = CountDownLatch(2)
        val settleWinner = AtomicInteger(0) // 1 = completion won, 0 = cancel won

        val completer = Thread {
            ready.countDown()
            releases.await()
            if (coordinator.applyOutcome(gen, completed(8_000), session)) settleWinner.set(1)
        }
        val canceller = Thread {
            ready.countDown()
            releases.await()
            coordinator.cancel()
        }
        completer.start()
        canceller.start()
        ready.await()
        releases.countDown()
        releases.countDown()
        completer.join()
        canceller.join()

        val completionWon = settleWinner.get() == 1
        if (completionWon) {
            // Completion won: RECORDED with the WAV, still uploadable once.
            assertEquals(RecordingSession.State.RECORDED, session.state)
            assertTrue(session.canSend())
            assertEquals(8_000, session.sampleCount)
        } else {
            // Cancel won: the ViewModel resets the session to READY with nothing
            // to upload — the late completion was dropped by the gate.
            session.reset()
            session.toReady()
            assertEquals(RecordingSession.State.READY, session.state)
            assertNull(session.wavBytes)
            assertFalse(session.canSend())
        }
    }

    @Test
    fun `isCurrent is true only for the live generation`() {
        val coordinator = RecordingOutcomeCoordinator()
        val gen = coordinator.begin()
        assertTrue(coordinator.isCurrent(gen))
        coordinator.cancel()
        assertFalse(coordinator.isCurrent(gen))
        val gen2 = coordinator.begin()
        assertTrue(coordinator.isCurrent(gen2))
        assertFalse(coordinator.isCurrent(gen))
    }
}
