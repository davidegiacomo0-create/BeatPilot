package it.dave.beatpilot.core;

import java.nio.ByteBuffer;

/** Pixel copy and clock shared by recording and host-side regression tests. */
public final class RecordingFrames {
    private RecordingFrames() {}

    public static void copyRgba(ByteBuffer source, int rowStride, int pixelStride,
                                int width, int height, ByteBuffer destination) {
        if (width <= 0 || height <= 0 || pixelStride < 4 || rowStride < width * pixelStride)
            throw new IllegalArgumentException("Invalid RGBA plane layout");
        int size = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (destination.capacity() < size) throw new IllegalArgumentException("Destination too small");
        ByteBuffer input = source.duplicate(); int origin = input.position();
        long last = origin + (long)(height - 1) * rowStride + (long)(width - 1) * pixelStride + 4;
        if (last > input.limit()) throw new IllegalArgumentException("Incomplete RGBA plane");
        destination.clear(); destination.limit(size);
        if (pixelStride == 4) {
            for (int y = 0; y < height; y++) {
                input.limit(source.limit()); input.position(origin + y * rowStride);
                input.limit(input.position() + width * 4); destination.put(input);
            }
        } else {
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int at = origin + y * rowStride + x * pixelStride;
                for (int c = 0; c < 4; c++) destination.put(input.get(at + c));
            }
        }
        destination.flip();
    }

    public static final class Clock {
        private long firstMs = -1, lastUs = -1;
        public long firstMs() { return firstMs; }
        public long nextUs(long arrivalMs) {
            if (firstMs < 0) firstMs = arrivalMs;
            long pts = Math.max(0, (arrivalMs - firstMs) * 1000);
            // Preserve real gaps when frames are dropped; do not invent a constant frame rate.
            lastUs = Math.max(lastUs + 1, pts);
            return lastUs;
        }
    }
}
