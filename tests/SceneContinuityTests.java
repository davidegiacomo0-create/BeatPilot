import it.dave.beatpilot.core.*;
import java.util.*;

/** Offline scene-lifecycle checks; no Android runtime or game-score assertion. */
public final class SceneContinuityTests {
    private static final double LINE = .721;
    private static void expect(boolean okay, String reason) {
        if (!okay) throw new AssertionError(reason);
    }

    private static void transientFrame() {
        GameplayGate gate = new GameplayGate();
        Tracker tracker = new Tracker();
        TouchPlanner planner = new TouchPlanner(BeatstarDetector.LANES, LINE, true);
        int starts = 0; long busyUntil = 0, firstStart = -1;
        boolean paused = false;
        for (long now = 1000; now <= 1200; now++) {
            if ((now - 1000) % 20 == 0 && now <= 1140) {
                boolean visible = gate.update(now != 1080, false, now);
                if (!visible) {
                    if (gate.preserveNotes()) paused = true;
                    else { tracker.reset(); planner.clear(); paused = false; }
                } else {
                    paused = false;
                    for (Tracker.Hit hit : tracker.update(List.of(new Detector.Detection(
                            0, Kind.TAP, LINE + .001 * (now - 1100), 1, .10)),
                            now, now, LINE, 0)) planner.add(hit);
                }
            }
            long next = planner.nextTime();
            if (paused || now < busyUntil || next == Long.MAX_VALUE || GestureTiming.wakeAt(next) > now) continue;
            for (TouchPlanner.Segment s : planner.plan(now, GestureTiming.HORIZON_MS)) {
                if (!s.continuation) {
                    starts++; if (firstStart < 0) firstStart = now + s.offset;
                }
                busyUntil = Math.max(busyUntil, now + s.offset + s.duration);
            }
        }
        expect(starts == 1 && firstStart == 1100,
                "One uncertain HUD frame lost, duplicated, or retimed the predicted note");
    }

    private static void heldContact() {
        GameplayGate gate = new GameplayGate();
        TouchPlanner planner = new TouchPlanner(BeatstarDetector.LANES, LINE, true);
        planner.add(new Tracker.Hit(1,0,Kind.HOLD_START,1000));
        List<TouchPlanner.Segment> start = planner.plan(988,32);
        expect(start.size() == 1 && start.get(0).more, "Invalid hold setup");
        long contact = start.get(0).id;
        planner.add(new Tracker.Hit(2,0,Kind.HOLD_END,1400));
        gate.update(true,false,1000);
        expect(!gate.update(false,false,1020) && gate.preserveNotes(), "Transient image cleared state");
        // No call to planner.plan/clear while uncertain: the existing pointer
        // and already measured tail remain retained, with no speculative tap.
        expect(planner.nextTime() == 1400, "Uncertain frame lost the release");
        expect(gate.update(true,false,1040) && !gate.preserveNotes(), "Restored HUD did not resume");
        List<TouchPlanner.Segment> end = planner.plan(1388,32);
        expect(end.size() == 1 && end.get(0).id == contact && end.get(0).continuation
                && !end.get(0).more && 1388 + end.get(0).offset + end.get(0).duration == 1400,
                "Continuity changed the held pointer or tail timing");
        expect(planner.idle(), "Completed hold left pending state");
    }

    private static void boundedUncertainty() {
        GameplayGate gate = new GameplayGate();
        expect(!gate.update(false,false,0) && !gate.preserveNotes(), "Initial menu inherited notes");
        expect(gate.update(true,false,20), "Failed to acquire gameplay");
        for (long now = 40; now < 120; now += 20)
            expect(!gate.update(false,false,now) && gate.preserveNotes(), "Grace interval changed");
        expect(!gate.update(false,false,120) && !gate.preserveNotes(), "Ambiguous scene retained state beyond 80 ms");
        for (long now = 140; now <= 540; now += 20)
            expect(!gate.update(false,false,now) && !gate.preserveNotes(), "Exit retained stale notes");
        for (long now = 560; now < 1160; now += 20)
            expect(!gate.update(true,false,now) && !gate.preserveNotes(), "Recovery bypassed the exit guard");
        expect(gate.update(true,false,1160), "Stable gameplay did not return");
        gate.reset();
        expect(!gate.preserveNotes(), "New activation inherited uncertainty");
        gate.update(true,false,1200);
        expect(!gate.update(false,false,1600) && !gate.preserveNotes(), "Capture gap received a grace interval");
        gate.reset(); gate.update(true,false,2000);
        expect(!gate.update(false,false,1990) && !gate.preserveNotes(), "Backwards clock retained old notes");
    }

    public static void main(String[] args) {
        transientFrame(); heldContact(); boundedUncertainty();
        System.out.println("PASS: scene continuity preserves one predicted tap and a held contact; uncertainty and exit remain bounded. No device score inferred.");
    }
}
