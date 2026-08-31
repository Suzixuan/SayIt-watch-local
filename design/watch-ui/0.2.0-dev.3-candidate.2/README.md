# Watch UI candidate — 0.2.0-dev.3-candidate.2

- Status: `candidate`; source/build evidence only, not a real Galaxy Watch 7
  visual or interaction acceptance.
- Runtime version: unchanged at `0.2.0-dev.3`, `versionCode = 3`.
- Parent: `0.2.0-dev.3-candidate.1`, preserved untouched.
- `candidate.1` is **rejected by the user as over-designed**: its uploading,
  success, failure, retry, pending and discard presentations do not match the
  desired Watch role.

## New direction

The Watch now has only Config, Ready and Recording. Stop starts one automatic
whole-WAV upload and immediately returns the visible screen to Ready. Upload is
silent: it has no panel, toast, success/failure copy, retry, queue, pending WAV
or discard decision. The user evaluates the result in the PC input target.

While that HTTP request is active, the visible Ready microphone does nothing.
The internal latch is cleared after success or failure, the WAV/session is
cleared, and a new recording becomes available. Start and stop retain their
simple haptics; HTTP outcomes do not vibrate.

See [DESIGN-SPEC.md](DESIGN-SPEC.md) for the exact state/UI mapping and source
paths. `SHA256SUMS` covers the frozen design documents, excluding itself by
normal manifest convention. No token, address, device data, WAV, or APK appears
in this candidate.

The next review gate is real-device screenshots and interaction proof; source
review is not a substitute for that gate.
