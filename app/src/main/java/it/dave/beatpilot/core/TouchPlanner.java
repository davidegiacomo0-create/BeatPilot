package it.dave.beatpilot.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/** Plans all fingers together, so a new tap does not cancel a held finger. No Android dependency. */
public final class TouchPlanner {
    public static final class Segment {
        public long id, noteId, offset, duration, releaseGuardMs;
        public double x0, y0, x1, y1;
        public boolean continuation, more;
    }
    private static final class Contact {
        long id, noteId, start, end;
        int lane;
        double x, y, dx, dy, lastX, lastY;
        boolean sent, holding, tap;
        double fraction(long t) { return Math.max(0, Math.min(1, (t - start) / 50.0)); }
        double xAt(long t) { return x + dx * fraction(t); }
        double yAt(long t) { return y + dy * fraction(t); }
    }
    private final PriorityQueue<Tracker.Hit> queue = new PriorityQueue<>(
            Comparator.comparingLong((Tracker.Hit h) -> h.at)
                    // Free the lane before processing a new note at the same time,
                    // independent of the order in which vision reported the events.
                    .thenComparingInt(h -> h.kind == Kind.HOLD_END ? 0 : 1)
                    .thenComparingInt(h -> h.lane).thenComparingLong(h -> h.id));
    private final List<Contact> contacts = new ArrayList<>();
    private final double[] lanes;
    private final double line;
    private final double holdLine;
    private long serial = 1;
    public TouchPlanner(double[] lanes, double line) { this(lanes,line,false); }
    public TouchPlanner(double[] lanes, double line, boolean videoProfile) {
        this.lanes = lanes.clone(); this.line = line;
        holdLine = Math.min(.98,line+(videoProfile ? HoldTiming.CONTACT_OFFSET : 0));
    }
    public void add(Tracker.Hit hit) {
        if (hit.revision) {
            // Only a still-queued hit may be updated. A dispatched/completed note must
            // never be reintroduced by a delayed vision message.
            boolean queued = queue.removeIf(old -> old.id == hit.id);
            if (!queued) return;
        }
        if (queue.size() < 128) queue.add(hit);
    }
    public void clear() { queue.clear(); contacts.clear(); }
    public boolean idle() { return queue.isEmpty() && contacts.isEmpty(); }

    /** True only while the planner is physically keeping this lane pressed. */
    public boolean holdingLane(int lane) {
        for (Contact c : contacts)
            if (c.lane == lane && c.holding) return true;
        return false;
    }

    public long nextTime() {
        // willContinue keeps a stationary pointer down between gestures. No repeated
        // keepalive gesture is needed; leave the scheduler available for other notes.
        for (Contact c : contacts) if (!c.holding) return 0;
        return queue.isEmpty() ? Long.MAX_VALUE : queue.peek().at;
    }
    public boolean openingNext() { return contacts.isEmpty() && !queue.isEmpty() && queue.peek().opening; }

    public List<Segment> plan(long now, int horizon) {
        long until = now + horizon;
        boolean continuing = false;
        for (Contact c : contacts) if (c.sent) continuing = true;
        while (!queue.isEmpty() && queue.peek().at < until) {
            Tracker.Hit hit = queue.remove();
            long at = Math.max(now, hit.at);
            // Stale starts are unsafe and cannot improve a score. Releases must still happen.
            if (now - hit.at > (hit.opening ? 200 : 70) && hit.kind != Kind.HOLD_END) continue;
            if (hit.kind == Kind.HOLD_END) {
                for (Contact c : contacts) if (c.lane == hit.lane && c.holding) {
                    c.end = Math.max(c.start + 1, Math.min(c.end, at)); c.holding = false;
                }
                continue;
            }
            // A directional tail on a held note must be performed by the SAME finger.
            // Releasing the hold and creating a fresh swipe loses the Beatstar hold-swipe.
            if (hit.kind == Kind.UP || hit.kind == Kind.DOWN || hit.kind == Kind.LEFT || hit.kind == Kind.RIGHT) {
                Contact held = null;
                for (Contact old : contacts) if (old.lane == hit.lane && old.holding && old.end > at) { held = old; break; }
                if (held != null) {
                    held.noteId = hit.id;
                    held.x = held.sent ? held.lastX : held.x; held.y = held.sent ? held.lastY : held.y;
                    held.start = at; held.end = at + 50; held.holding = false; held.tap = false;
                    held.dx = held.dy = 0;
                    if (hit.kind == Kind.LEFT) held.dx = -.085;
                    if (hit.kind == Kind.RIGHT) held.dx = .085;
                    if (hit.kind == Kind.UP) held.dy = -.045;
                    if (hit.kind == Kind.DOWN) held.dy = .045;
                    held.dx = Math.max(.001 - held.x, Math.min(.999 - held.x, held.dx));
                    held.dy = Math.max(.001 - held.y, Math.min(.999 - held.y, held.dy));
                    continue;
                }
            }
            // A circular stage checkpoint is normally a tap. If it arrives in a lane
            // that is already being held, it is the checkpoint embedded in that hold:
            // keep the finger down instead of converting it into a new tap and
            // prematurely terminating the hold.
            if (hit.kind == Kind.CHECKPOINT) {
                boolean held = false;
                for (Contact old : contacts) if (old.lane == hit.lane && old.holding && old.end > at) held = true;
                if (held) continue;
            }
            // Android's first continuation sample must contain only the old fingers.
            // A late new note cannot share offset zero with them: that rejects the
            // whole gesture. Future deadlines stay unchanged; late starts use 1 ms.
            if (continuing) at = Math.max(now + 1, at);
            // Never silently consume a predicted note just because the same lane still
            // owns an older contact. Different short gestures may legitimately overlap
            // in Android's multi-pointer stream. A HOLD is different: one physical lane
            // cannot remain indefinitely occupied when its tail was missed by vision.
            // When a new note reaches that lane, close only the stale held contact at
            // the new note's deadline. protectPointerTransitions() will separate an UP
            // and DOWN that land on the same millisecond without moving the new DOWN.
            for (Contact old : contacts) if (old.lane == hit.lane && old.holding && old.end > at) {
                old.end = Math.max(old.start + 1, at);
                old.holding = false;
            }
            Contact c = new Contact(); c.id = serial++; c.noteId = hit.id; c.lane = hit.lane;
            c.start = at; c.x = lanes[c.lane]; c.y = line;
            c.holding = hit.kind == Kind.HOLD_START;
            c.tap = hit.kind == Kind.TAP || hit.kind == Kind.CHECKPOINT;
            if (c.holding) c.y = holdLine;
            c.end = c.holding ? Long.MAX_VALUE : at + (hit.opening ? GestureTiming.OPENING_HOLD_MS : c.tap ? 8 : 50);
            if (hit.kind == Kind.LEFT) c.dx = -.085;
            if (hit.kind == Kind.RIGHT) c.dx = .085;
            if (hit.kind == Kind.UP) c.dy = -.045;
            if (hit.kind == Kind.DOWN) c.dy = .045;
            c.dx = Math.max(.001 - c.x, Math.min(.999 - c.x, c.dx));
            c.dy = Math.max(.001 - c.y, Math.min(.999 - c.y, c.dy));
            contacts.add(c);
        }
        List<Segment> segments = new ArrayList<>();
        Iterator<Contact> iter = contacts.iterator();
        while (iter.hasNext()) {
            Contact c = iter.next();
            long start = Math.max(now, c.start);
            long end = c.holding ? start+1 : Math.max(start + 1, c.tap ? c.end : Math.min(until, c.end));
            Segment s = new Segment(); s.id = c.id; s.noteId = c.noteId; s.offset = start - now; s.duration = end - start;
            s.continuation = c.sent;
            // Continue exactly at the previous endpoint, even if Android delivered the callback late.
            s.x0 = c.sent ? c.lastX : c.x; s.y0 = c.sent ? c.lastY : c.y;
            s.x1 = c.xAt(end); s.y1 = c.yAt(end); s.more = !c.tap && c.end > until;
            c.lastX = s.x1; c.lastY = s.y1; c.sent = true;
            segments.add(s);
            if (!s.more) iter.remove();
        }
        protectPointerTransitions(segments);
        // A stationary willContinue-only batch creates no MotionEvents. Android
        // rejects it and calls onCancelled. The fingers are already down: keep
        // their state locally and wait for a real start, move, or release.
        for (Segment s : segments)
            if (!s.continuation || !s.more || s.x0 != s.x1 || s.y0 != s.y1) return segments;
        return List.of();
    }

    private static void protectPointerTransitions(List<Segment> segments) {
        Set<Long> starts = new HashSet<>();
        for (Segment s : segments) if (!s.continuation) starts.add(s.offset);
        long last = 0;
        for (Segment s : segments) {
            // AOSP samples both endpoints at a shared boundary, removes UP pointers,
            // then uses the *sample* index for DOWN. An ending earlier stroke can
            // therefore give the new finger an invalid/wrong action index.
            // Keep every new DOWN on time; postpone only a colliding UP by the
            // minimum whole milliseconds. Keypoints are sampled exactly by Android.
            if (!s.more) while (starts.contains(s.offset + s.duration)) {
                s.duration++; s.releaseGuardMs++;
            }
            last = Math.max(last, s.offset + s.duration);
        }
        for (Segment s : segments) {
            // willContinue keeps a finger physically down after its segment ends.
            // Also keep stationary fingers present in later samples of THIS batch,
            // otherwise a new DOWN's sample index can refer to an older finger.
            // Padding to the existing batch end adds no scheduler/callback delay.
            if (s.more && s.x0 == s.x1 && s.y0 == s.y1)
                s.duration = Math.max(s.duration, last - s.offset);
        }
    }
}
