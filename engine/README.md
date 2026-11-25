# ThorVault Pokémon save engine (Generations 1–9)

PKHeX.Core 26.10.18 is used to parse the main-series save classes for Generations 1–9, including Pokémon Legends: Z-A, Sword/Shield, and Legends: Arceus.

| Game family | Save class | Native Pokémon format |
|---|---|---|
| Red / Blue / Yellow | `SAV1` | `PK1` |
| Gold / Silver / Crystal | `SAV2` | `PK2` |
| Ruby / Sapphire / Emerald / FireRed / LeafGreen | `SAV3RS`, `SAV3E`, `SAV3FRLG` | `PK3` |
| Diamond / Pearl / Platinum / HeartGold / SoulSilver | `SAV4DP`, `SAV4Pt`, `SAV4HGSS` | `PK4` |
| Black / White / Black 2 / White 2 | `SAV5BW`, `SAV5B2W2` | `PK5` |
| X / Y / Omega Ruby / Alpha Sapphire | `SAV6XY`, `SAV6AO` | `PK6` |
| Sun / Moon / Ultra Sun / Ultra Moon | `SAV7SM`, `SAV7USUM` | `PK7` |
| Let's Go Pikachu / Eevee | `SAV7b` | `PB7` |
| Sword / Shield | `SAV8SWSH` | `PK8` |
| Brilliant Diamond / Shining Pearl | `SAV8BS` | `PB8` |
| Legends: Arceus | `SAV8LA` | `PA8` |
| Scarlet / Violet | `SAV9SV` | `PK9` |
| Legends: Z-A | `SAV9ZA` | `PA9` |

The destination save's `BlankPKM.Extension` determines its required native entity format. This matters because different games in the same generation can use different formats. ThorVault blocks mismatches; it does not silently convert Pokémon between contexts.

Import, inspection, compatibility checking, and copy-to-empty-box-slot are implemented through PKHeX.Core. The source save remains untouched while a modified working copy is built and checked. True-move source clearing remains restricted to the existing Generation 3–7 path until per-game write and rollback testing is completed.

The engine accepts regular save exports and emulator `main` save files where PKHeX recognises the data. It is not a universal emulator-folder scanner; Android discovery depends on accessible folders, game identifiers, and the emulator's directory structure.
