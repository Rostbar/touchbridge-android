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
    private static final int SEGMENT_MS = 95;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private String foregroundPackage = "";
    private final Set<Integer> heldKeys = new HashSet<>();
    private final Map<String, PointF> wanted = new HashMap<>();
    private Map<String, GestureDescription.StrokeDescription> continued = new HashMap<>();
    private Map<String, PointF> lastPositions = new HashMap<>();
    private boolean busy = false;
    private boolean running = false;
    private int hookedMouseSources = -1;
    private WindowManager windowManager;
    private View selectionOverlay;
    private View cursorOverlay;
    private WindowManager.LayoutParams cursorParams;
    private float cursorX = -1, cursorY = -1;
    private boolean leftMouse = false, rightMouse = false, middleMouse = false;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = MappingStore.prefs(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        reconfigure();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            String next = event.getPackageName().toString();
            if (!next.equals(foregroundPackage)) {
                foregroundPackage = next;
                reconfigure();
            }
        }
    }

    @Override public void onInterrupt() { stopTouches(); }

    @Override public void onDestroy() {
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        hideSelection(); hideCursor();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        handler.post(this::reconfigure);
    }

    private boolean isTargetForeground() {
        if (prefs == null) return false;
        String target = prefs.getString(MappingStore.TARGET, "");
        return target != null && !target.isEmpty() && target.equals(foregroundPackage);
    }

    private void reconfigure() {
        if (prefs == null) return;
        boolean shouldRun = prefs.getBoolean(MappingStore.ENABLED, false) && isTargetForeground();
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
    }

    private void stopTouches() {
        heldKeys.clear();
        leftMouse = rightMouse = middleMouse = false;
        wanted.clear();
        scheduleFrame();
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (!running || selectionOverlay != null) return false;
        int code = event.getKeyCode();
        boolean mapped = isDirection(code);
        if (!mapped) {
            for (MappingStore.Binding binding : MappingStore.read(this)) {
                if (MappingStore.keyCode(binding.key) == code && code != KeyEvent.KEYCODE_UNKNOWN) {
                    mapped = true; break;
                }
            }
        }
        if (!mapped) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0 && heldKeys.add(code)) rebuildWanted();
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            if (heldKeys.remove(code)) rebuildWanted();
        }
        return true;
    }

    private static boolean isDirection(int k) {
        return k == KeyEvent.KEYCODE_W || k == KeyEvent.KEYCODE_A
            || k == KeyEvent.KEYCODE_S || k == KeyEvent.KEYCODE_D;
    }
    private boolean down(int key) { return heldKeys.contains(key); }

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

    private void scheduleFrame() {
        if (!busy) handler.post(this::sendFrame);
    }

    private void sendFrame() {
        if (busy || (continued.isEmpty() && wanted.isEmpty())) return;
        // dispatchGesture allows at most 10 simultaneous pointer paths on normal devices.
        List<String> ids = new ArrayList<>(wanted.keySet());
        ids.sort(String::compareTo);
        Map<String, PointF> snapshot = new HashMap<>();
        for (String id : ids) {
            if (snapshot.size() < GestureDescription.getMaxStrokeCount())
                snapshot.put(id, wanted.get(id));
        }
        GestureDescription.Builder gesture = new GestureDescription.Builder();
        Map<String, GestureDescription.StrokeDescription> nextStrokes = new HashMap<>();
        Map<String, PointF> nextPositions = new HashMap<>();
        Set<String> keys = new HashSet<>(continued.keySet());
        keys.addAll(snapshot.keySet());
        try {
            for (String id : keys) {
                boolean staying = snapshot.containsKey(id);
                PointF oldPosition = lastPositions.get(id);
                PointF destination = staying ? snapshot.get(id) : oldPosition;
                if (destination == null) continue;
                Path path = new Path();
                PointF start = oldPosition != null ? oldPosition : destination;
                if (id.equals(MappingStore.STICK) && oldPosition == null) {
                    MappingStore.Binding stick = MappingStore.find(this, MappingStore.STICK);
                    Point screen = displaySize();
                    if (stick != null) start = new PointF(stick.x * screen.x, stick.y * screen.y);
                }
                path.moveTo(start.x, start.y);
                if (Math.abs(start.x - destination.x) > .01f || Math.abs(start.y - destination.y) > .01f)
                    path.lineTo(destination.x, destination.y);
                GestureDescription.StrokeDescription previous = continued.get(id);
                GestureDescription.StrokeDescription stroke = previous == null
                    ? new GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, staying)
                    : previous.continueStroke(path, 0, SEGMENT_MS, staying);
                gesture.addStroke(stroke);
                if (staying) {
                    nextStrokes.put(id, stroke);
                    nextPositions.put(id, new PointF(destination.x, destination.y));
                }
            }
            if (keys.isEmpty()) return;
            busy = true;
            boolean accepted = dispatchGesture(gesture.build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    busy = false;
                    continued = nextStrokes;
                    lastPositions = nextPositions;
                    if (!continued.isEmpty() || !wanted.isEmpty()) handler.post(MapperService.this::sendFrame);
                }
                @Override public void onCancelled(GestureDescription g) {
                    busy = false;
                    continued.clear(); lastPositions.clear();
                    if (!wanted.isEmpty()) handler.postDelayed(MapperService.this::sendFrame, 70);
                }
            }, handler);
            if (!accepted) {
                busy = false;
                continued.clear(); lastPositions.clear();
            }
        } catch (Exception ex) {
            busy = false;
            continued.clear(); lastPositions.clear();
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
