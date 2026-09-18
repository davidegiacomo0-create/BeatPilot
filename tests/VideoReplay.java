import it.dave.beatpilot.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Replays recorded pixels only. It sends no touches and cannot measure live Android latency. */
public final class VideoReplay {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]); Files.createDirectories(output);
        DataInputStream input = new DataInputStream(new BufferedInputStream(System.in, 1024 * 1024));
        int width = input.readInt(), height = input.readInt();
        if (width < 100 || width > 2000 || height < 100 || height > 4000) throw new IOException("Invalid frame dimensions");
        byte[] pixels = new byte[width * height];
        GrayFrame frame = new GrayFrame(width, height, pixels);
        BeatstarDetector detector = new BeatstarDetector(); Tracker tracker = new Tracker(BeatstarDetector.PERSPECTIVE);
        GameplayGate gate = new GameplayGate();
        int frames = 0, actions = 0, refinements = 0, detections = 0, gameplayFrames = 0;
        long totalNs = 0;
        try (PrintWriter notes = new PrintWriter(Files.newBufferedWriter(output.resolve("detections.csv")));
             PrintWriter hits = new PrintWriter(Files.newBufferedWriter(output.resolve("hits.csv")));
             PrintWriter states = new PrintWriter(Files.newBufferedWriter(output.resolve("frames.csv")))) {
            notes.println("time_ms,lane,kind,y,score"); hits.println("frame_ms,capture_ms,ready_ms,id,lane,kind,due_ms,revision");
            states.println("time_ms,gameplay,notes,processing_ms,hud_visible");
            while (true) {
                long time;
                try { time = input.readLong(); } catch (EOFException end) { break; }
                long captured = input.readLong(), ready = input.readLong();
                input.readFully(pixels); long started = System.nanoTime();
                boolean hud = detector.isGameplay(frame);
                boolean opening = !hud && detector.startingNotes(frame, BeatstarDetector.LANES, BeatstarDetector.HIT_LINE).size() == 2;
                boolean gameplay = gate.update(hud, opening, time);
                List<Detector.Detection> found = gameplay
                        ? detector.detect(frame, BeatstarDetector.LANES, BeatstarDetector.HIT_LINE,
                                tracker.expectations(captured,BeatstarDetector.HIT_LINE)) : List.of();
                if (!gameplay) tracker.reset();
                List<Tracker.Hit> events = tracker.update(found, captured, ready, BeatstarDetector.HIT_LINE, 0);
                long elapsed = System.nanoTime() - started; totalNs += elapsed;
                frames++; if (gameplay) gameplayFrames++; detections += found.size();
                for (Tracker.Hit hit : events) { if (hit.revision) refinements++; else actions++; }
                states.printf(Locale.ROOT, "%d,%s,%d,%.3f,%s%n", time, gameplay, found.size(), elapsed / 1e6,hud);
                for (Detector.Detection d : found) notes.printf(Locale.ROOT, "%d,%d,%s,%.7f,%.4f%n", time, d.lane + 1, d.kind, d.y, d.score);
                for (Tracker.Hit hit : events) hits.printf(Locale.ROOT, "%d,%d,%d,%d,%d,%s,%d,%s%n",
                        time, captured, ready, hit.id, hit.lane + 1, hit.kind, hit.at, hit.revision);
            }
        }
        System.out.printf(Locale.ROOT,"Replay complete: %d frames, %d gameplay frames, %d detections, %d predicted notes, %d timing refinements; %.3f ms average CPU/frame on this computer.%n",
                frames, gameplayFrames, detections, actions, refinements, totalNs / 1e6 / Math.max(1, frames));
        System.out.println("No live Android touches; no Perfect score or physical-device compatibility measured.");
    }
}
