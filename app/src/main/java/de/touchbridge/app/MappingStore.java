package de.touchbridge.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MappingStore {
    private MappingStore() {}
    public static final String PREF = "mapper";
    public static final String ENABLED = "enabled";
    public static final String MOUSE = "mouse_capture";
    public static final String TARGET = "target_package";
    public static final String PICK = "pending_pick";
    public static final String CONFIG = "bindings";
    public static final String STICK = "STICK";
    public static final String MOUSE_RIGHT = "MOUSE_RIGHT";
    public static final String MOUSE_MIDDLE = "MOUSE_MIDDLE";

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static class Binding {
        public String key;
        public float x, y;
        public Binding(String key, float x, float y) {
            this.key = key;
            this.x = x;
            this.y = y;
        }
    }

    public static List<Binding> read(Context c) {
        String raw = prefs(c).getString(CONFIG, "");
        if (raw == null || raw.isEmpty()) return defaults();
        try {
            JSONArray arr = new JSONArray(raw);
            List<Binding> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new Binding(obj.getString("key"),
                    clamp((float)obj.getDouble("x")), clamp((float)obj.getDouble("y"))));
            }
            return list;
        } catch (Exception ex) { return defaults(); }
    }

    public static void write(Context c, List<Binding> bindings) {
        JSONArray arr = new JSONArray();
        for (Binding b : bindings) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("key", b.key);
                obj.put("x", b.x);
                obj.put("y", b.y);
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        prefs(c).edit().putString(CONFIG, arr.toString()).apply();
    }

    public static void upsert(Context c, String key, float x, float y) {
        List<Binding> list = read(c);
        for (Binding b : list) {
            if (b.key.equals(key)) {
                b.x = clamp(x); b.y = clamp(y);
                write(c, list);
                return;
            }
        }
        list.add(new Binding(key, clamp(x), clamp(y)));
        write(c, list);
    }

    public static void remove(Context c, String key) {
        List<Binding> list = read(c);
        list.removeIf(b -> b.key.equals(key));
        write(c, list);
    }

    public static Binding find(Context c, String key) {
        for (Binding b : read(c)) if (b.key.equals(key)) return b;
        return null;
    }

    public static List<Binding> defaults() {
        List<Binding> list = new ArrayList<>();
        list.add(new Binding(STICK, .16f, .75f));
        list.add(new Binding("SPACE", .88f, .77f));
        list.add(new Binding("SHIFT_LEFT", .79f, .90f));
        list.add(new Binding("E", .73f, .70f));
        list.add(new Binding("R", .93f, .54f));
        list.add(new Binding(MOUSE_RIGHT, .89f, .45f));
        list.add(new Binding(MOUSE_MIDDLE, .77f, .44f));
        return list;
    }

    public static float clamp(float v) {
        return Math.max(0.025f, Math.min(.975f, v));
    }

    public static int keyCode(String name) {
        if (name == null) return KeyEvent.KEYCODE_UNKNOWN;
        String n = name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (n.equals("CTRL")) n = "CTRL_LEFT";
        if (n.equals("SHIFT")) n = "SHIFT_LEFT";
        if (n.equals("ALT")) n = "ALT_LEFT";
        return KeyEvent.keyCodeFromString("KEYCODE_" + n);
    }

    public static String readable(String name) {
        if (STICK.equals(name)) return "WASD: Joystick-Mitte";
        if (MOUSE_RIGHT.equals(name)) return "Rechte Maustaste";
        if (MOUSE_MIDDLE.equals(name)) return "Mittlere Maustaste";
        return "Taste " + name;
    }
}
