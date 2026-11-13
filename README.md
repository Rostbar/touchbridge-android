# TouchBridge v0.6 – Fortnite Joystick-Stabilisierung

Beim Halten von WASD wurde der virtuelle Joystick in v0.5 alle 95 ms durch eine neue `dispatchGesture`-Etappe fortgeführt. In manchen Spielen entstand dadurch ein merkbares Unterbrechen/erneutes Heranziehen des Sticks.

**Änderungen**
- Joystick-Richtungswechsel werden über eine kurze 70-ms-Etappe erreicht, anschließend läuft der Halteabschnitt länger (standardmäßig 260 ms), um die Anzahl der Übergänge zu reduzieren.
- Die App nutzt weiterhin `StrokeDescription.continueStroke(...)`, damit Android den virtuellen Finger zwischen Etappen grundsätzlich gedrückt halten kann.
- Im Hauptmenü gibt es einen Regler **Joystick-Stabilität (120–500 ms)**: länger reduziert Etappenwechsel, kann aber Richtungswechsel und Loslassen verzögern. 260 ms ist der Startwert, bei Ruckeln 350–450 ms testen, bei Trägheit 160–220 ms.
- Diagnose zeigt **Joystick-Starts** sowie Tastendrücke **DOWN/UP**. Wenn "Starts" während eines ununterbrochen gehaltenen W steigt, wurde die Geste abgebrochen und musste neu beginnen. Steigt UP obwohl du W weiter hältst, meldet das System ein unerwartetes Loslassen.

**Einschränkungen:** Diese Änderung reduziert die Häufigkeit kurzer Gesten, garantiert aber kein vollständig nahtloses Verhalten in Fortnite. Android kann Accessibility-Gesten jederzeit abbrechen und manche Spiele behandeln die künstliche Berührung anders als echte Finger-/Controller-Eingaben. Für einen stabilen nativen Gamepad-Input braucht man andere Systemrechte beziehungsweise einen echten Controller. Der v0.6-Code wurde strukturell geprüft, aber nicht auf dem Gerät gebaut oder getestet.

**Termux-Update**: Patch-ZIP nach `~/storage/downloads/` herunterladen, im Repository `unzip -o ~/storage/downloads/TouchBridge_JoystickFix_Patch_v0.6.zip`, dann `git add app README.md && git commit -m "Smooth joystick v0.6" && git push origin main`. Der Push startet den GitHub-Actions-Build automatisch.

---

# TouchBridge v0.5 – Geometry Dash Tap-Fix

**Neu: Kurze Taps statt Halten.** Aktiviere in TouchBridge den Schalter **„Kurze Taps statt Halten (z. B. Geometry Dash)“**. Jeder physische Tastendruck (z. B. SPACE) erzeugt dann einen einzigen **65-ms-Touch**, der **automatisch endet**, selbst wenn Android kein KEY_UP meldet. Die automatischen Wiederholungen beim Halten der Taste werden nicht als weitere Taps verarbeitet. WASD-Joystick und Maus bleiben weiterhin im bisherigen kontinuierlichen Modus.

**So testest du Geometry Dash:** Ziel-App wählen → SPACE-Position im Spiel festlegen → Mapping aktivieren → Tap-Modus aktivieren → Geometry Dash starten. Bei einem einzelnen Druck auf SPACE sollte die Figur einmal springen; dauerhaftes Halten erzeugt keine Touch-Dauerschleife mehr. GD kann je nach Spielmodus Eingaben anders interpretieren. Die genaue Touch-Koordinate muss stimmen.

**Hinweise zur Ursache:** Version 0.4 nutzte für belegte Tasten eine Folge von fortgesetzten 95-ms-Gesten (`continueStroke`), die erst nach KEY_UP beendet wurde. Wenn Android KEY_UP nicht an den Bedienungshilfedienst weiterleitete oder es verzögert ankam, blieb die Figur scheinbar dauerhaft gedrückt. V0.5 umgeht das für normale Tasten im Tap-Modus mit zeitlich begrenzten Touch-Gesten. Die normale Haltefunktion ist weiterhin vorhanden, damit Spiele mit Druck-und-Halten-Mechaniken funktionieren.

**Build:** bestehenden GitHub Actions Workflow verwenden. Android-Bedienungshilfe muss aktiviert sein. Bei wechselnd signierten Debug-APKs kann eine Deinstallation (mit Datenverlust) erforderlich sein. Nicht auf Gerät getestet.

---

# TouchBridge — Android Keyboard/Mouse → Touch (prototype)

**Version 0.6.0 (source code, not a prebuilt APK).**

TouchBridge maps a connected physical Bluetooth/USB keyboard and (on Android 14+) mouse to synthetic touchscreen gestures in one chosen Android game or app. It is designed for learning and local testing, not to evade anti-cheat rules. The app has **no INTERNET permission** and keeps its configuration only on the device.

## Neu in v0.4: Spiel-Erkennung und aussagekräftigeres Debugging

- Der AccessibilityService berücksichtigt jetzt auch `TYPE_WINDOWS_CHANGED` und versucht, das fokussierte Anwendungsfenster über `getWindows()` zu ermitteln (mit deklarierter `canRetrieveWindowContent`-Berechtigung). Diese Information wird nur zur Paketkennung verwendet, nicht zum Lesen von Textinhalten.
- Das optionale Diagnose-Overlay wird **auch dann angezeigt, wenn die Ziel-App nicht erkannt wird**. Es zeigt *Ziel erkannt: JA/NEIN*, *Mapping AN/AUS*, Vordergrund-Paket sowie Zähler. Das Overlay bleibt bis zur Deaktivierung der Option über anderen Apps sichtbar.
- Der Zähler für gemappte Tastendrücke läuft auch außerhalb der Ziel-App, sofern Android die Ereignisse an den Service liefert; die Tasten werden außerhalb der Ziel-App **nicht abgefangen**.
- Nach Installation: Bedienungshilfe einmal aus- und wieder einschalten, im Hauptbildschirm die gewünschte Ziel-App und Mapping aktivieren, Diagnose-Overlay einschalten, ins Spiel wechseln. Falls `Ziel erkannt: NEIN`, Paketnamen vergleichen; falls `Tasten: 0`, erhält der Service keine KeyEvents. Hinweis: Nur ein AccessibilityService kann systemweit Tastatureingaben filtern.
- **Nicht geräteseitig getestet.** Dieses Update behebt die Erkennungslogik, garantiert aber nicht, dass jedes Spiel Accessibility-Gesten akzeptiert.

## Neu in v0.3: Fehlersuche direkt am Gerät

- **🧪 Echte Android-Touch-Geste testen**: prüft durch einen echten `AccessibilityService.dispatchGesture()`-Aufruf, ob Android einen generierten Touch in der App ausliefert. Der Test funktioniert ohne Zielspiel und erfordert einen aktivierten Bedienungshilfedienst. Tippe auf **Echten Touch auslösen**, nicht auf die Zielscheibe. Erfolgreich ist der Test nur, wenn **„Geste an Android gesendet“** erscheint UND der Touch-Zähler steigt. Die bloße Erfolgsmeldung des API-Callbacks beweist noch nicht, dass das Ziel den Touch erhalten hat.
- **Diagnoseanzeige im Spiel (optional)**: blendet in der gewählten Ziel-App Status (`Mapping AN/AUS`, Anzahl erkannter Mappings-Tastendrücke, abgeschlossene und abgebrochene Gesten) über das Bild. Nur Zähler, keine aufgezeichneten Tasten.
- Der Diagnosebildschirm funktioniert bewusst unabhängig vom Ziel-App-Filter. Die Spielfunktion bleibt weiterhin auf die gewählte App begrenzt.

**Troubleshooting**: Wenn der Diagnose-Touch erfolgreich ist, aber im Spiel keine Aktion stattfindet, Ziel-App, Mapping-Schalter und Bildschirmpositionen kontrollieren. Wenn die Diagnoseanzeige im Spiel fehlt, erkennt der Dienst möglicherweise die Ziel-App nicht oder das System blockiert Overlay-Darstellung. Wenn `Tasten: 0` stehen bleibt, erhält der Dienst keine Tasten (Dienst/Android-Tastenfilter prüfen). Wenn `Gesten` steigen, aber nichts passiert, kommen Simulation und/oder die gewählten Positionen nicht bei der Spielsteuerung an. Manche Spiele lehnen künstliche Eingaben ab.

## New in v0.2: Test laboratory / Gesten-Testlabor

Open **🎮 Touch-Gesten live testen** from the main screen. The interactive laboratory displays all configured mappings as dots on a scaled touchscreen preview:

- Press **W/A/S/D** to move the visual joystick, including diagonal movement.
- Press and hold mapped keys, such as **SPACE**, **SHIFT** or **E**, to highlight the associated touch target.
- Release a key to see a brief fading ring at the last target.
- Move the mouse to see its cursor. Left-click and drag to visualize a touch path; right/middle-click highlight their configured targets.
- Touch the test area with a finger to visualize physical screen touches too.
- The last key/button event and all held mapped inputs are shown above the preview.

**Important:** This is a **visual preview of the mapping**, not a measurement of actual AccessibilityService touch injection and not a virtual hardware gamepad. It does not send system-wide touch gestures from the laboratory. No target app, active mapping or AccessibilityService permission is needed for this test. As with the original mapping engine, in-game support varies by device and game.

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
3. Open **🎮 Touch-Gesten live testen** to verify your physical input and preview your configured buttons. No accessibility permission is needed in this preview.
4. Enable **TouchBridge Eingabesteuerung** in Android Settings → Accessibility / Bedienungshilfen → Downloaded apps / Installierte Apps.
5. If sideloaded app accessibility services are restricted, open Android Settings → App Info → TouchBridge → menu → **Allow restricted settings** (wording differs across Android versions; only do this for APKs you trust).
6. Choose your target game/application in TouchBridge.
7. Adjust the button positions (touch layout differs among games and screen orientations). “Im Spiel wählen” opens the game and captures the next screen tap.
8. Enable mapping. On Android 14+ optionally enable “Maus abfangen”.
9. Switch to the game and test the mapped keyboard keys.

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
- `app/src/main/java/de/touchbridge/app/TestActivity.java` — Test lab with visual key/mouse/touch preview, no real gesture injection
- `app/src/main/java/de/touchbridge/app/DiagnosticActivity.java` — actual AccessibilityService touch injection test
- `app/src/main/java/de/touchbridge/app/MappingStore.java` — Local configuration and defaults
- `app/src/main/java/de/touchbridge/app/MapperService.java` — Accessibility input capture + touch gesture engine + picker/cursor overlays
- `.github/workflows/build-apk.yml` — Debug APK build pipeline

## Privacy

TouchBridge does not read text fields, send data over the network, take screenshots, record key histories, or request contacts/storage/location. Android's accessibility permission is still powerful, so always review the source and turn off the service when you are finished.

## Installing an updated debug APK

An Android debug APK built on an ephemeral GitHub Actions runner may get a different debug signing key than a previous build. If Android says an existing installed APK is incompatible with an update, uninstall the old TouchBridge and install the new build. **Uninstalling deletes app data and custom mapping preferences.** For upgrades that preserve app data, configure a persistent signing keystore in GitHub Actions. Never commit release signing secrets to source control.
