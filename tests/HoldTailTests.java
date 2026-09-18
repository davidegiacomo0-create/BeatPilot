import it.dave.beatpilot.core.*;
import java.nio.file.*;
import java.util.*;

/** Regression for the bright hold cap and the cancelled stationary continuation. */
public final class HoldTailTests {
    private static int checks;
    private static final double LINE=.721;
    private static void expect(boolean okay,String why) { checks++;if (!okay) throw new AssertionError(why); }
    private record Input(long ready,Tracker.Hit hit) {}

    private static List<Input> recording(Path root) throws Exception {
        HoldDetector hold=new HoldDetector();
        GrayFrame bright=new GrayFrame(480,1040,Files.readAllBytes(root.resolve("071000.gray")));
        List<Detector.Detection> found=hold.detect(bright,BeatstarDetector.LANES,LINE);
        expect(found.stream().anyMatch(d -> d.lane==0 && d.kind==Kind.HOLD_END && d.y*1040>=408 && d.y*1040<=423),
                "Visible bright end cap at 71 seconds was not recognised");
        BeatstarDetector detector=new BeatstarDetector();Tracker tracker=new Tracker(BeatstarDetector.PERSPECTIVE);
        List<Input> events=new ArrayList<>();Map<Long,Tracker.Hit> finalHits=new HashMap<>();
        List<String> rows=Files.readAllLines(root.resolve("sequence.csv"));
        for (String row:rows.subList(1,rows.size())) {
            String[] f=row.split(",");long capture=Long.parseLong(f[1]),ready=Long.parseLong(f[2]);
            // Begin after the preceding successful hold/release (70317 ms in the
            // live trace). The encoded cap of that earlier note differs from live
            // pixels; do not manufacture a pending finger at this test boundary.
            if (capture<70400) continue;
            GrayFrame frame=new GrayFrame(480,1040,Files.readAllBytes(root.resolve(f[3])));
            List<Detector.Detection> notes=detector.detect(frame,BeatstarDetector.LANES,LINE,tracker.expectations(capture,LINE));
            for (Tracker.Hit h:tracker.update(notes,capture,ready,LINE,0)) {
                events.add(new Input(ready,h));finalHits.put(h.id,h);
            }
        }
        List<Tracker.Hit> heads=finalHits.values().stream().filter(h -> h.kind==Kind.HOLD_START && h.at>71000).sorted(Comparator.comparingLong(h -> h.at)).toList();
        expect(heads.size()==2,"Expected the two consecutive recorded holds: "+heads.size());
        expect(Math.abs(heads.get(0).at-71039)<=5 && Math.abs(heads.get(1).at-71759)<=5,"Hold starts changed");
        Tracker.Hit end=finalHits.values().stream().filter(h -> h.kind==Kind.HOLD_END && h.at>71480 && h.at<71600).findFirst().orElseThrow(
                () -> new AssertionError("No timely release for the first of the two holds"));
        expect(events.stream().anyMatch(e -> e.hit.id==end.id && e.ready<71029),"Tail was only detected after the hold changed appearance");
        System.out.println("Consecutive recorded holds: first down "+heads.get(0).at+", release "+end.at+", next down "+heads.get(1).at+" ms.");
        return events;
    }

    private static void dispatch(List<Input> events,int delay) {
        TouchPlanner planner=new TouchPlanner(BeatstarDetector.LANES,LINE,true);
        ContactTests.Injector input=new ContactTests.Injector();
        int cursor=0;long busy=0;
        for (long now=70400;now<72400;now++) {
            while (cursor<events.size() && events.get(cursor).ready<=now) {
                Tracker.Hit hit=events.get(cursor++).hit;planner.add(hit);
            }
            long next=planner.nextTime();
            if (now<busy || next==Long.MAX_VALUE || GestureTiming.wakeAt(next)>now) continue;
            List<TouchPlanner.Segment> segments=planner.plan(now,32);
            if (segments.isEmpty()) continue;
            input.batch(now,segments,16);input.valid();busy=now;
            for (TouchPlanner.Segment s:segments) busy=Math.max(busy,now+s.offset+s.duration);
            busy+=delay;
        }
        List<Long> heads=new ArrayList<>();
        for (Input e:events) if (!e.hit.revision && e.hit.kind==Kind.HOLD_START && e.hit.at>71000) heads.add(e.hit.id);
        expect(heads.size()==2,"Recorded holds not available to scheduler");
        expect(Math.abs(input.down(heads.get(0))-71039)<=5,"First hold down shifted");
        expect(input.up(heads.get(0))>=71480 && input.up(heads.get(0))<71600,"First hold stuck or released too early");
        expect(Math.abs(input.down(heads.get(1))-71759)<=5,"Next same-lane hold was swallowed");
        expect(input.up(heads.get(0))<input.down(heads.get(1)),"No release between consecutive holds");
        expect(input.active.isEmpty() && planner.idle(),"Recorded sequence left a held finger/queued work");
    }

    private static void stationary() {
        TouchPlanner planner=new TouchPlanner(BeatstarDetector.LANES,LINE,true);
        planner.add(new Tracker.Hit(309,0,Kind.HOLD_START,71039));
        List<TouchPlanner.Segment> first=planner.plan(71029,32);
        expect(first.size()==1 && first.get(0).more,"Setup hold not retained");
        // Test only the harmless no-op. Swallowing another same-lane note is a
        // separate release-blocking failure, covered by ReliabilityAudit.
        expect(planner.plan(71749,32).isEmpty(),"Would send an empty stationary continuation rejected by Android");
        expect(planner.nextTime()==Long.MAX_VALUE && !planner.idle(),"No-op lost or repeatedly scheduled the held pointer");
        planner.add(new Tracker.Hit(314,1,Kind.TAP,72000));
        List<TouchPlanner.Segment> pair=planner.plan(71988,32);
        expect(pair.size()==2 && pair.stream().anyMatch(s -> s.continuation && s.more),"Skipping a no-op broke the next valid gesture");
        planner.add(new Tracker.Hit(315,0,Kind.HOLD_END,72500));
        List<TouchPlanner.Segment> release=planner.plan(72488,32);
        expect(release.size()==1 && release.get(0).continuation && !release.get(0).more,"No-op lost the eventual release");
        expect(planner.idle(),"No-op regression left pending work");
    }

    private static void brightness() {
        HoldDetector detector=new HoldDetector();
        for (int lane=0;lane<3;lane++) for (int value:new int[]{175,224,225,226,245,255}) {
            List<Detector.Detection> notes=detector.detect(HoldTests.synthetic(lane,740,400,8,value),BeatstarDetector.LANES,LINE);
            expect(notes.stream().filter(d -> d.kind==Kind.HOLD_START).count()==1,"Brightness changed hold start count");
            expect(notes.stream().filter(d -> d.kind==Kind.HOLD_END).count()==1,"Dim/bright cap missing or duplicated");
            for (Detector.Detection d:notes) expect(d.lane==lane && Math.abs(d.y*1040-(d.kind==Kind.HOLD_START?740:400.5))<=1,
                    "Cap brightness changed the lane or geometric anchor");
        }
    }

    private static void sparseLiveTail(Path root) throws Exception {
        // The LIVE JSON has only these two shaded-cap observations. Compression
        // adds a third in the MP4, so replaying the MP4 alone hides the live failure.
        // Add only the genuinely bright-cap samples from the later recorded pixels.
        for (boolean alreadyPlanned:new boolean[]{false,true}) {
            Tracker tracker=new Tracker(BeatstarDetector.PERSPECTIVE);
            List<Tracker.Hit> hits=new ArrayList<>();
            hits.addAll(tracker.update(List.of(new Detector.Detection(0,Kind.HOLD_END,.3485576923076923,.96,.3)),70899,70907,LINE,0));
            hits.addAll(tracker.update(List.of(new Detector.Detection(0,Kind.HOLD_END,.35721153846153847,.96,.3)),70916,70925,LINE,0));
            expect(hits.isEmpty(),"Two samples must not invent a release");
            HoldDetector detector=new HoldDetector();
            if (alreadyPlanned) {
                GrayFrame frame=new GrayFrame(480,1040,Files.readAllBytes(root.resolve("070933.gray")));
                List<Detector.Detection> tails=detector.detect(frame,BeatstarDetector.LANES,LINE).stream().filter(d -> d.kind==Kind.HOLD_END).toList();
                expect(tails.size()==1 && !tails.get(0).recovered,"Recorded standard third sample changed");
                hits.addAll(tracker.update(tails,70932,70941,LINE,0));
                expect(hits.size()==1,"Setup must establish a standard deadline");
            }
            int before=hits.size(),brightFrames=0;
            List<String> rows=Files.readAllLines(root.resolve("sequence.csv"));
            for (String row:rows.subList(1,rows.size())) {
                String[] f=row.split(",");long capture=Long.parseLong(f[1]);
                if (capture<70949 || capture>71050) continue;
                GrayFrame frame=new GrayFrame(480,1040,Files.readAllBytes(root.resolve(f[3])));
                List<Detector.Detection> tails=detector.detect(frame,BeatstarDetector.LANES,LINE).stream()
                        .filter(d -> d.lane==0 && d.kind==Kind.HOLD_END && d.recovered).toList();
                if (!tails.isEmpty()) brightFrames++;
                hits.addAll(tracker.update(tails,capture,Long.parseLong(f[2]),LINE,0));
            }
            expect(brightFrames>=4,"Not enough real bright-cap samples exercised");
            if (alreadyPlanned) expect(hits.size()==before,"Fallback changed a previously established release");
            else {
                expect(!hits.isEmpty() && hits.stream().filter(h -> !h.revision).count()==1,"Sparse live tail not recovered exactly once");
                Tracker.Hit last=hits.get(hits.size()-1);
                expect(last.at>=71480 && last.at<71600,"Recovered release outside the measured tail passage");
                System.out.println("Sparse live tail recovered from bright frames; final release "+last.at+" ms.");
            }
        }
        // The next live cap had an inconsistent first interval under the HUD:
        // its provisional release was ~0.5 s late. Recovery must refine that one.
        Tracker tracker=new Tracker(BeatstarDetector.PERSPECTIVE);List<Tracker.Hit> hits=new ArrayList<>();
        long[] times={71566,71616,71632,71649};
        double[] ys={.33990384615384617,.3466346153846154,.35528846153846155,.3639423076923077};
        for (int i=0;i<times.length;i++) hits.addAll(tracker.update(List.of(
                new Detector.Detection(0,Kind.HOLD_END,ys[i],.96,.3)),times[i],times[i]+11,LINE,0));
        expect(!hits.isEmpty() && hits.get(hits.size()-1).at>72500,"Live provisional tail not reproduced");
        HoldDetector detector=new HoldDetector();
        List<String> rows=Files.readAllLines(root.resolve("sequence.csv"));
        for (String row:rows.subList(1,rows.size())) {
            String[] f=row.split(",");long capture=Long.parseLong(f[1]);
            if (capture<71666 || capture>71750) continue;
            GrayFrame frame=new GrayFrame(480,1040,Files.readAllBytes(root.resolve(f[3])));
            List<Detector.Detection> tails=detector.detect(frame,BeatstarDetector.LANES,LINE).stream()
                    .filter(d -> d.lane==0 && d.kind==Kind.HOLD_END && d.recovered).toList();
            hits.addAll(tracker.update(tails,capture,Long.parseLong(f[2]),LINE,0));
        }
        expect(hits.stream().filter(h -> !h.revision).count()==1,"Unstable cap recovery created a duplicate tail");
        expect(hits.get(hits.size()-1).at>=72200 && hits.get(hits.size()-1).at<=72260,"Unstable native deadline stayed locked");
        System.out.println("Unstable live tail refined to "+hits.get(hits.size()-1).at+" ms without a duplicate release.");
    }

    public static void main(String[] args) throws Exception {
        List<Input> events=recording(Path.of(args[0]));
        for (int delay:new int[]{0,5,17}) dispatch(events,delay);
        stationary();brightness();sparseLiveTail(Path.of(args[0]));
        System.out.println("PASS: "+(checks+ContactTests.modelCheckCount())+" bright hold-tail and pointer regression assertions. No game scores inferred.");
    }
}
