package it.dave.beatpilot.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Profile measured from Dave's full-screen 1080x2340 recording, 2026-09-15. */
public final class BeatstarDetector {
    public static final double VIDEO_ASPECT = 1080.0 / 2340;
    public static final double HIT_LINE = .721;
    public static final double PERSPECTIVE = .55;
    public static final double[] LANES = {.205, .5, .795};
    private boolean[] dark = new boolean[0], insideTile = new boolean[0];
    private byte[] mask = new byte[0], dilated = new byte[0], closed = new byte[0];
    private int[] labels = new int[0], queue = new int[0];
    private final HoldDetector holds = new HoldDetector();

    public boolean isGameplay(GrayFrame f) {
        // Song backgrounds change colour and brightness during play. Check the
        // actual score and pause control instead of assuming a pale backdrop.
        return hasScoreCircle(f) && hasScoreText(f) && hasPauseControl(f);
    }

    private boolean hasScoreText(GrayFrame f) {
        int ink = 0;
        for (int y = (int)(.108 * f.height); y < .149 * f.height; y++)
            for (int x = (int)(.40 * f.width); x < .60 * f.width; x++)
                if (f.at(x,y) > 200 && ++ink >= 24) return true;
        return false;
    }

    private boolean hasPauseControl(GrayFrame f) {
        int left = 0, right = 0, gap = 0, rows = 0;
        for (int y = (int)(.066 * f.height); y < .078 * f.height; y++) {
            if (f.at((int)(.929 * f.width),y) > 190) left++;
            if (f.at((int)(.944 * f.width),y) > 190) right++;
            if (f.at((int)(.936 * f.width),y) < 150) gap++;
            rows++;
        }
        return left >= rows * .45 && right >= rows * .45 && gap >= rows * .45;
    }

    private boolean hasScoreCircle(GrayFrame f) {
        int darkCount = 0;
        for (double y : new double[]{.09, .18}) for (double x : new double[]{.4, .5, .6})
            if (f.at((int)(x * f.width), (int)(y * f.height)) < 115) darkCount++;
        return darkCount >= 4;
    }

    private int brightBackdrop(GrayFrame f) {
        int bright = 0;
        for (double y : new double[]{.07, .22, .25}) for (double x : new double[]{.15, .85})
            if (f.at((int)(x * f.width), (int)(y * f.height)) > 135) bright++;
        return bright;
    }

    /** The recorded start screen has an empty score circle and bright, aligned tap bars. */
    public List<Detector.Detection> startingNotes(GrayFrame f, double[] lanes, double line) {
        if (!hasScoreCircle(f) || brightBackdrop(f) != 0) return List.of();
        for (int y = (int)(.145 * f.height); y < .21 * f.height; y += 2)
            for (int x = (int)(.38 * f.width); x < .62 * f.width; x += 2)
                if (f.at(x, y) > 65) return List.of();
        // Start tiles pulse from black to grey. Their white bars remain visible even when
        // the normal dark-border segmentation intentionally rejects the grey bodies.
        List<Detector.Detection> candidates = new ArrayList<>();
        int top = Math.max(0, (int)((line - .075) * f.height));
        int bottom = Math.min(f.height, (int)((line + .080) * f.height));
        for (int lane : new int[]{0, 2}) {
            Detector.Detection d = classify(f, lane, lanes[lane], line, top, bottom);
            if (d == null || d.kind != Kind.TAP || Math.abs(d.y - line) > .009 || d.score < .85)
                return List.of();
            int x = (int)(lanes[lane] * f.width);
            if (f.at(x, (int)((line - .035) * f.height)) > 185
                    || f.at(x, (int)((line + .035) * f.height)) > 185) return List.of();
            candidates.add(d);
        }
        return candidates;
    }

    public List<Detector.Detection> detect(GrayFrame f, double[] lanes, double line) {
        return detect(f,lanes,line,List.of());
    }

    public List<Detector.Detection> detect(GrayFrame f, double[] lanes, double line,
                                            List<Detector.Detection> expected) {
        if (!isGameplay(f)) return List.of();
        List<Detector.Detection> found = recover(f,lanes,line,detectTiles(f,lanes,line),expected);
        List<Detector.Detection> longNotes = holds.detect(f,lanes,line);
        for (Detector.Detection hold : longNotes) if (hold.kind == Kind.HOLD_START)
            found.removeIf(d -> d.lane == hold.lane && Math.abs(d.y-hold.y)<.016);
        found.addAll(longNotes);
        found.sort(Comparator.comparingInt((Detector.Detection d) -> d.lane).thenComparingDouble(d -> -d.y));
        return found;
    }

    /** Recovery checks a known glyph directly when border segmentation has dropped it. */
    public List<Detector.Detection> recover(GrayFrame f, double[] lanes, double line,
            List<Detector.Detection> observed, List<Detector.Detection> expected) {
        List<Detector.Detection> found = new ArrayList<>(observed);
        for (Detector.Detection hint : expected) {
            // The dedicated hold pass inspects stems/caps even when the tile is clipped.
            if (hint.kind == Kind.HOLD_START || hint.kind == Kind.HOLD_END) continue;
            boolean present = false;
            for (Detector.Detection d : found) if (d.lane == hint.lane && d.kind == hint.kind
                    && Math.abs(d.y-hint.y) < .025) { present = true; break; }
            if (present) continue;
            double half = Math.max(.045,Math.min(.13,hint.height*.5+.015));
            int top = Math.max(0,(int)((hint.y-half)*f.height));
            int bottom = Math.min(f.height,(int)((hint.y+half)*f.height));
            Detector.Detection d = classify(f,hint.lane,lanes[hint.lane],line,top,bottom);
            if (d == null || d.kind != hint.kind || d.score < .78 || Math.abs(d.y-hint.y) > .018) continue;
            found.removeIf(old -> old.lane == d.lane && Math.abs(old.y-d.y) < .018);
            found.add(new Detector.Detection(d.lane,d.kind,d.y,d.score,hint.height,true));
        }
        found.sort(Comparator.comparingInt((Detector.Detection d) -> d.lane).thenComparingDouble(d -> -d.y));
        return found;
    }

    private List<Detector.Detection> detectTiles(GrayFrame f, double[] lanes, double line) {
        List<Detector.Detection> found = new ArrayList<>();
        int top = Math.max(0, (int)((line - .316) * f.height));
        int bottom = Math.min(f.height, (int)((line + .109) * f.height));
        if (dark.length < f.height) { dark = new boolean[f.height]; insideTile = new boolean[f.height]; }
        double[] fractions = {-.44, -.40, -.36, .36, .40, .44};
        for (int lane = 0; lane < 3; lane++) {
            Arrays.fill(dark, false);
            for (int y = top; y < bottom; y++) {
                double scale = 1 + PERSPECTIVE * (y / (double)f.height - line);
                double width = .285 * f.width * scale;
                double x = (.5 + (lanes[lane] - .5) * scale) * f.width;
                int count = 0;
                for (double offset : fractions) {
                    int xx = (int)Math.round(x + offset * width);
                    if (xx >= 0 && xx < f.width && f.at(xx, y) < 110) count++;
                }
                dark[y] = count >= 4;
                insideTile[y] = count >= 2;
            }
            // Glow can interrupt some border probes while others still see the tile.
            // A true gap between rapid taps exposes the bright background instead.
            // Never bridge that gap: close-by taps would become one oversized blob.
            int maxHole = Math.max(4, (int)Math.round(f.height * .010));
            int previous = -100;
            for (int y = top; y < bottom; y++) if (dark[y]) {
                if (y - previous <= maxHole + 1) {
                    boolean interior = true;
                    for (int gap = previous + 1; gap < y; gap++) if (!insideTile[gap]) { interior = false; break; }
                    if (interior) for (int fill = previous + 1; fill < y; fill++) dark[fill] = true;
                }
                previous = y;
            }
            int start = -1;
            for (int y = top; y <= bottom; y++) {
                boolean on = y < bottom && dark[y];
                if (on && start < 0) start = y;
                if (!on && start >= 0) {
                    int h = y - start;
                    if (h >= .067 * f.height && h <= .245 * f.height && start > top + 4) {
                        Detector.Detection detection = classify(f, lane, lanes[lane], line, start, y);
                        if (detection != null) found.add(detection);
                    }
                    start = -1;
                }
            }
        }
        found.sort(Comparator.comparingInt((Detector.Detection d) -> d.lane).thenComparingDouble(d -> -d.y));
        return found;
    }

    private void workspace(int size) {
        if (mask.length >= size) return;
        int capacity = Math.max(size, mask.length * 2);
        mask = new byte[capacity]; dilated = new byte[capacity]; closed = new byte[capacity];
        labels = new int[capacity]; queue = new int[capacity];
    }

    private Detector.Detection classify(GrayFrame f, int lane, double laneX, double line, int y0, int y1) {
        double middle = (y0 + y1) * .5;
        double scale = 1 + PERSPECTIVE * (middle / f.height - line);
        double tileWidth = .285 * f.width * scale;
        double center = (.5 + (laneX - .5) * scale) * f.width;
        int x0 = Math.max(0, (int)(center - tileWidth * .38));
        int x1 = Math.min(f.width, (int)(center + tileWidth * .38));
        int w = x1 - x0, h = y1 - y0, size = w * h;
        if (w < 5 || h < 5) return null;
        workspace(size);
        Arrays.fill(labels, 0, size, 0);
        Arrays.fill(dilated, 0, size, (byte)0);
        Arrays.fill(closed, 0, size, (byte)0);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            mask[y * w + x] = (byte)(f.at(x0 + x, y0 + y) > 225 ? 1 : 0);
        // Repair a one-pixel pointer/debug line through a glyph.
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean any = false;
            for (int yy = Math.max(0, y - 1); yy <= Math.min(h - 1, y + 1) && !any; yy++)
                for (int xx = Math.max(0, x - 1); xx <= Math.min(w - 1, x + 1); xx++)
                    if (mask[yy * w + xx] != 0) { any = true; break; }
            if (any) dilated[y * w + x] = 1;
        }
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            boolean all = true;
            for (int yy = y - 1; yy <= y + 1 && all; yy++) for (int xx = x - 1; xx <= x + 1; xx++)
                if (dilated[yy * w + xx] == 0) { all = false; break; }
            if (all) closed[y * w + x] = 1;
        }
        int label = 0, bestLabel = 0, bestArea = 0;
        int left = 0, right = 0, upper = 0, lower = 0;
        for (int index = 0; index < size; index++) {
            if (closed[index] == 0 || labels[index] != 0) continue;
            label++; int head = 0, tail = 1, area = 0;
            queue[0] = index; labels[index] = label;
            int l = w, r = 0, t = h, b = 0;
            while (head < tail) {
                int p = queue[head++], y = p / w, x = p % w; area++;
                l = Math.min(l, x); r = Math.max(r, x + 1); t = Math.min(t, y); b = Math.max(b, y + 1);
                if (x > 0) tail = enqueue(p - 1, label, tail);
                if (x + 1 < w) tail = enqueue(p + 1, label, tail);
                if (y > 0) tail = enqueue(p - w, label, tail);
                if (y + 1 < h) tail = enqueue(p + w, label, tail);
            }
            if (area > bestArea) { bestArea = area; bestLabel = label; left = l; right = r; upper = t; lower = b; }
        }
        int gw = right - left, gh = lower - upper;
        if (bestArea < 35 || gw < tileWidth * .31 || gh == 0) return null;
        int[] rowMass = new int[gh];
        for (int y = upper; y < lower; y++) for (int x = left; x < right; x++)
            if (labels[y * w + x] == bestLabel) rowMass[y - upper]++;
        int band = Math.min(gh, Math.max(3, (int)Math.round(h * .065)));
        int mass = 0, bestMass = 0, bestBand = 0;
        for (int y = 0; y < gh; y++) {
            mass += rowMass[y]; if (y >= band) mass -= rowMass[y - band];
            if (y >= band - 1 && mass > bestMass) { bestMass = mass; bestBand = y - band + 1; }
        }
        double anchor = upper + gh * .5, score;
        Kind kind;
        if (bestMass / (double)bestArea > .68) {
            kind = Kind.TAP; score = bestMass / (double)bestArea;
            double moment = 0;
            for (int y = bestBand; y < bestBand + band; y++) moment += (upper + y + .5) * rowMass[y];
            anchor = moment / bestMass;
        } else if (bestArea / (double)(gw * gh) > .64 && gw / (double)gh > .78 && gw / (double)gh < 1.25) {
            // The circular BEATSTAR stage-transition note is a tap, not a direction.
            kind = Kind.TAP; score = .9;
        } else {
            int[] candidate = new int[24]; int count = 0;
            for (int row = 0; row < 24; row++) for (int col = 0; col < 24; col++) {
                int xx = left + (int)((col + .5) * gw / 24), yy = upper + (int)((row + .5) * gh / 24);
                if (labels[yy * w + xx] == bestLabel) { candidate[row] |= 1 << col; count++; }
            }
            score = 0; kind = null;
            for (int k = 0; k < ArrowMasks.KINDS.length; k++) {
                int referenceCount = 0, intersection = 0;
                for (int row = 0; row < 24; row++) {
                    referenceCount += Integer.bitCount(ArrowMasks.ROWS[k][row]);
                    intersection += Integer.bitCount(candidate[row] & ArrowMasks.ROWS[k][row]);
                }
                double match = 2.0 * intersection / (referenceCount + count);
                if (match > score) { score = match; kind = ArrowMasks.KINDS[k]; }
            }
            if (kind == null || score < .76) return null;
        }
        return new Detector.Detection(lane, kind, (y0 + anchor) / f.height, score, h / (double)f.height);
    }

    private int enqueue(int index, int label, int tail) {
        if (closed[index] != 0 && labels[index] == 0) { labels[index] = label; queue[tail++] = index; }
        return tail;
    }
}
