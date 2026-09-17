package it.dave.beatpilot.core;

/** Image timebases vary. Use source time only when it agrees with the sampled monotonic clock. */
public final class FrameClock {
    private long lastSource = -1, lastCapture = -1;
    private boolean sourceBased, changed;
    public long sample(long imageNs, long monotonicNowNs, long uptimeMs) {
        long ageNs = monotonicNowNs - imageNs;
        boolean valid = imageNs > 0 && ageNs >= -2_000_000L && ageNs <= 200_000_000L;
        changed = valid != sourceBased;
        sourceBased = valid;
        long capture = valid ? uptimeMs - Math.max(0, Math.round(ageNs / 1_000_000.0)) : uptimeMs;
        // Repeated images must not create extra observations with invented movement/time.
        if (valid && !changed && imageNs == lastSource) capture = lastCapture;
        if (valid && lastSource > 0 && imageNs < lastSource) changed = true;
        lastSource = imageNs; lastCapture = capture;
        return capture;
    }
    public boolean sourceBased() { return sourceBased; }
    public boolean changed() { return changed; }
}
