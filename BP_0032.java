package it.dave.beatpilot.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Tracker {
    public static final class Hit {
        public final long id, at;
        public final int lane;
        public final Kind kind;
        public final boolean revision, opening;
        public Hit(long id, int lane, Kind kind, long at) {
            this(id, lane, kind, at, false, false);
        }
        public Hit(long id, int lane, Kind kind, long at, boolean revision, boolean opening) {
            this.id = id; this.lane = lane; this.kind = kind; this.at = at;
            this.revision = revision; this.opening = opening;
        }
    }
    private static final class Track {
        long id, last, scheduledAt;
        int lane, frames;
        Kind kind;
        double y, velocity, fittedY, residual, coordinate, motionSpeed, height;
        final long[] times = new long[8];
        final double[] positions = new double[8];
        int samples;
        boolean fired, planned, matched, recoveredTail;
    }
    private final List<Track> tracks = new ArrayList<>();
    private long nextId = 1, lastFrame = -1;
    private double framePeriod = 17;
    private final double perspective;
    public Tracker() { this(0); }
    public Tracker(double perspective) { this.perspective = perspective; }
    public void reset() { tracks.clear(); lastFrame = -1; framePeriod = 17; }

    /** Search hints only: every recovered note must still be classified in the current pixels. */
    public List<Detector.Detection> expectations(long now, double line) {
        List<Detector.Detection> result = new ArrayList<>();
        for (Track t : tracks) {
            long age = now-t.last;
            if (t.fired || age < 0 || age > 100 || t.samples < 4 || t.residual >= .004
                    || t.velocity < .00012 || t.velocity > .007 || t.height <= 0) continue;
            double y = screenPosition(t.coordinate+t.motionSpeed*age,line);
            if (y < line-.29 || y > line+.02) continue;
            double h = t.height * (1+perspective*(y-line)) / (1+perspective*(t.y-line));
            result.add(new Detector.Detection(t.lane,t.kind,y,1,h));
        }
        return result;
    }

    public List<Hit> update(List<Detector.Detection> detections, long now, double line, double advanceMs) {
        return update(detections, now, now, line, advanceMs);
    }

    /** now is image capture time; ready is when analysis has finished, in the same uptime clock. */
    public List<Hit> update(List<Detector.Detection> detections, long now, long ready, double line, double advanceMs) {
        if (lastFrame >= 0 && now <= lastFrame) return List.of();
        if (lastFrame >= 0 && now - lastFrame > 200) tracks.clear();
        if (lastFrame >= 0 && now - lastFrame <= 200)
            framePeriod = .65 * framePeriod + .35 * (now - lastFrame);
        lastFrame = now;
        // Leave time for analysis AND the next frame. The former fixed 38-ms window
        // routinely expired during the observed 32-ms analysis at roughly 30 fps.
        double horizon = Math.max(55, Math.min(120, Math.max(0, ready - now) + framePeriod + 18));
        tracks.removeIf(t -> now - t.last > 160);
        for (Track t : tracks) t.matched = false;
        List<Hit> hits = new ArrayList<>();
        for (Detector.Detection d : detections) {
            Track best = null;
            double distance = Double.MAX_VALUE;
            for (Track t : tracks) {
                if (t.matched || t.lane != d.lane || t.kind != d.kind) continue;
                double predicted = t.y + Math.max(0, t.velocity) * (now - t.last);
                double delta = Math.abs(d.y - predicted);
                double limit = t.frames > 1 ? .025 : .08;
                if (delta < limit && delta < distance && d.y >= t.y - .006) { best = t; distance = delta; }
            }
            if (best == null) {
                best = new Track(); best.id = nextId++; best.lane = d.lane; best.kind = d.kind;
                best.y = d.y; best.last = now; tracks.add(best);
            }
            if (d.kind == Kind.HOLD_END && d.recovered && best.planned && !best.recoveredTail
                    && stableTail(best,line)) {
                // Bright-cap recovery must not retime an end already established by
                // the original shaded-cap recogniser. Keep its identity/visible
                // position alive, but leave its timing samples and deadline intact.
                best.matched = true; best.last = now; best.y = d.y;
                continue;
            }
            if (d.kind == Kind.HOLD_END && d.recovered && best.planned) best.recoveredTail = true;
            best.frames++; best.matched = true; best.last = now; best.y = d.y;
            if (!d.recovered) best.height = d.height;
            boolean fresh = fit(best, now, d.y, line);
            if (best.planned && ready >= GestureTiming.wakeAt(best.scheduledAt)) best.fired = true;
            // A repeated image can reveal a pause after a contact was predicted.
            // Correct a queued contact from the last visible position, but do not
            // use a frozen frame to create a new contact.
            if (!fresh && !best.planned) continue;
            // A stationary menu label must never become a tap. Observe actual downward motion.
            if (!best.fired && best.samples >= 3 && now - best.times[0] >= 24
                    && best.residual < .008 && best.velocity > .00012 && best.velocity < .007) {
                boolean tail = best.kind == Kind.HOLD_END && perspective != 0;
                double target = tail ? HoldTiming.RELEASE_OFFSET/(1+perspective*HoldTiming.RELEASE_OFFSET) : 0;
                double remaining = (target-best.coordinate) / best.motionSpeed - (tail ? 0 : advanceMs);
                long due = now + Math.round(remaining);
                // Retain a measured tail deadline before the hold changes appearance
                // under a finger. Later observations can still refine that deadline.
                double lookahead = tail ? HoldTiming.END_LOOKAHEAD_MS : horizon;
                if ((best.planned || remaining <= lookahead) && due >= ready - 25) {
                    // Refine an already queued note as its speed changes, but never issue
                    // it again after the scheduler has begun the gesture.
                    if (!best.planned || (due >= ready && Math.abs(due - best.scheduledAt) >= 2)) {
                        if (!best.planned) best.recoveredTail = best.kind == Kind.HOLD_END && d.recovered;
                        hits.add(new Hit(best.id, best.lane, best.kind, due, best.planned, false));
                        best.scheduledAt = due; best.planned = true;
                    }
                } else if (remaining < -25) best.fired = true; // missed, never chase far behind
            }
        }
        hits.sort(Comparator.comparingLong(h -> h.at));
        return hits;
    }
    private boolean stableTail(Track t,double line) {
        if (t.samples<3 || t.residual>.001) return false;
        double slow=Double.MAX_VALUE,fast=0;
        for (int i=t.samples-2;i<t.samples;i++) {
            long dt=t.times[i]-t.times[i-1];
            double a=t.positions[i-1]-line,b=t.positions[i]-line;
            double speed=(b/(1+perspective*b)-a/(1+perspective*a))/dt;
            if (dt<=0 || speed<=0) return false;
            slow=Math.min(slow,speed);fast=Math.max(fast,speed);
        }
        // An edge emerging from the HUD can produce a very slow first interval,
        // then jump to the real cap speed. Do not lock that provisional deadline.
        return fast<=slow*1.35;
    }
    private double screenPosition(double coordinate, double line) {
        return line + coordinate / Math.max(.2,1-perspective*coordinate);
    }
    private boolean fit(Track t, long now, double y, double line) {
        // A new capture can contain the same game frame (e.g. an overlay redraw).
        // Do not count it as a second motion sample; anchor the position to the
        // current pixels instead of extrapolating through a visible pause.
        boolean duplicate = perspective != 0 && t.samples > 1 && t.velocity > .00012
                && now-t.times[t.samples-1] <= 45 && Math.abs(y-t.positions[t.samples-1]) < .00035;
        int keep = 0;
        for (int i = 0; i < t.samples; i++) if (now - t.times[i] <= 110) {
            t.times[keep] = t.times[i]; t.positions[keep++] = t.positions[i];
        }
        if (!duplicate && keep == t.times.length) {
            System.arraycopy(t.times, 1, t.times, 0, keep - 1);
            System.arraycopy(t.positions, 1, t.positions, 0, keep - 1); keep--;
        }
        boolean fresh = !duplicate || keep == 0;
        if (fresh) { t.times[keep] = now; t.positions[keep++] = y; }
        t.samples = keep;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < keep; i++) {
            double x = t.times[i] - now;
            double dy = t.positions[i]-line;
            double p = dy/(1+perspective*dy);
            sx += x; sy += p; sxx += x * x; sxy += x * p;
        }
        double denominator = keep * sxx - sx * sx;
        t.motionSpeed = denominator == 0 ? 0 : (keep * sxy - sx * sy) / denominator;
        t.coordinate = (sy - t.motionSpeed * sx) / keep;
        t.fittedY = screenPosition(t.coordinate,line);
        double scale = 1+perspective*(t.fittedY-line);
        t.velocity = t.motionSpeed*scale*scale;
        double sum = 0;
        for (int i = 0; i < keep; i++) {
            double error = t.positions[i] - screenPosition(t.coordinate+t.motionSpeed*(t.times[i]-now),line);
            sum += error * error;
        }
        t.residual = Math.sqrt(sum / keep);
        if (!fresh) {
            double dy = y-line;
            t.coordinate = dy/(1+perspective*dy);
            t.fittedY = y;
            double visibleScale = 1+perspective*dy;
            t.velocity = t.motionSpeed*visibleScale*visibleScale;
        }
        return fresh;
    }
}
