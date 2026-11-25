# ThorVault Android v0.12.26-alpha

Android builds bundle the local WebAssembly Pokémon engine. Supported main-series save families are Generations 1–9; game-specific native formats include PK8 (Sword/Shield), PA8 (Legends: Arceus), and PA9 (Legends: Z-A), as well as PB7/PB8 for Let's Go and BDSP.

Build from the repository root with GitHub Actions using the `Build installable ThorVault APK` workflow. Download the `ThorVault-v0.12.26-alpha-APK` artifact from the successful run and extract the APK.

Copy-to-game is format-checked and creates a modified copy. True-move source clearing remains restricted to Generations 3–7. Back up real save files before applying a modified save.
