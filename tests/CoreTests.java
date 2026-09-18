import it.dave.beatpilot.core.*;
import java.util.*;

public final class CoreTests {
    private static int assertions = 0;
    private static final double[] LANES = {1.0/6, .5, 5.0/6};
    private static void check(boolean condition, String explanation) {
        assertions++;
        if (!condition) throw new AssertionError(explanation);
    }
    private static GrayFrame blank() {
        byte[] data = new byte[360 * 780]; Arrays.fill(data, (byte)20);
        return new GrayFrame(360, 780, data);
    }
    private static int tileValue(int x, int y) {
        int row = y / 6, col = x / 6;
        return 40 + new Random(71L * row + 1871L * col + 903).nextInt(100);
    }
    private static void tile(GrayFrame frame, int lane, int top, boolean brighter) {
        int left = (int)Math.round(LANES[lane] * frame.width) - 30;
        for (int y = 0; y < 84; y++) for (int x = 0; x < 60; x++) {
            if (top + y >= 0 && top + y < frame.height) {
                int value = tileValue(x, y);
                if (brighter) value = (int)(value * 1.1) + 30;
                frame.pixels[(top + y) * frame.width + left + x] = (byte)value;
            }
        }
    }
    private static NotePattern sample() {
        GrayFrame frame = blank(); tile(frame, 1, 220, false);
        return NotePattern.crop("sample", Kind.TAP, frame, 150, 220, 60, 84, .5, .5);
    }
    private static void visualRecognition() {
        Detector detector = new Detector(); NotePattern pattern = sample();
        GrayFrame frame = blank(); tile(frame, 0, 410, false); tile(frame, 2, 450, true);
        List<Detector.Detection> found = detector.detect(frame, List.of(pattern), LANES, .4, .9, .95);
        check(found.size() == 2, "Two simultaneous lanes, with different brightness, must match");
        check(found.stream().anyMatch(d -> d.lane == 0 && Math.abs(d.y - 452.0/780) < .004), "Left note anchor");
        check(found.stream().anyMatch(d -> d.lane == 2 && Math.abs(d.y - 492.0/780) < .004), "Right brighter note anchor");
        check(detector.detect(blank(), List.of(pattern), LANES, .2, .95, .9).isEmpty(), "Flat background is not a note");
        boolean rejected = false;
        try { NotePattern.crop("bad", Kind.TAP, blank(), 50, 60, 60, 84, .5, .5); }
        catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "Flat templates must be rejected");
        NotePattern restored = new NotePattern(pattern.id, pattern.kind, pattern.widthRatio, pattern.heightRatio,
                pattern.anchorX, pattern.anchorY, pattern.storageSamples());
        check(detector.detect(frame, List.of(restored), LANES, .4, .9, .95).size() == 2, "Stored templates preserve correlation");
        System.out.println("PASS: visual matching, brightness, multi-lane, blank rejection, template serialization");
    }
    private static void pixelsToTimedHit() {
        Detector detector = new Detector(); Tracker tracker = new Tracker();
        NotePattern pattern = sample(); List<Tracker.Hit> hits = new ArrayList<>();
        long crossing = 530;
        for (long now = 0; now <= 640; now += 16) {
            GrayFrame frame = blank();
            double anchor = .82 - .0008 * (crossing - now);
            int top = (int)Math.round(anchor * frame.height - 42);
            tile(frame, 1, top, false);
            hits.addAll(tracker.update(detector.detect(frame, List.of(pattern), LANES, .4, .95, .9), now, .82, 0));
        }
        check(hits.size() == 1, "A moving note must produce one hit, no duplicates");
        check(Math.abs(hits.get(0).at - crossing) <= 8, "Hit predicted within 8 ms in synthetic 60 Hz frames");
        System.out.println("PASS: synthetic pixels -> detection -> tracking -> one timed hit (8 ms test tolerance)");
    }
    private static void trackingJitterAndPause() {
        Tracker tracker = new Tracker(); List<Tracker.Hit> output = new ArrayList<>();
        List<Long> due = new ArrayList<>();
        for (int i = 0; i < 45; i++) due.add(450L + i * 110L);
        int frame = 0;
        for (long now = 0; now < 5700; now += new int[]{15, 17, 16, 19, 14}[frame++ % 5]) {
            List<Detector.Detection> observations = new ArrayList<>();
            for (int i = 0; i < due.size(); i++) {
                double y = .82 + .0009 * (now - due.get(i));
                if (y > .44 && y < .89 && !(frame % 19 == 0)) observations.add(new Detector.Detection(i % 3, Kind.TAP, y, .98, .05));
            }
            observations.sort(Comparator.comparingInt((Detector.Detection d) -> d.lane).thenComparingDouble(d -> -d.y));
            output.addAll(tracker.update(observations, now, .82, 12));
        }
        check(output.size() == due.size(), "Jitter/drop frames must not duplicate or lose this synthetic sequence");
        for (int i = 0; i < due.size(); i++) check(Math.abs(output.get(i).at - (due.get(i) - 12)) <= 3, "Compensated hit " + i);
        tracker.reset();
        for (int i = 0; i < 50; i++) check(tracker.update(List.of(new Detector.Detection(0, Kind.TAP, .8, .99, .04)), i * 16, .82, 0).isEmpty(), "Stationary UI cannot tap");
        tracker.reset();
        tracker.update(List.of(new Detector.Detection(0, Kind.TAP, .75, .99, .04)), 0, .82, 0);
        tracker.update(List.of(new Detector.Detection(0, Kind.TAP, .77, .99, .04)), 16, .82, 0);
        check(tracker.update(List.of(new Detector.Detection(0, Kind.TAP, .81, .99, .04)), 500, .82, 0).isEmpty(), "Stale tracks reset after a long pause");
        System.out.println("PASS: 45 timed notes, frame jitter/drop, anticipation, stationary UI, stale-track reset");
    }
    private static void simultaneousAndHeldTouches() {
        TouchPlanner planner = new TouchPlanner(LANES, .82);
        planner.add(new Tracker.Hit(1, 0, Kind.HOLD_START, 100));
        planner.add(new Tracker.Hit(2, 2, Kind.TAP, 100));
        List<TouchPlanner.Segment> first = planner.plan(100, 20);
        check(first.size() == 2, "Hold and tap must be one multi-pointer batch");
        TouchPlanner.Segment hold = first.stream().filter(s -> s.more).findFirst().orElseThrow();
        check(first.stream().anyMatch(s -> !s.more && s.duration == 8), "Tap ends independently");
        planner.add(new Tracker.Hit(3, 1, Kind.TAP, 130));
        List<TouchPlanner.Segment> second = planner.plan(123, 20);
        check(second.size() == 2, "New tap while hold continues");
        check(second.stream().anyMatch(s -> s.id == hold.id && s.continuation && s.more), "Same held pointer continues");
        check(second.stream().anyMatch(s -> !s.continuation && s.offset == 7), "Future tap offset stays timed");
        planner.add(new Tracker.Hit(4, 0, Kind.HOLD_END, 155));
        List<TouchPlanner.Segment> third = planner.plan(146, 20);
        check(third.size() == 1 && third.get(0).continuation && !third.get(0).more && third.get(0).duration == 9, "Release at hold-end marker");
        check(planner.idle(), "No fingers or events remain after release");
        System.out.println("PASS: simultaneous taps, held pointer continuity, overlapping tap, scheduled hold release");
    }
    private static void swipeAndStop() {
        TouchPlanner planner = new TouchPlanner(LANES, .82);
        planner.add(new Tracker.Hit(1, 1, Kind.RIGHT, 0));
        TouchPlanner.Segment first = planner.plan(0, 20).get(0);
        TouchPlanner.Segment second = planner.plan(28, 20).get(0);
        check(first.x1 > first.x0 && first.more, "Swipe moves right");
        check(second.continuation && second.x0 == first.x1 && second.y0 == first.y1, "Delayed callback keeps exact previous endpoint");
        TouchPlanner.Segment last = planner.plan(53, 20).get(0);
        check(last.continuation && !last.more && Math.abs(last.x1 - .585) < .0001, "Swipe ends at intended location");
        planner.add(new Tracker.Hit(2, 2, Kind.TAP, 200)); planner.clear();
        check(planner.idle() && planner.plan(200, 20).isEmpty(), "Stop clears queued events and contacts");
        planner.add(new Tracker.Hit(3, 0, Kind.TAP, 0));
        check(planner.plan(200, 20).isEmpty(), "Late taps are dropped");
        planner.add(new Tracker.Hit(4, 0, Kind.HOLD_START, 300)); planner.plan(300, 20);
        planner.add(new Tracker.Hit(5, 0, Kind.HOLD_END, 350));
        check(!planner.plan(500, 20).get(0).more, "Late releases are still executed");
        System.out.println("PASS: swipe endpoints after callback delay, stop/queue reset, stale taps, late releases");
    }
    public static void main(String[] args) {
        visualRecognition(); pixelsToTimedHit(); trackingJitterAndPause(); simultaneousAndHeldTouches(); swipeAndStop();
        System.out.println("SUCCESS: " + assertions + " assertions. Synthetic inputs only; no Android/device/game validation.");
    }
}
