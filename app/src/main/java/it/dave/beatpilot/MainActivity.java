package it.dave.beatpilot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.List;
import it.dave.beatpilot.core.NotePattern;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private TextView status;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (status != null) {
                int count = 0; String error = "";
                try { count = SettingsStore.patterns(MainActivity.this).size(); } catch (Exception e) { error = " · Campioni non validi"; }
                SettingsStore cfg = SettingsStore.load(MainActivity.this);
                status.setText("Accessibilità: " + (TouchService.instance != null ? "attiva" : "da attivare")
                        + (cfg.videoProfile ? "\nProfilo video · " + (cfg.calibrated ? "configurato" : "da applicare")
                        : "\nCampioni manuali: " + count + error) + "\n" + CaptureService.status
                        + "\n" + CaptureService.recordingStatus
                        + (RecordingStore.latestName(MainActivity.this).isEmpty() ? "" : "\nUltima prova: " + RecordingStore.latestName(MainActivity.this)));
            }
            handler.postDelayed(this, 700);
        }
    };
    @Override public void onCreate(Bundle state) { super.onCreate(state); }
    @Override public void onResume() { super.onResume(); render(); handler.post(refresh); }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private void render() {
        SettingsStore cfg = SettingsStore.load(this);
        ScrollView scroll = new ScrollView(this); LinearLayout body = Ui.column(this); scroll.addView(body);
        setContentView(scroll); Ui.insets(scroll);
        Ui.text(body, "BeatPilot", 30);
        Ui.text(body, "BeatPilot 0.1.16 · Frecce finali note lunghe", 16);
        Ui.text(body, "Profilo per tocchi, frecce e note di cambio stage. Tiene conto della prospettiva delle corsie. "
                + "Tocchi, frecce e note lunghe dritte. Supporta anche le pressioni lunghe con freccia finale.", 15);
        status = Ui.text(body, "", 14);
        Ui.button(body, "1 · Attiva controllo tocchi", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        Ui.button(body, "2 · Applica profilo del video", () -> {
            try {
                stopIfRunning(); SettingsStore.applyVideoProfile(this); render();
                Ui.toast(this, "Profilo applicato. La prima prova è in modalità Osserva.");
            } catch (Exception e) { Ui.toast(this, e.getMessage()); }
        });
        Ui.button(body, "3 · Avvia condivisione schermo", this::requestCapture);
        Ui.button(body, "Apri Beatstar", this::openGame);
        Ui.text(body,"Video della prova",20);
        Switch record = new Switch(this); record.setText("Registra quando premi Avvia"); record.setChecked(cfg.recordVideo);
        body.addView(record);
        record.setOnCheckedChangeListener((v, checked) -> {
            stopIfRunning(); SettingsStore s = SettingsStore.load(this); s.recordVideo = checked; s.save(this);
        });
        Ui.text(body,"Avvia prepara la registrazione e fa partire il bot. Il pannello mostra REC. "
                + "Premi Stop per salvare il video; se il bot si è già fermato, premi Salva. "
                + "Non aprire il registratore Samsung. La prova dura al massimo 3 minuti ed è senza audio.",14);
        Ui.button(body,"Apri ultimo video",() -> RecordingStore.openLast(this));
        Ui.button(body,"Condividi video e resoconto",() -> RecordingStore.shareLast(this,true));
        Ui.button(body,"Condividi solo il video",() -> RecordingStore.shareLast(this,false));
        Ui.button(body, "Regola corsie e linea manualmente", () -> {
            if (TouchService.instance == null) { Ui.toast(this, "Attiva prima BeatPilot nelle impostazioni di accessibilità."); return; }
            TouchService.instance.showPanel();
            openGame();
            Ui.toast(this, "Nel pannello tocca Calibra e allinea le tre corsie e la linea di arrivo.");
        });
        Ui.text(body, "Prima prova", 20);
        Switch observe = new Switch(this); observe.setText("Osserva soltanto: nessun tocco"); observe.setChecked(cfg.observeOnly);
        body.addView(observe);
        observe.setOnCheckedChangeListener((v, checked) -> {
            stopIfRunning(); SettingsStore s = SettingsStore.load(this); s.observeOnly = checked; s.save(this);
        });
        Ui.text(body, "Con Osserva attivo, il pannello conta le note previste. "
                + "Gioca normalmente per questa verifica; disattivalo per far toccare il bot.", 14);
        slider(body, "Anticipo", -150, 150, (int)cfg.advanceMs, " ms", value -> {
            SettingsStore s = SettingsStore.load(this); s.advanceMs = value; s.save(this);
        });
        Ui.text(body, "Valori positivi anticipano il tocco; negativi lo ritardano. Cambia di 5 ms per volta in base ai risultati.", 14);
        Ui.text(body, "Impostazioni avanzate", 20);
        Switch videoMode = new Switch(this); videoMode.setText("Usa il profilo del video"); videoMode.setChecked(cfg.videoProfile);
        body.addView(videoMode);
        videoMode.setOnCheckedChangeListener((v, checked) -> {
            stopIfRunning(); SettingsStore s = SettingsStore.load(this); s.videoProfile = checked; s.save(this);
        });
        Ui.button(body, "Aggiungi campioni manuali", () -> startActivity(new Intent(this, LearnActivity.class)));
        Ui.button(body, "Gestisci campioni manuali", this::managePatterns);
        slider(body, "Somiglianza dei campioni manuali", 80, 98, (int)Math.round(cfg.threshold * 100), "%", value -> {
            SettingsStore s = SettingsStore.load(this); s.threshold = value / 100.0; s.save(this);
        });
        Ui.button(body, "Termina sessione", () -> {
            stopIfRunning();
            if (CaptureService.instance != null) CaptureService.instance.requestClose("Sessione chiusa");
            if (TouchService.instance != null) TouchService.instance.hidePanel();
        });
        Ui.text(body, "Uso: condividi lo schermo intero, apri un brano, premi Avvia nel pannello. "
                + "Con i tocchi attivi, il profilo avvia anche le due caselle iniziali mostrate nel video. "
                + "Stop sospende i tocchi e salva la registrazione. Video apre questa pagina. Chiudi termina anche la cattura. "
                + "Esci dal gioco per interrompere il bot e salvare il video. "
                + "Il prototipo interrompe una sessione attiva dopo 10 minuti.", 15);
        Ui.text(body, "L’app non richiede accesso a Internet. Con Registra attivo, il video viene salvato in Galleria, album BeatPilot, "
                + "e il resoconto dei tempi in Download/BeatPilot. Scegli tu quando condividerli. "
                + "L’obiettivo è Perfect+, ma il prototipo non garantisce un risultato perfetto.", 14);
    }
    private interface IntChange { void changed(int value); }
    private void slider(LinearLayout parent, String name, int low, int high, int initial, String unit, IntChange save) {
        TextView label = Ui.text(parent, name + ": " + initial + unit, 16);
        SeekBar bar = new SeekBar(this); bar.setMax(high - low); bar.setProgress(initial - low); parent.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar v, int n, boolean user) { label.setText(name + ": " + (n + low) + unit); }
            public void onStartTrackingTouch(SeekBar v) { stopIfRunning(); }
            public void onStopTrackingTouch(SeekBar v) { save.changed(v.getProgress() + low); }
        });
    }
    private void stopIfRunning() {
        if (TouchService.instance != null) TouchService.instance.disarm("In pausa");
        if (CaptureService.instance != null) CaptureService.instance.finishRecording("Impostazioni modificate");
    }
    private void openGame() {
        Intent game = getPackageManager().getLaunchIntentForPackage(SettingsStore.TARGET);
        if (game == null) { Ui.toast(this, "Beatstar non risulta avviabile. Aprilo manualmente."); return; }
        try { startActivity(game); } catch (Exception e) { Ui.toast(this, "Apri Beatstar manualmente."); }
    }
    private void requestCapture() {
        if (TouchService.instance == null) { Ui.toast(this, "Attiva prima il controllo tocchi."); return; }
        if (CaptureService.instance != null) {
            if (!CaptureService.instance.ready) { Ui.toast(this,"Attendi la chiusura della sessione e riprova."); return; }
            TouchService.instance.showPanel(); Ui.toast(this, "Sessione già aperta. Apri Beatstar e usa Avvia."); return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 13);
        else launchCaptureConsent();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 13) launchCaptureConsent();
    }
    private void launchCaptureConsent() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()), 12);
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 12 || result != RESULT_OK || data == null) return;
        Intent start = new Intent(this, CaptureService.class).putExtra("result", result).putExtra("consent", data);
        startForegroundService(start);
        Ui.toast(this, "Sessione aperta. Ora apri Beatstar; poi premi Avvia nel pannello.");
    }
    private void managePatterns() {
        try {
            List<NotePattern> patterns = SettingsStore.patterns(this);
            if (patterns.isEmpty()) { Ui.toast(this, "Nessun campione salvato."); return; }
            String[] labels = patterns.stream().map(p -> p.kind.label + " · " + p.id).toArray(String[]::new);
            new AlertDialog.Builder(this).setTitle("Tocca un campione per eliminarlo")
                    .setItems(labels, (dialog, which) -> new AlertDialog.Builder(this)
                            .setMessage("Eliminare questo campione?").setNegativeButton("Annulla", null)
                            .setPositiveButton("Elimina", (d, w) -> {
                                try { stopIfRunning(); patterns.remove(which); SettingsStore.savePatterns(this, patterns); }
                                catch (Exception e) { Ui.toast(this, "Impossibile salvare: " + e.getMessage()); }
                            }).show()).setNegativeButton("Chiudi", null).show();
        } catch (Exception e) { Ui.toast(this, "Campioni non leggibili: " + e.getMessage()); }
    }
}
