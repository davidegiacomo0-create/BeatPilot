import it.dave.beatpilot.core.*;
import java.nio.file.*;
import java.util.*;

/** Manually labelled real frames. These are recognition checks, not game-score tests. */
public final class RecordedFrameTests {
    private static int checks;
    private static void expect(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]);
        String[] names = {"000000", "006500", "012000", "023750", "028950", "032450", "036000",
                "037950", "042750", "049450", "060250", "066850", "070500", "072250"};
        String[] expected = {"", "", "2:TAP", "3:TAP", "2:LEFT", "2:RIGHT", "2:LEFT",
                "1:TAP,2:TAP", "2:TAP", "3:UP", "3:UP", "1:TAP", "", ""};
        BeatstarDetector detector = new BeatstarDetector();
        for (int i = 0; i < names.length; i++) {
            byte[] bytes = Files.readAllBytes(fixtures.resolve(names[i] + ".gray"));
            expect(bytes.length == 480 * 1040, names[i] + " dimensions");
            GrayFrame frame = new GrayFrame(480, 1040, bytes);
            List<Detector.Detection> notes = detector.detect(frame, BeatstarDetector.LANES, BeatstarDetector.HIT_LINE);
            String actual = String.join(",", notes.stream().map(d -> (d.lane + 1) + ":" + (d.kind == Kind.CHECKPOINT ? Kind.TAP : d.kind)).toList());
            expect(expected[i].equals(actual), names[i] + " expected " + expected[i] + ", got " + actual);
            expect(detector.isGameplay(frame) == (i >= 2 && i <= 11), names[i] + " gameplay state");
            List<Detector.Detection> initial = detector.startingNotes(frame, BeatstarDetector.LANES, BeatstarDetector.HIT_LINE);
            expect(initial.size() == (i == 1 ? 2 : 0), names[i] + " initial tiles");
        }
        byte[] dark = new byte[480 * 1040];
        expect(!detector.isGameplay(new GrayFrame(480, 1040, dark)), "blank black screen");
        expect(detector.startingNotes(new GrayFrame(480, 1040, dark), BeatstarDetector.LANES,
                BeatstarDetector.HIT_LINE).isEmpty(), "black screen must not start a song");
        GrayFrame obscured = new GrayFrame(480, 1040, Files.readAllBytes(fixtures.resolve("016412.gray")));
        expect(detector.isGameplay(obscured), "pointer line obscuring one side of the playing field");
        expect(detector.startingNotes(obscured, BeatstarDetector.LANES, BeatstarDetector.HIT_LINE).isEmpty(),
                "ongoing play must not trigger initial taps");
        for (String name : new String[]{"bot_start_grey", "bot_start_pulse"}) {
            GrayFrame f = new GrayFrame(480,1040,Files.readAllBytes(fixtures.resolve(name + ".gray")));
            expect(!detector.isGameplay(f), name + " is a waiting screen");
            expect(detector.startingNotes(f,BeatstarDetector.LANES,.721).size() == 2, name + " pulsing pair lost");
        }
        for (String name : new String[]{"bot_left_glow", "bot_left_past_line"}) {
            GrayFrame f = new GrayFrame(480,1040,Files.readAllBytes(fixtures.resolve(name + ".gray")));
            List<Detector.Detection> notes = detector.detect(f,BeatstarDetector.LANES,.721);
            expect(notes.stream().anyMatch(d -> d.lane == 1 && d.kind == Kind.LEFT), name + " glow split the arrow");
            expect(notes.stream().anyMatch(d -> d.lane == 2 && d.kind == Kind.TAP), name + " neighbouring tap lost");
            expect(detector.startingNotes(f,BeatstarDetector.LANES,.721).isEmpty(), name + " false start");
        }
        System.out.println("PASS: " + checks + " assertions on 19 real frames and blank screen.");
    }
}
