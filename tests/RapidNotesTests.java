import it.dave.beatpilot.core.*;
import java.nio.file.*;
import java.util.*;

/** Actual pixels from the failed rapid left/centre/left passage, with recorded frame times. */
public final class RapidNotesTests {
    private static int checks;
    private static void expect(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        BeatstarDetector detector = new BeatstarDetector(); Tracker tracker = new Tracker(BeatstarDetector.PERSPECTIVE);
        Map<Long,Tracker.Hit> latest = new HashMap<>();
        Map<Long,Long> firstReady = new HashMap<>();
        boolean separated = false;
        List<String> rows = Files.readAllLines(root.resolve("sequence.csv"));
        for (String row : rows.subList(1,rows.size())) {
            String[] fields = row.split(",");
            long capture = Long.parseLong(fields[1]), ready = Long.parseLong(fields[2]);
            GrayFrame frame = new GrayFrame(480,1040,Files.readAllBytes(root.resolve(fields[3])));
            expect(detector.isGameplay(frame), "Rapid passage lost gameplay gate");
            List<Detector.Detection> notes = detector.detect(frame,BeatstarDetector.LANES,BeatstarDetector.HIT_LINE);
            if (notes.stream().filter(n -> n.lane == 0 && n.kind == Kind.TAP).count() == 2
                    && notes.stream().anyMatch(n -> n.lane == 1 && n.kind == Kind.TAP)) separated = true;
            for (Tracker.Hit hit : tracker.update(notes,capture,ready,BeatstarDetector.HIT_LINE,0)) {
                if (!hit.revision) {
                    expect(!firstReady.containsKey(hit.id), "Same note planned twice");
                    firstReady.put(hit.id,ready);
                }
                latest.put(hit.id,hit);
            }
        }
        expect(separated, "Two close left tiles were merged instead of recognized separately");
        List<Tracker.Hit> hits = latest.values().stream().sorted(Comparator.comparingLong(h -> h.at)).toList();
        expect(hits.size() == 3, "Expected exactly three rapid taps, got " + hits.size());
        // Bar/line crossings annotated from the original video, not game judgement windows.
        long[] crossings = {84971,85083,85196}; int[] lanes = {0,1,0};
        TouchPlanner planner = new TouchPlanner(BeatstarDetector.LANES,BeatstarDetector.HIT_LINE);
        for (int i = 0; i < hits.size(); i++) {
            Tracker.Hit h = hits.get(i);
            expect(h.kind == Kind.TAP && h.lane == lanes[i], "Wrong gesture/order for rapid note " + i);
            expect(Math.abs(h.at-crossings[i]) <= 8, "Rapid prediction outside 8-ms replay tolerance: " + h.at);
            expect(firstReady.get(h.id) < h.at, "Rapid note was planned after its deadline");
            planner.add(new Tracker.Hit(h.id,h.lane,h.kind,h.at));
        }
        for (Tracker.Hit h : hits) {
            List<TouchPlanner.Segment> segments = planner.plan(h.at,20);
            expect(segments.size() == 1 && segments.get(0).noteId == h.id && !segments.get(0).more,
                    "Rapid note merged with or cancelled its neighbour");
        }
        expect(planner.idle(), "Unexpected extra tap left in the rapid sequence");
        System.out.println("PASS: " + checks + " assertions on " + (rows.size()-1) + " consecutive real rapid-note frames; three distinct taps at "
                + hits.stream().map(h -> Long.toString(h.at)).toList() + " ms. No game score inferred.");
    }
}
