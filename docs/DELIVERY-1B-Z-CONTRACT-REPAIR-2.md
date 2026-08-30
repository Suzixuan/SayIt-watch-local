# Delivery 1B — Z1 Contract Repair 2

Status: PM re-review found one blocking accounting defect. Documentation repair only; product implementation remains locked.

Branch: `codex/review-watch-pipeline`

Review base: Z revision 2 `0d6213008b910d2d9b7898d1dadac8e59c814869`

## Goal

Correct the final-chunk sample accounting in the frozen external-audio contract. Do not redesign the accepted parts of revision 2.

## Required correction

`docs/DELIVERY-1B-PROVIDER-CONTRACT.md` §B.5 step 7 currently specifies `audioSentSamples += 4096` for every chunk. The final PCM chunk is not guaranteed to contain 4096 samples, so this can overcount duration and corrupt the values consumed by minimum-duration handling, processing timeouts, History audio metadata, and Stop-to-Paste evidence.

Freeze exact accounting instead:

- form each chunk as an even-length raw PCM byte slice;
- compute `chunkSamples = chunk.byteLength / 2` for that exact slice;
- append the exact copied chunk to `recordedChunks`;
- increment `audioSentSamples += chunkSamples`, never the configured maximum chunk size;
- assert after the feed that `audioSentSamples === admission.sampleCount` and that the sum of `recordedChunks` byte lengths equals `pcm.byteLength`;
- any mismatch must take the correlated abort path before `provider.stop`, with a fixed non-secret reason;
- derive `wallTimeAtStopSec`, `pttHoldMs`, timeout input, and History duration only from the exact final `audioSentSamples`.

Add an explicit test case whose sample count is not divisible by 4096, proving the final short chunk is counted exactly and the reconstruction byte-for-byte matches the admitted PCM.

Update the sequence/checklist/risk text only where needed so no remaining sentence implies every chunk is exactly 4096 samples.

## Allowed files

- `docs/DELIVERY-1B-PROVIDER-CONTRACT.md`
- `HANDOFF.md`
- `PROJECT_PROGRESS.md`

Do not change product source, tests, dependencies, generated files, UI files, WAVs, APKs, tokens, or logs.

`docs/WATCH-UI-Z-HANDOFF.md` is PM reference for a later slice. Do not implement it during this repair.

## Return package

- New commit SHA on `codex/review-watch-pipeline`.
- Changed-file list proving only the three allowed files changed.
- Exact contract sections changed.
- Confirmation that revision 2's other Repair 1 decisions were not weakened.

Push and stop. The binary-IPC spike and all Delivery 1B product implementation remain unauthorized until PM accepts this repair.
