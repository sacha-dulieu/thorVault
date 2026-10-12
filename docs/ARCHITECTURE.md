# ThorVault architecture

## Core rule

The Thor is fully usable offline. The server is a backup and optional remote interface, not a requirement.

```text
Thor PWA/app
├── local vault
├── local banks and boxes
├── local game profiles
├── offline queue
└── local exports
       ↕ when online
ThorVault server
├── immutable snapshots
├── SQLite snapshot index
├── latest-backup restore
└── optional NAS catalogue
```

## Version 0.1 synchronization

Version 0.1 intentionally uses one-way snapshots:

```text
Thor local state -> authenticated immutable snapshot -> server storage
```

The server never pushes changes automatically. Restore is explicit and first downloads a local JSON backup.

## Planned data-safety rules

- Close the emulator before changing a save.
- Back up before every save write.
- Retain original Pokémon bytes.
- Treat generation conversions as derived variants.
- Never resolve two-way sync conflicts silently.
- Do not require root.
- Use Android Storage Access Framework permissions.
- Prefer WireGuard over public exposure.
