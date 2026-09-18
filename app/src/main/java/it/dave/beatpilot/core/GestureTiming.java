package it.dave.beatpilot.core;

/** Submit early, preserving the desired contact time as the Android stroke offset. */
public final class GestureTiming {
    public static final int LEAD_MS = 12;
    public static final int HORIZON_MS = 32;
    public static final int OPENING_HOLD_MS = 160;
    private GestureTiming() {}
    public static long wakeAt(long due) { return due-LEAD_MS; }
}
