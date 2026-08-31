package com.sayit.watch.ui

import java.util.concurrent.atomic.AtomicReference

/**
 * Cancel latch and multi-generation completion coordinator (Z3 Repair 4 必修 1).
 *
 * One atomic state replaces the previous three independent Atomics
 * (generation / cancelled / settledGeneration). `begin`, `cancel` and `settle`
 * all CAS the SAME state, so Cancel and completion are a single atomic
 * transition — there is no interleaving where `settle` reads `cancelled=false`,
 * Cancel writes `true`, and `settle` still succeeds.
 *
 * State: `Idle -> Active(gen) -> Settled(gen) | Cancelled(gen)`.
 *
 * - Every `begin()` creates a fresh generation that can settle independently.
 * - A late completion of an older generation can never claim a newer one:
 *   `settle` succeeds only while the state is `Active` with the SAME
 *   generation id.
 * - `settle` returns true exactly once per generation (exactly-once).
 *
 * The ViewModel applies recording outcomes ONLY through [applyOutcome] on the
 * main coroutine — never inside `Dispatchers.IO` — so Cancel vs completion
 * product-state writes cannot race across threads.
 */
class RecordingRequestLatch {

    private sealed interface Phase {
        data object Idle : Phase
        data class Active(val generation: Int) : Phase
        data class Cancelled(val generation: Int) : Phase
        data class Settled(val generation: Int) : Phase
    }

    private val phase = AtomicReference<Phase>(Phase.Idle)
    private val counter = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Starts a new recording generation and returns its ID. Safe from any
     * state: a new generation supersedes any previous one, and the previous
     * generation's late completion can no longer settle.
     */
    fun begin(): Int {
        while (true) {
            val current = phase.get()
            val gen = counter.incrementAndGet()
            if (phase.compareAndSet(current, Phase.Active(gen))) return gen
        }
    }

    /**
     * Cancels the CURRENT active generation. No-op when the latch is idle,
     * already cancelled, or already settled — only one terminal state exists.
     */
    fun cancel() {
        while (true) {
            val current = phase.get()
            if (current !is Phase.Active) return // already idle/cancelled/settled
            if (phase.compareAndSet(current, Phase.Cancelled(current.generation))) return
        }
    }

    /** True only while `generationId` is the live, non-cancelled generation. */
    fun isCurrent(generationId: Int): Boolean =
        phase.get() is Phase.Active && (phase.get() as Phase.Active).generation == generationId

    /**
     * The completion decision, atomically: claims the single right to report
     * `generationId`'s outcome. True exactly when the generation is the live,
     * non-cancelled one AND nothing has settled it yet; false for a cancelled,
     * superseded, or already-settled generation (exactly-once per generation).
     */
    fun settle(generationId: Int): Boolean {
        while (true) {
            val current = phase.get()
            if (current !is Phase.Active || current.generation != generationId) return false
            if (phase.compareAndSet(current, Phase.Settled(generationId))) return true
        }
    }
}

/** Result of a recording I/O run. Produced on Dispatchers.IO, applied on the
 *  main coroutine only after the settle gate. */
sealed class RecordingOutcome {
    data class Completed(val samples: Int, val wav: ByteArray) : RecordingOutcome()
    data class Failed(val reason: String) : RecordingOutcome()
}

/**
 * The single outcome-application coordinator shared by the ViewModel and the
 * coordinator tests (Z3 Repair 4 必修 1.5). The I/O side produces only a
 * [RecordingOutcome]; this class decides — atomically, once per generation —
 * whether that outcome may be written to the session.
 */
class RecordingOutcomeCoordinator(
    private val latch: RecordingRequestLatch = RecordingRequestLatch(),
) {
    fun begin(): Int = latch.begin()

    fun cancel() = latch.cancel()

    fun isCurrent(generationId: Int): Boolean = latch.isCurrent(generationId)

    /**
     * Applies [outcome] to [session] iff the settle gate passes for
     * [generationId]. Returns true when the outcome was accepted and written.
     * Never touches the session for a cancelled, superseded, or already-settled
     * generation. Call on the main coroutine only.
     */
    fun applyOutcome(generationId: Int, outcome: RecordingOutcome, session: com.sayit.watch.recording.RecordingSession): Boolean {
        if (!latch.settle(generationId)) return false
        when (outcome) {
            is RecordingOutcome.Completed -> session.recordingCompleted(outcome.samples, outcome.wav)
            is RecordingOutcome.Failed -> session.recordingFailed(outcome.reason)
        }
        return true
    }
}
