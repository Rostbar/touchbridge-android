# TouchBridge — Android Keyboard/Mouse → Touch (prototype)

**Version 0.1.0 (source code, not a prebuilt APK).**

TouchBridge maps a connected physical Bluetooth/USB keyboard and (on Android 14+) mouse to synthetic touchscreen gestures in one chosen Android game or app. It is designed for learning and local testing, not to evade anti-cheat rules. The app has **no INTERNET permission** and keeps its configuration only on the device.

## Features

- Per-app activation: choose one target app; mapper stays inactive outside that app.
- WASD → virtual joystick. Drag center configurable; directional radius scales to display size.
- Hold a keyboard button → hold a touch at a specified screen location.
- Assign arbitrary Android `KeyEvent` names (e.g., `Q`, `F`, `SPACE`, `CTRL_LEFT`, `TAB`, `1`).
- Mouse (Android 14 / API 34 or newer): intercept mouse, draw a local virtual cursor, map left click to the cursor position, right/middle click to configured screen spots. Cursor data uses relative axes when available and absolute axes otherwise.
- Edit X/Y touch coordinates as percentages, or select a location by tapping over the target app.
- Multi-touch and continued touch strokes via Android AccessibilityService; no root requirement for these APIs.

## Important limitations

- **A real Bluetooth/USB gamepad or virtual controller device is NOT emulated**: this is only touch simulation. True virtual gamepad devices normally require privileges outside normal app permissions.
- The app **does not** currently map unconstrained mouse movement into 3D camera/look gestures (FPS aim). Mouse capture and cursor behavior vary by device.
- Continuous strokes are implemented via chained `AccessibilityService.dispatchGesture()` calls and **are not guaranteed to be latency-free** or work in every game.
- Hardware mouse interception is an Android 14+ feature and may not work with every mouse/Android ROM. The cursor might not move correctly on some tablets because of Android input source handling.
- Full-screen games, secure views, anti-cheat, accessibility policies, or overlay restrictions may prevent this from working. Read the rules of the game being used.
- Unverified on physical devices. Android SDK was not available in the authoring environment, so no local APK compile/runtime test was possible.
- The mouse gets intercepted *only* while the chosen target app is active and the mouse toggle is enabled. If control gets stuck, disable the accessibility service in Android settings.

## Build an APK in GitHub Actions (easiest without Android Studio)

1. Create a **private** GitHub repository, upload these project files with `.github/workflows/build-apk.yml` included, and commit to a `main` branch.
2. Open **Actions → Build Android APK → Run workflow** (or push to `main`).
3. Open the successful workflow run, download the artifact named `TouchBridge-debug-apk`.
4. Unzip the artifact and install `app-debug.apk` on your tablet.

The workflow uses Java 17, Gradle 8.9, Android SDK platform 35 and Android Gradle Plugin 8.7.3. Debug APKs are automatically signed with a debug key and should not be publicly distributed as release builds.

## Build using Windows + Android Studio

1. Install Android Studio with Android SDK platform 35 and JDK 17.
2. Install/use Gradle 8.9 or generate a Gradle wrapper (`gradle wrapper --gradle-version 8.9`) in the project root (wrapper files are not bundled).
3. Open the **TouchBridge** project directory in Android Studio and let Gradle sync.
4. Run `gradle :app:assembleDebug` (or the equivalent **Build APK** action in Android Studio).
5. Find the file at `app/build/outputs/apk/debug/app-debug.apk`.

Build-time Internet access is required for the Gradle plugin and SDK dependencies; the installed app itself has no network permission.

## Usage

1. Pair a Bluetooth mouse and keyboard or connect them through USB OTG.
2. Install the APK, then open **TouchBridge**.
3. Enable **TouchBridge Eingabesteuerung** in Android Settings → Accessibility / Bedienungshilfen → Downloaded apps / Installierte Apps.
4. If sideloaded app accessibility services are restricted, open Android Settings → App Info → TouchBridge → menu → **Allow restricted settings** (wording differs across Android versions; only do this for APKs you trust).
5. Choose your target game/application in TouchBridge.
6. Adjust the button positions (touch layout differs among games and screen orientations). “Im Spiel wählen” opens the game and captures the next screen tap.
7. Enable mapping. On Android 14+ optionally enable “Maus abfangen”.
8. Switch to the game and test the mapped keyboard keys.

### Default mappings

| Input | Behavior | Relative position |
|---|---|---|
| WASD | Virtual joystick (continuous drag) | center 16% / 75% |
| SPACE | Hold jump button | 88% / 77% |
| SHIFT_LEFT | Sprint button | 79% / 90% |
| E | Interact | 73% / 70% |
| R | Reload | 93% / 54% |
| Mouse left | Touch at software cursor | moving |
| Mouse right | Hold aim button | 89% / 45% |
| Mouse middle | Hold alternate button | 77% / 44% |

Defaults are only examples, not tuned for any particular game. To change each mapping, tap its coordinate editor or select a point on the game's screen.

## Source layout

- `app/src/main/java/de/touchbridge/app/MainActivity.java` — Settings/UI, app picker, mapping editor
- `app/src/main/java/de/touchbridge/app/MappingStore.java` — Local configuration and defaults
- `app/src/main/java/de/touchbridge/app/MapperService.java` — Accessibility input capture + touch gesture engine + picker/cursor overlays
- `.github/workflows/build-apk.yml` — Debug APK build pipeline

## Privacy

TouchBridge does not read text fields, send data over the network, take screenshots, record key histories, or request contacts/storage/location. Android's accessibility permission is still powerful, so always review the source and turn off the service when you are finished.
