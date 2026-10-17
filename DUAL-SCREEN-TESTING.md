# v0.12.25-alpha AYN Thor dual-screen and controller test checklist

## Dual-screen mode

1. Install the APK on the AYN Thor and open **Settings**.
2. Press **Check display support**. The diagnostic must either name an Android-exposed secondary display or explicitly say that the firmware has not exposed one.
3. Enable **Dual Screen** and save settings. Select a Pokémon from the Vault.
4. If a secondary display was reported, its details panel should open there while the main ThorVault UI stays on the default display. Select another Pokémon and confirm that panel updates.
5. If no secondary display was reported, press **Details**. The normal in-app dialog must open; this is the intentional fallback.
6. Turn Dual Screen off, save settings, and confirm that the external details panel closes and Details uses the in-app dialog.

## Screen-role swap limitation

The **Swap primary / secondary screens** control is disabled unless Android exposes a real public capability for it. Standard Android presentation APIs can add a details panel to an exposed second display, but cannot move the already-running main activity to the other built-in panel. This build therefore does not claim to swap screens on firmware that hides that capability.

## Controller mode

1. Use L1/R1 to switch tabs; use touch to select a tab. Confirm the D-pad and analog sticks do not enter the tab bar.
2. In a Pokémon box, press Right/Left/Up/Down. Each move should select the immediate neighbour in that row or column; Right must not select a control above the grid.
3. Move the analog stick in each direction. It should match the D-pad behaviour with a short repeat delay.
4. Press A/centre to activate the focused control and B/Escape to close a dialog.

This checklist requires a physical AYN Thor. The lower display's Android visibility and any manufacturer-specific task-moving API remain firmware-dependent and were not verifiable in this environment.
