# Regressione dei contatti — 17 settembre 2026

Fonte: `BeatPilot_20260917_113021_084.json` e relativo MP4, BeatPilot 0.1.7-holds,
Samsung SM-S948B/API 36. Il resoconto originale non è incluso; hash e impostazioni
sono in `source.json`. Le corsie del CSV sono numerate da zero.

`hits.csv` conserva 158 previsioni/revisioni con istante di accettazione e scadenza.
`batches.csv` conserva le 33 chiamate registrate al pianificatore e le callback.
`segments.csv` conserva i tratti inviati, nell'ordine originale. La vecchia traccia
non riportava `willContinue`: qui è dedotto dalla continuazione dello stesso ID
nel gesto immediatamente successivo. La 0.1.8 lo registra esplicitamente.

Riproduzione dell'estrazione:

```sh
python3 tools/extract_contact_trace.py /percorso/BeatPilot_20260917_113021_084.json tests/fixtures/contacts
python3 tools/check.py
```

## Caso osservato

Nel video la piastrella centrale passa la linea senza reagire, poi diventa rossa.
La visione l'ha riconosciuta come TAP 33. Batch 30, pianificato a 16391 ms:

| Contatto | Inizio relativo al gesto | Durata 0.1.7 | Durata 0.1.8 |
| --- | ---: | ---: | ---: |
| Continuazione HOLD 30, sinistra | 0 ms | 10 ms | 11 ms |
| Nuovo TAP 33, centro | 10 ms | 8 ms | 8 ms |

Il TAP resta quindi a 16401 ms; il rilascio precedente passa da 16401 a 16402 ms.
Tutti gli altri inizi, percorsi e rilasci delle 33 chiamate restano uguali nella
riproduzione delle chiamate originali. Le callback registrate attestano il
completamento del gesto Android, non l'accettazione del tocco da parte del gioco.

## Modello verificato

`ContactTests` modella il campionamento inclusivo dei tratti, i contatti conservati,
UP prima di DOWN, l'indice usato per DOWN e il primo campione di continuazione.
La logica è stata confrontata con queste fonti primarie AOSP, consultate il
17 settembre 2026:

- [GestureDescription: punti e campionamento](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/accessibilityservice/GestureDescription.java).
- [MotionEventInjector: continuazioni e generazione degli eventi](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/MotionEventInjector.java).

Nel campione condiviso la vecchia pressione viene rimossa prima del nuovo DOWN;
l'indice di quest'ultimo resta 1, pur essendoci un solo puntatore. Il controllo
negativo riproduce esattamente questo errore. Il nuovo pianificatore supera il
controllo mantenendo la scadenza della nota. Sono verificati campionamenti da
8 e 16 ms; i punti di inizio/fine restano campionati anche a distanza di 1 ms.

Lo stesso modello controlla note durante due pressioni, rilascio simultaneo di
due dita, tutte le quattro frecce, TAP, un altro HOLD_START, confini a -2..+2 ms,
rilascio e nota nella stessa corsia, e pianificazione tardiva di 0/5/17 ms.
Una nuova pressione tardiva usa offset 1 ms quando esiste una continuazione:
il primo campione di quest'ultima deve contenere soltanto le dita precedenti.

Questo è un modello dei contratti descritti dal sorgente AOSP, non un'esecuzione
del framework Samsung. Non riproduce l'intera pipeline input, il clock reale,
Unity, l'allocazione completa degli ID del framework o la valutazione Perfect+.
Il comportamento del nuovo APK sul telefono deve ancora essere osservato.
