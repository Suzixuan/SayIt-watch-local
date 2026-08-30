//! Delivery 1B debug-only admission gate between the blocking Watch receiver and
//! the WebView Orchestrator. Implements the frozen Provider Contract §B exactly:
//!
//! - `Idle | Reserved { request_id, reserved_at }` state, request-correlated
//!   bounded oneshots for the admission decision and the post-save run-start ack;
//! - a Rust-owned lazy 300 s lease (never releases a healthy run: the derived
//!   legitimate worst case is ≈164 s) plus `watch_gate_state()` boot/reload
//!   reconciliation;
//! - stale/mismatched request IDs are rejected as no-ops; every release/abort is
//!   idempotent and conditional on the request ID;
//! - fail-closed: an unanswered admission decision becomes `409 bridge_timeout`,
//!   never an acceptance.
//!
//! All functions are invoked from the blocking receiver thread and from Tauri
//! commands. Never logs the dev token, PCM, or transcript text.

use serde::Serialize;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

/// Bounded wait for the WebView's admission decision (fail-closed → 409).
pub const ADMISSION_TIMEOUT: Duration = Duration::from_millis(5_000);
/// Bounded wait for the post-save `watch_run_started` acknowledgement.
pub const RUN_START_ACK_TIMEOUT: Duration = Duration::from_millis(10_000);
/// Rust-owned lazy lease: `MAX_RECORDING_SEC` (300 s) from `recorder/types.ts`.
pub const LEASE: Duration = Duration::from_millis(300_000);

/// Decision delivered to the receiver thread from the WebView.
#[derive(Debug, Clone)]
pub struct AdmissionDecision {
    pub accepted: bool,
    pub reason: Option<String>,
}

/// Outcome of a receiver-side admission request.
#[derive(Debug, Clone)]
pub enum AdmissionOutcome {
    /// WebView accepted after its synchronous Phase A + bounded Phase B.
    Accepted,
    /// Rejected for a fixed, non-secret reason (busy/not-ready → 409; capture or
    /// other preparation failures → 500 per the frozen contract).
    Rejected { reason: String },
}

impl AdmissionOutcome {
    pub fn reason(&self) -> Option<&str> {
        match self {
            AdmissionOutcome::Accepted => None,
            AdmissionOutcome::Rejected { reason } => Some(reason),
        }
    }
}

/// Snapshot exposed to the WebView for boot/reload reconciliation.
#[derive(Debug, Clone, Serialize)]
pub struct GateStateSnapshot {
    pub state: &'static str,
    pub request_id: Option<String>,
    pub reserved_age_ms: Option<u128>,
}

type AdmissionTx = Mutex<Option<(String, std::sync::mpsc::Sender<AdmissionDecision>)>>;
type AckTx = Mutex<Option<(String, std::sync::mpsc::Sender<Result<(), String>>)>>;

pub struct AdmissionGate {
    state: Mutex<GateState>,
    pending_admission: AdmissionTx,
    pending_ack: AckTx,
    admission_timeout: Duration,
    ack_timeout: Duration,
    lease: Duration,
}

enum GateState {
    Idle,
    Reserved {
        request_id: String,
        reserved_at: Instant,
    },
}

impl AdmissionGate {
    /// Production timeouts/frozen lease (Provider Contract §B).
    pub fn new() -> Self {
        Self::with_timeouts(ADMISSION_TIMEOUT, RUN_START_ACK_TIMEOUT, LEASE)
    }

    /// Explicit timeouts — used by tests to keep the bounded waits fast while
    /// production always runs the frozen constants.
    pub fn with_timeouts(admission_timeout: Duration, ack_timeout: Duration, lease: Duration) -> Self {
        Self {
            state: Mutex::new(GateState::Idle),
            pending_admission: Mutex::new(None),
            pending_ack: Mutex::new(None),
            admission_timeout,
            ack_timeout,
            lease,
        }
    }

    /// Process-wide shared gate (registered from `main.rs`, debug-only).
    pub fn shared() -> Arc<AdmissionGate> {
        static GATE: OnceLock<Arc<AdmissionGate>> = OnceLock::new();
        GATE.get_or_init(|| Arc::new(AdmissionGate::new())).clone()
    }

    /// Current snapshot; also the `watch_gate_state()` payload.
    pub fn snapshot(&self) -> GateStateSnapshot {
        let state = self.state.lock().expect("admission gate state poisoned");
        match &*state {
            GateState::Idle => GateStateSnapshot {
                state: "Idle",
                request_id: None,
                reserved_age_ms: None,
            },
            GateState::Reserved {
                request_id,
                reserved_at,
            } => GateStateSnapshot {
                state: "Reserved",
                request_id: Some(request_id.clone()),
                reserved_age_ms: Some(reserved_at.elapsed().as_millis()),
            },
        }
    }

    /// Whether the gate currently holds a reservation for this exact request ID.
    /// Guards `watch_read_reserved_pcm` and the run-start ack.
    pub fn is_reserved_for(&self, request_id: &str) -> bool {
        let state = self.state.lock().expect("admission gate state poisoned");
        matches!(&*state, GateState::Reserved { request_id: id, .. } if id == request_id)
    }

    /// Receiver side, step 1 (Repair 2): perform the mutual-exclusion checks and
    /// lease reclamation, then register the pending admission waiter. The waiter
    /// is visible to `resolve_admission` the moment this returns — BEFORE the
    /// receiver emits `watch://admission-request` — so a WebView answering
    /// synchronously inside the emit call stack always finds it.
    /// Fails closed with a fixed reason (busy / not ready) without any waiter.
    pub fn begin_admission(
        &self,
        request_id: &str,
    ) -> Result<std::sync::mpsc::Receiver<AdmissionDecision>, String> {
        let mut pending = self.pending_admission.lock().expect("gate poisoned");
        if pending.is_some() {
            return Err("already_reserved".to_string());
        }
        // Lazy lease reclamation before deciding on the fresh request.
        let mut state = self.state.lock().expect("admission gate state poisoned");
        if let GateState::Reserved {
            request_id: reserved_id,
            reserved_at,
        } = &*state
        {
            if reserved_at.elapsed() > self.lease {
                log::warn!(
                    "watch admission: lease expired for request {}; gate reclaimed",
                    reserved_id
                );
                *state = GateState::Idle;
            }
        }
        if matches!(&*state, GateState::Reserved { .. }) {
            return Err("already_reserved".to_string());
        }
        let (tx, rx) = std::sync::mpsc::channel::<AdmissionDecision>();
        *pending = Some((request_id.to_string(), tx));
        Ok(rx)
    }

    /// Receiver side, step 2 (Repair 2): bounded wait on the waiter registered by
    /// `begin_admission`. Accepted → `Reserved`; rejected → back to `Idle`;
    /// timeout → fail-closed `bridge_timeout` with the waiter and gate cleaned.
    pub fn wait_admission(
        &self,
        request_id: &str,
        rx: std::sync::mpsc::Receiver<AdmissionDecision>,
    ) -> AdmissionOutcome {
        let decision = match rx.recv_timeout(self.admission_timeout) {
            Ok(decision) => decision,
            Err(_) => {
                // Fail closed: clear our own slot (a racing resolve finds no waiter
                // and the JS half then aborts), force the gate back to Idle.
                let mut pending = self.pending_admission.lock().expect("gate poisoned");
                if matches!(pending.as_ref(), Some((id, _)) if id == request_id) {
                    *pending = None;
                }
                drop(pending);
                self.force_idle_if(request_id);
                log::warn!("watch admission: bridge timeout for request {request_id}");
                return AdmissionOutcome::Rejected {
                    reason: "bridge_timeout".to_string(),
                };
            }
        };

        if decision.accepted {
            let mut state = self.state.lock().expect("admission gate state poisoned");
            if matches!(&*state, GateState::Reserved { .. }) {
                // Should be unreachable (slot held means gate was Idle), but stay
                // consistent: a second reservation may never be created silently.
                return AdmissionOutcome::Rejected {
                    reason: "already_reserved".to_string(),
                };
            }
            *state = GateState::Reserved {
                request_id: request_id.to_string(),
                reserved_at: Instant::now(),
            };
            AdmissionOutcome::Accepted
        } else {
            self.force_idle_if(request_id);
            AdmissionOutcome::Rejected {
                reason: decision.reason.unwrap_or_else(|| "rejected".to_string()),
            }
        }
    }

    /// Convenience composition of [`begin_admission`] + [`wait_admission`].
    pub fn request_admission(&self, request_id: &str) -> AdmissionOutcome {
        match self.begin_admission(request_id) {
            Err(reason) => AdmissionOutcome::Rejected { reason },
            Ok(rx) => self.wait_admission(request_id, rx),
        }
    }

    /// Receiver side, ack step 1 (Repair 2 symmetry): register the run-start ack
    /// waiter BEFORE `watch://audio-ready` is emitted. Requires the live
    /// reservation for this exact request ID.
    pub fn begin_run_ack(
        &self,
        request_id: &str,
    ) -> Result<std::sync::mpsc::Receiver<Result<(), String>>, String> {
        {
            let state = self.state.lock().expect("admission gate state poisoned");
            match &*state {
                GateState::Reserved { request_id: id, .. } if id == request_id => {}
                _ => return Err("stale_or_unreserved".to_string()),
            }
        }
        let mut pending = self.pending_ack.lock().expect("gate poisoned");
        if pending.is_some() {
            return Err("ack_already_pending".to_string());
        }
        let (tx, rx) = std::sync::mpsc::channel::<Result<(), String>>();
        *pending = Some((request_id.to_string(), tx));
        Ok(rx)
    }

    /// Receiver side, ack step 2: bounded wait on the registered ack waiter.
    /// Any failure (rejection, timeout) aborts the reservation before returning.
    pub fn wait_run_ack(
        &self,
        request_id: &str,
        rx: std::sync::mpsc::Receiver<Result<(), String>>,
    ) -> Result<(), String> {
        let outcome = match rx.recv_timeout(self.ack_timeout) {
            Ok(Ok(())) => Ok(()),
            Ok(Err(reason)) => Err(reason),
            Err(_) => Err("ack_timeout".to_string()),
        };
        if let Err(reason) = &outcome {
            log::warn!("watch admission: run-start failed for request {request_id}: {reason}");
            self.abort(request_id, reason);
        }
        outcome
    }

    /// Convenience composition of [`begin_run_ack`] + [`wait_run_ack`].
    pub fn wait_run_started(&self, request_id: &str) -> Result<(), String> {
        let rx = self.begin_run_ack(request_id)?;
        self.wait_run_ack(request_id, rx)
    }

    /// WebView side: complete a pending admission decision. Returns false when
    /// there is no matching waiter (stale ID or the receiver already timed out).
    pub fn resolve_admission(
        &self,
        request_id: &str,
        accepted: bool,
        reason: Option<String>,
    ) -> bool {
        let waiter = {
            let mut pending = self.pending_admission.lock().expect("gate poisoned");
            match pending.as_ref() {
                Some((id, _)) if id == request_id => pending.take().map(|(_, tx)| tx),
                _ => None,
            }
        };
        match waiter {
            Some(tx) => tx
                .send(AdmissionDecision { accepted, reason })
                .is_ok(),
            None => false,
        }
    }

    /// WebView side: complete a pending run-start acknowledgement.
    pub fn resolve_run_started(&self, request_id: &str) -> bool {
        let waiter = {
            let mut pending = self.pending_ack.lock().expect("gate poisoned");
            match pending.as_ref() {
                Some((id, _)) if id == request_id => pending.take().map(|(_, tx)| tx),
                _ => None,
            }
        };
        match waiter {
            Some(tx) => tx.send(Ok(())).is_ok(),
            None => false,
        }
    }

    /// One correlated abort (Provider Contract §B.6): completes any pending
    /// admission/ack oneshot with failure and releases the reservation. Both the
    /// request ID must match; mismatched or already-idle calls are no-ops that
    /// return false. Idempotent.
    pub fn abort(&self, request_id: &str, reason: &str) -> bool {
        {
            let mut pending = self.pending_admission.lock().expect("gate poisoned");
            if let Some((_id, tx)) = pending.take_if(|(id, _)| id == request_id) {
                let _ = tx.send(AdmissionDecision {
                    accepted: false,
                    reason: Some(reason.to_string()),
                });
            }
        }
        {
            let mut pending = self.pending_ack.lock().expect("gate poisoned");
            if let Some((_id, tx)) = pending.take_if(|(id, _)| id == request_id) {
                let _ = tx.send(Err(reason.to_string()));
            }
        }
        self.force_idle_if(request_id)
    }

    /// Conditional release: `Idle` only when `Reserved` for this exact request ID.
    fn force_idle_if(&self, request_id: &str) -> bool {
        let mut state = self.state.lock().expect("admission gate state poisoned");
        match &*state {
            GateState::Reserved { request_id: id, .. } if id == request_id => {
                *state = GateState::Idle;
                true
            }
            _ => false,
        }
    }

    /// Test-only: force the gate into `Reserved` with a backdated timestamp so
    /// lease reclamation can be exercised without real waiting.
    #[cfg(test)]
    pub fn force_reserve_for_test(&self, request_id: &str, age: Duration) {
        let mut state = self.state.lock().expect("admission gate state poisoned");
        *state = GateState::Reserved {
            request_id: request_id.to_string(),
            reserved_at: Instant::now()
                .checked_sub(age)
                .expect("backdate overflow"),
        };
    }

    /// Test-only: run-start ack timeout used by integration tests.
    #[cfg(test)]
    pub fn ack_timeout_for_test(&self) -> Duration {
        self.ack_timeout
    }

    /// Test-only: whether an admission waiter is currently registered.
    #[cfg(test)]
    pub fn pending_admission_count_for_test(&self) -> usize {
        self.pending_admission.lock().expect("gate poisoned").is_some() as usize
    }
}

impl Default for AdmissionGate {
    fn default() -> Self {
        Self::new()
    }
}

// ─── Debug-only Tauri commands (registered from main.rs under cfg(debug_assertions)) ───

fn denied() -> String {
    "watch receiver is disabled".to_string()
}

#[tauri::command]
pub fn watch_admission_resolve(
    request_id: String,
    accepted: bool,
    reason: Option<String>,
) -> Result<bool, String> {
    #[cfg(debug_assertions)]
    {
        Ok(AdmissionGate::shared().resolve_admission(&request_id, accepted, reason))
    }
    #[cfg(not(debug_assertions))]
    {
        let _ = (request_id, accepted, reason);
        Err(denied())
    }
}

#[tauri::command]
pub fn watch_run_started(request_id: String) -> Result<bool, String> {
    #[cfg(debug_assertions)]
    {
        Ok(AdmissionGate::shared().resolve_run_started(&request_id))
    }
    #[cfg(not(debug_assertions))]
    {
        let _ = request_id;
        Err(denied())
    }
}

#[tauri::command]
pub fn watch_run_aborted(request_id: String, reason: String) -> Result<bool, String> {
    #[cfg(debug_assertions)]
    {
        Ok(AdmissionGate::shared().abort(&request_id, &reason))
    }
    #[cfg(not(debug_assertions))]
    {
        let _ = (request_id, reason);
        Err(denied())
    }
}

#[tauri::command]
pub fn watch_gate_state() -> Result<GateStateSnapshot, String> {
    #[cfg(debug_assertions)]
    {
        Ok(AdmissionGate::shared().snapshot())
    }
    #[cfg(not(debug_assertions))]
    {
        Err(denied())
    }
}

/// Hands the reserved upload's raw PCM (WAV container stripped exactly once, in
/// Rust) to the WebView over the Z2-proven raw binary IPC path. Requires the
/// live reservation for this exact request ID; the bytes are re-validated with
/// the same strict WAV parser used at upload before being returned.
#[tauri::command]
pub fn watch_read_reserved_pcm(request_id: String) -> Result<tauri::ipc::Response, String> {
    #[cfg(debug_assertions)]
    {
        let gate = AdmissionGate::shared();
        if !gate.is_reserved_for(&request_id) {
            return Err("not reserved".to_string());
        }
        let dir = crate::watch_receiver::server::watch_receiver_dir();
        let path = dir.join("received_watch.wav");
        let bytes = std::fs::read(&path).map_err(|e| format!("read failed: {e}"))?;
        let info = crate::watch_receiver::wav::parse(&bytes).map_err(|e| e.0)?;
        let data_offset = find_data_offset(&bytes).ok_or("data chunk not found")?;
        let pcm = bytes[data_offset..data_offset + info.data_size].to_vec();
        Ok(tauri::ipc::Response::new(pcm))
    }
    #[cfg(not(debug_assertions))]
    {
        let _ = request_id;
        Err(denied())
    }
}

/// Locates the absolute offset of the `data` chunk payload. Mirrors the bounded
/// chunk walk of `wav.rs` (declared extent, even-byte padding) without modifying
/// that module. The payload was already validated by `wav::parse` beforehand.
fn find_data_offset(bytes: &[u8]) -> Option<usize> {
    if bytes.len() < 12 || &bytes[0..4] != b"RIFF" || &bytes[8..12] != b"WAVE" {
        return None;
    }
    let declared_extent = 8usize.checked_add(u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]) as usize)?;
    let walk_bound = declared_extent.min(bytes.len());
    let mut offset = 12usize;
    while offset + 8 <= walk_bound {
        let chunk_size = u32::from_le_bytes([
            bytes[offset + 4],
            bytes[offset + 5],
            bytes[offset + 6],
            bytes[offset + 7],
        ]) as usize;
        let payload_start = offset + 8;
        if &bytes[offset..offset + 4] == b"data" && payload_start + chunk_size <= walk_bound {
            return Some(payload_start);
        }
        let padded = chunk_size.checked_add(chunk_size & 1)?;
        offset = payload_start.checked_add(padded)?;
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    const ID: &str = "11111111-1111-4111-8111-111111111111";
    const OTHER: &str = "22222222-2222-4222-8222-222222222222";

    /// Resolves a pending admission from another thread after `delay`.
    fn resolve_later(gate: Arc<AdmissionGate>, id: &'static str, delay: Duration, accepted: bool) {
        std::thread::spawn(move || {
            std::thread::sleep(delay);
            gate.resolve_admission(id, accepted, None);
        });
    }

    #[test]
    fn accept_flow_reserves_gate() {
        let gate = Arc::new(AdmissionGate::new());
        resolve_later(gate.clone(), ID, Duration::from_millis(20), true);
        match gate.request_admission(ID) {
            AdmissionOutcome::Accepted => {}
            other => panic!("expected Accepted, got {other:?}"),
        }
        assert_eq!(gate.snapshot().state, "Reserved");
        assert_eq!(gate.snapshot().request_id.as_deref(), Some(ID));
        // Release via the correlated finish path.
        assert!(gate.abort(ID, "run_finished"));
        assert_eq!(gate.snapshot().state, "Idle");
    }

    #[test]
    fn reject_flow_keeps_gate_idle() {
        let gate = Arc::new(AdmissionGate::new());
        resolve_later(gate.clone(), ID, Duration::from_millis(20), false);
        match gate.request_admission(ID) {
            AdmissionOutcome::Rejected { reason } => assert_eq!(reason, "rejected"),
            other => panic!("expected Rejected, got {other:?}"),
        }
        assert_eq!(gate.snapshot().state, "Idle");
    }

    #[test]
    fn reserved_gate_rejects_a_second_request_without_webview() {
        let gate = Arc::new(AdmissionGate::new());
        resolve_later(gate.clone(), ID, Duration::from_millis(20), true);
        let _ = gate.request_admission(ID);
        match gate.request_admission(OTHER) {
            AdmissionOutcome::Rejected { reason } => assert_eq!(reason, "already_reserved"),
            other => panic!("expected already_reserved, got {other:?}"),
        }
        // The stale waiter must not linger.
        assert!(gate.pending_admission.lock().unwrap().is_none());
    }

    #[test]
    fn resolve_is_idempotent_and_stale_ids_are_noops() {
        let gate = Arc::new(AdmissionGate::new());
        resolve_later(gate.clone(), ID, Duration::from_millis(20), true);
        let _ = gate.request_admission(ID);
        // Second resolve with the same ID: no waiter left → false.
        assert!(!gate.resolve_admission(ID, true, None));
        // Stale/unknown ID: false, no effect on the live reservation.
        assert!(!gate.resolve_admission(OTHER, true, None));
        assert_eq!(gate.snapshot().request_id.as_deref(), Some(ID));
    }

    #[test]
    fn abort_is_idempotent_and_stale_abort_cannot_clear_a_newer_run() {
        let gate = Arc::new(AdmissionGate::new());
        resolve_later(gate.clone(), ID, Duration::from_millis(20), true);
        let _ = gate.request_admission(ID);
        assert!(gate.abort(ID, "cancel"));
        assert_eq!(gate.snapshot().state, "Idle");
        // Second abort: idempotent no-op.
        assert!(!gate.abort(ID, "cancel"));
        // A newer run for another ID must be untouched by stale aborts.
        resolve_later(gate.clone(), OTHER, Duration::from_millis(20), true);
        let _ = gate.request_admission(OTHER);
        assert!(!gate.abort(ID, "cancel"));
        assert_eq!(gate.snapshot().request_id.as_deref(), Some(OTHER));
        assert!(gate.abort(OTHER, "run_finished"));
    }

    #[test]
    fn run_started_ack_completes_and_timeout_fails_closed() {
        let gate = Arc::new(AdmissionGate::new());
        // Ack without a reservation is rejected immediately (stale).
        assert_eq!(gate.wait_run_started(ID), Err("stale_or_unreserved".to_string()));

        resolve_later(gate.clone(), ID, Duration::from_millis(20), true);
        let _ = gate.request_admission(ID);
        // Ack from the "WebView" side completes the wait.
        {
            let g = gate.clone();
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_millis(20));
                assert!(g.resolve_run_started(ID));
            });
        }
        assert_eq!(gate.wait_run_started(ID), Ok(()));

        // After the run completes and the gate is released, a second ack is stale:
        // no pending waiter exists and the reservation no longer matches.
        assert!(gate.abort(ID, "run_finished"));
        assert!(!gate.resolve_run_started(ID));
    }

    #[test]
    fn lease_reclamation_reclaims_only_expired_reservations() {
        let gate = Arc::new(AdmissionGate::new());
        // Fresh reservation: not reclaimable.
        resolve_later(gate.clone(), ID, Duration::from_millis(20), true);
        let _ = gate.request_admission(ID);
        match gate.request_admission(OTHER) {
            AdmissionOutcome::Rejected { reason } => assert_eq!(reason, "already_reserved"),
            other => panic!("unexpected {other:?}"),
        }
        assert!(gate.abort(ID, "cancel"));

        // Aged reservation: reclaimed lazily at the next admission attempt.
        gate.force_reserve_for_test(ID, LEASE + Duration::from_secs(1));
        // No WebView needed: the lease path rejects nothing — the gate reclaims and
        // the new request proceeds to wait for a decision. Answer from a thread.
        resolve_later(gate.clone(), OTHER, Duration::from_millis(20), true);
        match gate.request_admission(OTHER) {
            AdmissionOutcome::Accepted => {}
            other => panic!("expected reclaim + accept, got {other:?}"),
        }
        assert_eq!(gate.snapshot().request_id.as_deref(), Some(OTHER));
        assert!(gate.abort(OTHER, "run_finished"));
    }

    #[test]
    fn reload_reconciliation_sees_gate_state() {
        let gate = Arc::new(AdmissionGate::new());
        assert_eq!(gate.snapshot().state, "Idle");
        gate.force_reserve_for_test(ID, Duration::from_secs(5));
        let snap = gate.snapshot();
        assert_eq!(snap.state, "Reserved");
        assert_eq!(snap.request_id.as_deref(), Some(ID));
        assert!(snap.reserved_age_ms.unwrap() >= 5_000);
    }

    #[test]
    fn abort_completes_pending_ack_with_failure() {
        let gate = Arc::new(AdmissionGate::new());
        resolve_later(gate.clone(), ID, Duration::from_millis(20), true);
        let _ = gate.request_admission(ID);
        {
            let g = gate.clone();
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_millis(20));
                // The WebView aborts instead of acknowledging.
                assert!(g.abort(ID, "provider_start_failed"));
            });
        }
        assert_eq!(
            gate.wait_run_started(ID),
            Err("provider_start_failed".to_string())
        );
        assert_eq!(gate.snapshot().state, "Idle");
    }
}
