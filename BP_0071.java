import it.dave.beatpilot.core.*;
import java.nio.file.*;
import java.util.*;

/** Live recognition gaps + recorded pixels. These tests cannot measure Android input delivery. */
public final class PrecisionTests {
    private static int checks;
    private static void expect(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static void recoveredArrow(Path root) throws Exception {
        Tracker baseline = new Tracker(), tracker = new Tracker(BeatstarDetector.PERSPECTIVE);
        BeatstarDetector detector = new BeatstarDetector();
        Map<Long,Tracker.Hit> hits = new HashMap<>(); int baselineHits = 0, recovered = 0;
        long firstReady = -1; boolean exercisedBlank = false;
        List<String> rows = Files.readAllLines(root.resolve("lost_arrow.csv"));
        for (String row : rows.subList(1,rows.size())) {
            String[] f = row.split(","); long capture = Long.parseLong(f[1]), ready = Long.parseLong(f[2]);
            GrayFrame frame = new GrayFrame(480,1040,Files.readAllBytes(root.resolve(f[3])));
            // The live trace is authoritative about recognition failure. H.264 changes pixels:
            // replaying the compressed MP4 alone already recognises this arrow in the old build.
            List<Detector.Detection> live = Boolean.parseBoolean(f[4])
                    ? List.of(new Detector.Detection(2,Kind.LEFT,Double.parseDouble(f[5]),Double.parseDouble(f[6]),.16))
                    : List.of();
            for (Tracker.Hit h : baseline.update(live,capture,ready,.721,0)) if (!h.revision) baselineHits++;
            List<Detector.Detection> hints = tracker.expectations(capture,.721);
            if (live.isEmpty() && !hints.isEmpty()) {
                expect(detector.recover(new GrayFrame(480,1040,new byte[480*1040]),BeatstarDetector.LANES,
                        .721,List.of(),hints).isEmpty(),"A motion hint invented an arrow on blank pixels");
                List<Detector.Detection> wrong = hints.stream().map(h -> new Detector.Detection(h.lane,Kind.RIGHT,h.y,1,h.height)).toList();
                expect(detector.recover(frame,BeatstarDetector.LANES,.721,List.of(),wrong).isEmpty(),
                        "Recovery accepted the wrong arrow direction");
                exercisedBlank = true;
            }
            List<Detector.Detection> observed = detector.recover(frame,BeatstarDetector.LANES,.721,live,hints);
            recovered += (int)observed.stream().filter(d -> d.recovered).count();
            for (Tracker.Hit h : tracker.update(observed,capture,ready,.721,0)) {
                if (!h.revision && firstReady < 0) firstReady = ready;
                hits.put(h.id,h);
            }
        }
        expect(baselineHits == 0,"Fixture must reproduce the actual live missed note");
        expect(exercisedBlank && recovered >= 3,"Lost-note recovery was not exercised");
        expect(hits.size() == 1,"Recovered arrow missing or duplicated");
        Tracker.Hit h = hits.values().iterator().next();
        expect(h.kind == Kind.LEFT && h.lane == 2,"Wrong recovered gesture");
        expect(Math.abs(h.at-84058) <= 8,"Recovered arrow prediction outside visible-crossing tolerance: "+h.at);
        expect(firstReady < h.at-20,"Recovered arrow was recognised too late");
        System.out.println("Recovered live-missed LEFT at "+h.at+" ms; ready "+firstReady+"; "+recovered+" pixel confirmations.");
    }
    private static void projectionAndDuplicates() {
        for (double speed : new double[]{.00035,.0007,.0014,.0022}) for (int phase : new int[]{0,5,11}) {
            Tracker tracker = new Tracker(BeatstarDetector.PERSPECTIVE);
            Map<Long,Tracker.Hit> hits = new HashMap<>(); double previous = 0;
            for (int t=600+phase;t<1040;t+=16) {
                double u=speed*(t-1000), y=.721+u/(1-.55*u);
                // One captured image repeats game pixels while time still advances.
                if (t>=940 && t<956) y=previous; else previous=y;
                for (Tracker.Hit h : tracker.update(List.of(new Detector.Detection(1,Kind.TAP,y,1,.12)),t,t+9,.721,0))
                    hits.put(h.id,h);
            }
            expect(hits.size()==1,"Projective note was lost or duplicated");
            expect(Math.abs(hits.values().iterator().next().at-1000)<=2,"Perspective/duplicate-frame timing drift");
        }
    }
    private static void gestures() {
        TouchPlanner planner = new TouchPlanner(BeatstarDetector.LANES,.721);
        for (Tracker.Hit h : StartGate.openingHits(1000)) planner.add(h);
        List<TouchPlanner.Segment> pair = planner.plan(1080,250);
        expect(pair.size()==2 && pair.stream().allMatch(s -> s.offset==0 && s.duration==160 && !s.more),
                "A delayed opening lost one or both stationary buttons");
        expect(planner.idle(),"Opening left a continuing contact");
        planner.add(new Tracker.Hit(1,0,Kind.TAP,2000));
        long now=GestureTiming.wakeAt(2000);
        List<TouchPlanner.Segment> tap=planner.plan(now,GestureTiming.HORIZON_MS);
        expect(tap.size()==1 && now+tap.get(0).offset==2000,"Early submission advanced the actual contact");
        expect(tap.get(0).duration==8 && !tap.get(0).more,"Normal tap was stretched or fragmented");
        planner.add(new Tracker.Hit(2,2,Kind.TAP,2031));
        tap=planner.plan(2000,32);
        expect(tap.size()==1 && tap.get(0).duration==8 && !tap.get(0).more,
                "A tap at the planning boundary acquired a delayed continuation");
        planner.clear();
        planner.add(new Tracker.Hit(3,1,Kind.UP,3000));
        List<TouchPlanner.Segment> swipe=planner.plan(2988,32);
        TouchPlanner.Segment first=swipe.get(0);
        expect(first.offset==12 && first.more,"Swipe start offset lost");
        swipe=planner.plan(3023,32);
        expect(swipe.size()==1 && swipe.get(0).continuation && swipe.get(0).y0==first.y1
                && !swipe.get(0).more && Math.abs(swipe.get(0).y1-.676)<1e-6,"Swipe continuation broke after callback delay");
    }
    private static void recordedFrozenFrame(Path root) throws Exception {
        Tracker tracker = new Tracker(BeatstarDetector.PERSPECTIVE);
        Map<Long,Tracker.Hit> hits = new HashMap<>();
        List<String> rows = Files.readAllLines(root.resolve("duhast_observations.csv"));
        for (String row : rows.subList(1,rows.size())) {
            String[] f=row.split(","); long capture=Long.parseLong(f[1]),ready=Long.parseLong(f[2]);
            if (capture<8900 || capture>9250 || !f[4].equals("0") || !f[5].equals("TAP")) continue;
            List<Tracker.Hit> events=tracker.update(List.of(new Detector.Detection(0,Kind.TAP,
                    Double.parseDouble(f[6]),Double.parseDouble(f[7]),.14)),capture,ready,.721,0);
            if (capture==9150) expect(events.isEmpty(),"Frozen game frame created an early contact");
            for (Tracker.Hit h:events) hits.put(h.id,h);
        }
        expect(hits.size()==1,"Real frozen-frame passage lost or duplicated a tap");
        expect(Math.abs(hits.values().iterator().next().at-9209)<=8,"Frozen frame shifted the tap away from the visible crossing");
    }
    private static void pauseAfterPrediction(Path root) throws Exception {
        for (boolean encoded : new boolean[]{false,true}) {
            Tracker tracker = new Tracker(BeatstarDetector.PERSPECTIVE);
            Map<Long,Tracker.Hit> hits = new HashMap<>();
            List<String> rows = Files.readAllLines(root.resolve(encoded
                    ? "duhast_frozen_encoded.csv" : "duhast_observations.csv"));
            boolean correctedPause = false; long firstDue = -1;
            for (String row : rows.subList(1,rows.size())) {
                String[] f=row.split(","); long capture=Long.parseLong(f[1]),ready=Long.parseLong(f[2]);
                int laneColumn=encoded ? 3 : 4;
                if (capture<70680 || capture>71050 || !f[laneColumn].equals("0")
                        || !f[laneColumn+1].equals("TAP")) continue;
                for (Tracker.Hit h : tracker.update(List.of(new Detector.Detection(0,Kind.TAP,
                        Double.parseDouble(f[laneColumn+2]),Double.parseDouble(f[laneColumn+3]),.14)),
                        capture,ready,.721,0)) {
                    if (!h.revision) firstDue=h.at;
                    if (capture==70916 && h.revision && h.at>firstDue+8) correctedPause=true;
                    hits.put(h.id,h);
                }
            }
            expect(hits.size()==1,"Pause after prediction lost or duplicated a tap");
            expect(correctedPause,"Already queued contact ignored a visible pause");
            long due=hits.values().iterator().next().at;
            expect(Math.abs(due-70961)<=8,"Pause after prediction shifted the contact: "+due);
            System.out.println("Du hast queued-pause correction ("+(encoded ? "encoded" : "live")+"): "+firstDue+" -> "+due+" ms.");
        }
    }
    public static void main(String[] args) throws Exception {
        recoveredArrow(Path.of(args[0])); projectionAndDuplicates(); gestures(); recordedFrozenFrame(Path.of(args[0]));
        pauseAfterPrediction(Path.of(args[0]));
        System.out.println("PASS: "+checks+" precision/recovery/gesture assertions. No Perfect+ scores inferred.");
    }
}
