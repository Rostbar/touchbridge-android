package de.touchbridge.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A local-only accessibility key-to-touch proof of concept.
 * No clipboard, screenshots, recording, storage of individual presses, or network access.
 */
public class MapperService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {
    // A held finger must survive between successive AccessibilityService gestures.
    // Short 95 ms chunks produced too many boundaries in games such as Fortnite.
    private static final int DEFAULT_HOLD_SEGMENT_MS = 95;
    private static final int MIN_HOLD_SEGMENT_MS = 60;
    private static final int MAX_HOLD_SEGMENT_MS = 180;
    private static final int MOVE_SEGMENT_MS = 28; // Fast follow-up for direction changes.
    private static final int RELEASE_SEGMENT_MS = 8; // Do not hold released keys for a full frame.
    public static final String HOLD_SEGMENT_MS = "touchbridge_hold_segment_ms_v7";
    private static final int TAP_MS = 65;
    public static final String RESET_NONCE = "touchbridge_reset_nonce";
    public static final String TAP_MODE = "touchbridge_tap_mode";
    public static final String DIAG_NONCE = "touchbridge_test_nonce";
    public static final String DIAG_X = "touchbridge_test_x";
    public static final String DIAG_Y = "touchbridge_test_y";
    public static final String DIAG_RESULT = "touchbridge_test_result";
    public static final String SHOW_DIAG = "touchbridge_show_diagnostics";
    private static volatile boolean connected = false;
    public static boolean isConnected() { return connected; }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private String foregroundPackage = "";
    private final Set<Integer> heldKeys = new HashSet<>();
    private final Map<String, PointF> wanted = new HashMap<>();
    // One-shot taps: their StrokeDescription ends automatically after TAP_MS.
    // They never require the keyboard ACTION_UP event to release the virtual finger.
    private final Map<String, PointF> oneShotTouches = new HashMap<>();
    private Map<String, GestureDescription.StrokeDescription> continued = new HashMap<>();
    private Map<String, PointF> lastPositions = new HashMap<>();
    private boolean busy = false;
    private boolean frameScheduled = false;
    private final Runnable frameRunnable = () -> { frameScheduled = false; sendFrame(); };
    private boolean running = false;
    private boolean lastTapMode = false;
    private int hookedMouseSources = -1;
    private WindowManager windowManager;
    private View selectionOverlay;
    private View cursorOverlay;
    private TextView diagnosticsOverlay;
    private int keyCount = 0;
    private int keyUpCount = 0;
    private int joystickStartCount = 0;
    private int gestureCount = 0;
    private int gestureErrors = 0;
    private WindowManager.LayoutParams cursorParams;
    private float cursorX = -1, cursorY = -1;
    private boolean leftMouse = false, rightMouse = false, middleMouse = false;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        prefs = MappingStore.prefs(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            setServiceInfo(info);
        }
        refreshForeground(null);
        reconfigure();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        final int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            refreshForeground(event);
        }
    }

    /**
     * Detect the focused *application* window, not the overlay or an arbitrary
     * accessibility event producer. Some games expose window transitions only
     * as TYPE_WINDOWS_CHANGED. Null means unknown, not "wrong app".
     */
    private String foregroundFromWindows() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return null;
            AccessibilityWindowInfo candidate = null;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if (window.isFocused()) { candidate = window; break; }
                if (candidate == null && window.isActive()) candidate = window;
            }
            if (candidate == null) return null;
            AccessibilityNodeInfo root = candidate.getRoot();
            if (root == null || root.getPackageName() == null) return null;
            String pkg = root.getPackageName().toString();
            return pkg.isEmpty() ? null : pkg;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void refreshForeground(AccessibilityEvent event) {
        String next = foregroundFromWindows();
        if (next == null && event != null
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null) {
            next = event.getPackageName().toString();
        }
        if (next != null && !next.equals(foregroundPackage)) {
            foregroundPackage = next;
            reconfigure();
        }
    }

    @Override public void onInterrupt() { stopTouches(); }

    @Override public void onDestroy() {
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        connected = false;
        stopTouches();
        hideSelection(); hideCursor(); hideDiagnostics();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (DIAG_NONCE.equals(key)) {
            handler.post(this::sendDiagnosticTap);
        } else if (RESET_NONCE.equals(key)) {
            handler.post(this::stopTouches);
        } else if (!DIAG_RESULT.equals(key) && !DIAG_X.equals(key) && !DIAG_Y.equals(key)) {
            handler.post(this::reconfigure);
        }
    }

    /**
     * One actual system-generated touch. The diagnostic screen measures whether its
     * target VIEW receives this tap; an "onCompleted" callback alone is not proof.
     */
    private void sendDiagnosticTap() {
        if (prefs == null) return;
        float x = prefs.getFloat(DIAG_X, -1);
        float y = prefs.getFloat(DIAG_Y, -1);
        Point screen = displaySize();
        if (x < 0 || y < 0 || x >= screen.x || y >= screen.y) {
            prefs.edit().putString(DIAG_RESULT, "Ungültige Bildschirmposition").apply();
            return;
        }
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 90))
                .build();
            boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    prefs.edit().putString(DIAG_RESULT, "Geste an Android gesendet").apply();
                }
                @Override public void onCancelled(GestureDescription g) {
                    prefs.edit().putString(DIAG_RESULT, "Geste von Android abgebrochen").apply();
                }
            }, handler);
            if (!accepted) {
                prefs.edit().putString(DIAG_RESULT, "Android hat dispatchGesture abgelehnt").apply();
            }
        } catch (Exception error) {
            prefs.edit().putString(DIAG_RESULT,
                "Fehler: " + error.getClass().getSimpleName()).apply();
        }
    }

    private boolean isTargetForeground() {
        if (prefs == null) return false;
        String target = prefs.getString(MappingStore.TARGET, "");
        return target != null && !target.isEmpty() && target.equals(foregroundPackage);
    }

    private void reconfigure() {
        if (prefs == null) return;
        boolean shouldRun = prefs.getBoolean(MappingStore.ENABLED, false) && isTargetForeground();
        boolean tapMode = prefs.getBoolean(TAP_MODE, false);
        if (lastTapMode != tapMode) {
            stopTouches();
            lastTapMode = tapMode;
        }
        if (running != shouldRun) {
            running = shouldRun;
            if (!running) stopTouches();
        }
        String picker = prefs.getString(MappingStore.PICK, "");
        boolean picking = picker != null && !picker.isEmpty() && isTargetForeground();
        if (picking) showSelection(picker);
        else hideSelection();

        // Capturing a mouse consumes its regular system events. Never capture it outside the chosen app.
        int sources = (running && !picking && prefs.getBoolean(MappingStore.MOUSE, false)
            && Build.VERSION.SDK_INT >= 34) ? InputDevice.SOURCE_MOUSE : 0;
        if (Build.VERSION.SDK_INT >= 34 && hookedMouseSources != sources) {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.setMotionEventSources(sources);
                setServiceInfo(info);
                hookedMouseSources = sources;
            }
        }
        if (sources == 0) {
            hideCursor();
            if (leftMouse || rightMouse || middleMouse) {
                leftMouse = rightMouse = middleMouse = false;
                rebuildWanted();
            }
        } else showCursor();
        if (running) rebuildWanted();
        // Diagnostics are deliberately visible even when target detection fails.
        // User can turn the overlay off in the main activity after testing.
        if (prefs.getBoolean(SHOW_DIAG, false)) showDiagnostics();
        else hideDiagnostics();
        updateDiagnostics();
    }

    private void stopTouches() {
        heldKeys.clear();
        leftMouse = rightMouse = middleMouse = false;
        wanted.clear();
        oneShotTouches.clear();
        scheduleFrame();
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        // A window-change event may have been missed by an immersive game.
        refreshForeground(null);
        int code = event.getKeyCode();
        boolean mapped = isDirection(code) || heldKeys.contains(code);
        if (!mapped) {
            for (MappingStore.Binding binding : MappingStore.read(this)) {
                if (MappingStore.keyCode(binding.key) == code && code != KeyEvent.KEYCODE_UNKNOWN) {
                    mapped = true; break;
                }
            }
        }
        if (!mapped) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            keyCount++;
            updateDiagnostics();
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            keyUpCount++;
            updateDiagnostics();
        }
        // Release an already tracked held key even if the foreground changed while pressed.
        if (event.getAction() == KeyEvent.ACTION_UP && heldKeys.remove(code)) {
            rebuildWanted();
        }
        // Count but never consume a physical key outside the enabled target app.
        if (!running || selectionOverlay != null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (prefs.getBoolean(TAP_MODE, false) && !isDirection(code)) {
                if (event.getRepeatCount() == 0) enqueueShortTap(code);
            } else if (heldKeys.add(code)) {
                // Also accept a repeat-down if the first down was not delivered.
                rebuildWanted();
            }
        }
        return true;
    }

    /** GD-style button: a finite tap rather than a potentially endless chained hold. */
    private void enqueueShortTap(int code) {
        if (!running || selectionOverlay != null) return;
        Point size = displaySize();
        for (MappingStore.Binding binding : MappingStore.read(this)) {
            if (MappingStore.STICK.equals(binding.key) || binding.key.startsWith("MOUSE_")) continue;
            if (MappingStore.keyCode(binding.key) == code) {
                oneShotTouches.put("TAP_" + code,
                    new PointF(binding.x * size.x, binding.y * size.y));
                scheduleFrame();
                return;
            }
        }
    }

    private static boolean isDirection(int k) {
        return k == KeyEvent.KEYCODE_W || k == KeyEvent.KEYCODE_A
            || k == KeyEvent.KEYCODE_S || k == KeyEvent.KEYCODE_D;
    }
    private boolean down(int key) { return heldKeys.contains(key); }

    private int holdSegmentMs() {
        return prefs == null ? DEFAULT_HOLD_SEGMENT_MS : Math.max(MIN_HOLD_SEGMENT_MS,
            Math.min(MAX_HOLD_SEGMENT_MS, prefs.getInt(HOLD_SEGMENT_MS, DEFAULT_HOLD_SEGMENT_MS)));
    }

    private Point displaySize() {
        Point p = new Point();
        windowManager.getDefaultDisplay().getRealSize(p);
        return p;
    }

    private void rebuildWanted() {
        wanted.clear();
        if (!running || selectionOverlay != null) { scheduleFrame(); return; }
        Point size = displaySize();
        MappingStore.Binding stick = MappingStore.find(this, MappingStore.STICK);
        if (stick != null) {
            int dx = (down(KeyEvent.KEYCODE_D) ? 1 : 0) - (down(KeyEvent.KEYCODE_A) ? 1 : 0);
            int dy = (down(KeyEvent.KEYCODE_S) ? 1 : 0) - (down(KeyEvent.KEYCODE_W) ? 1 : 0);
            if (dx != 0 || dy != 0) {
                float len = (float)Math.hypot(dx, dy);
                float radius = Math.min(size.x, size.y) * .095f;
                float x = stick.x * size.x + dx / len * radius;
                float y = stick.y * size.y + dy / len * radius;
                wanted.put(MappingStore.STICK, new PointF(x, y));
            }
        }
        for (MappingStore.Binding b : MappingStore.read(this)) {
            if (MappingStore.STICK.equals(b.key) || b.key.startsWith("MOUSE_")) continue;
            if (prefs.getBoolean(TAP_MODE, false)) continue; // Button taps run once on key DOWN.
            int keyCode = MappingStore.keyCode(b.key);
            if (keyCode != KeyEvent.KEYCODE_UNKNOWN && heldKeys.contains(keyCode)) {
                wanted.put("KEY_" + keyCode, new PointF(b.x * size.x, b.y * size.y));
            }
        }
        if (leftMouse) wanted.put("MOUSE_LEFT", new PointF(cursorX, cursorY));
        MappingStore.Binding right = MappingStore.find(this, MappingStore.MOUSE_RIGHT);
        if (rightMouse && right != null) wanted.put(MappingStore.MOUSE_RIGHT, new PointF(right.x * size.x, right.y * size.y));
        MappingStore.Binding middle = MappingStore.find(this, MappingStore.MOUSE_MIDDLE);
        if (middleMouse && middle != null) wanted.put(MappingStore.MOUSE_MIDDLE, new PointF(middle.x * size.x, middle.y * size.y));
        scheduleFrame();
    }

    /**
     * One in-flight gesture at a time. Calling dispatchGesture while one is in
     * progress cancels the current fingers, so new keys wait until the callback.
     * With short segments, the worst-case input/release latency is bounded by
     * the segment duration (not the previous 260-500 ms).
     */
    private void scheduleFrame() {
        if (busy || frameScheduled) return;
        frameScheduled = true;
        handler.post(frameRunnable);
    }

    private void sendFrame() {
        if (busy) return;
        if (continued.isEmpty() && wanted.isEmpty() && oneShotTouches.isEmpty()) return;

        // Release, continue and press all fingers in ONE gesture frame.
        // Previously, long segments caused released keys to remain down and
        // newly pressed keys to wait hundreds of milliseconds.
        List<GestureFramePlanner.Step> steps = GestureFramePlanner.plan(
            continued.keySet(), wanted.keySet(), oneShotTouches.keySet(),
            GestureDescription.getMaxStrokeCount());
        if (steps.isEmpty()) return;

        GestureDescription.Builder builder = new GestureDescription.Builder();
        Map<String, GestureDescription.StrokeDescription> nextStrokes = new HashMap<>();
        Map<String, PointF> nextPositions = new HashMap<>();
        Set<String> dispatchedTaps = new HashSet<>();
        try {
            for (GestureFramePlanner.Step step : steps) {
                String id = step.id;
                boolean release = step.kind == GestureFramePlanner.Kind.RELEASE;
                boolean tap = step.kind == GestureFramePlanner.Kind.TAP;
                boolean staying = !release && !tap;

                PointF old = lastPositions.get(id);
                PointF destination = tap ? oneShotTouches.get(id)
                    : release ? old : wanted.get(id);
                if (destination == null) continue;
                PointF start = old == null ? destination : old;
                if (id.equals(MappingStore.STICK) && old == null && staying) {
                    MappingStore.Binding binding = MappingStore.find(this, MappingStore.STICK);
                    if (binding != null) {
                        Point screen = displaySize();
                        start = new PointF(binding.x * screen.x, binding.y * screen.y);
                    }
                }

                Path path = new Path();
                path.moveTo(start.x, start.y);
                boolean moving = Math.abs(start.x - destination.x) > .5f
                    || Math.abs(start.y - destination.y) > .5f;
                if (moving) path.lineTo(destination.x, destination.y);

                // Releasing a key ends in 8 ms, even when other fingers hold.
                // Directions update quickly, while stationary touches continue
                // for a short, configurable period (default 95 ms).
                int duration = release ? RELEASE_SEGMENT_MS : tap ? TAP_MS
                    : moving ? MOVE_SEGMENT_MS : holdSegmentMs();
                GestureDescription.StrokeDescription previous = continued.get(id);
                GestureDescription.StrokeDescription stroke = previous == null
                    ? new GestureDescription.StrokeDescription(path, 0, duration, staying)
                    : previous.continueStroke(path, 0, duration, staying);
                builder.addStroke(stroke);
                if (tap) dispatchedTaps.add(id);
                if (staying) {
                    nextStrokes.put(id, stroke);
                    nextPositions.put(id, new PointF(destination.x, destination.y));
                }
                if (id.equals(MappingStore.STICK) && previous == null && staying)
                    joystickStartCount++;
            }

            busy = true;
            boolean accepted = dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gesture) {
                    gestureCount++;
                    busy = false;
                    continued = nextStrokes;
                    lastPositions = nextPositions;
                    updateDiagnostics();
                    if (!continued.isEmpty() || !wanted.isEmpty() || !oneShotTouches.isEmpty())
                        scheduleFrame();
                }
                @Override public void onCancelled(GestureDescription gesture) {
                    gestureErrors++;
                    busy = false;
                    continued.clear();
                    lastPositions.clear();
                    updateDiagnostics();
                    if (!wanted.isEmpty() || !oneShotTouches.isEmpty())
                        handler.postDelayed(MapperService.this::scheduleFrame, 75);
                }
            }, handler);
            if (accepted) {
                for (String tap : dispatchedTaps) oneShotTouches.remove(tap);
            } else {
                busy = false;
                continued.clear();
                lastPositions.clear();
                gestureErrors++;
                updateDiagnostics();
            }
        } catch (Exception ex) {
            busy = false;
            continued.clear();
            lastPositions.clear();
            gestureErrors++;
            updateDiagnostics();
        }
    }

    @Override public void onMotionEvent(MotionEvent event) {
        if (Build.VERSION.SDK_INT < 34 || !running || selectionOverlay != null) return;
        if (!prefs.getBoolean(MappingStore.MOUSE, false) || !event.isFromSource(InputDevice.SOURCE_MOUSE)) return;
        Point size = displaySize();
        float rx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
        float ry = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
        if (cursorX < 0 || cursorY < 0) { cursorX = size.x * .5f; cursorY = size.y * .5f; }
        if (rx != 0 || ry != 0) {
            cursorX = Math.max(1, Math.min(size.x - 2, cursorX + rx));
            cursorY = Math.max(1, Math.min(size.y - 2, cursorY + ry));
        } else if (event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE ||
                   event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            cursorX = Math.max(1, Math.min(size.x - 2, event.getRawX()));
            cursorY = Math.max(1, Math.min(size.y - 2, event.getRawY()));
        }
        moveCursor();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_BUTTON_PRESS:
                buttonChange(event.getActionButton(), true);
                break;
            case MotionEvent.ACTION_BUTTON_RELEASE:
                buttonChange(event.getActionButton(), false);
                break;
            case MotionEvent.ACTION_DOWN:
                if ((event.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0)
                    buttonChange(MotionEvent.BUTTON_PRIMARY, true);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                leftMouse = rightMouse = middleMouse = false;
                rebuildWanted();
                break;
            case MotionEvent.ACTION_MOVE:
                if (leftMouse) rebuildWanted();
                break;
            default:
                break;
        }
    }

    private void buttonChange(int button, boolean pressed) {
        if ((button & MotionEvent.BUTTON_PRIMARY) != 0) leftMouse = pressed;
        if ((button & MotionEvent.BUTTON_SECONDARY) != 0) rightMouse = pressed;
        if ((button & MotionEvent.BUTTON_TERTIARY) != 0) middleMouse = pressed;
        rebuildWanted();
    }

    private void showSelection(String key) {
        if (selectionOverlay != null) return;
        hideCursor();
        stopTouches();
        FrameLayout panel = new FrameLayout(this);
        panel.setBackgroundColor(0x3C06111F);
        TextView text = new TextView(this);
        text.setText("TOUCHBRIDGE\nTippe auf die Position für " + MappingStore.readable(key) + "\nMit Zurück abbrechen");
        text.setTextColor(Color.WHITE); text.setTextSize(18); text.setGravity(Gravity.CENTER);
        text.setPadding(20, 25, 20, 25); text.setBackgroundColor(0xD915233B);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        panel.addView(text, labelParams);
        panel.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                Point size = displaySize();
                String selected = prefs.getString(MappingStore.PICK, "");
                if (selected != null && !selected.isEmpty()) {
                    MappingStore.upsert(this, selected,
                        MappingStore.clamp(e.getRawX() / size.x), MappingStore.clamp(e.getRawY() / size.y));
                    prefs.edit().remove(MappingStore.PICK).apply();
                    Toast.makeText(this, "Position gespeichert", Toast.LENGTH_SHORT).show();
                }
                hideSelection();
                reconfigure();
            }
            return true;
        });
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        try { windowManager.addView(panel, params); selectionOverlay = panel; }
        catch (Exception ignored) { selectionOverlay = null; }
    }

    private void hideSelection() {
        if (selectionOverlay == null || windowManager == null) return;
        try { windowManager.removeView(selectionOverlay); } catch (Exception ignored) {}
        selectionOverlay = null;
    }

    /** Noninteractive on-screen diagnostic (only over the selected target app). */
    private void showDiagnostics() {
        if (diagnosticsOverlay != null || windowManager == null) return;
        TextView view = new TextView(this);
        view.setTextSize(13);
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(0xD91B354A);
        view.setPadding(18, 14, 18, 14);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            -2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 12;
        params.y = 85;
        try {
            windowManager.addView(view, params);
            diagnosticsOverlay = view;
        } catch (Exception ignored) { diagnosticsOverlay = null; }
    }

    private void updateDiagnostics() {
        if (diagnosticsOverlay == null) return;
        String target = prefs == null ? "" : prefs.getString(MappingStore.TARGET, "");
        boolean detected = isTargetForeground();
        diagnosticsOverlay.setText("TOUCHBRIDGE v0.7 · Service aktiv\n" +
            "Ziel erkannt: " + (detected ? "JA" : "NEIN") +
            " | Mapping: " + (running ? "AN" : "AUS") +
            " | " + (prefs.getBoolean(TAP_MODE, false) ? "TAP" : "HALTEN") + "\n" +
            "Joystick: " + (wanted.containsKey(MappingStore.STICK) ? "GEDRUECKT" : "FREI") +
            " | Segment: " + holdSegmentMs() + " ms" +
            " | Starts: " + joystickStartCount + "\n" +
            "Aktives Paket: " + (foregroundPackage.isEmpty() ? "unbekannt" : foregroundPackage) + "\n" +
            "Zielpaket: " + (target == null || target.isEmpty() ? "nicht gewählt" : target) + "\n" +
            "Aktive Tasten: " + heldKeys.size() + " | DOWN/UP: " + keyCount + "/" + keyUpCount +
            " | Gesten: " + gestureCount +
            " | Fehler: " + gestureErrors);
    }

    private void hideDiagnostics() {
        if (diagnosticsOverlay == null || windowManager == null) return;
        try { windowManager.removeView(diagnosticsOverlay); } catch (Exception ignored) {}
        diagnosticsOverlay = null;
    }

    private void showCursor() {
        if (cursorOverlay != null || windowManager == null) return;
        View cursor = new View(this) {
            final Paint paint = new Paint(3);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                paint.setColor(Color.WHITE);
                paint.setStrokeWidth(2.5f);
                paint.setStyle(Paint.Style.STROKE);
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                canvas.drawCircle(cx, cy, 7, paint);
                canvas.drawLine(cx - 15, cy, cx - 9, cy, paint);
                canvas.drawLine(cx + 9, cy, cx + 15, cy, paint);
                canvas.drawLine(cx, cy - 15, cx, cy - 9, paint);
                canvas.drawLine(cx, cy + 9, cx, cy + 15, paint);
            }
        };
        cursorParams = new WindowManager.LayoutParams(48, 48,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT);
        cursorParams.gravity = Gravity.TOP | Gravity.LEFT;
        try { windowManager.addView(cursor, cursorParams); cursorOverlay = cursor; moveCursor(); }
        catch (Exception ignored) { cursorOverlay = null; }
    }

    private void moveCursor() {
        if (cursorOverlay == null || windowManager == null) return;
        Point size = displaySize();
        if (cursorX < 0) cursorX = size.x / 2f;
        if (cursorY < 0) cursorY = size.y / 2f;
        cursorParams.x = (int)cursorX - 24;
        cursorParams.y = (int)cursorY - 24;
        try { windowManager.updateViewLayout(cursorOverlay, cursorParams); }
        catch (Exception ignored) {}
    }

    private void hideCursor() {
        if (cursorOverlay == null || windowManager == null) return;
        try { windowManager.removeView(cursorOverlay); } catch (Exception ignored) {}
        cursorOverlay = null;
    }
}
