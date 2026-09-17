import it.dave.beatpilot.core.*;
import java.nio.file.*;
import java.util.*;

/** Recorded dark-stage failures in Du hast and No One Knows. No game scores inferred. */
public final class ThemeRegressionTests {
    private static int checks;
    private static void expect(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private record Sample(String group, long time, long capture, long ready, GrayFrame frame) {}
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        List<Sample> samples = new ArrayList<>();
        List<String> rows = Files.readAllLines(root.resolve("sequence.csv"));
        for (String row : rows.subList(1,rows.size())) {
            String[] f = row.split(",");
            samples.add(new Sample(f[0],Long.parseLong(f[1]),Long.parseLong(f[2]),Long.parseLong(f[3]),
                    new GrayFrame(480,1040,Files.readAllBytes(root.resolve(f[4])))));
        }
        BeatstarDetector detector = new BeatstarDetector();
        for (String group : List.of("teal","red")) {
            Tracker tracker = new Tracker(BeatstarDetector.PERSPECTIVE); GameplayGate gate = new GameplayGate();
            TouchPlanner planner = new TouchPlanner(BeatstarDetector.LANES,BeatstarDetector.HIT_LINE);
            Map<Long,Tracker.Hit> latest = new HashMap<>(); Map<Long,Long> firstReady = new HashMap<>();
            int darkFrames = 0; boolean downSeen = false;
            for (Sample s : samples) if (s.group.equals(group)) {
                expect(detector.isGameplay(s.frame),group + " dark background stopped gameplay at " + s.time);
                expect(gate.update(true,false,s.time),group + " uninterrupted play acquired a recovery delay");
                int bright = 0;
                for (double y : new double[]{.07,.22,.25}) for (double x : new double[]{.15,.85})
                    if (s.frame.at((int)(x*480),(int)(y*1040)) > 135) bright++;
                if (bright < 3) darkFrames++;
                List<Detector.Detection> notes = detector.detect(s.frame,BeatstarDetector.LANES,BeatstarDetector.HIT_LINE);
                downSeen |= notes.stream().anyMatch(d -> d.lane == 0 && d.kind == Kind.DOWN);
                for (Tracker.Hit h : tracker.update(notes,s.capture,s.ready,BeatstarDetector.HIT_LINE,0)) {
                    if (!h.revision) {
                        expect(!firstReady.containsKey(h.id),"Duplicate prediction");
                        firstReady.put(h.id,s.ready);
                    }
                    latest.put(h.id,h);
                    planner.add(h);
                }
            }
            expect(darkFrames >= 8,group + " fixture no longer reproduces the old brightness rejection");
            List<Tracker.Hit> hits = latest.values().stream().sorted(Comparator.comparingLong(h -> h.at)).toList();
            expect(hits.size() == (group.equals("teal") ? 1 : 2),"Unexpected hits for " + group + ": " + hits.size());
            for (Tracker.Hit h : hits) {
                expect(firstReady.get(h.id) <= h.at-20,group + " prediction has insufficient lead time");
                // Tolerance concerns visible glyph/line crossings, not Perfect+ score windows.
                long crossing = group.equals("teal") ? 21101 : 66157;
                expect(Math.abs(h.at-crossing) <= 8,group + " wrong crossing prediction: " + h.at);
            }
            if (group.equals("teal")) {
                expect(hits.get(0).lane == 1 && hits.get(0).kind == Kind.UP,"Lost upward swipe");
                expect(downSeen,"Real downward arrows were not recognised");
            } else {
                expect(hits.stream().allMatch(h -> h.kind == Kind.TAP),"Chord misclassified");
                expect(hits.stream().map(h -> h.lane).collect(java.util.stream.Collectors.toSet()).equals(Set.of(1,2)),
                        "Lost one side of centre/right chord");
                List<TouchPlanner.Segment> segments = planner.plan(hits.get(0).at,20);
                expect(segments.size() == 2,"Chord was split across separate Android gesture requests");
                expect(Math.abs(segments.get(0).offset-segments.get(1).offset) <= 3,"Chord contact times diverged");
                expect(planner.idle(),"Residual chord contacts");
            }
        }
        Sample failure = samples.stream().filter(s -> s.group.equals("failure")).findFirst().orElseThrow();
        Sample menu = samples.stream().filter(s -> s.group.equals("menu")).findFirst().orElseThrow();
        Sample exit = samples.stream().filter(s -> s.group.equals("exit")).findFirst().orElseThrow();
        expect(!detector.isGameplay(failure.frame),"Failure overlay accepted as gameplay");
        expect(!detector.isGameplay(menu.frame),"Menu accepted as gameplay");
        expect(detector.isGameplay(exit.frame),"Exit fixture must contain the misleading visible HUD");
        GameplayGate gate = new GameplayGate();
        expect(gate.update(true,false,0),"Initial acquisition delayed");
        for (int t=20;t<=1000;t+=20) expect(!gate.update(false,false,t),"Hidden field accepted");
        // The exit animation shows the HUD for about 430 ms. It must not resume note tracking.
        for (int t=1020;t<=1460;t+=20) expect(!gate.update(true,false,t),"Exit animation reactivated touches");
        expect(!gate.update(false,false,1480),"Menu reset");
        for (int t=1500;t<2100;t+=20) expect(!gate.update(true,false,t),"Unconfirmed return accepted");
        expect(gate.update(true,false,2100),"Stable manual return did not resume");
        for (int t=2120;t<=2600;t+=20) gate.update(false,false,t);
        for (int t=2620;t<=2820;t+=20) expect(!gate.update(false,true,t),"Opening pair treated as moving notes");
        expect(gate.update(true,false,2840),"Verified new song inherited recovery delay");
        expect(!gate.update(false,false,2860),"Missing HUD accepted");
        expect(gate.update(true,false,2880),"Single-frame interruption imposed long delay");
        expect(!gate.update(true,false,3200),"Missing frame interval counted as confirmed return");
        gate.reset();
        expect(gate.update(true,false,3300),"Explicit activation did not reset scene state");
        System.out.println("PASS: " + checks + " dark-stage / transition assertions on " + samples.size()
                + " recorded frames, recovered UP and double TAP, plus real DOWN recognition.");
    }
}
