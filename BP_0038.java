import it.dave.beatpilot.core.*;
import java.util.*;

/** Adversarial behavioral checks against the delivered 0.1.9 core.
 * No Android runtime, game, screenshot classification, or score measurement.
 * An expected FAILURE is a known release blocker, not a passing regression.
 */
public final class ReliabilityAudit {
    private static final double LINE = .721;
    private static int controls, failures;
    private record Outcome(int starts, long firstStart, long nextTime, boolean idle) {}

    private static boolean preserveNotes(GameplayGate gate) {
        // The delivered baseline has no retention API. The candidate's capture
        // branch uses it, so the same probe exercises both source snapshots.
        try { return (Boolean) gate.getClass().getMethod("preserveNotes").invoke(gate); }
        catch (NoSuchMethodException oldBaseline) { return false; }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    // Reproduces the !gameplay branch of CaptureService.frameAvailable and
    // cancelPending in TouchService. Vision updates run before scheduler ticks.
    // The single hidden frame precedes the scheduler wake time (due - 12 ms).
    private static Outcome oneHiddenFrame(boolean hide) {
        GameplayGate gate = new GameplayGate();
        Tracker tracker = new Tracker();
        TouchPlanner planner = new TouchPlanner(BeatstarDetector.LANES, LINE, true);
        int starts = 0; long first = -1, busyUntil = 0;
        boolean paused = false;
        for (long now = 1000; now <= 1200; now++) {
            if ((now - 1000) % 20 == 0 && now <= 1140) {
                boolean gameplay = gate.update(!(hide && now == 1080), false, now);
                if (!gameplay) {
                    paused = preserveNotes(gate);
                    if (!paused) { tracker.reset(); planner.clear(); }
                } else {
                    paused = false;
                    // One perfectly detected tap moves at a constant speed,
                    // crosses the line at 1100 ms, then leaves the visible area.
                    double y = LINE + .001 * (now - 1100);
                    for (Tracker.Hit hit : tracker.update(List.of(
                            new Detector.Detection(0, Kind.TAP, y, 1, .10)),
                            now, now, LINE, 0)) planner.add(hit);
                }
            }
            long next = planner.nextTime();
            if (paused || now < busyUntil || next == Long.MAX_VALUE || GestureTiming.wakeAt(next) > now) continue;
            for (TouchPlanner.Segment s : planner.plan(now, GestureTiming.HORIZON_MS)) {
                if (!s.continuation) { starts++; if (first < 0) first = now + s.offset; }
                busyUntil = Math.max(busyUntil, now + s.offset + s.duration);
            }
        }
        return new Outcome(starts, first, planner.nextTime(), planner.idle());
    }

    // Same input shape and timings as the previously retained live failure:
    // hold 309, missing release, new hold 312 on the occupied lane.
    // Repeat symmetrically: this defect does not depend on lane number/colour.
    private static Outcome missingTail(int lane, boolean supplyTail) {
        TouchPlanner planner = new TouchPlanner(BeatstarDetector.LANES, LINE, true);
        planner.add(new Tracker.Hit(309, lane, Kind.HOLD_START, 71039));
        List<TouchPlanner.Segment> first = planner.plan(71029, 32);
        if (first.size() != 1 || !first.get(0).more) throw new AssertionError("Invalid hold setup");
        if (supplyTail) {
            planner.add(new Tracker.Hit(310, lane, Kind.HOLD_END, 71507));
            planner.plan(71495, 32);
        }
        planner.add(new Tracker.Hit(312, lane, Kind.HOLD_START, 71759));
        int starts = 0; long at = -1;
        for (TouchPlanner.Segment s : planner.plan(71749, 32)) {
            if (s.noteId == 312 && !s.continuation) { starts++; at = 71749 + s.offset; }
        }
        return new Outcome(starts, at, planner.nextTime(), planner.idle());
    }

    // Synthetic stress case, NOT a claim about spacing in an uploaded song.
    // A short same-lane gap must not silently consume an already predicted hit.
    private static Outcome overlappingSwipe() {
        TouchPlanner planner = new TouchPlanner(BeatstarDetector.LANES, LINE, true);
        planner.add(new Tracker.Hit(1, 0, Kind.UP, 1000));
        planner.plan(988, 32);
        planner.add(new Tracker.Hit(2, 0, Kind.TAP, 1040));
        int starts = 0; long at = -1;
        for (TouchPlanner.Segment s : planner.plan(1020, 32)) {
            if (s.noteId == 2 && !s.continuation) { starts++; at = 1020 + s.offset; }
        }
        return new Outcome(starts, at, planner.nextTime(), planner.idle());
    }

    private static void control(boolean valid, String label) {
        if (!valid) throw new AssertionError("Invalid positive control: " + label);
        controls++;
        System.out.println("CONTROL PASS: " + label);
    }

    private static void required(Outcome actual, long expectedAt, String label) {
        boolean okay = actual.starts == 1 && actual.firstStart == expectedAt;
        if (!okay) failures++;
        System.out.println((okay ? "PASS: " : "FAIL: ") + label +
                "; expected one DOWN at " + expectedAt + "; planned starts=" + actual.starts +
                "; first=" + actual.firstStart + "; next=" +
                (actual.nextTime == Long.MAX_VALUE ? "NONE" : actual.nextTime) +
                "; idle=" + actual.idle);
    }

    public static void main(String[] args) {
        Outcome visible = oneHiddenFrame(false);
        control(visible.starts == 1 && visible.firstStart == 1100, "uninterrupted HUD: tap at 1100 ms");
        required(oneHiddenFrame(true), 1100, "queued tap survives one missing HUD frame");
        for (int lane = 0; lane < 3; lane++) {
            Outcome released = missingTail(lane, true);
            control(released.starts == 1 && released.firstStart == 71759,
                    "known hold end allows next hold, lane " + (lane + 1));
            required(missingTail(lane, false), 71759,
                    "missing hold end silently consumes next hold, lane " + (lane + 1));
        }
        required(overlappingSwipe(), 1040, "40 ms same-lane swipe/tap overlap (synthetic)");
        System.out.println("RESULT: " + controls + " positive controls passed; " + failures +
                " failed behavior checks. No device execution or Perfect+ score inferred.");
        if (failures != 0) System.exit(1);
    }
}
