package it.dave.beatpilot.core;

import java.util.ArrayList;
import java.util.List;

/** Bounded, in-memory timing trace. Disk I/O never runs on the touch or vision threads. */
public final class TraceBuffer {
    public record Event(long uptimeMs, String type, String data) {}
    private final int capacity;
    private final List<Event> events = new ArrayList<>();
    private long dropped;
    public TraceBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }
    public synchronized void add(long uptimeMs, String type, String data) {
        if (events.size() == capacity) { dropped++; return; }
        events.add(new Event(uptimeMs, type, data));
    }
    public synchronized List<Event> snapshot() { return new ArrayList<>(events); }
    public synchronized long dropped() { return dropped; }
}
