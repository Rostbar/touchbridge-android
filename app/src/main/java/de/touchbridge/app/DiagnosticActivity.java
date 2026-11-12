package de.touchbridge.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.FrameLayout;

/**
 * End-to-end accessibility test: the service injects a REAL gesture, and the
 * target View counts actual touch-up events. There is no keyboard logging.
 */
public class DiagnosticActivity extends Activity
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int BG = Color.rgb(13, 19, 31);
    private static final int WHITE = Color.rgb(242, 247, 255);
    private static final int MUTED = Color.rgb(179, 197, 219);
    private SharedPreferences prefs;
    private TextView serviceState;
    private TextView result;
    private TextView counter;
    private FrameLayout target;
    private int receivedTaps = 0;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = MappingStore.prefs(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(18));
        root.setBackgroundColor(BG);

        TextView title = text("ECHTER TOUCH-TEST", 23, WHITE);
        root.addView(title);
        root.addView(text("Hier wird nicht nur eine Animation angezeigt. " +
            "Der Bedienungshilfedienst versucht, das untere Feld wirklich anzutippen.", 14, MUTED));

        serviceState = text("", 16, WHITE);
        serviceState.setPadding(0, dp(16), 0, dp(10));
        root.addView(serviceState);

        Button settings = new Button(this);
        settings.setText("Bedienungshilfe öffnen");
        settings.setOnClickListener(v -> startActivity(
            new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(settings);

        Button trigger = new Button(this);
        trigger.setText("Echten Touch auslösen");
        trigger.setOnClickListener(v -> requestTap());
        root.addView(trigger);

        result = text("Noch kein Test durchgeführt", 15, WHITE);
        result.setPadding(0, dp(12), 0, dp(12));
        root.addView(result);

        target = new FrameLayout(this);
        target.setBackgroundColor(Color.rgb(28, 65, 82));
        TextView targetLabel = text("ZIELSCHEIBE\n\nBitte NICHT mit dem Finger antippen", 18, WHITE);
        targetLabel.setGravity(Gravity.CENTER);
        target.addView(targetLabel, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        // This listener runs for generated touches as well as manual touches.
        target.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                receivedTaps++;
                counter.setText("Im Zielfeld empfangene Touches: " + receivedTaps);
                target.setBackgroundColor(Color.rgb(28, 107, 79));
                target.postDelayed(() -> target.setBackgroundColor(Color.rgb(28, 65, 82)), 500);
            }
            return true;
        });
        root.addView(target, new LinearLayout.LayoutParams(-1, dp(240)));

        counter = text("Im Zielfeld empfangene Touches: 0", 15, WHITE);
        counter.setPadding(0, dp(12), 0, dp(6));
        root.addView(counter);
        root.addView(text("Erfolg: 'Geste an Android gesendet' UND Zähler steigt. " +
            "Wenn nur die erste Meldung erscheint, wird die Berührung nicht im Zielfeld empfangen. " +
            "Das ist noch kein Spiel-Test.", 13, MUTED));

        setContentView(root);
    }

    private int dp(int value) {
        return (int)(getResources().getDisplayMetrics().density * value);
    }
    private TextView text(String value, int size, int color) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextColor(color);
        result.setTextSize(size);
        result.setPadding(0, dp(8), 0, dp(8));
        return result;
    }

    @Override protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(this);
        updateServiceState();
    }

    @Override protected void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    private void updateServiceState() {
        serviceState.setText(MapperService.isConnected()
            ? "Bedienungshilfe: VERBUNDEN"
            : "Bedienungshilfe: NICHT VERBUNDEN");
    }

    private void requestTap() {
        updateServiceState();
        if (!MapperService.isConnected()) {
            result.setText("Bedienungshilfe erst aktivieren und dann zurückkehren.");
            return;
        }
        int[] position = new int[2];
        target.getLocationOnScreen(position);
        if (target.getWidth() <= 0 || target.getHeight() <= 0) {
            result.setText("Zielfeld wurde noch nicht gezeichnet");
            return;
        }
        float x = position[0] + target.getWidth() / 2f;
        float y = position[1] + target.getHeight() / 2f;
        result.setText("Touch angefordert …");
        int nonce = prefs.getInt(MapperService.DIAG_NONCE, 0) + 1;
        // apply() notifies this app's in-process AccessibilityService.
        prefs.edit()
            .putFloat(MapperService.DIAG_X, x)
            .putFloat(MapperService.DIAG_Y, y)
            .putInt(MapperService.DIAG_NONCE, nonce)
            .apply();
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (MapperService.DIAG_RESULT.equals(key)) {
            runOnUiThread(() -> result.setText(preferences.getString(
                MapperService.DIAG_RESULT, "Keine Rückmeldung")));
        }
    }
}
