package it.dave.beatpilot;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import it.dave.beatpilot.core.RecordingFrames;
import it.dave.beatpilot.core.TraceBuffer;

/** One projection is shared with vision. Encoding is isolated and never awaited by the vision thread. */
final class VideoRecorder {
    interface Listener {
        void ready(VideoRecorder recorder);
        void finished(VideoRecorder recorder, boolean saved, String message);
    }
    private static final int FRAME_RATE = 60, MAX_SECONDS = 180;
    private final Context context;
    private final int width, height;
    private final SettingsStore config;
    private final Listener listener;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("BeatPilot-video",android.os.Process.THREAD_PRIORITY_DEFAULT);
    private final Handler worker;
    private final ArrayBlockingQueue<ByteBuffer> available = new ArrayBlockingQueue<>(3);
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final TraceBuffer trace = new TraceBuffer(30000);
    private final RecordingFrames.Clock clock = new RecordingFrames.Clock();
    private final String name = "BeatPilot_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.ROOT).format(new Date());
    private MediaCodec codec;
    private MediaMuxer muxer;
    private EncoderSurface renderer;
    private Surface surface;
    private ParcelFileDescriptor file;
    private Uri videoUri;
    private int track = -1;
    private boolean codecStarted, muxerStarted, released;
    private volatile boolean accepting;
    private volatile long firstFrameMs = -1, droppedFrames, rateSkipped, encodedFrames;
    private long lastOfferedMs = -1, lastOutputUs = -1;
    private volatile String badge = "VIDEO…";
    private String codecName = "", stopReason = "Stop";
    private final Runnable limit = () -> finish("Limite video di 3 minuti");

    VideoRecorder(Context context, int width, int height, SettingsStore config, Listener listener) {
        this.context = context.getApplicationContext(); this.width = width; this.height = height;
        this.config = config; this.listener = listener;
        thread.start(); worker = new Handler(thread.getLooper());
        worker.post(this::initialize);
    }
    boolean isAccepting() { return accepting && !stopping.get(); }
    boolean isStopping() { return stopping.get(); }
    String badge() {
        long zero = firstFrameMs;
        if (!isAccepting() || zero < 0) return badge;
        long seconds = Math.max(0,(SystemClock.uptimeMillis() - zero) / 1000);
        return String.format(Locale.ROOT,"REC %d:%02d",seconds / 60,seconds % 60);
    }
    void log(long uptimeMs, String type, String data) { if (!stopping.get()) trace.add(uptimeMs,type,data); }

    private void initialize() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE,6_000_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE,FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES,0);
            // Require hardware encoding, so a software fallback cannot silently consume the game's CPU budget.
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder() || !info.isHardwareAccelerated()) continue;
                try {
                    if (info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).isFormatSupported(format)) {
                        codecName = info.getName(); break;
                    }
                } catch (IllegalArgumentException ignored) {}
            }
            if (codecName.isEmpty()) throw new IllegalStateException("Encoder H.264 hardware 60 fps non disponibile");
            codec = MediaCodec.createByCodecName(codecName);
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface(); codec.start(); codecStarted = true;
            renderer = new EncoderSurface(surface,width,height);
            videoUri = RecordingStore.newVideo(context,name);
            file = context.getContentResolver().openFileDescriptor(videoUri,"rw");
            if (file == null) throw new IllegalStateException("File video non scrivibile");
            muxer = new MediaMuxer(file.getFileDescriptor(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            for (int i = 0; i < 3; i++) available.add(ByteBuffer.allocateDirect(width * height * 4));
            if (stopping.get()) return; // The queued finish operation owns cleanup.
            accepting = true; badge = "REC pronta";
            main.postDelayed(limit,MAX_SECONDS * 1000L);
            main.post(() -> { if (!stopping.get()) listener.ready(this); });
        } catch (Exception e) { fail("Registrazione non avviata: " + e.getMessage()); }
    }

    /** Called after vision has scheduled its touches. All expensive codec work happens elsewhere. */
    void offer(ByteBuffer rgba, int rowStride, int pixelStride, long arrivalMs, long imageTimestampNs) {
        if (!isAccepting()) return;
        if (lastOfferedMs >= 0 && arrivalMs - lastOfferedMs < 16) { rateSkipped++; return; }
        ByteBuffer copy = available.poll();
        if (copy == null) { droppedFrames++; return; }
        try {
            RecordingFrames.copyRgba(rgba,rowStride,pixelStride,width,height,copy);
            lastOfferedMs = arrivalMs;
            boolean posted = worker.post(() -> {
                try {
                    if (released) return;
                    long ptsUs = clock.nextUs(arrivalMs);
                    firstFrameMs = clock.firstMs();
                    drain(false);
                    renderer.draw(copy,ptsUs * 1000);
                    drain(false);
                    trace.add(arrivalMs,"video_frame","pts_us=" + ptsUs + ";source_image_ns=" + imageTimestampNs);
                } catch (Exception e) { fail("Errore registrazione: " + e.getMessage()); }
                finally { available.offer(copy); }
            });
            if (!posted) available.offer(copy);
        } catch (Exception e) {
            available.offer(copy);
            worker.post(() -> fail("Immagine video non leggibile: " + e.getMessage()));
        }
    }

    void finish(String reason) {
        if (!stopping.compareAndSet(false,true)) return;
        accepting = false; badge = "Salvataggio…"; stopReason = reason; main.removeCallbacks(limit);
        worker.post(() -> {
            if (released) return;
            try {
                if (codecStarted) { codec.signalEndOfInputStream(); drain(true); }
                if (!muxerStarted || encodedFrames == 0) throw new IllegalStateException("Nessun fotogramma registrato");
                muxer.stop(); muxerStarted = false;
                release();
                RecordingStore.publish(context,videoUri);
                Uri report = null; String message = "Video salvato in Galleria · BeatPilot";
                try { report = RecordingStore.report(context,name,reportJson()); }
                catch (Exception e) { message += " · resoconto non salvato: " + e.getMessage(); }
                RecordingStore.remember(context,videoUri,report,name);
                String result = message; main.post(() -> listener.finished(this,true,result));
            } catch (Exception e) { fail("Video non salvato: " + e.getMessage()); }
            finally { thread.quitSafely(); }
        });
    }

    private void drain(boolean endOfStream) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (true) {
            int index = codec.dequeueOutputBuffer(info,endOfStream ? 10_000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return;
                if (SystemClock.uptimeMillis() > deadline) throw new IllegalStateException("Encoder video non risponde alla chiusura");
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw new IllegalStateException("Formato video cambiato durante la prova");
                track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); muxerStarted = true;
            } else if (index >= 0) {
                try {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0) {
                        if (!muxerStarted) throw new IllegalStateException("Encoder senza formato video");
                        ByteBuffer output = codec.getOutputBuffer(index);
                        if (output == null) throw new IllegalStateException("Fotogramma codificato assente");
                        output.position(info.offset); output.limit(info.offset + info.size);
                        if (info.presentationTimeUs <= lastOutputUs) throw new IllegalStateException("Tempi video non crescenti");
                        muxer.writeSampleData(track,output,info); lastOutputUs = info.presentationTimeUs; encodedFrames++;
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                } finally { codec.releaseOutputBuffer(index,false); }
            }
        }
    }

    private String reportJson() throws Exception {
        JSONObject root = new JSONObject(); root.put("schema",1); root.put("app_version", "0.1.14");
        root.put("video_name",name + ".mp4"); root.put("device",Build.MANUFACTURER + " " + Build.MODEL);
        root.put("android_sdk",Build.VERSION.SDK_INT); root.put("width",width); root.put("height",height);
        root.put("codec",codecName); root.put("target_fps",FRAME_RATE); root.put("audio",false);
        root.put("video_zero_uptime_ms",firstFrameMs); root.put("frames_encoded",encodedFrames);
        root.put("video_frames_dropped_busy",droppedFrames); root.put("video_frames_rate_limited",rateSkipped);
        root.put("trace_events_dropped",trace.dropped()); root.put("stop_reason",stopReason);
        root.put("advance_ms",config.advanceMs); root.put("hit_line",config.line);
        root.put("lanes",new JSONArray(config.lanes)); root.put("video_profile",config.videoProfile);
        root.put("observe_only",config.observeOnly);
        root.put("timing_note","Event time is Android uptime minus the first recorded frame's arrival uptime."
                + " Gesture dispatch is a request timestamp, not measured touch delivery. Image source timestamp has producer-defined timebase."
                + " Video preserves arrival-time gaps; recording can affect device load. No score is inferred.");
        root.put("event_columns",new JSONArray(new String[]{"video_time_ms","type","data"}));
        JSONArray events = new JSONArray();
        for (TraceBuffer.Event event : trace.snapshot()) events.put(new JSONArray()
                .put(event.uptimeMs() - firstFrameMs).put(event.type()).put(event.data()));
        root.put("events",events); return root.toString();
    }

    private void fail(String message) {
        accepting = false; stopping.set(true); main.removeCallbacks(limit);
        if (released) {
            RecordingStore.discard(context,videoUri);
            main.post(() -> listener.finished(this,false,message)); return;
        }
        release(); RecordingStore.discard(context,videoUri); thread.quitSafely();
        main.post(() -> listener.finished(this,false,message));
    }
    private void release() {
        if (released) return; released = true;
        if (renderer != null) try { renderer.close(); } catch (Exception ignored) {}
        if (codec != null) {
            if (codecStarted) try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
        }
        if (surface != null) try { surface.release(); } catch (Exception ignored) {}
        if (muxer != null) {
            if (muxerStarted) try { muxer.stop(); } catch (Exception ignored) {}
            try { muxer.release(); } catch (Exception ignored) {}
        }
        if (file != null) try { file.close(); } catch (Exception ignored) {}
    }
}
