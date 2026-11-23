# TouchBridge

**TouchBridge** is an experimental Android app that maps external keyboard and mouse input to simulated touchscreen gestures. It is designed for testing touch controls in apps and mobile games using an Android tablet or phone.

> **Touch mapping, not a virtual gamepad.** TouchBridge uses Android's Accessibility Service and does not emulate a Bluetooth/USB controller or bypass game input restrictions. Compatibility varies by Android version, device and game.

## Features

- **Keyboard-to-touch mapping:** assign physical keys to editable screen positions.
- **Virtual joystick:** use W, A, S and D for a directional drag from a configurable joystick center; diagonals are supported.
- **Multi-touch:** keep movement active while pressing other mapped buttons such as Jump, Sprint or Interact (subject to Android's gesture-injection behavior).
- **Press, hold and release:** uses continued gestures for held buttons, with short intervals for faster direction changes and release handling.
- **Tap mode:** trigger brief taps on key-down instead of holding a button (useful for certain platform games). WASD stays in joystick mode.
- **Mouse support (Android 14+):** optional mouse capture and a software cursor; left-click targets the cursor and right/middle clicks use configurable points.
- **Per-app activation:** only maps inputs when the selected target application is in the foreground.
- **Test lab and diagnostics:** preview physical inputs and run an actual Accessibility Service tap test; an optional overlay shows target detection and gesture counters.
- **Local configuration:** mapping data stays in Android app preferences. The app does not request network access.

## Getting started

1. Pair a Bluetooth keyboard/mouse or connect them through USB OTG.
2. Install the APK and open TouchBridge.
3. Open **Touch gesture test lab** to check whether physical key events are detected.
4. Enable **TouchBridge Input Control** in **Android Settings → Accessibility**. Android may require you to explicitly allow restricted accessibility settings for a sideloaded APK; only grant this permission to builds you trust.
5. Select your target application and enable mapping.
6. Customize the joystick center and button touch points using the mapping editor. The default positions are examples and may not fit your game or display.
7. Open your target application and test movement plus another key simultaneously.
8. If input appears stuck, disable mapping / the Accessibility Service or use **Release all simulated keys** in the app.

**Tap mode** is best for single actions. Leave it **off** for controls that need to remain held, such as sustained jumping or aiming.

## Default mappings

| Physical input | Simulated action | Default screen position |
|---|---|---|
| W / A / S / D | Virtual joystick | 16% X, 75% Y (center) |
| Space | Jump/button hold | 88% X, 77% Y |
| Left Shift | Sprint/button hold | 79% X, 90% Y |
| E | Interact | 73% X, 70% Y |
| R | Reload | 93% X, 54% Y |
| Mouse left | Touch at software cursor | Dynamic |
| Mouse right | Mapped touch | 89% X, 45% Y |
| Mouse middle | Mapped touch | 77% X, 44% Y |

## Building the APK

This repository includes `.github/workflows/build-apk.yml`.

1. Open the repository's **Actions** tab and choose **Build Android APK**.
2. Select **Run workflow**, or push a commit to `main` to trigger a build.
3. Download the `TouchBridge-debug-apk` artifact from a successful run.
4. Extract it and install `app-debug.apk`.

Build requirements: JDK 17, Gradle 8.9 and Android SDK 35. The GitHub workflow sets these up automatically.

To start a build from Termux (with GitHub CLI authenticated):

```bash
gh workflow run build-apk.yml --ref main
gh run watch
```

Each GitHub Actions run may sign its debug APK with a different debug key. Android can reject the new APK as an update to an older build. Uninstalling the old app deletes its settings; a persistent signing key is recommended for reliable upgrades and releases. Do not commit signing credentials.

## Project structure

- `MainActivity.java` — application settings, target selection and mapping editor
- `MapperService.java` — keyboard/mouse capture and Android touch injection
- `GestureFramePlanner.java` — coordinates which pointers continue, end or begin together
- `MappingStore.java` — local mapping preferences and default controls
- `TestActivity.java` — on-screen input visualization (not actual injection)
- `DiagnosticActivity.java` — real Android touch-injection test
- `.github/workflows/build-apk.yml` — debug APK build
- `tests/java/` — SDK-independent gesture planner tests (`bash scripts/test-planner.sh`)

## Limitations & privacy

- Android Accessibility gestures are not guaranteed to be perfectly smooth or accepted by every game. Some games restrict synthesized touch input.
- The service cannot always receive physical key-release events. If the OS or another service drops an `ACTION_UP`, a key can remain logically pressed until the mapping is reset; check the diagnostic DOWN/UP counters.
- For responsiveness, held gestures are dispatched in short chained segments. Lower segment durations reduce response delay, but may be less stable on some devices.
- Free-look / FPS camera control via relative mouse movement is **not** implemented.
- TouchBridge does not create a real virtual HID controller or require root for its Accessibility Service features.
- Key histories and gameplay are not uploaded, and there are no app network permissions. Accessibility permissions are powerful; inspect and trust the code before enabling the service.

## Latest changelog — v0.7.0

- Reworked the gesture scheduler to combine **held, newly pressed and released inputs** in the same multi-touch frame.
- Reduced default gesture segments from 260 ms to **95 ms**, with a tunable 60–180 ms range, for more responsive key releases and direction changes.
- Added an **8 ms release stroke** so released buttons do not stay held for an entire new gesture segment.
- Preserved already-held pointers when other controls are pressed or released, and made pointer scheduling deterministic.
- Added a **Release all simulated keys** control and active-key count in diagnostics.
- Added SDK-independent planner tests for simultaneous press/release and pointer-capacity handling.

**Status:** v0.7.0 is an experimental source update. The gesture planner tests pass locally; a full Android build and real-device/game validation are still required.
