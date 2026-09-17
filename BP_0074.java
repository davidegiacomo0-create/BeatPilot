import it.dave.beatpilot.core.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Tests corruption and timing cases that would make a diagnostic video misleading. */
public final class RecordingTests {
    private static int checks;
    private static void expect(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static void rejects(Runnable operation, String message) {
        try { operation.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        // The last row need not include padding bytes. Pixel channels and top/bottom must survive exactly.
        ByteBuffer src = ByteBuffer.allocate(25); src.position(3);
        byte[] expected = { (byte)255,0,0,(byte)255, 0,(byte)255,0,(byte)255,
                0,0,(byte)255,(byte)255, (byte)255,(byte)255,(byte)255,(byte)255 };
        for (int i = 0; i < 8; i++) { src.put(3+i,expected[i]); src.put(15+i,expected[8+i]); }
        src.limit(23); ByteBuffer out = ByteBuffer.allocateDirect(16);
        RecordingFrames.copyRgba(src,12,4,2,2,out);
        expect(src.position()==3 && src.limit()==23,"input buffer state changed");
        for (byte b : expected) expect(out.get()==b,"padded row copy changed channel or orientation");
        ByteBuffer sparse = ByteBuffer.allocate(28);
        for (int i = 0; i < 4; i++) for (int c = 0; c < 4; c++) sparse.put(i*8+c,expected[i*4+c]);
        RecordingFrames.copyRgba(sparse,16,8,2,2,out);
        for (byte b : expected) expect(out.get()==b,"pixel stride copy failed");
        ByteBuffer shortFrame = ByteBuffer.allocate(15);
        rejects(() -> RecordingFrames.copyRgba(shortFrame,8,4,2,2,out),"truncated frame accepted");
        rejects(() -> RecordingFrames.copyRgba(src,4,4,2,2,out),"invalid row stride accepted");
        rejects(() -> RecordingFrames.copyRgba(src,12,4,2,2,ByteBuffer.allocate(15)),"small target accepted");

        RecordingFrames.Clock clock = new RecordingFrames.Clock();
        expect(clock.nextUs(12000)==0,"first video frame is not at zero");
        expect(clock.nextUs(12017)==17000,"real interval was lost");
        expect(clock.nextUs(12117)==117000,"dropped frames compressed time");
        expect(clock.nextUs(12117)==117001,"duplicate timestamp is not monotonic");
        expect(clock.nextUs(12116)==117002,"backward timestamp is not monotonic");
        expect(clock.firstMs()==12000,"video/report zero mismatch");

        TraceBuffer trace = new TraceBuffer(1000);
        CountDownLatch begin = new CountDownLatch(1); List<Thread> writers = new ArrayList<>();
        for (int producer=0; producer<4; producer++) {
            int id=producer;
            Thread t=new Thread(() -> {
                try { begin.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                for (int i=0;i<600;i++) trace.add(i,"p"+id,"event-"+i);
            }); writers.add(t);t.start();
        }
        begin.countDown(); for (Thread t:writers) t.join();
        List<TraceBuffer.Event> saved=trace.snapshot();
        expect(saved.size()==1000,"trace exceeded bound or lost stored events");
        expect(trace.dropped()==1400,"trace overflow not accounted");
        saved.clear(); expect(trace.snapshot().size()==1000,"snapshot modified live trace");
        Set<String> unique=new HashSet<>();
        for (TraceBuffer.Event event:trace.snapshot()) unique.add(event.type()+event.data());
        expect(unique.size()==1000,"concurrent trace contains duplicated or torn events");

        TouchPlanner planner = new TouchPlanner(new double[]{.2,.5,.8},.72);
        planner.add(new Tracker.Hit(918,1,Kind.UP,100));
        List<TouchPlanner.Segment> first=planner.plan(100,20),second=planner.plan(120,20);
        expect(first.size()==1 && second.size()==1,"swipe segments missing");
        expect(first.get(0).noteId==918 && second.get(0).noteId==918,"diagnostic note identity lost across gesture segments");
        System.out.println("PASS: " + checks + " recording assertions: RGBA strides, timestamps, bounded concurrent trace, gesture correlation.");
    }
}
