package de.touchbridge.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(13, 19, 31);
    private static final int PANEL = Color.rgb(27, 36, 52);
    private static final int TEXT = Color.rgb(242, 245, 251);
    private static final int MUTED = Color.rgb(176, 190, 211);
    private LinearLayout body;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        draw();
    }
    @Override public void onResume() {
        super.onResume();
        draw();
    }

    private int dp(float value) { return (int)(getResources().getDisplayMetrics().density * value); }

    private TextView label(String s, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setPadding(dp(2), dp(5), dp(2), dp(5));
        return v;
    }
    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text); b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(42, 65, 104)));
        return b;
    }
    private void separator() {
        View v = new View(this); v.setBackgroundColor(Color.rgb(55, 68, 85));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
        p.setMargins(0, dp(8), 0, dp(8)); body.addView(v, p);
    }
    private void draw() {
        ScrollView scroller = new ScrollView(this); scroller.setFillViewport(true);
        scroller.setBackgroundColor(BG);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroller.addView(body);
        setContentView(scroller);

        body.addView(label("TOUCHBRIDGE", 27, TEXT, true));
        body.addView(label("Bluetooth-/USB-Maus und Tastatur → Touch-Gesten", 14, MUTED, false));
        body.addView(label("PROTOTYP · kein echter virtueller Gamepad-Treiber", 12, Color.rgb(255, 194, 112), true));
        separator();

        Button access = button("1. Bedienungshilfe aktivieren");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        body.addView(access);
        body.addView(label("Einstellungen → Bedienungshilfen → Installierte Apps → TouchBridge Eingabesteuerung", 12, MUTED, false));

        String pkg = MappingStore.prefs(this).getString(MappingStore.TARGET, "");
        String name = "Keine Ziel-App ausgewählt";
        if (pkg != null && !pkg.isEmpty()) {
            try { name = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
            catch (Exception ex) { name = pkg; }
        }
        Button target = button("2. Ziel-App: " + name);
        target.setOnClickListener(v -> chooseApp());
        body.addView(target);
        body.addView(label("Eingaben werden nur umgesetzt, wenn diese App im Vordergrund ist.", 12, MUTED, false));

        Switch enable = new Switch(this);
        enable.setText("3. Mapping aktivieren");
        enable.setTextColor(TEXT); enable.setTextSize(16);
        enable.setPadding(0, dp(15), 0, dp(15));
        enable.setChecked(MappingStore.prefs(this).getBoolean(MappingStore.ENABLED, false));
        enable.setOnCheckedChangeListener((view, checked) -> {
            String selected = MappingStore.prefs(this).getString(MappingStore.TARGET, "");
            if (checked && (selected == null || selected.isEmpty())) {
                enable.setChecked(false);
                Toast.makeText(this, "Bitte erst eine Ziel-App wählen", Toast.LENGTH_LONG).show();
                return;
            }
            MappingStore.prefs(this).edit().putBoolean(MappingStore.ENABLED, checked).apply();
        });
        body.addView(enable);

        Switch mouse = new Switch(this);
        mouse.setText("Maus abfangen (Android 14+)"); mouse.setTextColor(TEXT);
        mouse.setTextSize(16); mouse.setPadding(0, dp(8), 0, dp(8));
        mouse.setEnabled(Build.VERSION.SDK_INT >= 34);
        mouse.setChecked(MappingStore.prefs(this).getBoolean(MappingStore.MOUSE, false));
        mouse.setOnCheckedChangeListener((view, checked) ->
            MappingStore.prefs(this).edit().putBoolean(MappingStore.MOUSE, checked).apply());
        body.addView(mouse);
        body.addView(label("Die Mauserkennung wird nur in deiner Ziel-App abgefangen. Linksklick wird an der Cursorposition simuliert; Rechts-/Mittelklick an festen Punkten. Mausbewegung als 3D-Kamera ist hier noch nicht implementiert.", 12, MUTED, false));
        Switch diagnosis = new Switch(this);
        diagnosis.setText("Diagnoseanzeige auf dem Bildschirm (optional)");
        diagnosis.setTextColor(TEXT);
        diagnosis.setTextSize(16);
        diagnosis.setChecked(MappingStore.prefs(this).getBoolean(MapperService.SHOW_DIAG, false));
        diagnosis.setOnCheckedChangeListener((view, checked) ->
            MappingStore.prefs(this).edit().putBoolean(MapperService.SHOW_DIAG, checked).apply());
        body.addView(diagnosis);
        body.addView(label("Zeigt vorübergehend in ALLEN Apps: Zielerkennung, Vordergrund-Paket und Eingabe-/Gestenzähler. Damit sind auch Fehler in der Ziel-App-Erkennung sichtbar. Nach dem Test ausschalten. Keine Tasteninhalte werden gespeichert.", 12, MUTED, false));
        separator();

        body.addView(label("TEST-UMGEBUNG", 19, TEXT, true));
        body.addView(label("Überprüfe deine Bluetooth-Tastatur und Maus direkt in einer virtuellen Touch-Fläche – ohne Spiel und ohne aktiviertes Mapping.", 12, MUTED, false));
        Button test = button("🎮 Touch-Gesten live testen");
        test.setOnClickListener(v -> startActivity(new Intent(this, TestActivity.class)));
        body.addView(test);
        Button actualTouchTest = button("🧪 Echte Android-Touch-Geste testen");
        actualTouchTest.setOnClickListener(v -> startActivity(new Intent(this, DiagnosticActivity.class)));
        body.addView(actualTouchTest);
        body.addView(label("Dieser Test prüft unabhängig vom Spiel, ob die Bedienungshilfe tatsächlich einen Touch auslösen kann.", 12, MUTED, false));
        separator();

        body.addView(label("TOUCH-BELEGUNG", 19, TEXT, true));
        body.addView(label("Koordinaten sind Bildschirm-Prozente. Tippe auf einen Eintrag zum Bearbeiten oder wähle direkt im Spiel einen Punkt.", 12, MUTED, false));
        for (MappingStore.Binding binding : MappingStore.read(this)) addBinding(binding);

        Button plus = button("+ Weitere Taste hinzufügen");
        plus.setOnClickListener(v -> addBindingDialog());
        body.addView(plus);
        Button reset = button("Standardbelegung wiederherstellen");
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Belegung zurücksetzen?")
            .setPositiveButton("Zurücksetzen", (d, w) -> {
                MappingStore.write(this, MappingStore.defaults()); draw();
            }).setNegativeButton("Abbrechen", null).show());
        body.addView(reset);
        separator();
        body.addView(label("Hinweise", 16, TEXT, true));
        body.addView(label("• WASD simuliert einen kontinuierlichen virtuellen Joystick. Für andere Tasten wird ein gehaltenes Touch-Ereignis verwendet.\n• Keine Internet-Berechtigung, keine Protokollierung von Tastendrücken.\n• Manche Spiele ignorieren simulierte Gesten oder verbieten Mapping-Tools.\n• Falls Android die Bedienungshilfe blockiert: App-Info → Drei-Punkte-Menü → Eingeschränkte Einstellungen zulassen (nur wenn du dem eigenen APK vertraust).\n• Bei Problemen Mapping jederzeit deaktivieren.", 13, MUTED, false));
    }

    private void addBinding(MappingStore.Binding b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.setMargins(0, dp(6), 0, dp(6));
        body.addView(row, rp);
        row.addView(label(MappingStore.readable(b.key) + "  •  " + Math.round(b.x * 100) + "% / " + Math.round(b.y * 100) + "%", 14, TEXT, true));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button edit = button("X/Y ändern");
        Button pick = button("Im Spiel wählen");
        actions.addView(edit, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(pick, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(actions);
        edit.setOnClickListener(v -> editBinding(b));
        pick.setOnClickListener(v -> {
            if (MappingStore.prefs(this).getString(MappingStore.TARGET, "").isEmpty()) {
                Toast.makeText(this, "Erst Ziel-App auswählen", Toast.LENGTH_SHORT).show(); return;
            }
            MappingStore.prefs(this).edit().putString(MappingStore.PICK, b.key).apply();
            Toast.makeText(this, "Öffne jetzt das Spiel und tippe auf die gewünschte Position.", Toast.LENGTH_LONG).show();
            launchTargetApp();
        });
    }

    private void editBinding(MappingStore.Binding binding) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(dp(18), 0, dp(18), 0);
        EditText x = decimalInput("X in %", binding.x * 100);
        EditText y = decimalInput("Y in %", binding.y * 100);
        layout.addView(x); layout.addView(y);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
            .setTitle(MappingStore.readable(binding.key)).setView(layout)
            .setPositiveButton("Speichern", (d, w) -> {
                try {
                    float xv = Float.parseFloat(x.getText().toString().replace(',', '.')) / 100;
                    float yv = Float.parseFloat(y.getText().toString().replace(',', '.')) / 100;
                    MappingStore.upsert(this, binding.key, xv, yv);
                    draw();
                } catch (NumberFormatException ex) { toast("Ungültige Koordinaten"); }
            }).setNegativeButton("Abbrechen", null);
        if (!MappingStore.STICK.equals(binding.key)) dialog.setNeutralButton("Löschen", (d, w) -> {
            MappingStore.remove(this, binding.key); draw();
        });
        dialog.show();
    }

    private EditText decimalInput(String hint, float value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(String.format(Locale.US, "%.1f", value));
        return e;
    }
    private void addBindingDialog() {
        EditText edit = new EditText(this);
        edit.setHint("z.B. Q, F, TAB, CTRL_LEFT, 1");
        edit.setSingleLine(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setPadding(dp(16), 0, dp(16), 0);
        panel.addView(edit);
        new AlertDialog.Builder(this).setTitle("Taste hinzufügen").setView(panel)
            .setPositiveButton("Hinzufügen", (d, w) -> {
                String key = edit.getText().toString().trim().toUpperCase(Locale.ROOT);
                if (MappingStore.keyCode(key) == android.view.KeyEvent.KEYCODE_UNKNOWN || "WASD".contains(key) && key.length() == 1) {
                    toast("Taste unbekannt oder für WASD reserviert"); return;
                }
                MappingStore.upsert(this, key, .75f, .65f); draw();
            }).setNegativeButton("Abbrechen", null).show();
    }

    private void chooseApp() {
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN); query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(query, 0);
        apps.sort(Comparator.comparing(info -> info.loadLabel(pm).toString().toLowerCase(Locale.ROOT)));
        List<ResolveInfo> candidates = new ArrayList<>();
        for (ResolveInfo info : apps) {
            if (info.activityInfo != null && !getPackageName().equals(info.activityInfo.packageName)) {
                candidates.add(info);
            }
        }
        if (candidates.isEmpty()) { toast("Keine Apps gefunden"); return; }
        String[] names = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) names[i] = candidates.get(i).loadLabel(pm).toString();
        new AlertDialog.Builder(this).setTitle("Spiel / Ziel-App wählen")
            .setItems(names, (d, index) -> {
                String pkg = candidates.get(index).activityInfo.packageName;
                MappingStore.prefs(this).edit().putString(MappingStore.TARGET, pkg).apply();
                draw();
            }).setNegativeButton("Abbrechen", null).show();
    }

    private void launchTargetApp() {
        String pkg = MappingStore.prefs(this).getString(MappingStore.TARGET, "");
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent != null) startActivity(intent);
        else toast("Starte die Ziel-App bitte selbst");
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
