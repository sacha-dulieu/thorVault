# ThorVault v0.12.26-alpha

## Offline-first Pokémon save support expansion

This release expands the local PKHeX.Core engine to recognise and read main-series save files across Generations 1–9, including the user's priority games:

- **Pokémon Legends: Z-A** — Gen 9 `SAV9ZA`, native `PA9` Pokémon records.
- **Pokémon Sword / Shield** — Gen 8 `SAV8SWSH`, native `PK8` records.
- **Pokémon Legends: Arceus** — Gen 8 `SAV8LA`, native `PA8` records.

It also adds the remaining main-series save families:

- Gen 1: Red, Blue, Yellow (`SAV1` / `PK1`)
- Gen 2: Gold, Silver, Crystal (`SAV2` / `PK2`)
- Gen 3: Ruby, Sapphire, Emerald, FireRed, LeafGreen (`PK3`)
- Gen 4: Diamond, Pearl, Platinum, HeartGold, SoulSilver (`PK4`)
- Gen 5: Black, White, Black 2, White 2 (`PK5`)
- Gen 6: X, Y, Omega Ruby, Alpha Sapphire (`PK6`)
- Gen 7: Sun, Moon, Ultra Sun, Ultra Moon (`PK7`); Let's Go Pikachu/Eevee (`PB7`)
- Gen 8: Sword/Shield (`PK8`); Brilliant Diamond/Shining Pearl (`PB8`); Legends: Arceus (`PA8`)
- Gen 9: Scarlet/Violet (`PK9`); Legends: Z-A (`PA9`)

### Transfer safety and limitations

- Import, inspection, compatibility checking, and copy-to-empty-box-slot use the native entity format required by the destination save. Generation alone is not sufficient: `PK8`, `PB8`, and `PA8` are different formats; so are `PK9` and `PA9`.
- ThorVault blocks format mismatches rather than silently converting an entity between games. General cross-game/cross-generation conversion is not part of this release, apart from the existing experimental PK6 → PK7 workflow.
- Copy mode preserves the source save and writes to a separate working copy with post-write validation. Back up your original save before applying any generated save.
- True-move source clearing remains limited to the previous Generation 3–7 implementation. Generations 1–2 and 8–9 are copy-only until their clear/write/rollback path is separately tested.
- Emulator save discovery now recognises game folders and Switch title IDs for Pokémon titles, including Legends: Z-A, Sword/Shield, Legends: Arceus, BDSP, Scarlet/Violet, and Let's Go. Discovery remains heuristic and depends on Android folder permissions and the directory naming/path.

## Android and local-engine details

- Local Android save operations use the bundled WebAssembly / PKHeX.Core engine.
- Server-side mode continues to use the same .NET engine when installed.
- PKHeX.Core updated to 26.10.18 for support of current Gen 9/Z-A save structures.
- The repository pins .NET SDK 10.0.302 through `global.json` for reproducible WebAssembly publishing.
- Android app versionCode: 1226; versionName: 0.12.26-alpha.
- The GitHub Actions artifact is `ThorVault-v0.12.26-alpha-APK`.

**Testing note:** Static checks can verify project consistency, but a full .NET/Android build and tests with representative real save backups are still required. Do not test write-back on your only copy of a save.

## Previous release

### v0.12.25-alpha

- Added Android Settings controls for Single Screen, Dual Screen, display diagnostics, and swapping the active ThorVault window between detected displays.
- Dual-screen mode attempts a dedicated full-screen Pokémon details Activity on the other display, then falls back to an Android Presentation when firmware refuses Activity-on-display launching.
- Controller D-pad and analog joystick focus excludes navigation tabs. Tabs are reached by touch or switched with L1/R1.
- Pinned the local-engine WebAssembly build to .NET SDK 10.0.302.
