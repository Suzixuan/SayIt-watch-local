mod payload;

use payload::{generate_payload, spike_meta, SpikeMeta};

/// JSON metadata channel: length, expected SHA-256 and sentinel bytes.
#[tauri::command]
fn spike_payload_meta() -> SpikeMeta {
    spike_meta()
}

/// The probe itself: raw bytes over the custom-protocol IPC path with no JSON wrapper.
#[tauri::command]
fn spike_payload() -> tauri::ipc::Response {
    tauri::ipc::Response::new(generate_payload())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![spike_payload_meta, spike_payload])
        .run(tauri::generate_context!())
        .expect("error while running the Z2 binary-IPC spike application");
}
