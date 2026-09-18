package it.dave.beatpilot.core;

public final class NotePattern {
    public static final int COLS = 10, ROWS = 14, SIZE = COLS * ROWS;
    public final String id;
    public final Kind kind;
    public final double widthRatio, heightRatio, anchorX, anchorY;
    public final double[] normalized;

    // Ratios are relative to full-screen width, including the vertical dimension.
    public NotePattern(String id, Kind kind, double widthRatio, double heightRatio,
                       double anchorX, double anchorY, double[] samples) {
        if (samples.length != SIZE || !Double.isFinite(widthRatio) || !Double.isFinite(heightRatio)
                || widthRatio <= 0 || widthRatio > .5 || heightRatio <= 0 || heightRatio > 1
                || !(anchorX >= 0 && anchorX <= 1) || !(anchorY >= 0 && anchorY <= 1))
            throw new IllegalArgumentException("Ritaglio o punto di allineamento non valido");
        this.id = id; this.kind = kind; this.widthRatio = widthRatio; this.heightRatio = heightRatio;
        this.anchorX = anchorX; this.anchorY = anchorY;
        double mean = 0;
        for (double v : samples) { if (!Double.isFinite(v)) throw new IllegalArgumentException("Invalid sample"); mean += v; }
        mean /= SIZE;
        double energy = 0;
        for (double v : samples) energy += (v - mean) * (v - mean);
        if (energy / SIZE < 16) throw new IllegalArgumentException("Campione troppo uniforme: includi bordi e simbolo");
        normalized = new double[SIZE];
        for (int i = 0; i < SIZE; i++) normalized[i] = (samples[i] - mean) / Math.sqrt(energy);
    }

    public static NotePattern crop(String id, Kind kind, GrayFrame image, int x, int y, int w, int h,
                                   double anchorX, double anchorY) {
        if (w < 10 || h < 14 || x < 0 || y < 0 || x + w > image.width || y + h > image.height)
            throw new IllegalArgumentException("Ritaglio troppo piccolo o fuori immagine");
        double[] samples = new double[SIZE];
        for (int row = 0; row < ROWS; row++) for (int col = 0; col < COLS; col++)
            samples[row * COLS + col] = image.at(x + (int)((col + .5) * w / COLS),
                    y + (int)((row + .5) * h / ROWS));
        return new NotePattern(id, kind, w / (double)image.width, h / (double)image.width,
                anchorX, anchorY, samples);
    }

    public double[] storageSamples() {
        double[] values = new double[SIZE];
        for (int i = 0; i < SIZE; i++) values[i] = 128 + normalized[i] * 400;
        return values;
    }
}
