package it.dave.beatpilot.core;

import java.nio.ByteBuffer;

/** Bulk-copy native memory once; do the per-pixel arithmetic on a reusable Java array. */
public final class RgbaToGray {
    private byte[] rgba = new byte[0];

    public void convert(ByteBuffer source, int rowStride, int pixelStride,
                        int width, int height, byte[] gray) {
        if (width <= 0 || height <= 0 || pixelStride < 4 || rowStride < (long)width * pixelStride
                || gray.length != (long)width * height)
            throw new IllegalArgumentException("Invalid RGBA frame layout");
        long required = (long)(height - 1) * rowStride + (long)(width - 1) * pixelStride + 4;
        if (required > source.remaining() || required > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Incomplete RGBA frame");
        int size = (int)required;
        if (rgba.length < size) rgba = new byte[size];
        source.duplicate().get(rgba, 0, size);
        for (int y = 0, out = 0; y < height; y++) {
            for (int x = 0, p = y * rowStride; x < width; x++, p += pixelStride)
                gray[out++] = (byte)((77 * (rgba[p] & 255) + 150 * (rgba[p + 1] & 255)
                        + 29 * (rgba[p + 2] & 255)) >> 8);
        }
    }
}
