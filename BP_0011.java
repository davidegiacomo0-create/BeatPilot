package it.dave.beatpilot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Publishes app-owned files through MediaStore, without broad storage permissions. */
final class RecordingStore {
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("recordings", Context.MODE_PRIVATE); }
    static Uri newVideo(Context c, String name) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name + ".mp4");
        values.put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_MOVIES + "/BeatPilot");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri = c.getContentResolver().insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values);
        if (uri == null) throw new IOException("Impossibile creare il video");
        return uri;
    }
    static Uri report(Context c, String name, String json) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name + ".json");
        values.put(MediaStore.MediaColumns.MIME_TYPE,"application/json");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS + "/BeatPilot");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri = c.getContentResolver().insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values);
        if (uri == null) throw new IOException("Impossibile creare il resoconto");
        try {
            try (OutputStream out = c.getContentResolver().openOutputStream(uri,"w")) {
                if (out == null) throw new IOException("Resoconto non scrivibile");
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            publish(c,uri); return uri;
        } catch (IOException | RuntimeException e) { discard(c,uri); throw e; }
    }
    static void publish(Context c, Uri uri) throws IOException {
        ContentValues ready = new ContentValues(); ready.put(MediaStore.MediaColumns.IS_PENDING,0);
        if (c.getContentResolver().update(uri,ready,null,null) != 1) throw new IOException("Pubblicazione del file non riuscita");
    }
    static void discard(Context c, Uri uri) {
        if (uri != null) try { c.getContentResolver().delete(uri,null,null); } catch (RuntimeException ignored) {}
    }
    static void remember(Context c, Uri video, Uri report, String name) {
        prefs(c).edit().putString("video",video.toString()).putString("report",report == null ? "" : report.toString())
                .putString("name",name).apply();
    }
    static String latestName(Context c) { return prefs(c).getString("name",""); }
    private static Uri readable(Context c, String key) {
        String value = prefs(c).getString(key,""); if (value.isEmpty()) return null;
        Uri uri = Uri.parse(value);
        try (ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(uri,"r")) {
            return pfd == null ? null : uri;
        } catch (Exception e) { return null; }
    }
    static void openLast(Activity activity) {
        if (!readyToRead(activity)) return;
        Uri uri = readable(activity,"video");
        if (uri == null) { Ui.toast(activity,"Nessun video disponibile. Esegui una prova e premi Stop."); return; }
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
        catch (Exception e) { Ui.toast(activity,"Apri il video nella Galleria, album BeatPilot."); }
    }
    static void shareLast(Activity activity, boolean withReport) {
        if (!readyToRead(activity)) return;
        Uri video = readable(activity,"video"), report = withReport ? readable(activity,"report") : null;
        if (video == null) { Ui.toast(activity,"Nessun video disponibile. Attendi il salvataggio dopo Stop."); return; }
        Intent send;
        ClipData clip = ClipData.newUri(activity.getContentResolver(),"Prova BeatPilot",video);
        if (report != null) {
            ArrayList<Uri> files = new ArrayList<>(); files.add(video); files.add(report);
            clip.addItem(new ClipData.Item(report));
            send = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM,files);
        } else send = new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM,video);
        send.setClipData(clip); send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.putExtra(Intent.EXTRA_SUBJECT,"Prova BeatPilot");
        try { activity.startActivity(Intent.createChooser(send,withReport ? "Condividi video e resoconto" : "Condividi video")); }
        catch (Exception e) { Ui.toast(activity,"Condividi il video dalla Galleria, album BeatPilot."); }
    }
    private static boolean readyToRead(Activity activity) {
        CaptureService capture = CaptureService.instance;
        if (capture != null && capture.hasRecording()) {
            Ui.toast(activity,"Attendi il salvataggio della prova in corso, poi riprova."); return false;
        }
        return true;
    }
}
