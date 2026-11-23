package de.touchbridge.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Platform-independent planning for one atomic multi-pointer gesture frame.
 *
 * A continued finger must be present in EVERY subsequent dispatched frame;
 * leaving it out cancels it. Releases and new presses therefore share the
 * same gesture as other fingers that remain held.
 */
final class GestureFramePlanner {
    enum Kind { HOLD, RELEASE, PRESS, TAP }

    static final class Step {
        final String id;
        final Kind kind;
        Step(String id, Kind kind) { this.id = id; this.kind = kind; }
        @Override public String toString() { return kind + ":" + id; }
    }

    private GestureFramePlanner() { }

    static List<Step> plan(Set<String> continued,
                           Set<String> wanted,
                           Set<String> pendingTaps,
                           int maxPointers) {
        if (maxPointers <= 0) return Collections.emptyList();
        ArrayList<Step> steps = new ArrayList<>();
        // Existing fingers ALWAYS get priority. All of them are continued or
        // explicitly released, even if the user pressed other keys meanwhile.
        for (String id : new TreeSet<>(continued)) {
            if (steps.size() >= maxPointers) break;
            steps.add(new Step(id, wanted.contains(id) ? Kind.HOLD : Kind.RELEASE));
        }
        for (String id : new TreeSet<>(wanted)) {
            if (steps.size() >= maxPointers) break;
            if (!continued.contains(id)) steps.add(new Step(id, Kind.PRESS));
        }
        for (String id : new TreeSet<>(pendingTaps)) {
            if (steps.size() >= maxPointers) break;
            if (!continued.contains(id) && !wanted.contains(id)) {
                steps.add(new Step(id, Kind.TAP));
            }
        }
        return steps;
    }
}
