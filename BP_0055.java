package it.dave.beatpilot.core;

import java.util.List;

/** Confirm the stationary start pair, then press both tiles exactly once per activation. */
public final class StartGate {
    private long visibleSince = -1, previousFrame = -1, lastAttempt = -1;
    private int attempts;
    private boolean playing;
    public void reset() { visibleSince = previousFrame = lastAttempt = -1; attempts = 0; playing = false; }
    public void gameplayHasNotes() { playing = true; }
    public boolean update(boolean pairVisible, long now) {
        if (previousFrame >= 0 && now - previousFrame > 250) visibleSince = -1;
        previousFrame = now;
        if (!pairVisible) { visibleSince = -1; return false; }
        if (visibleSince < 0) visibleSince = now;
        if (playing || attempts >= 1 || now - visibleSince < 450) return false;
        attempts++; lastAttempt = now; return true;
    }
    public int attempts() { return attempts; }
    public boolean gestureInFlight(long now) { return lastAttempt >= 0 && now - lastAttempt < 500; }
    public static List<Tracker.Hit> openingHits(long at) {
        return List.of(new Tracker.Hit(-1, 0, Kind.TAP, at, false, true),
                new Tracker.Hit(-3, 2, Kind.TAP, at, false, true));
    }
}
