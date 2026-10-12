# ThorVault v0.12.22-alpha

## Offline-first Android transfer milestone

Android save operations are now local:

- Open/parse save → bundled WASM/PKHeX.Core.
- Game → Vault true move source clearing → bundled WASM/PKHeX.Core.
- Vault → Game compatibility check → bundled WASM/PKHeX.Core.
- Vault → Game save write → bundled WASM/PKHeX.Core.
- Final Apply to device → Android Storage Access Framework.

The server is only used for Vault synchronization, backups, snapshots and
multi-device reconciliation. Browser save operations remain server-backed.
