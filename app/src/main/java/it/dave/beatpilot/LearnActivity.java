package it.dave.beatpilot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.InputStream;
import java.util.List;
import it.dave.beatpilot.core.GrayFrame;
import it.dave.beatpilot.core.Kind;
import it.dave.beatpilot.core.NotePattern;

public final class LearnActivity extends Activity {
    private Bitmap bitmap;
    private CropView crop;
    private Spinner kind;
    private TextView instructions;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (TouchService.instance != null) { TouchService.instance.disarm("Apprendimento note"); TouchService.instance.hidePanel(); }
        LinearLayout body = Ui.column(this); setContentView(body); Ui.insets(body);
        Ui.text(body, "Impara una nota", 23);
        instructions = Ui.text(body, "Apri uno screenshot intero, non ritagliato, di una partita sul tuo telefono.", 14);
        kind = new Spinner(this);
        String[] kinds = java.util.Arrays.stream(Kind.values()).map(k -> k.label).toArray(String[]::new);
        kind.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, kinds)); body.addView(kind);
        Ui.button(body, "Apri screenshot", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, 22);
        });
        crop = new CropView(); body.addView(crop, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout buttons = new LinearLayout(this); body.addView(buttons);
        android.widget.Button reset = new android.widget.Button(this); reset.setText("Nuovo ritaglio"); reset.setAllCaps(false);
        buttons.addView(reset, new LinearLayout.LayoutParams(0, -2, 1));
        reset.setOnClickListener(v -> { crop.selected = false; crop.selection.setEmpty(); crop.invalidate(); instructions.setText("Trascina un rettangolo attorno a una sola nota."); });
        android.widget.Button save = new android.widget.Button(this); save.setText("Salva campione"); save.setAllCaps(false);
        buttons.addView(save, new LinearLayout.LayoutParams(0, -2, 1)); save.setOnClickListener(v -> save());
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 22 || result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(data.getData())) { BitmapFactory.decodeStream(in, null, bounds); }
            if (bounds.outWidth <= 0 || bounds.outHeight <= bounds.outWidth) throw new IllegalArgumentException("Usa uno screenshot verticale completo");
            double currentAspect = getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds().width()
                    / (double)getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds().height();
            if (Math.abs(bounds.outWidth / (double)bounds.outHeight - currentAspect) > .015)
                throw new IllegalArgumentException("Le proporzioni non corrispondono allo schermo. Usa uno screenshot completo del telefono");
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / opts.inSampleSize > 1440) opts.inSampleSize *= 2;
            Bitmap decoded;
            try (InputStream in = getContentResolver().openInputStream(data.getData())) { decoded = BitmapFactory.decodeStream(in, null, opts); }
            if (decoded == null) throw new IllegalArgumentException("Immagine non leggibile");
            Bitmap scaled = Bitmap.createScaledBitmap(decoded, 720, Math.round(720 * decoded.getHeight() / (float)decoded.getWidth()), true);
            if (scaled != decoded) decoded.recycle();
            if (bitmap != null) bitmap.recycle(); bitmap = scaled;
            crop.selected = false; crop.selection.setEmpty(); crop.invalidate();
            instructions.setText("Trascina un rettangolo su UNA nota vicino alla linea. Includi bordi e simbolo, senza altre note. Poi tocca nel ritaglio il punto che deve arrivare sulla linea.");
        } catch (Exception e) { Ui.toast(this, e.getMessage()); }
    }
    private void save() {
        if (bitmap == null || !crop.selected || crop.selection.width() < 10 || crop.selection.height() < 14) {
            Ui.toast(this, "Apri uno screenshot e seleziona una nota."); return;
        }
        try {
            int w = bitmap.getWidth(), h = bitmap.getHeight();
            int[] pixels = new int[w * h]; bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            byte[] gray = new byte[w * h];
            for (int i = 0; i < pixels.length; i++) gray[i] = (byte)((77 * Color.red(pixels[i]) + 150 * Color.green(pixels[i]) + 29 * Color.blue(pixels[i])) >> 8);
            RectF r = crop.selection;
            NotePattern pattern = NotePattern.crop(Long.toString(System.currentTimeMillis(), 36), Kind.values()[kind.getSelectedItemPosition()],
                    new GrayFrame(w, h, gray), (int)r.left, (int)r.top, (int)r.width(), (int)r.height(), crop.anchorX, crop.anchorY);
            List<NotePattern> patterns = SettingsStore.patterns(this);
            if (patterns.size() >= 24) throw new IllegalArgumentException("Massimo 24 campioni. Elimina quelli inutilizzati per mantenere veloce l’analisi");
            patterns.add(pattern); SettingsStore.savePatterns(this, patterns);
            Ui.toast(this, "Campione salvato: " + pattern.kind.label); finish();
        } catch (Exception e) { Ui.toast(this, "Campione non salvato: " + e.getMessage()); }
    }
    @Override public void onDestroy() { if (bitmap != null) bitmap.recycle(); super.onDestroy(); }

    private final class CropView extends View {
        final RectF selection = new RectF();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean selected, dragging;
        float startX, startY;
        double anchorX = .5, anchorY = .5;
        CropView() { super(LearnActivity.this); setBackgroundColor(0xFF142922); }
        float scale() { return bitmap == null ? 1 : Math.min(getWidth() / (float)bitmap.getWidth(), getHeight() / (float)bitmap.getHeight()); }
        float left() { return bitmap == null ? 0 : (getWidth() - bitmap.getWidth() * scale()) / 2; }
        float top() { return bitmap == null ? 0 : (getHeight() - bitmap.getHeight() * scale()) / 2; }
        @Override protected void onDraw(Canvas canvas) {
            if (bitmap == null) return;
            float s = scale(), x = left(), y = top();
            canvas.drawBitmap(bitmap, null, new RectF(x, y, x + bitmap.getWidth() * s, y + bitmap.getHeight() * s), paint);
            if (!selection.isEmpty()) {
                paint.setColor(0xFF40FFD0); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Ui.dp(LearnActivity.this, 2));
                canvas.drawRect(x + selection.left * s, y + selection.top * s, x + selection.right * s, y + selection.bottom * s, paint);
                paint.setStyle(Paint.Style.FILL); paint.setColor(0xFFFFCC55);
                canvas.drawCircle(x + (float)(selection.left + anchorX * selection.width()) * s,
                        y + (float)(selection.top + anchorY * selection.height()) * s, Ui.dp(LearnActivity.this, 4), paint);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (bitmap == null) return true;
            float x = Math.max(0, Math.min(bitmap.getWidth() - 1, (event.getX() - left()) / scale()));
            float y = Math.max(0, Math.min(bitmap.getHeight() - 1, (event.getY() - top()) / scale()));
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (selected) {
                    if (selection.contains(x, y)) {
                        anchorX = (x - selection.left) / selection.width(); anchorY = (y - selection.top) / selection.height(); invalidate();
                    }
                    return true;
                }
                startX = x; startY = y; dragging = true;
            }
            if (dragging && (event.getActionMasked() == MotionEvent.ACTION_MOVE || event.getActionMasked() == MotionEvent.ACTION_UP)) {
                selection.set(Math.min(startX, x), Math.min(startY, y), Math.max(startX, x), Math.max(startY, y));
                anchorX = .5; anchorY = .5;
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    dragging = false; selected = selection.width() >= 10 && selection.height() >= 14;
                    if (selected) instructions.setText("Ora tocca il punto della nota che deve allinearsi alla linea. Il punto giallo sarà usato per il tempo del tocco. Poi salva.");
                }
                invalidate();
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) dragging = false;
            return true;
        }
    }
}
