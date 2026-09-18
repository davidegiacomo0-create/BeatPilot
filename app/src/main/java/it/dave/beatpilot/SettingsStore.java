package it.dave.beatpilot;

import android.content.Context;
import android.content.SharedPreferences;
import it.dave.beatpilot.core.Kind;
import it.dave.beatpilot.core.NotePattern;
import it.dave.beatpilot.core.BeatstarDetector;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class SettingsStore {
    static final String TARGET = "com.spaceapegames.beatstar";
    final double[] lanes = BeatstarDetector.LANES.clone();
    double line = BeatstarDetector.HIT_LINE, threshold = .91, advanceMs = 0;
    boolean calibrated = false, observeOnly = true, videoProfile = true, recordVideo = true;
    int calibrationWidth, calibrationHeight;
    static SettingsStore load(Context c) {
        SharedPreferences p = c.getSharedPreferences("config", Context.MODE_PRIVATE);
        SettingsStore s = new SettingsStore();
        for (int i = 0; i < 3; i++) s.lanes[i] = p.getFloat("x" + i, (float)s.lanes[i]);
        s.line = p.getFloat("line", (float)BeatstarDetector.HIT_LINE); s.threshold = p.getFloat("threshold", .91f);
        s.advanceMs = p.getFloat("advance", 0); s.calibrated = p.getBoolean("calibrated", false);
        s.observeOnly = p.getBoolean("observe", true);
        s.videoProfile = p.getBoolean("videoProfile", true);
        s.recordVideo = p.getBoolean("recordVideo", true);
        s.calibrationWidth = p.getInt("width", 0); s.calibrationHeight = p.getInt("height", 0);
        return s;
    }
    void save(Context c) {
        SharedPreferences.Editor p = c.getSharedPreferences("config", Context.MODE_PRIVATE).edit();
        for (int i = 0; i < 3; i++) p.putFloat("x" + i, (float)lanes[i]);
        p.putFloat("line", (float)line).putFloat("threshold", (float)threshold)
                .putFloat("advance", (float)advanceMs).putBoolean("calibrated", calibrated)
                .putBoolean("observe", observeOnly).putInt("width", calibrationWidth)
                .putBoolean("videoProfile", videoProfile)
                .putBoolean("recordVideo", recordVideo)
                .putInt("height", calibrationHeight).apply();
    }
    static void applyVideoProfile(Context context) {
        android.graphics.Rect bounds = context.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
        if (Math.abs(bounds.width() / (double)bounds.height() - BeatstarDetector.VIDEO_ASPECT) > .01)
            throw new IllegalArgumentException("Le proporzioni dello schermo differiscono dal video. Usa Calibra per allineare manualmente le corsie.");
        SettingsStore s = load(context);
        System.arraycopy(BeatstarDetector.LANES, 0, s.lanes, 0, 3);
        s.line = BeatstarDetector.HIT_LINE; s.videoProfile = true; s.calibrated = true;
        s.calibrationWidth = bounds.width(); s.calibrationHeight = bounds.height();
        s.advanceMs = 0; s.observeOnly = true; s.save(context);
    }
    static List<NotePattern> patterns(Context c) throws Exception {
        String json = c.getSharedPreferences("patterns", Context.MODE_PRIVATE).getString("items", "[]");
        JSONArray array = new JSONArray(json);
        List<NotePattern> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            JSONArray values = o.getJSONArray("values");
            if (values.length() != NotePattern.SIZE) throw new IllegalArgumentException("Campione danneggiato");
            double[] samples = new double[NotePattern.SIZE];
            for (int j = 0; j < samples.length; j++) samples[j] = values.getDouble(j);
            result.add(new NotePattern(o.getString("id"), Kind.valueOf(o.getString("kind")),
                    o.getDouble("w"), o.getDouble("h"), o.getDouble("ax"), o.getDouble("ay"), samples));
        }
        return result;
    }
    static void savePatterns(Context c, List<NotePattern> patterns) throws Exception {
        JSONArray array = new JSONArray();
        for (NotePattern n : patterns) {
            JSONObject o = new JSONObject(); o.put("id", n.id); o.put("kind", n.kind.name());
            o.put("w", n.widthRatio); o.put("h", n.heightRatio); o.put("ax", n.anchorX); o.put("ay", n.anchorY);
            JSONArray values = new JSONArray(); for (double v : n.storageSamples()) values.put(v);
            o.put("values", values); array.put(o);
        }
        c.getSharedPreferences("patterns", Context.MODE_PRIVATE).edit().putString("items", array.toString()).apply();
    }
}
