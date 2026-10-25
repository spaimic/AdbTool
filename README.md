<<<<<<< HEAD
# AdbTool
early development of adb tool im making with claude.
=======
# ADB Tool

Bugjaeger-style ADB host app. Runs on a host Android device (e.g. Galaxy Tab A9) and talks to a target
(e.g. Galaxy A10) over USB OTG using a built-in implementation of the ADB protocol.

Features: shell commands, file push/pull (sync protocol), sideload (`sideload-host`), reboot to system/recovery/download.
Works in recovery as long as the recovery exposes an ADB interface (stock recovery: sideload only; TWRP/OrangeFox: everything).

## Build
Push to GitHub. The workflow in `.github/workflows/build.yml` builds the debug APK;
download it from the run's **Artifacts** section (Actions tab → latest run → `AdbTool-debug-apk`).
Local build: `gradle assembleDebug` (Gradle 8.9, JDK 17).
>>>>>>> d62e3da (Initial commit)
