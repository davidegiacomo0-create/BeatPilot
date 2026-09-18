package it.dave.beatpilot.core;

public final class GrayFrame {
    public final int width, height;
    public final byte[] pixels;
    public GrayFrame(int width, int height, byte[] pixels) {
        if (width < 1 || height < 1 || pixels.length != width * height)
            throw new IllegalArgumentException("Invalid frame");
        this.width = width; this.height = height; this.pixels = pixels;
    }
    public int at(int x, int y) { return pixels[y * width + x] & 255; }
}
