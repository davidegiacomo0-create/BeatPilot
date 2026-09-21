package it.dave.beatpilot;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.WindowManager;
import java.nio.ByteBuffer;
import java.util.List;
import it.dave.beatpilot.core.Detector;
import it.dave.beatpilot.core.GrayFrame;
import it.dave.beatpilot.core.NotePattern;
import it.dave.beatpilot.core.Tracker;
import it.dave.beatpilot.core.BeatstarDetector;
import it.dave.beatpilot.core.RgbaToGray;
import it.dave.beatpilot.core.FrameClock;
import it.dave.beatpilot.core.StartGate;
import it.dave.beatpilot.core.GameplayGate;

public final class CaptureService extends Service {
    static volatile CaptureService instance;
    static volatile String status = "Condivisione schermo non avviata";
    static volatile String recordingStatus = "Video: nessuna registrazione in corso";
    volatile boolean ready, armed;
    int screenWidth, screenHeight;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private HandlerThread thread;
    private Handler worker;
    private ImageReader reader;
    private MediaProjection projection;
    private VirtualDisplay display;
    private Tracker tracker = new Tracker();
    private final Detector detector = new Detector();
    private final BeatstarDetector beatstar = new BeatstarDetector();
    private final RgbaToGray converter = new RgbaToGray();
    private final FrameClock frameClock = new FrameClock();
    private final StartGate startGate = new StartGate();
    private final GameplayGate gameplayGate = new GameplayGate();
    private boolean scenePaused;
    private SettingsStore config;
    private List<NotePattern> patterns = List.of();
    private long epoch, started;
    private int width, height;
    private byte[] gray;
    private volatile VideoRecorder recorder;
    private volatile boolean closing;

    @Override public void onCreate() {
        super.onCreate(); instance = this;
        thread = new HandlerThread("BeatPilot-vision", android.os.Process.THREAD_PRIORITY_DISPLAY);
        thread.start(); worker = new Handler(thread.getLooper());
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "STOP".equals(intent.getAction())) { requestClose("Sessione chiusa"); return START_NOT_STICKY; }
        if (projection != null) return START_NOT_STICKY;
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("capture", "Sessione BeatPilot", NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CaptureService.class).setAction("STOP"),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification note = new Notification.Builder(this, "capture").setSmallIcon(R.drawable.ic_note)
                    .setContentTitle("BeatPilot · condivisione schermo")
                    .setContentText("Usa il pannello Avvia/Stop. Tocca Chiudi per terminare la sessione.")
                    .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "Chiudi", stop).build()).build();
            startForeground(11, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            Intent consent = intent.getParcelableExtra("consent", Intent.class);
            if (consent == null || intent.getIntExtra("result", 0) != Activity.RESULT_OK)
                throw new IllegalArgumentException("Manca il consenso alla condivisione schermo");
            Rect bounds = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
            screenWidth = bounds.width(); screenHeight = bounds.height();
            if (screenWidth >= screenHeight) throw new IllegalArgumentException("Usa il telefono in verticale");
            width = 480; height = Math.round(width * screenHeight / (float)screenWidth);
            gray = new byte[width * height];
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK, consent);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { requestClose("Condivisione schermo interrotta"); }
                @Override public void onCapturedContentResize(int w, int h) {
                    if (Math.abs(w / (double)h - screenWidth / (double)screenHeight) > .015
                            || w < screenWidth * .9 || h < screenHeight * .9) {
                        requestClose("Cattura non allineata: riavvia condividendo lo schermo intero in verticale");
                    }
                }
            }, main);
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this::frameAvailable, worker);
            display = projection.createVirtualDisplay("BeatPilot", width, height,
                    getResources().getDisplayMetrics().densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, worker);
            ready = true; status = "Sessione pronta · apri Beatstar e premi Avvia";
            if (TouchService.instance != null) TouchService.instance.showPanel();
        } catch (Exception e) { requestClose("Sessione non avviata: " + e.getMessage()); }
        return START_NOT_STICKY;
    }
    void arm(long token, SettingsStore cfg, List<NotePattern> templates) {
        worker.post(() -> {
            TouchService touch = TouchService.instance;
            if (touch == null || !touch.isArmed(token)) return;
            tracker = new Tracker(cfg.videoProfile ? BeatstarDetector.PERSPECTIVE : 0);
            config = cfg; patterns = templates; epoch = token;
            started = SystemClock.uptimeMillis(); startGate.reset(); gameplayGate.reset(); scenePaused = false; armed = true;
        });
    }
    void pause() { armed = false; }

    void ensureRecording(SettingsStore cfg, Runnable whenReady, java.util.function.Consumer<String> onError) {
        if (closing || !ready) { onError.accept("Sessione di cattura non disponibile"); return; }
        if (recorder != null) {
            if (recorder.isAccepting()) whenReady.run();
            else onError.accept("Attendi la fine del salvataggio del video e riprova");
            return;
        }
        recordingStatus = "Preparazione della registrazione…";
        recorder = new VideoRecorder(this,width,height,cfg,new VideoRecorder.Listener() {
            @Override public void ready(VideoRecorder sender) {
                if (sender != recorder || closing) { sender.finish("Sessione chiusa durante la preparazione"); return; }
                recordingStatus = "Registrazione attiva · premi Stop per salvare";
                whenReady.run();
            }
            @Override public void finished(VideoRecorder sender, boolean saved, String message) {
                if (sender != recorder) return;
                recorder = null; recordingStatus = message;
                Ui.toast(CaptureService.this,message);
                if (!saved) onError.accept(message);
                if (closing) stopSelf();
            }
        });
    }
    void finishRecording(String reason) {
        VideoRecorder video = recorder;
        if (video != null) { recordingStatus = "Salvataggio video…"; video.finish(reason); }
    }
    boolean hasRecording() { return recorder != null; }
    String recordingBadge() { VideoRecorder video = recorder; return video == null ? "" : video.badge(); }
    void trace(long time, String type, String data) {
        VideoRecorder video = recorder; if (video != null) video.log(time,type,data);
    }
    void requestClose(String reason) {
        if (closing) return;
        closing = true; ready = false; armed = false; status = reason;
        if (TouchService.instance != null) TouchService.instance.disarm(reason);
        if (recorder == null) stopSelf(); else finishRecording(reason);
    }

    private void traceFrame(long time, long captured, long converted, boolean hud, boolean gameplay, List<Detector.Detection> found) {
        VideoRecorder video = recorder; if (video == null) return;
        long done = SystemClock.uptimeMillis();
        StringBuilder info = new StringBuilder("analysis_ms=").append(done - time)
                .append(";gray_ms=").append(converted - time).append(";detect_ms=").append(done - converted)
                .append(";capture_uptime_ms=").append(captured).append(";image_age_ms=").append(time - captured)
                .append(";source_clock=").append(frameClock.sourceBased())
                .append(";hud_visible=").append(hud)
                .append(";recovered_notes=").append(found.stream().filter(n -> n.recovered).count())
                .append(";gameplay=").append(gameplay).append(";notes=lane:kind:y:score[");
        for (Detector.Detection note : found) info.append(note.lane + 1).append(':').append(note.kind)
                .append(':').append(note.y).append(':').append(note.score).append('|');
        video.log(time,"vision",info.append(']').toString());
    }

    private void frameAvailable(ImageReader source) {
        try (Image image = source.acquireLatestImage()) {
            if (image == null) return;
            TouchService touch = TouchService.instance;
            if (touch == null || !touch.foregroundOkay || closing) return;
            touch.lastImageReceived = SystemClock.uptimeMillis();
            long time = SystemClock.uptimeMillis();
            // The finally block records the same pixels after any time-critical vision work.
            try {
                if (!armed || !touch.isArmed(epoch)) return;
                if (time - started > 600_000) { main.post(() -> touch.disarm("Sessione di 10 minuti terminata")); return; }
                long captured = frameClock.sample(image.getTimestamp(), System.nanoTime(), time);
                if (frameClock.changed()) tracker.reset();
                Image.Plane plane = image.getPlanes()[0]; ByteBuffer buffer = plane.getBuffer();
                int rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
                converter.convert(buffer, rowStride, pixelStride, width, height, gray);
                long converted = SystemClock.uptimeMillis();
                GrayFrame frame = new GrayFrame(width, height, gray);
                boolean hud = !config.videoProfile || beatstar.isGameplay(frame);
                List<Detector.Detection> initial = config.videoProfile && !hud
                        ? beatstar.startingNotes(frame, config.lanes, config.line) : List.of();
                boolean gameplay = !config.videoProfile || gameplayGate.update(hud, initial.size() == 2, time);
                if (!gameplay) {
                    long token = epoch;
                    touch.lastFrame = SystemClock.uptimeMillis();
                    touch.frameMs = touch.lastFrame - time;
                    if (gameplayGate.preserveNotes()) {
                        if (!scenePaused) {
                            scenePaused = true;
                            touch.enqueueScenePause(token);
                            trace(time,"scene_uncertain","predictions_retained;dispatch_paused");
                        }
                        traceFrame(time,captured,converted,hud,false,initial);
                        return;
                    }
                    tracker.reset(); scenePaused = false;
                    if (!startGate.gestureInFlight(touch.lastFrame)) touch.enqueueCancel(token);
                    if (!config.observeOnly && startGate.update(initial.size() == 2, touch.lastFrame)) {
                        long begin = SystemClock.uptimeMillis();
                        List<Tracker.Hit> opening = StartGate.openingHits(begin);
                        trace(begin, "start_attempt", "attempt=" + startGate.attempts());
                        touch.enqueue(token, opening, begin);
                    }
                    traceFrame(time,captured,converted,hud,false,initial);
                    return;
                }
                if (scenePaused) {
                    scenePaused = false;
                    touch.enqueueSceneResume(epoch);
                    trace(time,"scene_restored","retained_predictions_resumed");
                }
                boolean[] heldLanes = new boolean[] {
                        touch.holdingLane(0),
                        touch.holdingLane(1),
                        touch.holdingLane(2)
                };
                List<Detector.Detection> found = config.videoProfile
                        ? beatstar.detect(frame, config.lanes, config.line,
                        tracker.expectations(captured,config.line), heldLanes)
                        : detector.detect(frame, patterns, config.lanes,
                        Math.max(.10, config.line - .38), Math.min(.99, config.line + .05), config.threshold);
                startGate.update(false, time);
                for (Detector.Detection d : found) if (d.y < config.line - .05) startGate.gameplayHasNotes();
                List<Tracker.Hit> hits = tracker.update(found, captured, SystemClock.uptimeMillis(),
                        config.line, config.advanceMs, heldLanes);
                long token = epoch;
                touch.lastFrame = SystemClock.uptimeMillis();
                touch.frameMs = touch.lastFrame - time;
                if (!hits.isEmpty()) touch.enqueue(token, hits, SystemClock.uptimeMillis());
                traceFrame(time,captured,converted,hud,true,found);
            } finally {
                VideoRecorder video = recorder;
                if (video != null && video.isAccepting()) {
                    Image.Plane plane = image.getPlanes()[0];
                    video.offer(plane.getBuffer(),plane.getRowStride(),plane.getPixelStride(),time,image.getTimestamp());
                }
            }
        } catch (Exception e) {
            armed = false;
            main.post(() -> { if (TouchService.instance != null) TouchService.instance.disarm("Errore immagini: " + e.getMessage()); });
        }
    }
    @Override public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        Rect r = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        if (ready && (r.width() != screenWidth || r.height() != screenHeight)) {
            requestClose("Dimensioni schermo cambiate: riavvia la sessione e ricalibra");
        }
    }
    @Override public void onDestroy() {
        ready = false; armed = false; instance = null;
        finishRecording("Sessione chiusa");
        if (TouchService.instance != null) { TouchService.instance.disarm("Sessione chiusa"); TouchService.instance.hidePanel(); }
        worker.post(() -> {
            if (display != null) display.release();
            if (reader != null) { reader.setOnImageAvailableListener(null, null); reader.close(); }
            if (projection != null) projection.stop();
            thread.quitSafely();
        });
        if (status.startsWith("Sessione pronta") || status.startsWith("Attivo")) status = "Condivisione schermo terminata";
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
