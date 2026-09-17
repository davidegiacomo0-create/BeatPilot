import it.dave.beatpilot.core.*;
import java.nio.file.*;
import java.util.*;

/** Pixel recognition and pointer lifecycle; no physical Android input or game score measured. */
public final class HoldTests {
    private static int checks;
    private static void expect(boolean okay,String why) { checks++; if (!okay) throw new AssertionError(why); }
    private static final double LINE=.721;
    private record Event(long ready,Tracker.Hit hit) {}

    private static List<Event> recorded(Path root) throws Exception {
        BeatstarDetector detector=new BeatstarDetector(); Tracker tracker=new Tracker(BeatstarDetector.PERSPECTIVE);
        List<Event> events=new ArrayList<>(); Map<Long,Tracker.Hit> finalHits=new HashMap<>();
        int heads=0,tails=0; boolean isolatedTail=false;
        List<String> lines=Files.readAllLines(root.resolve("holds/sequence.csv"));
        for (String row:lines.subList(1,lines.size())) {
            String[] f=row.split(","); long capture=Long.parseLong(f[1]),ready=Long.parseLong(f[2]);
            GrayFrame frame=new GrayFrame(480,1040,Files.readAllBytes(root.resolve("holds/"+f[3])));
            List<Detector.Detection> found=detector.detect(frame,BeatstarDetector.LANES,LINE,tracker.expectations(capture,LINE));
            boolean tailHere=false,firstHeadHere=false;
            for (Detector.Detection d:found) {
                if (d.kind==Kind.HOLD_START) {
                    heads++; expect(d.lane==0,"Ordinary neighbouring note became a hold");
                    if (d.y>.60) firstHeadHere=true;
                    expect(found.stream().noneMatch(n -> n.kind==Kind.TAP && n.lane==d.lane
                            && Math.abs(n.y-d.y)<.016),"Hold foot was also emitted as a tap");
                }
                if (d.kind==Kind.HOLD_END) { tails++; tailHere=true; expect(d.lane==0,"Wrong tail lane"); }
            }
            if (capture>6970 && tailHere && !firstHeadHere) isolatedTail=true;
            for (Tracker.Hit h:tracker.update(found,capture,ready,LINE,0)) {
                events.add(new Event(ready,h)); finalHits.put(h.id,h);
            }
        }
        expect(heads>=15 && tails>=15,"Real head/tail recognition not exercised");
        expect(isolatedTail,"Tail was not recognised after the foot left the region");
        expect(finalHits.size()==3,"Expected one hold, one release and the ordinary middle tap");
        Tracker.Hit head=finalHits.values().stream().filter(h -> h.kind==Kind.HOLD_START).findFirst().orElseThrow();
        Tracker.Hit tail=finalHits.values().stream().filter(h -> h.kind==Kind.HOLD_END).findFirst().orElseThrow();
        Tracker.Hit tap=finalHits.values().stream().filter(h -> h.kind==Kind.TAP).findFirst().orElseThrow();
        expect(Math.abs(head.at-6645)<=8,"Hold foot no longer meets visible judgement line: "+head.at);
        expect(tail.at>=7135 && tail.at<=7155,"Release must follow the cap beyond the lower contact: "+tail.at);
        expect(Math.abs(tap.at-7123)<=3 && tap.lane==1,"Neighbouring tap timing changed");
        expect(events.stream().anyMatch(e -> e.hit.kind==Kind.HOLD_END && e.ready<head.at),
                "No end deadline retained before the hold could change appearance");
        expect(events.stream().filter(e -> e.hit.kind==Kind.HOLD_START && !e.hit.revision).count()==1,
                "One hold generated repeated presses");
        System.out.println("Recorded hold: down "+head.at+", up "+tail.at+"; neighbouring tap "+tap.at+" ms.");
        return events;
    }

    private static void dispatchRecorded(List<Event> events,int callbackDelay) {
        TouchPlanner planner=new TouchPlanner(BeatstarDetector.LANES,LINE,true);
        Map<Long,TouchPlanner.Segment> down=new HashMap<>();
        int cursor=0,starts=0,releases=0,taps=0,batches=0; long busyUntil=0,headAt=-1,upAt=-1,tapAt=-1;
        for (long now=5800;now<7300;now++) {
            while (cursor<events.size() && events.get(cursor).ready<=now) planner.add(events.get(cursor++).hit);
            long next=planner.nextTime();
            if (now<busyUntil || next==Long.MAX_VALUE || GestureTiming.wakeAt(next)>now) continue;
            List<TouchPlanner.Segment> segments=planner.plan(now,GestureTiming.HORIZON_MS);
            if (segments.isEmpty()) continue;
            batches++; Map<Long,TouchPlanner.Segment> following=new HashMap<>(); long end=now;
            for (TouchPlanner.Segment s:segments) {
                if (s.continuation) {
                    TouchPlanner.Segment before=down.get(s.id);
                    expect(before!=null && before.x1==s.x0 && before.y1==s.y0,"Held finger continuity lost");
                    if (!s.more) { releases++; upAt=now+s.offset+s.duration; }
                } else if (s.more) { starts++; headAt=now+s.offset; }
                else { taps++; tapAt=now+s.offset; }
                if (s.more || s.continuation) expect(Math.abs(s.y0-(LINE+HoldTiming.CONTACT_OFFSET))<1e-9,
                        "Held contact moved out of its lower position");
                if (s.more) following.put(s.id,s);
                end=Math.max(end,now+s.offset+s.duration);
            }
            for (long id:down.keySet()) expect(segments.stream().anyMatch(s -> s.id==id),
                    "Another note omitted an existing held pointer");
            down=following; busyUntil=end+callbackDelay;
        }
        expect(starts==1 && releases==1 && taps==1,"Wrong press/release/tap count");
        expect(Math.abs(headAt-6645)<=8 && Math.abs(tapAt-7123)<=3,"Hold delayed the neighbouring tap");
        expect(upAt>=7135 && upAt<=7170,"Release missing, early, or unreasonably late");
        expect(batches<=4,"Stationary hold generated continuous keepalive gestures");
        expect(planner.idle() && down.isEmpty(),"Held finger remained after its end");
    }

    private static GrayFrame synthetic(int lane,int head,int tail,int stemWidth) {
        return synthetic(lane,head,tail,stemWidth,175);
    }

    static GrayFrame synthetic(int lane,int head,int tail,int stemWidth,int capValue) {
        byte[] pixels=new byte[480*1040]; Arrays.fill(pixels,(byte)210);
        for (int y=Math.max(0,tail-15);y<Math.min(1040,head+45);y++) {
            double scale=1+.55*(y/1040.0-LINE),width=.285*480*scale;
            double center=(.5+(BeatstarDetector.LANES[lane]-.5)*scale)*480;
            for (int x=Math.max(0,(int)(center-.48*width));x<Math.min(480,(int)(center+.48*width));x++) pixels[y*480+x]=20;
            int half=0,value=245;
            if (y>=tail && y<tail+8) { half=(int)(stemWidth*.75);value=capValue; }
            else if (y>=tail+8 && y<head+4) half=stemWidth/2;
            if (y>=head-4 && y<head+4) half=(int)(width*.30);
            if (half>0) for (int x=(int)center-half;x<=(int)center+half;x++) if (x>=0 && x<480) pixels[y*480+x]=(byte)value;
        }
        return new GrayFrame(480,1040,pixels);
    }

    private static void shapes(Path root) throws Exception {
        HoldDetector detector=new HoldDetector();
        for (int lane=0;lane<3;lane++) for (int[] shape:new int[][]{{740,400},{740,150},{980,740},{700,640}}) {
            List<Detector.Detection> found=detector.detect(synthetic(lane,shape[0],shape[1],8),BeatstarDetector.LANES,LINE);
            boolean headExpected=shape[0]<850, tailExpected=shape[1]>350;
            expect(found.stream().filter(d -> d.kind==Kind.HOLD_START).count()==(headExpected?1:0),"Cropped/short hold head wrong");
            expect(found.stream().filter(d -> d.kind==Kind.HOLD_END).count()==(tailExpected?1:0),"Cropped/short hold tail wrong");
            for (Detector.Detection d:found) expect(d.lane==lane && Math.abs(d.y*1040-(d.kind==Kind.HOLD_START?shape[0]:shape[1]+.5))<=1,
                    "Hold anchor is not the foot/cap");
        }
        expect(detector.detect(synthetic(0,740,400,0),BeatstarDetector.LANES,LINE).isEmpty(),"Plain tap became a hold");
        expect(detector.detect(synthetic(0,740,400,2),BeatstarDetector.LANES,LINE).isEmpty(),"Thin debug line became a hold");
        int previous=0;
        try (var paths=Files.walk(root)) {
            for (Path p:paths.filter(p -> p.toString().endsWith(".gray") && !p.startsWith(root.resolve("holds"))).toList()) {
                byte[] data=Files.readAllBytes(p); if (data.length!=480*1040) continue;
                expect(detector.detect(new GrayFrame(480,1040,data),BeatstarDetector.LANES,LINE).isEmpty(),
                        "Existing normal-note fixture became a hold: "+p.getFileName()); previous++;
            }
        }
        System.out.println("Rejected false holds on "+previous+" previous real frames.");
    }

    private static void lifecycle() {
        TouchPlanner planner=new TouchPlanner(BeatstarDetector.LANES,LINE,true);
        planner.add(new Tracker.Hit(1,0,Kind.HOLD_START,1000));
        planner.add(new Tracker.Hit(2,2,Kind.HOLD_START,1000));
        List<TouchPlanner.Segment> pair=planner.plan(988,32);
        expect(pair.size()==2 && pair.stream().allMatch(s -> s.offset==12 && s.more),"Two holds not started together");
        expect(planner.nextTime()==Long.MAX_VALUE && !planner.idle(),"Stationary holds should wait with fingers down");
        planner.add(new Tracker.Hit(3,1,Kind.UP,1200));
        expect(planner.nextTime()==1200,"Held pointers obscured the next note deadline");
        List<TouchPlanner.Segment> swipe=planner.plan(1188,32);
        expect(swipe.size()==3 && swipe.stream().filter(s -> s.continuation).count()==2,"Swipe interrupted simultaneous holds");
        while (planner.nextTime()==0) planner.plan(1225,50);
        planner.add(new Tracker.Hit(4,0,Kind.HOLD_END,1500));
        planner.add(new Tracker.Hit(8,0,Kind.TAP,1508));
        List<TouchPlanner.Segment> firstRelease=planner.plan(1488,32);
        expect(firstRelease.size()==3 && firstRelease.stream().filter(s -> s.continuation && !s.more).count()==1,
                "Release affected the wrong fingers");
        expect(firstRelease.stream().anyMatch(s -> !s.continuation && !s.more && s.offset==20 && s.duration==8),
                "Hold swallowed the following note in its own lane");
        expect(planner.nextTime()==Long.MAX_VALUE,"Other held finger should remain down");
        planner.add(new Tracker.Hit(5,2,Kind.HOLD_END,12000));
        List<TouchPlanner.Segment> lastRelease=planner.plan(11988,32);
        expect(lastRelease.size()==1 && lastRelease.get(0).continuation && !lastRelease.get(0).more
                && lastRelease.get(0).duration==12,"Long hold was cut at a fixed duration");
        expect(planner.idle(),"Final release left state behind");
        planner.add(new Tracker.Hit(6,0,Kind.HOLD_START,13000));planner.plan(12988,32);planner.clear();
        expect(planner.idle() && planner.plan(14000,32).isEmpty(),"Stop left pending hold work");
        planner.add(new Tracker.Hit(7,0,Kind.HOLD_END,15000));
        expect(planner.plan(14988,32).isEmpty(),"An isolated tail created a touch");
    }

    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]); List<Event> events=recorded(root);
        for (int delay:new int[]{0,5,17}) dispatchRecorded(events,delay);
        shapes(root); lifecycle();
        System.out.println("PASS: "+checks+" hold recognition/continuity/release assertions. No game score inferred.");
    }
}
