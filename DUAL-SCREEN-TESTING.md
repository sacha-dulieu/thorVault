# v0.12.25-alpha dual-screen and controller test checklist

## Dual-screen mode

1. Install the v0.12.25-alpha APK on the AYN Thor.
2. Open Settings and check the **Android displays** status block. It should list both physical/logical displays and show which display ThorVault is using.
3. Select **Dual screen**. The other display should open a full-screen ThorVault Pokémon-details page, initially showing “Select a Pokémon”.
4. Select a Pokémon in the Vault. The other display should update with its name, species, level, nature, ability, held item, origin, trainer, location, and moves while the main UI stays interactive.
5. Select **Swap primary / secondary**. ThorVault should relaunch on the other display and keep a details panel on the screen it left. If Android blocks moving the Activity between displays, the status block should explain that the swap failed and leave the existing screen usable.
6. Select **Single screen**. The secondary page should close and Pokémon details should use the in-app dialog.
7. If Android lists only one display, or reports that secondary launch was rejected, save a screenshot of Settings → Screen layout and note the AYN firmware/Android version. App code cannot target a display that the system does not expose or allow.

## Controller navigation

1. On any tab, press Right/Left on the D-pad: focus should move horizontally to the closest visible control in that row.
2. Press Up/Down: focus should prefer a control in the same column.
3. In a Pokémon box, each direction should move to the nearest slot in that direction before leaving the grid.
4. Press L1/R1 to switch tabs. The D-pad and analog sticks should not focus the tabs bar. Touch should still switch tabs.
5. Move either analog stick past its threshold and verify focus moves in that direction; release it to stop repeat movement.
6. Confirm with A/centre, and dismiss dialogs with B/Escape.

The source checks are not a substitute for this hardware test. This build has not been run on a physical AYN Thor in this environment.
