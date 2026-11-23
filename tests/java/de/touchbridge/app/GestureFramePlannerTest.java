package de.touchbridge.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Run with javac/java without Android SDK: java de.touchbridge.app.GestureFramePlannerTest */
public final class GestureFramePlannerTest {
    private static Set<String> keys(String... ids) { return new HashSet<>(Arrays.asList(ids)); }
    private static void expect(List<GestureFramePlanner.Step> plan, String... expected) {
        if (plan.size() != expected.length) throw new AssertionError(plan + " expected " + Arrays.toString(expected));
        for (int i = 0; i < expected.length; i++) {
            if (!expected[i].equals(plan.get(i).toString())) {
                throw new AssertionError(plan + " expected " + Arrays.toString(expected));
            }
        }
    }
    public static void main(String[] args) {
        // W + SPACE + SHIFT -> joystick plus two separate fingers.
        expect(GestureFramePlanner.plan(keys(), keys("STICK", "KEY_62", "KEY_59"), keys(), 10),
            "PRESS:KEY_59", "PRESS:KEY_62", "PRESS:STICK");
        // Releasing SPACE must not release the still-held virtual joystick / SHIFT.
        expect(GestureFramePlanner.plan(keys("STICK", "KEY_62", "KEY_59"),
                keys("STICK", "KEY_59"), keys(), 10),
            "HOLD:KEY_59", "RELEASE:KEY_62", "HOLD:STICK");
        // Joystick stays the same pointer when changing from W to W+D.
        expect(GestureFramePlanner.plan(keys("STICK"), keys("STICK"), keys("TAP_33"), 10),
            "HOLD:STICK", "TAP:TAP_33");
        // Release every finger promptly.
        expect(GestureFramePlanner.plan(keys("STICK", "KEY_62"), keys(), keys(), 10),
            "RELEASE:KEY_62", "RELEASE:STICK");
        // Do not drop a continued pointer when capacity is reached.
        expect(GestureFramePlanner.plan(keys("STICK"), keys("STICK", "KEY_62"), keys(), 1),
            "HOLD:STICK");
        System.out.println("All GestureFramePlanner tests passed.");
    }
}
