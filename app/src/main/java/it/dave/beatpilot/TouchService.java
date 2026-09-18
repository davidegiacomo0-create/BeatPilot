package it.dave.beatpilot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import it.dave.beatpilot.core.NotePattern;
import it.dave.beatpilot.core.TouchPlanner;
import it.dave.beatpilot.core.Tracker;
import it.dave.beatpilot.core.GestureTiming;

public final class TouchService extends AccessibilityService {
    static volatile TouchService instance;
    volatile boolean foregroundOkay;
    volatile long lastFrame, frameMs;
    private volatile boolean armed;
    private volatile long epoch;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final Handler timing = Handler.createAsync(android.os.Looper.getMainLooper());
    private WindowManager windows;
    private LinearLayout panel;
    private View calibration;
    private TextView label;
    private Button toggle;
    private WindowManager.LayoutParams panelLayout;
    private SettingsStore config;
    private TouchPlanner planner;
    private boolean busy, flushAfterGesture, preparing, scenePaused;
    private long gestureSerial;
    private long notes;
    private long panelUpdated;
    private long displaySampled;
    private float refreshHz;
    private int width, height;
    private String message = "In pausa";
    private static final class Continued {
        GestureDescription.StrokeDescription stroke;
        float x, y;
        Continued(GestureDescription.StrokeDescription stroke, float x, float y) {
            this.stroke = stroke; this.x = x; this.y = y;
        }
    }
    private Map<Long, Continued> previous = new HashMap<>();
    private final Runnable tick = this::dispatchNext;
    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            foregroundOkay = isBeatstarForeground();
            CaptureService capture = CaptureService.instance;
            if ((armed || preparing) && !foregroundOkay) disarm("Fermato: Beatstar non è in primo piano");
            if (!foregroundOkay && capture != null) capture.finishRecording("Uscita da Beatstar");
            if (armed && SystemClock.uptimeMillis() - lastFrame > 250) disarm("Fermato: immagini assenti o troppo lente");
            if (armed && SystemClock.uptimeMillis()-displaySampled >= 1000) {
                displaySampled = SystemClock.uptimeMillis();
                android.view.Display display = getSystemService(android.hardware.display.DisplayManager.class)
                        .getDisplay(android.view.Display.DEFAULT_DISPLAY);
                float rate = display == null ? 0 : display.getRefreshRate();
                if (rate != refreshHz) { refreshHz = rate; trace("display_refresh","hz="+rate); }
            }
            if (panel != null && SystemClock.uptimeMillis()-panelUpdated >= 250) {
                panelUpdated = SystemClock.uptimeMillis();
                String badge = capture == null ? "" : capture.recordingBadge();
                label.setText((badge.isEmpty() ? "" : badge + " · ") + (armed ? (config.observeOnly ? "Osserva" : "Attivo") + " · " + notes
                        + " note · " + frameMs + " ms" : message));
                toggle.setText(armed || preparing ? "Stop" : capture != null && capture.hasRecording() ? "Salva" : "Avvia");
            }
            main.postDelayed(this, 100);
        }
    };

    @Override protected void onServiceConnected() {
        instance = this; windows = getSystemService(WindowManager.class); main.post(monitor);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        foregroundOkay = isBeatstarForeground();
        if ((armed || preparing) && !foregroundOkay) disarm("Fermato: Beatstar non è in primo piano");
        if (!foregroundOkay && CaptureService.instance != null) CaptureService.instance.finishRecording("Uscita da Beatstar");
    }
    @Override public void onInterrupt() { disarm("Accessibilità interrotta"); }
    @Override public void onDestroy() {
        disarm("Servizio disattivato"); hidePanel(); removeCalibration();
        main.removeCallbacks(monitor); instance = null;
        if (CaptureService.instance != null) CaptureService.instance.requestClose("Controllo tocchi disattivato");
        super.onDestroy();
    }
    boolean isArmed(long token) { return armed && epoch == token; }
    private boolean isBeatstarForeground() {
        try {
            List<AccessibilityWindowInfo> list = getWindows();
            boolean target = false;
            for (AccessibilityWindowInfo window : list) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                if (window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && window.isFocused()) return false;
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || (!window.isFocused() && !window.isActive())) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) return false;
                CharSequence name = root.getPackageName();
                boolean correct = name != null && SettingsStore.TARGET.contentEquals(name);
                root.recycle();
                if (!correct) return false;
                target = true;
            }
            return target;
        } catch (Exception e) { return false; }
    }
    private void arm() {
        if (armed || preparing) return;
        if (busy || !previous.isEmpty()) { Ui.toast(this, "Attendi la fine del gesto e riprova."); return; }
        CaptureService capture = CaptureService.instance;
        if (capture == null || !capture.ready) { Ui.toast(this, "Apri BeatPilot e avvia la condivisione schermo."); return; }
        if (!isBeatstarForeground()) { Ui.toast(this, "Apri Beatstar prima di premere Avvia."); return; }
        config = SettingsStore.load(this);
        if (!config.calibrated) { Ui.toast(this, "Premi Calibra e allinea corsie e linea di arrivo."); return; }
        width = capture.screenWidth; height = capture.screenHeight;
        if (config.calibrationWidth != width || config.calibrationHeight != height) {
            Ui.toast(this, "Le dimensioni schermo sono cambiate: ripeti Calibra."); return;
        }
        try {
            List<NotePattern> patterns = SettingsStore.patterns(this);
            if (!config.videoProfile && patterns.isEmpty()) { Ui.toast(this, "Aggiungi prima i campioni delle note in BeatPilot."); return; }
            long token = ++epoch; preparing = true; notes = 0; refreshHz = 0; displaySampled = 0;
            message = config.recordVideo ? "Preparazione video…" : "Avvio…";
            Runnable begin = () -> {
                if (epoch != token || !preparing) return;
                if (CaptureService.instance != capture || !capture.ready || !isBeatstarForeground()) {
                    disarm("Avvio annullato: riapri Beatstar"); capture.finishRecording("Avvio annullato"); return;
                }
                planner = new TouchPlanner(config.lanes, config.line, config.videoProfile); flushAfterGesture = false; scenePaused = false;
                preparing = false; armed = true; foregroundOkay = true; lastFrame = SystemClock.uptimeMillis();
                capture.arm(token, config, patterns); message = config.observeOnly ? "Osserva" : "Attivo";
                trace("bot_start","observe=" + config.observeOnly + ";advance_ms=" + config.advanceMs);
            };
            if (config.recordVideo) capture.ensureRecording(config,begin,error -> {
                if (epoch == token) disarm(error);
            }); else begin.run();
        } catch (Exception e) { disarm("Campioni non validi"); Ui.toast(this, e.getMessage()); }
    }
    void enqueue(long token, List<Tracker.Hit> hits, long ready) {
        timing.post(() -> {
            if (!isArmed(token)) return;
            trace("vision_queue", "delay_ms=" + (SystemClock.uptimeMillis()-ready));
            accept(token,hits);
        });
    }
    void enqueueCancel(long token) { timing.post(() -> cancelPending(token)); }
    void enqueueScenePause(long token) {
        timing.post(() -> {
            if (!isArmed(token)) return;
            scenePaused = true;
            timing.removeCallbacks(tick);
        });
    }
    void enqueueSceneResume(long token) {
        timing.post(() -> {
            if (!isArmed(token) || !scenePaused) return;
            scenePaused = false;
            if (!busy) { timing.removeCallbacks(tick); timing.post(tick); }
        });
    }
    void accept(long token, List<Tracker.Hit> hits) {
        if (!isArmed(token)) return;
        if (!isBeatstarForeground()) { disarm("Fermato: Beatstar non è in primo piano"); return; }
        for (Tracker.Hit hit : hits) {
            if (!hit.revision && hit.kind != it.dave.beatpilot.core.Kind.HOLD_END) notes++;
            trace(hit.revision ? "hit_refined" : "hit_predicted", "note_id=" + hit.id + ";lane=" + (hit.lane + 1)
                    + ";kind=" + hit.kind + ";due_uptime_ms=" + hit.at + ";observe=" + config.observeOnly);
        }
        if (config.observeOnly) return;
        for (Tracker.Hit hit : hits) planner.add(hit);
        if (!busy) { timing.removeCallbacks(tick); timing.post(tick); }
    }
    void disarm(String reason) {
        if (armed || preparing) trace("bot_stop",reason);
        armed = false; preparing = false; scenePaused = false; epoch++; message = reason;
        if (CaptureService.instance != null) CaptureService.instance.pause();
        if (planner != null) planner.clear();
        timing.removeCallbacks(tick);
        if (!busy) releaseHeldPointers();
    }
    void cancelPending(long token) {
        if (!isArmed(token)) return;
        scenePaused = false;
        if (planner != null && !planner.idle()) trace("queue_cancelled","scene_not_gameplay");
        if (planner != null) planner.clear();
        timing.removeCallbacks(tick);
        if (busy) flushAfterGesture = true; else releaseHeldPointers();
    }
    private void dispatchNext() {
        if (busy || !armed || scenePaused || planner == null || config.observeOnly) return;
        if (!isBeatstarForeground()) { disarm("Fermato: Beatstar non è in primo piano"); return; }
        long now = SystemClock.uptimeMillis();
        if (now - lastFrame > 250) { disarm("Fermato: immagini assenti o troppo lente"); return; }
        long next = planner.nextTime();
        if (next == Long.MAX_VALUE) return;
        if (next > now + GestureTiming.LEAD_MS) { timing.postAtTime(tick, GestureTiming.wakeAt(next)); return; }
        // Opening tiles need two simultaneous touches. Send the complete pair
        // in one Android gesture, without cancelling it on each waiting-screen frame.
        List<TouchPlanner.Segment> segments = planner.plan(now, planner.openingNext() ? 250 : GestureTiming.HORIZON_MS);
        if (segments.isEmpty()) { if (!planner.idle()) timing.postDelayed(tick, 1); return; }
        if (segments.size() > GestureDescription.getMaxStrokeCount()) { disarm("Troppi gesti sovrapposti"); return; }
        try {
            GestureDescription.Builder builder = new GestureDescription.Builder();
            Map<Long, Continued> following = new HashMap<>();
            for (TouchPlanner.Segment segment : segments) {
                float x0 = (float)(segment.x0 * width), y0 = (float)(segment.y0 * height);
                float x1 = (float)(segment.x1 * width), y1 = (float)(segment.y1 * height);
                Path path = new Path();
                GestureDescription.StrokeDescription stroke;
                if (segment.continuation) {
                    Continued old = previous.get(segment.id);
                    if (old == null) throw new IllegalStateException("Continuità del tocco persa");
                    // Use the exact stored floats to satisfy Android's endpoint continuity contract.
                    x0 = old.x; y0 = old.y;
                    path.moveTo(x0, y0); if (x1 != x0 || y1 != y0) path.lineTo(x1, y1);
                    stroke = old.stroke.continueStroke(path, segment.offset, segment.duration, segment.more);
                } else {
                    path.moveTo(x0, y0); if (x1 != x0 || y1 != y0) path.lineTo(x1, y1);
                    stroke = new GestureDescription.StrokeDescription(path, segment.offset, segment.duration, segment.more);
                }
                builder.addStroke(stroke);
                if (segment.more) following.put(segment.id, new Continued(stroke, x1, y1));
            }
            busy = true;
            long batch = ++gestureSerial;
            long dispatchTime = SystemClock.uptimeMillis();
            boolean accepted = dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    trace("gesture_completed","batch=" + batch);
                    busy = false;
                    if (flushAfterGesture) { flushAfterGesture = false; releaseHeldPointers(); }
                    else if (armed) timing.post(tick); else releaseHeldPointers();
                }
                @Override public void onCancelled(GestureDescription g) {
                    trace("gesture_cancelled","batch=" + batch);
                    busy = false; previous.clear(); disarm("Gesto interrotto · premi Avvia per riprendere");
                }
            }, timing);
            if (CaptureService.instance != null) {
                StringBuilder detail = new StringBuilder("batch=").append(batch).append(";accepted=").append(accepted)
                        .append(";plan_uptime_ms=").append(now).append(";segments=note_id:x0:y0:x1:y1:offset_ms:duration_ms:continued[");
                for (TouchPlanner.Segment s : segments) detail.append(s.noteId).append(':').append(s.x0).append(':').append(s.y0)
                        .append(':').append(s.x1).append(':').append(s.y1).append(':').append(s.offset).append(':').append(s.duration)
                        .append(':').append(s.continuation).append('|');
                detail.append("];contact_state=note_id:will_continue:release_guard_ms[");
                for (TouchPlanner.Segment s : segments) detail.append(s.noteId).append(':').append(s.more)
                        .append(':').append(s.releaseGuardMs).append('|');
                CaptureService.instance.trace(dispatchTime,"gesture_request",detail.append(']').toString());
            }
            if (accepted) previous = following;
            else { busy = false; disarm("Android non ha accettato il gesto"); }
        } catch (Exception e) { busy = false; disarm("Errore gesto: " + e.getMessage()); }
    }

    private void trace(String type, String data) {
        CaptureService capture = CaptureService.instance;
        if (capture != null) capture.trace(SystemClock.uptimeMillis(),type,data);
    }
    private void releaseHeldPointers() {
        if (busy) return;
        if (previous.isEmpty()) {
            if (armed && planner != null && !planner.idle()) timing.post(tick);
            return;
        }
        Map<Long, Continued> releasing = previous; previous = new HashMap<>();
        try {
            GestureDescription.Builder builder = new GestureDescription.Builder();
            for (Continued c : releasing.values()) {
                Path path = new Path(); path.moveTo(c.x, c.y);
                builder.addStroke(c.stroke.continueStroke(path, 0, 1, false));
            }
            busy = true;
            boolean accepted = dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { busy = false; if (armed) timing.post(tick); }
                @Override public void onCancelled(GestureDescription g) { busy = false; if (armed) timing.post(tick); }
            }, timing);
            if (!accepted) busy = false;
        } catch (Exception e) { busy = false; }
    }

    void showPanel() {
        if (panel != null || windows == null) return;
        panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xEE173B35); panel.setPadding(Ui.dp(this, 7), Ui.dp(this, 4), Ui.dp(this, 7), Ui.dp(this, 4));
        label = new TextView(this); label.setTextColor(Color.WHITE); label.setTextSize(12); label.setSingleLine(true);
        label.setText("BeatPilot · trascina qui"); panel.addView(label);
        LinearLayout row = new LinearLayout(this); panel.addView(row);
        toggle = panelButton(row, "Avvia", () -> {
            CaptureService capture = CaptureService.instance;
            if (armed || preparing) {
                disarm("In pausa"); if (capture != null) capture.finishRecording("Stop premuto dall’utente");
            } else if (capture != null && capture.hasRecording()) capture.finishRecording("Salva premuto dall’utente");
            else arm();
        });
        panelButton(row, "Video", () -> {
            disarm("In pausa");
            if (CaptureService.instance != null) CaptureService.instance.finishRecording("Apertura elenco video");
            startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        panelButton(row, "Calibra", this::showCalibration);
        panelButton(row, "Chiudi", () -> {
            disarm("Sessione chiusa");
            if (CaptureService.instance != null) CaptureService.instance.requestClose("Sessione chiusa");
            hidePanel();
        });
        panelLayout = overlayParams(Ui.dp(this, 290), WindowManager.LayoutParams.WRAP_CONTENT);
        Rect screen = windows.getMaximumWindowMetrics().getBounds();
        panelLayout.x = Ui.dp(this, 8);
        // Keep the overlay below the detector region and away from the score-circle probes.
        panelLayout.y = (int)(screen.height() * .845);
        label.setOnTouchListener(new View.OnTouchListener() {
            float x, y; int originalX, originalY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    x = event.getRawX(); y = event.getRawY(); originalX = panelLayout.x; originalY = panelLayout.y; return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    Rect bounds = windows.getMaximumWindowMetrics().getBounds();
                    panelLayout.x = Math.max(0, Math.min(bounds.width() - panel.getWidth(), originalX + (int)(event.getRawX() - x)));
                    panelLayout.y = Math.max(0, Math.min(bounds.height() - panel.getHeight(), originalY + (int)(event.getRawY() - y)));
                    windows.updateViewLayout(panel, panelLayout); return true;
                }
                return true;
            }
        });
        try { windows.addView(panel, panelLayout); }
        catch (Exception e) { panel = null; Ui.toast(this, "Pannello non disponibile: " + e.getMessage()); }
    }
    private Button panelButton(LinearLayout row, String name, Runnable action) {
        Button b = new Button(this); b.setText(name); b.setTextSize(12); b.setAllCaps(false);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(0, 0, 0, 0);
        row.addView(b, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1));
        b.setOnClickListener(v -> action.run()); return b;
    }
    void hidePanel() {
        if (panel != null) { try { windows.removeView(panel); } catch (Exception ignored) {} panel = null; }
    }
    private WindowManager.LayoutParams overlayParams(int w, int h) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT; p.setFitInsetsTypes(0);
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return p;
    }
    private void removeCalibration() {
        if (calibration != null) { try { windows.removeView(calibration); } catch (Exception ignored) {} calibration = null; }
    }
    private void showCalibration() {
        disarm("Calibrazione"); hidePanel();
        if (CaptureService.instance != null) CaptureService.instance.finishRecording("Calibrazione aperta");
        if (calibration != null) return;
        SettingsStore draft = SettingsStore.load(this);
        calibration = new View(this) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            int dragging = -1;
            final float d = getResources().getDisplayMetrics().density;
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(0x3310211D); paint.setStrokeWidth(2 * d); paint.setTextSize(15 * d);
                for (int i = 0; i < 3; i++) {
                    paint.setColor(0xFF60FFE2);
                    float x = (float)(draft.lanes[i] * getWidth());
                    canvas.drawLine(x, 160 * d, x, getHeight(), paint);
                    canvas.drawCircle(x, (float)(draft.line * getHeight()), 9 * d, paint);
                    canvas.drawText("" + (i + 1), x + 7 * d, 180 * d, paint);
                }
                paint.setColor(0xFFFFD166);
                canvas.drawLine(0, (float)(draft.line * getHeight()), getWidth(), (float)(draft.line * getHeight()), paint);
                paint.setColor(0xF5173B35); canvas.drawRect(0, 40 * d, getWidth(), 145 * d, paint);
                paint.setColor(Color.WHITE); paint.setTextSize(13 * d);
                canvas.drawText("Trascina corsie e linea di allineamento", 12 * d, 67 * d, paint);
                canvas.drawText("Tocchi sospesi durante la calibrazione", 12 * d, 88 * d, paint);
                paint.setColor(0xFF60FFE2); paint.setTextSize(16 * d);
                canvas.drawText("ANNULLA", 18 * d, 127 * d, paint);
                canvas.drawText("SALVA", getWidth() - 95 * d, 127 * d, paint);
            }
            @Override public boolean onTouchEvent(MotionEvent event) {
                float x = event.getX(), y = event.getY();
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    if (y > 100 * d && y < 145 * d) {
                        if (x > getWidth() / 2f) {
                            Rect b = windows.getMaximumWindowMetrics().getBounds();
                            draft.calibrated = true; draft.calibrationWidth = b.width(); draft.calibrationHeight = b.height();
                            draft.save(TouchService.this);
                        }
                        removeCalibration(); showPanel(); return true;
                    }
                    if (Math.abs(y - draft.line * getHeight()) < 28 * d) dragging = 3;
                    else {
                        double distance = 40 * d;
                        for (int i = 0; i < 3; i++) if (Math.abs(x - draft.lanes[i] * getWidth()) < distance) {
                            dragging = i; distance = Math.abs(x - draft.lanes[i] * getWidth());
                        }
                    }
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    if (dragging == 3) draft.line = Math.max(.35, Math.min(.94, y / getHeight()));
                    else if (dragging >= 0) {
                        double min = dragging == 0 ? .06 : draft.lanes[dragging - 1] + .12;
                        double max = dragging == 2 ? .94 : draft.lanes[dragging + 1] - .12;
                        draft.lanes[dragging] = Math.max(min, Math.min(max, x / getWidth()));
                    }
                    invalidate();
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) dragging = -1;
                return true;
            }
        };
        try { windows.addView(calibration, overlayParams(-1, -1)); }
        catch (Exception e) { calibration = null; showPanel(); Ui.toast(this, "Calibrazione non disponibile"); }
    }
}
