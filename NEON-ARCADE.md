# NEON ARCADE — Android TV Game Station

Standalone Android TV app (`io.middlepoint.neonarcade`) that does not modify the MCP server or the original Xiaomi/Google TV launcher.

## Features
- Original offline Snake, Pong (versus AI), and Breakout games.
- TV remote and USB/Bluetooth gamepad support.
- Neon-style full-screen interface with six main cards.
- Lists launchable installed apps, including separately installed game emulators.
- Direct KinoWatch (`watch.kino`) and KinoPub (`com.kinopub`) shortcuts.
- Optional reversible HOME role; not activated by installing the APK.
- No ads, accounts, network permission, or bundled copyrighted games/ROMs.

## Build
```bash
./gradlew :arcade:assembleDebug
```
The APK is `arcade/build/outputs/apk/debug/arcade-debug.apk`.
CI workflow: `.github/workflows/build-neon-arcade.yml`.

## Controls
- D-pad: select tiles / steer Snake / move Pong or Breakout paddle.
- A or OK: launch / restart / serve ball.
- B or Back: return to game hub.
- Left analog stick: game movement and menu navigation.

## Install and launch (ADB shell)
```sh
pm list packages | grep io.middlepoint.neonarcade
monkey -p io.middlepoint.neonarcade 1
```
Installing the APK alone does **not** replace the system HOME.

## Optional: set NEON ARCADE as HOME after testing
```sh
cmd package set-home-activity io.middlepoint.neonarcade/.ArcadeActivity
```
Restore original Google Android TV HOME:
```sh
cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
```
If a Xiaomi firmware overrides HOME on boot, check its firmware behavior first; never disable system launchers blindly.

## Future upgrades
Install a legitimate Android TV-compatible emulator (e.g. PPSSPP or RetroArch) separately, with legally obtained games. The installed-app list will provide access. Emulation speed depends on TV hardware.
