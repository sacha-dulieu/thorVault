# ThorVault v0.12.25-alpha

## Offline-first Android transfer milestone

Android save operations are now local:

- Open/parse save → bundled WASM/PKHeX.Core.
- Game → Vault true move source clearing → bundled WASM/PKHeX.Core.
- Vault → Game compatibility check → bundled WASM/PKHeX.Core.
- Vault → Game save write → bundled WASM/PKHeX.Core.
- Final Apply to device → Android Storage Access Framework.

The server is used for Vault synchronization, server-side snapshots/backups, and
multi-device reconciliation. Device-save backups created before Apply/Restore are
stored locally under the selected Android scan location. Browser save operations
remain server-backed.


## Android scan and controller improvements

- Add and remember multiple Android Storage Access Framework folder locations.
- Choose which remembered folders to scan on each run.
- Scan asynchronously with progress, cancellation, partial results, and a two-minute no-progress watchdog.
- Keep partial or previous results when a location fails or a scan reaches a safety limit.
- List recognised Pokémon saves only; use the correct Nintendo 3DS title-ID families to name X, Y, Omega Ruby, Alpha Sapphire, Sun, Moon, Ultra Sun, and Ultra Moon. Other games and unidentified generic saves are hidden, including from previously cached scan results.
- Fingerprint small save candidates with SHA-256 to flag exact duplicates; duplicates are reported only and never deleted automatically.
- Cache discovery results across app restarts.
- Before applying or restoring a device save, create a uniquely named backup and JSON manifest, verify the backup, then verify the write; attempt a verified rollback if a write fails.
- Controller navigation: L1/R1 cycles app tabs; touch can select tabs; D-pad and either joystick navigate controls and Pokémon boxes only. Box movement is locked to the adjacent row/column slot so Right does not jump to a button above; A/centre activates the focused control; B/Escape dismisses an open dialog.

Scan detection is heuristic and does not guarantee every emulator save will be found. Android may deny access to protected directories; add any accessible save directories separately.


## v0.12.25-alpha

- Adds a persisted Single Screen / Dual Screen setting and live Android display diagnostics.
- Dual Screen actively creates a standard Android `Presentation` for selected Pokémon details whenever firmware exposes a non-default display. If it does not, the regular in-app details dialog remains the fallback.
- Includes a primary/secondary role-swap control with an explicit disabled state: standard Android does not provide an API to move this already-running activity between the Thor's built-in panels, so ThorVault never reports a fabricated swap.
- L1/R1 and touch are now the only tab controls; D-pad and analog sticks navigate only the active view. Pokémon-box navigation follows exact horizontal/vertical neighbours.
