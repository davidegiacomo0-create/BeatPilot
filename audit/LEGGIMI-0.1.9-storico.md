# BeatPilot 0.1.9 — finali delle note lunghe

Aggiornamento per il Samsung Galaxy S26 Ultra / Android 16 / One UI 8.5 di Dave. Destinazione: `com.spaceapegames.beatstar`.

La nuova registrazione `BeatPilot_20260917_120645_572` arriva fino a circa **1:12**, poi il bot si interrompe e il gioco mostra **FAILED TO HOLD**. La fine di una nota lunga sinistra era stata vista solo due volte: il cappuccio era diventato molto luminoso, e il precedente controllo cercava una punta più scura. Senza rilascio, la nota lunga successiva nella stessa corsia veniva ignorata; una continuazione senza eventi provocava poi l'arresto.

La **0.1.9-holdtails** riconosce anche la punta luminosa, verificando la forma completa della nota. Recupera le code con pochi campioni e aggiorna le previsioni incoerenti; mantiene quelle già stabili. Evita inoltre di inviare ad Android una continuazione immobile senza un nuovo tocco, movimento o rilascio.

**Test e compilazione superati.** Nel replay completo le 447 previsioni/revisioni restano uguali; i test dedicati riproducono invece i pochi campioni del JSON e recuperano il rilascio. Il video compresso contiene piccoli cambiamenti di pixel: da solo non riproduce fedelmente il mancato rilascio sul telefono. Il dettaglio è in `VERIFICA.txt` e nei test.

Installa l'aggiornamento sopra la versione attuale e lascia **anticipo 0 ms**, corsie e linea attuali. Non serve premere nuovamente Applica profilo. Riprova lo stesso brano con registrazione interna, soprattutto oltre il punto 1:12. **Il risultato della nuova versione sul telefono va ancora verificato; non è certificato il 100% di Perfect+ su ogni variante.** La combinazione pressione + freccia finale resta da implementare.

## Aggiornare e registrare la prova

1. Installa il nuovo `BeatPilot-0.1.9-holdtails.apk` come aggiornamento. La versione interna è **0.1.9-holdtails**. È firmato con la stessa chiave; le impostazioni vengono conservate durante un normale aggiornamento.
2. Apri BeatPilot. Controlla che **Registra quando premi Avvia** sia attivo e **Osserva soltanto** sia disattivato.
3. Se il profilo e il controllo tocchi sono già configurati, puoi usarli subito. **Applica profilo del video** è necessario solo per configurarlo nuovamente: premendolo si azzera l’anticipo e si riattiva Osserva.
4. Avvia la condivisione dello **schermo intero**, apri Beatstar e scegli lo stesso brano con note lunghe della prova allegata. Lascia spento il registratore dello schermo Samsung.
5. Nella schermata iniziale del brano premi **Avvia** nel pannello di BeatPilot. Attendi la preparazione del video; comparirà **REC**. Il bot parte dopo che il registratore interno è pronto.
6. Lascia giocare il bot senza toccare le note. Arriva alla fine del brano oppure alla schermata di errore, poi premi **Stop**. Se il bot si è già messo in pausa, il pulsante diventa **Salva**: premilo.
7. Attendi il messaggio **Video salvato**. Premi **Video** nel pannello, poi **Condividi video e resoconto** nell’app. Se l’app di destinazione non accetta entrambi i file, usa **Condividi solo il video** e allega il resoconto separatamente.

Il video è nella Galleria, album **BeatPilot** (`Movies/BeatPilot`). Il resoconto JSON è in **Download/BeatPilot**. Puoi allegarli anche direttamente a questa conversazione dal telefono. La condivisione apre il selettore Android: BeatPilot non invia nulla automaticamente.

Ogni prova crea file nuovi. I pulsanti dell’app aprono o condividono l’ultima prova salvata. Durante il salvataggio, la condivisione resta bloccata per evitare di inviare per errore la prova precedente.

## Comandi

- **Avvia:** prepara il registratore, poi attiva il bot. Se la registrazione è disattivata nelle impostazioni, attiva soltanto il bot.
- **Stop:** interrompe i tocchi e salva il video della prova.
- **Salva:** chiude il video quando il bot è già in pausa.
- **Video:** salva la prova in corso e apre BeatPilot, dove si trovano i pulsanti di condivisione.
- **Calibra:** salva il video prima di aprire le regolazioni manuali.
- **Chiudi:** salva il video e termina la sessione di cattura.

La registrazione termina anche uscendo da Beatstar o interrompendo la condivisione. È limitata a **3 minuti per video**; il bot ha il limite separato di 10 minuti. Un arresto anomalo del processo o lo spegnimento del telefono possono impedire di completare il salvataggio.

## Cosa contiene la prova

**Video:** MP4 H.264, senza audio, larghezza 480 pixel e proporzioni dello schermo (nel video ricevuto: 480 × 1040), frequenza obiettivo 60 fps. Il filmato ricevuto contiene 4356 fotogrammi in circa 75,5 secondi, con frequenza variabile. Il JSON distingue i frame analizzati da quelli effettivamente codificati. La velocità effettiva della nuova versione va misurata nella prossima prova. Il video usa la cattura già impiegata dal bot, senza avviare una seconda sessione Android.

**Resoconto:** versione dell’app, modello, impostazioni di corsie/linea/anticipo, fotogrammi, riconoscimenti, istanti previsti, richieste di gesto, callback e motivi di arresto. Sono conservati il numero di note recuperate, il ritardo di accodamento e i cambi di frequenza del display. La 0.1.8 aggiunge la continuazione prevista di ciascun contatto e l’eventuale separazione del rilascio in millisecondi (`contact_state`). Le note lunghe generano eventi HOLD_START e HOLD_END; il contatore delle note non conta nuovamente il rilascio. Non legge password, account o memoria interna del gioco. Non calcola da solo i Perfect+.

Il tempo delle richieste di gesto non equivale al momento esatto in cui il gioco riceve il tocco. I dati vanno confrontati con il video. Il timestamp originale viene conservato; il bot lo usa per il tracciamento soltanto quando concorda con l’orologio monotono campionato. Altrimenti usa l’arrivo del fotogramma. Il JSON indica quale orologio ha usato e l’età dell’immagine. I timestamp di produttori diversi non sono universalmente confrontabili: [Image.getTimestamp](https://developer.android.com/reference/android/media/Image#getTimestamp()).

Il salvataggio video usa un encoder hardware su un’attività separata e una coda di soli tre fotogrammi. Se l’encoder resta indietro, vengono scartati fotogrammi del video; il riconoscitore non attende che vengano codificati. La copia dei pixel e la registrazione consumano comunque risorse: **l’effetto sui tempi reali deve essere misurato**, non è assunto nullo. Il resoconto conta i fotogrammi scartati e conserva gli intervalli temporali.

## Se qualcosa non funziona

- Se compare **Preparazione video** e poi un errore, invia lo screenshot del messaggio. Il bot non avvia il livello automaticamente quando la preparazione della registrazione fallisce.
- Se il bot si interrompe, attendi un momento per vedere il messaggio e premi **Salva**. Il video può continuare per mostrare la schermata di errore mentre Beatstar è ancora aperto.
- Se manca il video, controlla la schermata iniziale di BeatPilot: indica se il salvataggio è ancora in corso oppure il motivo dell’errore.
- Se hai cancellato l’ultimo video dalla Galleria, i pulsanti dell’app non possono recuperarlo: esegui una nuova prova.
- Se il registratore Samsung viene acceso, può interrompere la cattura del bot. Usa soltanto la registrazione interna di BeatPilot per questa prova. [Ciclo di vita MediaProjection](https://developer.android.com/media/grow/media-projection#resource_recovery).
- Se l’accessibilità è bloccata, Android prevede il menu **Impostazioni → App → BeatPilot → Altro → Consenti impostazioni con limitazioni**, poi l’attivazione del servizio. Le etichette Samsung possono variare. [Guida Android](https://support.google.com/android/answer/12623953?hl=it).

## Riconoscimento e limiti

Il profilo usa le registrazioni dell’utente del 15–17 settembre 2026: tre corsie con centri sulla linea al 20,5%, 50% e 79,5% della larghezza; linea al 72,1% dell’altezza e correzione della prospettiva.

Il profilo riconosce tocchi, frecce sinistra/destra/alto/basso, note circolari di cambio stage, coppia iniziale e note lunghe dritte con barra, stelo e cappuccio finale del tipo osservato nel nuovo video. **Le combinazioni pressione + freccia finale non sono implementate**: non sono presenti in questa prova. Altre forme di nota lunga richiederanno esempi reali. Un rilascio non riconosciuto può ancora far fallire il brano; Stop, cambio schermata e perdita della cattura interrompono i contatti.

Il programma legge i pixel e invia gesti di accessibilità. Non legge la mappa del brano o il punteggio dal gioco. L’anticipo può essere regolato dopo aver confrontato i risultati; le variazioni di latenza non sono eliminate da un valore fisso.

## Verifiche e sorgenti

`VERIFICA.txt` distingue test eseguiti, riscontro dell’utente e prove ancora necessarie.

Con Java 17 e Python 3.10 o superiore:

```sh
python3 tools/check.py
```

Esegue 6284 controlli: i 6016 precedenti più 268 verifiche sulle code luminose, sui campioni sparsi del JSON e sulla continuità dei contatti. I casi difettosi precedenti restano nelle regressioni. Sono aggiunti 165 frame del nuovo video. Sono conservati i 41 fotogrammi delle note lunghe con tempi originali, i 117 campioni reali precedenti e i casi sintetici di pressione. Non esegue Android.

Su Linux con Mesa EGL, verifica aggiuntiva degli shader video e dell’orientamento:

```sh
python3 tools/check_gl.py
```

Questo controllo esegue realmente gli shader del registratore su un’immagine di prova. Non verifica MediaCodec o MediaStore su Android.

Il replay del video originale richiede ffmpeg/ffprobe e NumPy:

```sh
python3 tools/replay_video.py /percorso/registrazione.mp4 /percorso/risultati
```

Il replay con i tempi del resoconto si esegue così:

```sh
python3 tools/replay_video.py prova.mp4 risultati --timing-report prova.json
```

`tests/fixtures/holds/bright-caps` contiene i frame della nuova sequenza e la descrizione della regressione. `tests/replay-019` conserva i replay completi con base 0.1.8 e finale 0.1.9, insieme al confronto. I replay delle altre cartelle sono evidenza storica e non sono stati nuovamente eseguiti in questo aggiornamento. Il replay non invia tocchi e non simula un nuovo punteggio; le differenze fra pixel originali e video compresso sono documentate. Video e resoconti integrali non sono inclusi nell’archivio, e questi materiali personali non sono stati pubblicati.

## Ricompilare l’APK

Per installare l’APK già pronto non serve un computer. Per ricompilarlo servono Java 17, Python e Android SDK con **Platform 35** e **Build-Tools 35.0.0**.

```sh
python3 tools/build_apk.py
```

Lo script usa AAPT2, javac, D8, zipalign e apksigner e produce `BeatPilot-0.1.9-holdtails.apk`. Accetta `JAVA_HOME` e `ANDROID_HOME`. La chiave di sviluppo in `signing` consente aggiornamenti compatibili: conservala privata. I file Gradle sono inclusi, ma la pipeline verificata è quella dello script Python.

L’app non dichiara il permesso Internet, microfono o accesso generale ai file. Video e resoconto vengono creati tramite MediaStore e sono condivisi soltanto tramite un’azione dell’utente. Il programma non è ufficiale Space Ape.

Riferimenti: [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec), [salvataggio dei file multimediali](https://developer.android.com/training/data-storage/shared/media), [gesti di accessibilità](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService).
