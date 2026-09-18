import it.dave.beatpilot.core.*;
import java.nio.file.*;
import java.util.*;

/** AOSP pointer-index contract model. Does not run Android, Unity, or score the game.
 * Sources/method and recording provenance: fixtures/contacts/README.md.
 */
public final class ContactTests {
    private static int checks;
    static int modelCheckCount() { return checks; }
    private static final double LINE=.7210000157356262;
    private static final double[] LANES={.20499999821186066,.5,.7950000166893005};
    private static void expect(boolean okay,String why) {
        checks++; if (!okay) throw new AssertionError(why);
    }
    private record Input(long ready,Tracker.Hit hit) {}
    private record Transition(long time,boolean down,long note,int index,int count) {}

    /** Independent observable contract: start/end sampling, UP before DOWN,
     * current-sample index on DOWN, retained pointers between continuations.
     * IDs here are stable contact IDs instead of AOSP's remapped stroke IDs.
     */
    static final class Injector {
        final List<Long> active=new ArrayList<>();
        final Map<Long,TouchPlanner.Segment> retained=new HashMap<>();
        final List<Transition> transitions=new ArrayList<>();
        final List<String> errors=new ArrayList<>();
        void batch(long at,List<TouchPlanner.Segment> strokes,int sampleMs) {
            Set<Long> continued=new HashSet<>();
            for (TouchPlanner.Segment s:strokes) if (s.continuation) {
                TouchPlanner.Segment old=retained.get(s.id);
                expect(old!=null && s.offset==0 && old.x1==s.x0 && old.y1==s.y0,
                        "Invalid continuation for note "+s.noteId);
                continued.add(s.id);
            }
            expect(continued.equals(retained.keySet()),"An existing finger was omitted");
            if (!retained.isEmpty()) for (TouchPlanner.Segment s:strokes)
                expect(s.continuation || s.offset>0,"New pointer in Android's first continuation sample");
            TreeSet<Long> keys=new TreeSet<>();
            for (TouchPlanner.Segment s:strokes) {
                expect(s.offset>=0 && s.duration>0,"Invalid stroke timing");
                keys.add(s.offset); keys.add(s.offset+s.duration);
            }
            if (keys.isEmpty()) return;
            long time=keys.first();
            while (true) {
                List<TouchPlanner.Segment> points=new ArrayList<>();
                for (TouchPlanner.Segment s:strokes)
                    if (s.offset<=time && time<=s.offset+s.duration) points.add(s);
                for (TouchPlanner.Segment s:points) if (!s.more && time==s.offset+s.duration) {
                    int index=active.indexOf(s.id);
                    expect(index>=0,"Release without a pointer: "+s.noteId);
                    transitions.add(new Transition(at+time,false,s.noteId,index,active.size()));
                    active.remove(index);
                }
                for (int i=0;i<points.size();i++) {
                    TouchPlanner.Segment s=points.get(i);
                    if (!s.continuation && time==s.offset) {
                        expect(!active.contains(s.id),"Pointer began twice");
                        active.add(s.id);
                        // AOSP appendDownEvents uses the point's index in this sample,
                        // after appendUpEvents has already removed ending pointers.
                        if (i>=active.size() || active.get(i)!=s.id)
                            errors.add("t="+(at+time)+" note="+s.noteId+" index="+i+" count="+active.size());
                        transitions.add(new Transition(at+time,true,s.noteId,i,active.size()));
                    }
                }
                Long next=keys.higher(time);
                if (next==null) break;
                time=points.isEmpty()?next:Math.min(next,time+sampleMs);
            }
            retained.clear();
            for (TouchPlanner.Segment s:strokes) if (s.more) retained.put(s.id,s);
            expect(new HashSet<>(active).equals(retained.keySet()),"Unexpected fingers after gesture");
        }
        void valid() { expect(errors.isEmpty(),"Wrong Android pointer action: "+errors); }
        long down(long id) { return transitions.stream().filter(t -> t.down && t.note==id).findFirst().orElseThrow().time; }
        long up(long id) { return transitions.stream().filter(t -> !t.down && t.note==id).findFirst().orElseThrow().time; }
    }

    private static List<String[]> rows(Path file) throws Exception {
        List<String[]> result=new ArrayList<>(); List<String> lines=Files.readAllLines(file);
        for (String line:lines.subList(1,lines.size())) result.add(line.split(","));
        return result;
    }
    private static TouchPlanner.Segment segment(String[] f) {
        TouchPlanner.Segment s=new TouchPlanner.Segment(); s.id=s.noteId=Long.parseLong(f[1]);
        s.x0=Double.parseDouble(f[2]);s.y0=Double.parseDouble(f[3]);
        s.x1=Double.parseDouble(f[4]);s.y1=Double.parseDouble(f[5]);
        s.offset=Long.parseLong(f[6]);s.duration=Long.parseLong(f[7]);
        s.continuation=Boolean.parseBoolean(f[8]);s.more=Boolean.parseBoolean(f[9]);return s;
    }

    private static void recording(Path root,int sampleMs) throws Exception {
        Map<Integer,List<TouchPlanner.Segment>> originals=new HashMap<>();
        for (String[] f:rows(root.resolve("segments.csv")))
            originals.computeIfAbsent(Integer.parseInt(f[0]),k -> new ArrayList<>()).add(segment(f));
        List<Input> hits=new ArrayList<>();
        for (String[] f:rows(root.resolve("hits.csv"))) hits.add(new Input(Long.parseLong(f[0]),
                new Tracker.Hit(Long.parseLong(f[1]),Integer.parseInt(f[2]),Kind.valueOf(f[3]),
                        Long.parseLong(f[4]),Boolean.parseBoolean(f[5]),Boolean.parseBoolean(f[6]))));
        TouchPlanner planner=new TouchPlanner(LANES,LINE,true);
        Injector before=new Injector(),after=new Injector(); int cursor=0,changedEnds=0;
        for (String[] f:rows(root.resolve("batches.csv"))) {
            int batch=Integer.parseInt(f[0]); long now=Long.parseLong(f[2]);
            while (cursor<hits.size() && hits.get(cursor).ready<=now) planner.add(hits.get(cursor++).hit);
            List<TouchPlanner.Segment> old=originals.get(batch);
            before.batch(now,old,sampleMs);
            List<TouchPlanner.Segment> planned=planner.plan(now,batch==1?250:32);
            expect(planned.size()==old.size(),"Recorded contact count changed, batch "+batch);
            for (int i=0;i<planned.size();i++) {
                TouchPlanner.Segment a=old.get(i),b=planned.get(i);
                expect(a.noteId==b.noteId && a.offset==b.offset && a.continuation==b.continuation && a.more==b.more,
                        "Recorded note/start/continuation changed, batch "+batch);
                expect(a.x0==b.x0 && a.y0==b.y0 && a.x1==b.x1 && a.y1==b.y1,
                        "Recorded pointer trajectory changed, batch "+batch);
                if (a.duration!=b.duration) {
                    changedEnds++;
                    expect(batch==30 && b.noteId==30 && b.duration==a.duration+1,"Unexpected duration adjustment");
                }
            }
            after.batch(now,planned,sampleMs); after.valid();
        }
        expect(before.errors.equals(List.of("t=16401 note=33 index=1 count=1")),
                "Negative control no longer reproduces the recorded boundary: "+before.errors);
        expect(changedEnds==1,"The failing boundary was not isolated");
        expect(after.down(33)==16401 && after.up(30)==16402,"Tap timing moved or hold released early");
        expect(after.active.isEmpty(),"Recording ended with a stuck pointer");
        planner.clear(); expect(planner.idle(),"Cancellation left work queued");
        System.out.println("Live trace, sample "+sampleMs+" ms: 33 batches; baseline bad index at 16401; fixed tap 16401, release 16402.");
    }

    private static TouchPlanner make() { return new TouchPlanner(LANES,LINE,true); }
    private static void hit(TouchPlanner p,long id,int lane,Kind kind,long at) { p.add(new Tracker.Hit(id,lane,kind,at)); }
    private static void boundaryCases(int sampleMs) {
        // All ordinary note kinds at, just before, and just after a hold's end.
        for (Kind kind:new Kind[]{Kind.TAP,Kind.LEFT,Kind.RIGHT,Kind.UP,Kind.DOWN,Kind.HOLD_START})
            for (int gap=-2;gap<=2;gap++) for (int lane:new int[]{1,2}) {
                TouchPlanner p=make();Injector input=new Injector();
                hit(p,1,0,Kind.HOLD_START,1000); input.batch(988,p.plan(988,32),sampleMs);
                hit(p,2,0,Kind.HOLD_END,1500);hit(p,3,lane,kind,1500+gap);
                long now=1488;
                List<TouchPlanner.Segment> batch=p.plan(now,32);input.batch(now,batch,sampleMs);input.valid();
                expect(input.down(3)==1500+gap,"New note deadline shifted");
                expect(input.up(1)==1500+(gap==0?1:0),"Hold end adjustment too large/early");
                for (TouchPlanner.Segment s:batch) now=Math.max(now,1488+s.offset+s.duration);
                while (p.nextTime()==0) { List<TouchPlanner.Segment> next=p.plan(now+5,32);
                    input.batch(now+5,next,sampleMs);input.valid();
                    long end=now+5;for (TouchPlanner.Segment s:next) end=Math.max(end,now+5+s.offset+s.duration);now=end; }
                if (kind==Kind.HOLD_START) {hit(p,4,lane,Kind.HOLD_END,2000);input.batch(1988,p.plan(1988,32),sampleMs);}
                input.valid();expect(input.active.isEmpty() && p.idle(),"Final finger not released");
            }
        // A third finger during TWO ongoing holds must identify the new pointer,
        // even though stationary hold continuations used to have only 1 ms duration.
        TouchPlanner p=make(); Injector input=new Injector();
        hit(p,1,0,Kind.HOLD_START,1000);hit(p,2,2,Kind.HOLD_START,1000);
        input.batch(988,p.plan(988,32),sampleMs);
        hit(p,3,1,Kind.TAP,1200);input.batch(1188,p.plan(1188,32),sampleMs);input.valid();
        expect(input.down(3)==1200 && input.active.size()==2,"Tap interrupted held contacts");
        hit(p,4,0,Kind.HOLD_END,1500);hit(p,5,2,Kind.HOLD_END,1500);hit(p,6,1,Kind.TAP,1500);
        input.batch(1488,p.plan(1488,32),sampleMs);input.valid();
        expect(input.down(6)==1500 && input.up(1)==1501 && input.up(2)==1501,"Multiple release/start collision");
        expect(input.active.isEmpty() && p.idle(),"Multiple releases left fingers down");
        // Queue order cannot swallow a same-lane note at exactly the release time.
        for (boolean releaseFirst:new boolean[]{false,true}) {
            p=make();input=new Injector();hit(p,1,0,Kind.HOLD_START,1000);input.batch(988,p.plan(988,32),sampleMs);
            if (releaseFirst) hit(p,2,0,Kind.HOLD_END,1500);
            hit(p,3,0,Kind.TAP,1500);
            if (!releaseFirst) hit(p,2,0,Kind.HOLD_END,1500);
            input.batch(1488,p.plan(1488,32),sampleMs);input.valid();
            expect(input.down(3)==1500 && input.active.isEmpty(),"Equal-time same-lane tap lost");
        }
        // Collision also applies to ordinary short taps and final swipe segments.
        for (Kind kind:new Kind[]{Kind.TAP,Kind.LEFT,Kind.RIGHT,Kind.UP,Kind.DOWN}) {
            p=make();input=new Injector();hit(p,1,0,kind,1000);long end=kind==Kind.TAP?1008:1050;
            hit(p,2,1,Kind.TAP,end);
            input.batch(988,p.plan(988,32),sampleMs);input.valid();
            if (kind!=Kind.TAP) { input.batch(1025,p.plan(1025,32),sampleMs);input.valid(); }
            expect(input.down(2)==end && input.up(1)==end+1,"Tap/swipe boundary moved start");
            expect(input.active.isEmpty(),"Tap/swipe left a finger down");
        }
        // A callback arriving at/after a new note deadline must not make Android
        // reject the gesture: the first continuation sample can contain only old fingers.
        for (int late:new int[]{0,5,17}) {
            p=make();input=new Injector();hit(p,1,0,Kind.HOLD_START,1000);
            input.batch(988,p.plan(988,32),sampleMs);
            hit(p,2,1,Kind.TAP,1200);
            input.batch(1200+late,p.plan(1200+late,32),sampleMs);input.valid();
            expect(input.down(2)==1201+late,"Late continuation start needs exactly a 1 ms offset");
            expect(input.active.size()==1 && input.up(2)==1209+late,"Late tap lost or hold released");
            hit(p,3,0,Kind.HOLD_END,1500);input.batch(1488,p.plan(1488,32),sampleMs);input.valid();
            expect(input.active.isEmpty() && p.idle(),"Late scenario left a finger down");
        }
    }

    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);
        for (int sampleMs:new int[]{8,16}) { recording(root,sampleMs);boundaryCases(sampleMs); }
        System.out.println("PASS: "+checks+" Android pointer contract assertions. No device/game score inferred.");
    }
}
