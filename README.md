# ThorVault v0.12.22-alpha

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
- Identify likely save types and emulator/system/game hints from file names, paths, title IDs, and extensions.
- Fingerprint small save candidates with SHA-256 to flag exact duplicates; duplicates are reported only and never deleted automatically.
- Cache discovery results across app restarts.
- Before applying or restoring a device save, create a uniquely named backup and JSON manifest, verify the backup, then verify the write; attempt a verified rollback if a write fails.
- Use L1/R1 to cycle app tabs. The D-pad moves focus between visible controls and box slots; use the controller confirm button to activate the focused item.

Scan detection is heuristic and does not guarantee every emulator save will be found. Android may deny access to protected directories; add any accessible save directories separately.
