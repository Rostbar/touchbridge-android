package de.touchbridge.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Local input/gesture VISUALIZER. This activity does not call dispatchGesture().
 * It mirrors the configured mapping so a user can test physical input safely,
 * without choosing a target game or enabling the accessibility service.
 */
public class TestActivity extends Activity {
    private static final int BG = Color.rgb(13, 19, 31);
    private static final int PANEL = Color.rgb(21, 32, 49);
    private static final int WHITE = Color.rgb(242, 247, 255);
    private static final int MUTED = Color.rgb(155, 174, 198);
    private static final int CYAN = Color.rgb(62, 219, 247);
    private static final int GREEN = Color.rgb(105, 245, 168);
    private static final long FLASH_MS = 380;

    private final Set<Integer> heldKeys = new HashSet<>();
    private final Map<String, Long> releasedAt = new HashMap<>();
    private List<MappingStore.Binding> bindings = new ArrayList<>();
    private boolean mouseLeft, mouseRight, mouseMiddle;
    private float mouseX = -1, mouseY = -1;
    private final List<PointF> mouseTrail = new ArrayList<>();
    private long trailVisibleUntil;
    private float fingerX = -1, fingerY = -1;
    private boolean fingerDown;
    private TextView status;
    private TextView active;
    private TestSurface surface;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        bindings = MappingStore.read(this);
        refresh();
        if (surface != null) surface.requestFocus();
    }

    @Override protected void onPause() {
        heldKeys.clear();
        mouseLeft = mouseRight = mouseMiddle = false;
        mouseTrail.clear();
        fingerDown = false;
        refresh();
        super.onPause();
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private float sp(float n) { return n * getResources().getDisplayMetrics().scaledDensity; }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private void buildUi() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(12), dp(8), dp(12), dp(12));
        layout.setBackgroundColor(BG);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("← Zurück");
        back.setAllCaps(false);
        back.setTextColor(WHITE);
        back.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(43, 62, 85)));
        back.setOnClickListener(v -> finish());
        heading.addView(back, new LinearLayout.LayoutParams(-2, dp(48)));

        TextView title = text("GESTEN-TESTLABOR", 18, WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(12), 0, 0, 0);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(50), 1));
        layout.addView(heading);

        TextView subtitle = text("Drücke WASD, SPACE, E oder deine eigenen Tasten. Maus bewegen und klicken. Die Punkte zeigen die zugeordneten Touch-Positionen.", 12, MUTED);
        subtitle.setPadding(dp(2), dp(4), dp(2), dp(8));
        layout.addView(subtitle);

        status = text("Letzte Eingabe: –", 14, CYAN);
        status.setPadding(dp(8), dp(8), dp(8), dp(3));
        layout.addView(status);

        active = text("Aktive Gesten: keine", 12, WHITE);
        active.setPadding(dp(8), dp(2), dp(8), dp(8));
        layout.addView(active);

        surface = new TestSurface();
        surface.setFocusable(true);
        surface.setFocusableInTouchMode(true);
        surface.setClickable(true);
        layout.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView note = text("VORSCHAU: Der Bereich visualisiert die Zuordnung, erzeugt aber keine echten System-Touch-Gesten. Keine Ziel-App oder Bedienungshilfe nötig.", 11, MUTED);
        note.setPadding(dp(4), dp(10), dp(4), dp(2));
        layout.addView(note);

        setContentView(layout);
        surface.requestFocus();
    }

    private static boolean isDirection(int k) {
        return k == KeyEvent.KEYCODE_W || k == KeyEvent.KEYCODE_A
            || k == KeyEvent.KEYCODE_S || k == KeyEvent.KEYCODE_D;
    }

    private boolean mapped(int code) {
        if (isDirection(code)) return true;
        for (MappingStore.Binding b : bindings) {
            if (MappingStore.STICK.equals(b.key) || b.key.startsWith("MOUSE_")) continue;
            if (MappingStore.keyCode(b.key) == code && code != KeyEvent.KEYCODE_UNKNOWN) return true;
        }
        return false;
    }

    private String keyName(int code) {
        String s = KeyEvent.keyCodeToString(code);
        return s.startsWith("KEYCODE_") ? s.substring(8) : s;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_POWER) return super.dispatchKeyEvent(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        if (!down && !up) return super.dispatchKeyEvent(event);
        if (down && event.getRepeatCount() > 0) return true;
        String name = keyName(code);
        boolean isMapped = mapped(code);
        if (down) {
            heldKeys.add(code);
        } else {
            heldKeys.remove(code);
            if (isMapped) releasedAt.put(isDirection(code) ? MappingStore.STICK : "KEY_" + code,
                SystemClock.uptimeMillis() + FLASH_MS);
        }
        status.setText("Letzte Eingabe: " + name + (down ? " ↓ gedrückt" : " ↑ losgelassen")
                + (isMapped ? "  ·  zugeordnet" : "  ·  unbelegt"));
        refresh();
        return true;
    }

    private void refresh() {
        if (active != null) {
            List<String> pressed = new ArrayList<>();
            for (int code : heldKeys) if (mapped(code)) pressed.add(keyName(code));
            java.util.Collections.sort(pressed);
            if (mouseLeft) pressed.add("Maus L");
            if (mouseRight) pressed.add("Maus R");
            if (mouseMiddle) pressed.add("Maus M");
            active.setText("Aktive Gesten: " + (pressed.isEmpty() ? "keine" : android.text.TextUtils.join(" · ", pressed)));
        }
        if (surface != null) surface.invalidate();
    }

    private MappingStore.Binding find(String key) {
        for (MappingStore.Binding b : bindings) if (key.equals(b.key)) return b;
        return null;
    }

    private boolean held(int key) { return heldKeys.contains(key); }

    private class TestSurface extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TestSurface() { super(TestActivity.this); }

        private float x(float nx) { return nx * getWidth(); }
        private float y(float ny) { return ny * getHeight(); }
        private float radius() { return Math.min(getWidth(), getHeight()) * .095f; }

        private void circle(Canvas c, float cx, float cy, float r, int color, boolean stroke, float sw) {
            paint.setColor(color);
            paint.setStyle(stroke ? Paint.Style.STROKE : Paint.Style.FILL);
            paint.setStrokeWidth(sw);
            c.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void line(Canvas c, float x1, float y1, float x2, float y2, int color, float width) {
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width);
            c.drawLine(x1, y1, x2, y2, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void caption(Canvas c, String label, float cx, float cy, int color, float size) {
            paint.setColor(color);
            paint.setTextSize(sp(size));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            c.drawText(label, cx, cy + (paint.descent() - paint.ascent()) * .30f, paint);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
        }

        private void dot(Canvas c, float cx, float cy, String label, boolean down, long release, int color) {
            float d = dp(down ? 24 : 20);
            circle(c, cx, cy, d, down ? 0x553EDBF7 : 0x442A405D, false, 0);
            circle(c, cx, cy, d, down ? color : 0xFF58708C, true, dp(2));
            circle(c, cx, cy, dp(12), down ? color : PANEL, false, 0);
            caption(c, label, cx, cy, down ? BG : WHITE, 9);
            if (release > SystemClock.uptimeMillis()) {
                float progress = (release - SystemClock.uptimeMillis()) / (float)FLASH_MS;
                int alpha = Math.round(200 * progress);
                circle(c, cx, cy, dp(25 + 12 * (1 - progress)),
                    (alpha << 24) | (color & 0x00FFFFFF), true, dp(2));
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(PANEL);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            paint.setColor(0x22384E70);
            paint.setStrokeWidth(dp(1));
            for (int i = 1; i < 10; i++) {
                canvas.drawLine(i * w / 10, 0, i * w / 10, h, paint);
                canvas.drawLine(0, i * h / 10, w, i * h / 10, paint);
            }
            paint.setColor(0xFF8FA5C1);
            paint.setTextSize(sp(11));
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("SIMULIERTER TOUCHSCREEN", dp(12), dp(22), paint);

            long now = SystemClock.uptimeMillis();
            boolean animate = false;
            MappingStore.Binding stick = find(MappingStore.STICK);
            if (stick != null) {
                float cx = x(stick.x), cy = y(stick.y);
                float rad = radius();
                circle(canvas, cx, cy, rad, 0x996A8AA8, true, dp(2));
                circle(canvas, cx, cy, rad * .47f, 0x443EDBF7, false, 0);
                int dx = (held(KeyEvent.KEYCODE_D) ? 1 : 0) - (held(KeyEvent.KEYCODE_A) ? 1 : 0);
                int dy = (held(KeyEvent.KEYCODE_S) ? 1 : 0) - (held(KeyEvent.KEYCODE_W) ? 1 : 0);
                boolean moving = dx != 0 || dy != 0;
                float tx = cx, ty = cy;
                if (moving) {
                    float len = (float)Math.hypot(dx, dy);
                    tx += dx / len * rad;
                    ty += dy / len * rad;
                    line(canvas, cx, cy, tx, ty, GREEN, dp(4));
                }
                long flash = releasedAt.containsKey(MappingStore.STICK) ? releasedAt.get(MappingStore.STICK) : 0;
                dot(canvas, tx, ty, "●", moving, flash, GREEN);
                caption(canvas, "WASD", cx, cy - rad - dp(15), MUTED, 11);
                if (flash > now) animate = true;
            }

            for (MappingStore.Binding b : bindings) {
                if (MappingStore.STICK.equals(b.key)) continue;
                float px = x(b.x), py = y(b.y);
                boolean down;
                String id;
                String name;
                if (MappingStore.MOUSE_RIGHT.equals(b.key)) {
                    down = mouseRight; id = "MOUSE_RIGHT"; name = "RMB";
                } else if (MappingStore.MOUSE_MIDDLE.equals(b.key)) {
                    down = mouseMiddle; id = "MOUSE_MIDDLE"; name = "MMB";
                } else {
                    int code = MappingStore.keyCode(b.key);
                    down = heldKeys.contains(code); id = "KEY_" + code; name = b.key;
                    if (name.endsWith("_LEFT")) name = name.substring(0, name.length() - 5);
                    if (name.length() > 7) name = name.substring(0, 7);
                }
                long flash = releasedAt.containsKey(id) ? releasedAt.get(id) : 0;
                dot(canvas, px, py, name, down, flash, CYAN);
                if (flash > now) animate = true;
            }

            if (mouseTrail.size() > 1 && (mouseLeft || trailVisibleUntil > now)) {
                float opacity = mouseLeft ? 1f : (trailVisibleUntil - now) / (float) FLASH_MS;
                int trailColor = (Math.round(Math.max(0, opacity) * 200) << 24) | (GREEN & 0x00FFFFFF);
                for (int i = 1; i < mouseTrail.size(); i++) {
                    PointF a = mouseTrail.get(i - 1);
                    PointF b = mouseTrail.get(i);
                    line(canvas, a.x, a.y, b.x, b.y, trailColor, dp(3));
                }
                if (!mouseLeft) animate = true;
            }

            if (mouseX >= 0 && mouseY >= 0) {
                float cx = Math.min(Math.max(mouseX, 0), w), cy = Math.min(Math.max(mouseY, 0), h);
                line(canvas, cx - dp(17), cy, cx - dp(6), cy, WHITE, dp(2));
                line(canvas, cx + dp(6), cy, cx + dp(17), cy, WHITE, dp(2));
                line(canvas, cx, cy - dp(17), cx, cy - dp(6), WHITE, dp(2));
                line(canvas, cx, cy + dp(6), cx, cy + dp(17), WHITE, dp(2));
                circle(canvas, cx, cy, dp(mouseLeft ? 17 : 7), mouseLeft ? GREEN : WHITE, true, dp(2));
                if (mouseLeft) {
                    circle(canvas, cx, cy, dp(9), 0x884DF8B2, false, 0);
                    caption(canvas, "LMB", cx, cy - dp(26), GREEN, 11);
                }
                long flash = releasedAt.containsKey("MOUSE_LEFT") ? releasedAt.get("MOUSE_LEFT") : 0;
                if (flash > now) {
                    float progress = (flash - now) / (float)FLASH_MS;
                    circle(canvas, cx, cy, dp(19 + 18 * (1 - progress)),
                        (Math.round(220 * progress) << 24) | (GREEN & 0x00FFFFFF), true, dp(2));
                    animate = true;
                }
            }

            if (fingerDown) {
                circle(canvas, fingerX, fingerY, dp(20), 0x6679AFFF, false, 0);
                circle(canvas, fingerX, fingerY, dp(20), WHITE, true, dp(2));
                caption(canvas, "FINGER", fingerX, fingerY - dp(30), WHITE, 11);
            }
            if (animate) postInvalidateDelayed(16);
        }

        private void updateCursor(MotionEvent e) {
            mouseX = e.getX(); mouseY = e.getY();
            if (mouseLeft && (mouseTrail.isEmpty()
                    || Math.hypot(mouseX - mouseTrail.get(mouseTrail.size() - 1).x,
                                  mouseY - mouseTrail.get(mouseTrail.size() - 1).y) > dp(3))) {
                mouseTrail.add(new PointF(mouseX, mouseY));
                if (mouseTrail.size() > 100) mouseTrail.remove(0);
            }
            invalidate();
        }

        private void updateButtons(MotionEvent e) {
            boolean l = (e.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0;
            boolean r = (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0;
            boolean m = (e.getButtonState() & MotionEvent.BUTTON_TERTIARY) != 0;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) l = true;
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                l = false; r = false; m = false;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS) {
                if (e.getActionButton() == MotionEvent.BUTTON_PRIMARY) l = true;
                if (e.getActionButton() == MotionEvent.BUTTON_SECONDARY) r = true;
                if (e.getActionButton() == MotionEvent.BUTTON_TERTIARY) m = true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_BUTTON_RELEASE) {
                if (e.getActionButton() == MotionEvent.BUTTON_PRIMARY) l = false;
                if (e.getActionButton() == MotionEvent.BUTTON_SECONDARY) r = false;
                if (e.getActionButton() == MotionEvent.BUTTON_TERTIARY) m = false;
            }
            if (!mouseLeft && l) {
                mouseTrail.clear();
                mouseTrail.add(new PointF(mouseX, mouseY));
            }
            if (mouseLeft && !l) {
                releasedAt.put("MOUSE_LEFT", SystemClock.uptimeMillis() + FLASH_MS);
                trailVisibleUntil = SystemClock.uptimeMillis() + FLASH_MS;
            }
            if (mouseRight && !r) releasedAt.put("MOUSE_RIGHT", SystemClock.uptimeMillis() + FLASH_MS);
            if (mouseMiddle && !m) releasedAt.put("MOUSE_MIDDLE", SystemClock.uptimeMillis() + FLASH_MS);
            if (l != mouseLeft || r != mouseRight || m != mouseMiddle) {
                if (l != mouseLeft) status.setText("Letzte Eingabe: Maustaste LINKS " + (l ? "↓" : "↑"));
                else if (r != mouseRight) status.setText("Letzte Eingabe: Maustaste RECHTS " + (r ? "↓" : "↑"));
                else status.setText("Letzte Eingabe: Maustaste MITTE " + (m ? "↓" : "↑"));
            }
            mouseLeft = l; mouseRight = r; mouseMiddle = m;
            refresh();
        }

        @Override public boolean onGenericMotionEvent(MotionEvent event) {
            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                updateCursor(event);
                updateButtons(event);
                return true;
            }
            return super.onGenericMotionEvent(event);
        }

        @Override public boolean onHoverEvent(MotionEvent event) {
            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                updateCursor(event);
                return true;
            }
            return super.onHoverEvent(event);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                updateCursor(event);
                updateButtons(event);
                return true;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    fingerDown = true;
                    fingerX = event.getX(); fingerY = event.getY();
                    status.setText("Letzte Eingabe: Bildschirm berührt");
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    fingerDown = false;
                    break;
                default: break;
            }
            invalidate();
            return true;
        }
    }
}
