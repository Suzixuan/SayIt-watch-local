# Watch UI baseline recovery — 0.2.0-dev.1 (from parent commit blobs)

- Status: `recovered-manifest` (supersedes the invalid manifest in
  `../0.2.0-dev.1-baseline/`)
- Parent version: Watch `0.2.0-dev.1`, `versionCode = 1`
- Parent Git SHA: `5da1a32279b372810d83504aca2021b0c8146763`

## Why this directory exists

PM review of `0.2.0-dev.2-candidate.1` found the original
`0.2.0-dev.1-baseline/SHA256SUMS` to be **invalid evidence**: its
`watch/app/build.gradle.kts` entry matched the already-modified dev.2 file —
the candidate version bump had been applied before the baseline manifest was
computed. Per the Repair 2 instructions the invalid manifest is NOT overwritten
(it stays as the audit record of the mistake); this directory regenerates the
manifest correctly **from the parent commit's Git blobs**.

## Corrected manifest

`SHA256SUMS` below was computed with
`git show 5da1a32279b372810d83504aca2021b0c8146763:<path> | SHA-256` for each
key resource. The corrected `build.gradle.kts` hash is
`8eee9944f1e9aa0464774fe183ae4e0e3d1861ff1381f9ae11bd55066fc38254` (the invalid
manifest recorded `656dca8609cd8303ef36c58f67c07f354d82f17e0e959cc2dd9d234398462e29`,
the dev.2 file). All other entries happen to be identical because only
`build.gradle.kts` had been modified before the original manifest was taken.

## Baseline screenshots

Same environment limitation as declared in the original baseline and the
candidate README: no Android emulator or physical watch is attached to this
machine, so no photographic baseline screenshot exists here. The baseline is
pinned by the parent Git SHA plus this corrected per-file manifest; checking
out the parent SHA reproduces 0.2.0-dev.1 exactly.
