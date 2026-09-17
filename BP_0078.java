import it.dave.beatpilot.core.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Regressions from the Samsung trace, plus variable-speed/latency simulations. */
public final class TimingRegressionTests {
    private static int checks;
    private static void expect(boolean value, String why) {
        checks++; if (!value) throw new AssertionError(why);
    }
    private static void conversion() {
        Random random = new Random(8831); RgbaToGray convert = new RgbaToGray();
        for (int stride : new int[]{4, 8}) {
            int w = 17, h = 19, row = w * stride + 12, origin = 7;
            int length = origin + (h - 1) * row + (w - 1) * stride + 4;
            byte[] data = new byte[length]; random.nextBytes(data);
            ByteBuffer nativeBuffer = ByteBuffer.allocateDirect(length); nativeBuffer.put(data).position(origin);
            byte[] gray = new byte[w * h]; convert.convert(nativeBuffer, row, stride, w, h, gray);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int p = origin + y * row + x * stride;
                int expected = (77 * (data[p] & 255) + 150 * (data[p + 1] & 255) + 29 * (data[p + 2] & 255)) >> 8;
                expect((gray[y * w + x] & 255) == expected, "Grayscale changed at padded/strided pixel");
            }
            expect(nativeBuffer.position() == origin && nativeBuffer.limit() == length, "Source state mutated");
            nativeBuffer.limit(length - 1);
            boolean rejected = false;
            try { convert.convert(nativeBuffer, row, stride, w, h, gray); }
            catch (IllegalArgumentException e) { rejected = true; }
            expect(rejected, "Truncated native image accepted");
        }
    }
    private static void clock() {
        FrameClock clock = new FrameClock();
        expect(clock.sample(900_000_000L, 915_000_000L, 10000) == 9985, "Image age must be subtracted");
        expect(clock.sourceBased() && clock.changed(), "Valid source clock not selected");
        expect(clock.sample(900_000_000L, 930_000_000L, 10015) == 9985, "Duplicate image invented a new observation");
        expect(!clock.changed(), "Duplicate image changed clock mode");
        expect(clock.sample(100, 1_000_000_000L, 10100) == 10100 && !clock.sourceBased() && clock.changed(),
                "Foreign timebase must fall back to arrival time");
        expect(clock.sample(1_017_000_000L, 1_016_000_000L, 10116) == 10116, "Future timestamp rounding must be clamped");
        clock.sample(1_000_000_000L, 1_030_000_000L, 10130);
        expect(clock.changed(), "Reversed source clock must reset tracking");
    }
    private static void start() {
        StartGate gate = new StartGate(); gate.reset();
        for (int t = 0; t < 450; t += 20) expect(!gate.update(true,t), "Start before stable pair");
        expect(gate.update(true,450), "Stable grey pair did not start");
        expect(gate.gestureInFlight(650), "Start sequence cancelled before completion");
        int repeats = 0;
        for (int t = 480; t < 4000; t += 20) if (gate.update(true,t)) repeats++;
        expect(repeats == 0 && gate.attempts() == 1, "Opening pair must never repeat automatically");
        gate.reset(); gate.gameplayHasNotes();
        for (int t = 0; t < 500; t += 20) expect(!gate.update(true,t), "Start retried after play began");
        gate.reset(); gate.update(true,0); gate.update(false,160);
        expect(!gate.update(true,200), "Flashing non-start image retained stability");

        TouchPlanner p = new TouchPlanner(BeatstarDetector.LANES,.721);
        for (Tracker.Hit h : StartGate.openingHits(1000)) p.add(h);
        expect(p.openingNext(), "Opening gesture not recognized by planner");
        List<TouchPlanner.Segment> pair = p.plan(1000,180);
        expect(pair.size() == 2 && pair.get(0).duration == 160 && pair.get(1).duration == 160,
                "Opening taps must last 160 ms each");
        expect(pair.get(0).offset == 0 && pair.get(1).offset == 0 && pair.stream().noneMatch(s -> s.more),
                "Opening pair must be simultaneous and complete in one batch");
        expect(pair.get(0).id != pair.get(1).id && Math.abs(pair.get(0).x0 - pair.get(1).x0) > .5,
                "Opening pair must use two distinct outer-lane pointers");
        expect(p.idle(), "Opening gesture left queued/continued fingers");
    }
    private static void queueRevisions() {
        TouchPlanner p = new TouchPlanner(BeatstarDetector.LANES,.721);
        p.add(new Tracker.Hit(5,1,Kind.TAP,1000));
        p.add(new Tracker.Hit(5,1,Kind.TAP,990,true,false));
        expect(p.nextTime() == 990, "Changed speed did not update queued time");
        expect(p.plan(985,20).size() == 1, "Revision duplicated tap");
        p.add(new Tracker.Hit(5,1,Kind.TAP,1010,true,false));
        expect(p.idle(), "Late revision revived a dispatched note");
        p.add(new Tracker.Hit(6,2,Kind.TAP,1100)); p.clear();
        p.add(new Tracker.Hit(6,2,Kind.TAP,1110,true,false));
        expect(p.idle(), "Late revision revived a cancelled note");
    }
    private static void varyingSpeedAndLatency() {
        int revisions = 0;
        for (int period : new int[]{16, 33}) for (int analysis : new int[]{3, 32})
            for (double speed : new double[]{.00035,.0007,.0014}) {
                Tracker tracker = new Tracker(); long firstReady = -1, firstDue = -1, finalDue = -1;
                int originals = 0, index = 0;
                for (long capture = 600; capture < 1100; capture += period) {
                    long ready = capture + analysis + new int[]{0,7,14,4}[index++ % 4];
                    double t = capture - 1000, acceleration = speed * .0006;
                    double y = .721 + speed * t + .5 * acceleration * t * t;
                    for (Tracker.Hit h : tracker.update(List.of(new Detector.Detection(1,Kind.TAP,y,.99,.12)),
                            capture,ready,.721,0)) {
                        if (!h.revision) { originals++; firstReady = ready; firstDue = h.at; }
                        else revisions++;
                        finalDue = h.at;
                    }
                }
                expect(originals == 1, "Variable speed note lost or duplicated");
                expect(firstDue >= firstReady, "Prediction already expired during analysis");
                expect(Math.abs(finalDue - 1000) <= 8, "Accelerating note prediction outside 8 ms: " + finalDue);
            }
        expect(revisions > 0, "Acceleration simulation did not exercise queue refinements");
    }
    private static void recordedArrow(Path path) throws Exception {
        Tracker tracker = new Tracker(); int originals = 0; long due = -1, readyAtFirst = -1;
        for (String line : Files.readAllLines(path).subList(1, Files.readAllLines(path).size())) {
            String[] s = line.split(","); long capture = Long.parseLong(s[0]), ready = Long.parseLong(s[1]);
            Detector.Detection d = new Detector.Detection(Integer.parseInt(s[2]),Kind.valueOf(s[3]),
                    Double.parseDouble(s[4]),Double.parseDouble(s[5]),.16);
            for (Tracker.Hit h : tracker.update(List.of(d),capture,ready,.721,0)) {
                if (!h.revision) { originals++; readyAtFirst = ready; }
                due = h.at;
            }
        }
        expect(originals == 1, "Recorded final LEFT still missed");
        expect(due > readyAtFirst && due >= 71050 && due <= 71120,
                "Recorded arrow not scheduled before its visible crossing: " + due);
        System.out.println("Recorded missed LEFT now planned at " + due + " ms (first ready " + readyAtFirst + ").");
    }
    public static void main(String[] args) throws Exception {
        conversion(); clock(); start(); queueRevisions(); varyingSpeedAndLatency(); recordedArrow(Path.of(args[0]));
        System.out.println("PASS: " + checks + " timing/conversion/start regression assertions. No live game score measured.");
    }
}
