package it.dave.beatpilot.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Detector {
    public static final class Detection {
        public final int lane;
        public final Kind kind;
        public final double y, score, height;
        public final boolean recovered;
        public Detection(int lane, Kind kind, double y, double score, double height) {
            this(lane,kind,y,score,height,false);
        }
        public Detection(int lane, Kind kind, double y, double score, double height, boolean recovered) {
            this.lane = lane; this.kind = kind; this.y = y; this.score = score; this.height = height;
            this.recovered = recovered;
        }
    }
    private final double[] values = new double[NotePattern.SIZE];

    public List<Detection> detect(GrayFrame frame, List<NotePattern> patterns,
                                  double[] lanes, double top, double bottom, double threshold) {
        List<Detection> candidates = new ArrayList<>();
        for (NotePattern pattern : patterns) {
            int w = Math.max(NotePattern.COLS, (int)Math.round(pattern.widthRatio * frame.width));
            int h = Math.max(NotePattern.ROWS, (int)Math.round(pattern.heightRatio * frame.width));
            if (w >= frame.width || h >= frame.height) continue;
            for (int lane = 0; lane < 3; lane++) {
                int left = (int)Math.round(lanes[lane] * frame.width - pattern.anchorX * w);
                if (left < 0 || left + w > frame.width) continue;
                int y0 = Math.max(0, (int)(top * frame.height - pattern.anchorY * h));
                int y1 = Math.min(frame.height - h, (int)(bottom * frame.height - pattern.anchorY * h));
                for (int y = y0; y <= y1; y += 2) {
                    double sum = 0, square = 0, dot = 0;
                    int i = 0;
                    for (int row = 0; row < NotePattern.ROWS; row++) {
                        int yy = y + (int)((row + .5) * h / NotePattern.ROWS);
                        for (int col = 0; col < NotePattern.COLS; col++) {
                            double v = frame.at(left + (int)((col + .5) * w / NotePattern.COLS), yy);
                            values[i] = v; sum += v; square += v * v; i++;
                        }
                    }
                    double variance = square - sum * sum / NotePattern.SIZE;
                    if (variance < NotePattern.SIZE * 16) continue;
                    for (i = 0; i < NotePattern.SIZE; i++) dot += values[i] * pattern.normalized[i];
                    double score = dot / Math.sqrt(variance);
                    if (score >= threshold) candidates.add(new Detection(lane, pattern.kind,
                            (y + pattern.anchorY * h) / frame.height, score, h / (double)frame.height));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((Detection d) -> d.score).reversed());
        List<Detection> result = new ArrayList<>();
        for (Detection d : candidates) {
            boolean overlaps = false;
            for (Detection existing : result) if (existing.lane == d.lane
                    && Math.abs(existing.y - d.y) < Math.max(.008, Math.min(existing.height, d.height) * .5)) {
                overlaps = true; break;
            }
            if (!overlaps) result.add(d);
            if (result.size() == 30) break;
        }
        result.sort(Comparator.comparingInt((Detection d) -> d.lane).thenComparingDouble(d -> -d.y));
        return result;
    }
}
