# Generation 1–9 support testing checklist

Use copies of real save files. Never use the only copy of a save.

## Priority tests
- [ ] Legends: Z-A save detected and opened; summary says `PA9`.
- [ ] Sword save detected and opened; summary says `PK8`.
- [ ] Shield save detected and opened; summary says `PK8`.
- [ ] Legends: Arceus save detected and opened; summary says `PA8`.

## Other Switch formats
- [ ] Brilliant Diamond / Shining Pearl open as `PB8`.
- [ ] Scarlet / Violet open as `PK9`.
- [ ] Let's Go Pikachu / Eevee open as `PB7`.

## Earlier generations
- [ ] Gen 1 save opens as `PK1`.
- [ ] Gen 2 save opens as `PK2`.
- [ ] Gen 3 through Gen 6 saves open as `PK3` through `PK6`.
- [ ] Gen 7 Sun/Moon/USUM opens as `PK7`.

## Safe copy-write validation
- [ ] Test copy into an empty PC box slot with a backup save for each target game.
- [ ] Confirm the original input file remains unchanged.
- [ ] Confirm the exported modified save reopens in PKHeX and the target emulator/game.
- [ ] Confirm a mismatched format (for example PK8 into PA8) is blocked with a clear explanation.
- [ ] Do not enable true-move source clearing for Gen 1–2 or Gen 8–9; it remains unsupported.

Android save discovery depends on folder permissions and path/title-ID visibility. If an emulator's save path is inaccessible through Android's Storage Access Framework, select an exported copy of its save manually.
