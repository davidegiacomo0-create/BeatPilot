# Fotogrammi di regressione

Quindici immagini reali ricavate da `Screen_Recording_20260915_214049_Beatstar.mp4`, fornito dall’utente il 15 settembre 2026. Il filmato originale non è incluso.

Formato: 480 × 1040, un byte senza segno per pixel, righe dall’alto verso il basso, nessuna intestazione. Conversione luminanza: `(77*R + 150*G + 29*B) >> 8`, uguale all’app. Il nome indica il tempo di estrazione richiesto in millisecondi; il fotogramma effettivo è quello vicino a tale istante.

Le etichette sono state controllate visivamente: selezione brano, caselle iniziali ferme, tocchi, frecce sinistra/destra/alto, note circolari di cambio stage, punti dell’indicatore dei tocchi, errore e conto alla rovescia finale. I test non trattano il numero del conto alla rovescia come una nota.

`016412.gray` riproduce un caso in cui l’indicatore della posizione del dito oscura un lato dello sfondo durante il gioco. Il controllo dello stato deve continuare a riconoscere la partita.

L’archivio è destinato al progetto personale dell’utente. Nessun punteggio di gioco viene dedotto da queste immagini. Il replay delle note già colpite manualmente non misura l’efficacia del bot su un brano completo.

## Casi aggiunti dalla prova del bot del 16 settembre

`BeatPilot_20260916_120924_868.mp4` e il JSON omonimo provengono dall’utente. I quattro nuovi fotogrammi sono estratti senza ridimensionare dal video a 480 × 1040 e convertiti con la stessa formula dell’app:

- `bot_start_grey.gray`: circa 4,400 s, caselle iniziali grigie, coppia esterna ancora presente.
- `bot_start_pulse.gray`: circa 6,000 s, stessa schermata durante il lampeggio.
- `bot_left_glow.gray`: circa 71,070 s, freccia LEFT centrale vicina alla linea e TAP nella corsia destra. Il bagliore apre un foro di otto pixel nelle sonde del contorno.
- `bot_left_past_line.gray`: circa 71,140 s, stessa freccia oltre la linea; serve a verificare il riconoscimento, non ad autorizzare un tocco tardivo.

`bot_last_arrow.csv` riporta soltanto le osservazioni della freccia centrale dagli eventi reali del JSON, con tempo del fotogramma e fine analisi rispetto allo zero del video. Il vecchio tracciatore non aveva generato il gesto. Il test verifica che la previsione venga creata in anticipo anche mantenendo i tempi di analisi misurati. Non è una prova del punteggio ricevuto dal gioco.

## Passaggio rapido della prova 12:56:55

`rapid/sequence.csv` elenca tutti i 32 fotogrammi consecutivi presenti nel video `BeatPilot_20260916_125655_817.mp4` tra 84,717 e 85,249 s. Non sono interpolati o rallentati. Le immagini sono estratte per indice di fotogramma, conservando la cadenza reale del video; i tempi di cattura e fine analisi sono gli esatti valori degli eventi `vision` nel JSON dell’utente.

Le due note sinistre hanno un piccolo spazio luminoso fra i loro bordi: il filtro precedente chiudeva anche questo spazio, univa le sagome e non pianificava la prima nota. Le etichette di regressione sono tre TAP, sinistra–centro–sinistra. Gli allineamenti visibili alla linea sono approssimativamente a 84971, 85083 e 85196 ms; la tolleranza del test (8 ms) non è una dichiarazione sulla finestra Perfect+ del gioco.

`RapidNotesTests` verifica la separazione dei riconoscimenti, tre previsioni distinte pronte in anticipo e tre gesti separati. Il test usa la cadenza originale: un primo esperimento diradato artificialmente a circa 20 fps perdeva una previsione. Il risultato sui frame originali non dimostra quindi robustezza a forti cali di frequenza.

## Due brani e sfondi scuri della prova 21:12:35

`themes/sequence.csv` raccoglie 46 fotogrammi estratti per indice da `BeatPilot_20260916_211235_599.mp4`, con i tempi effettivi di cattura e fine analisi del JSON. Sono 18 frame consecutivi del passaggio UP in Du hast (20,858–21,157 s), 20 del doppio TAP in No One Knows (65,907–66,240 s) e otto immagini di contesto (avvio, freccia DOWN, errore, menu e transizioni).

Il primo passaggio conserva la freccia UP centrale, con allineamento intorno a 21101 ms, e mostra una vera freccia DOWN sinistra in arrivo. Il secondo conserva due barre allineate, corsie centrale/destra, intorno a 66157 ms. In entrambi lo sfondo viola la vecchia condizione di luminosità, mentre punteggio e pausa rimangono visibili. La tolleranza di 8 ms dei test riguarda gli allineamenti visibili, non la finestra Perfect+.

L’immagine `exit` contiene ancora i comandi del gioco durante un’animazione successiva all’errore. Non basta quindi un singolo fotogramma per riattivare il riconoscimento. `ThemeRegressionTests` verifica anche l’attesa al rientro, una nuova coppia iniziale, interruzioni brevi e intervalli senza immagini. Il replay integrale contiene la sequenza completa delle transizioni.
