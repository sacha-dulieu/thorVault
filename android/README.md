# ThorVault Android v0.12.23-alpha

## Offline-first save handling

Opening an Android save never contacts the ThorVault server.

The app reads the granted Android file URI, passes the bytes to the bundled
local WebAssembly/PKHeX.Core engine, and opens Box Manager from that local
result.

Server connectivity is only for background Vault synchronization and backups.

Test:

1. Disable network access.
2. Launch the APK.
3. Grant/select the White 2 save.
4. Open it.
5. Box Manager must load without any server URL or token.
