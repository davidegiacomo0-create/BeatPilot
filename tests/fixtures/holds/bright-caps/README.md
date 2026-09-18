# Coda luminosa e due note lunghe consecutive

Fonte: `BeatPilot_20260917_120645_572.mp4` e JSON, versione 0.1.8-contacts.
`source.json` conserva l'hash del resoconto. `sequence.csv` collega 165 frame
consecutivi, da 69,501 a 72,284 secondi, ai tempi originali di cattura/analisi.
I pixel sono ricavati dall'MP4, con conversione 77R + 150G + 29B diviso 256.
I file originali integrali non sono inclusi nell'archivio.

## Osservazione nel resoconto e nel video

- HOLD 309 sinistra: inizio 71039 ms, coda vista soltanto a 70899 e 70916 ms.
  Nessun HOLD_END programmato per questa nota: il tracciatore richiede tre campioni.
- TAP 311 destra: 71518 ms, eseguito mentre il dito sinistro resta premuto.
- HOLD 312 sinistra: prevista a 71759 ms, scartata perché la corsia risulta occupata.
- Batch 277 a 71749 ms: solo continuazione della HOLD 309, offset 0, durata 1,
  willContinue=true, coordinate invariate. Cancellazione e arresto a 71750 ms.
- Il video mostra poi FAILED TO HOLD. Non mostra l'esecuzione della versione nuova.

La punta della prima coda è più larga dello stelo ma diventa bianca quanto esso.
Il vecchio riconoscitore cercava soltanto il cappuccio più scuro sopra lo stelo.
Il nuovo controllo riconosce anche la spalla luminosa, richiedendo la base a T
completa, lo stelo più stretto e uno spazio libero sopra. Questo evita di ampliare
il riconoscimento a scritte dei menu o frecce.

## Test e distinzione fra video compresso e dati dal vivo

`HoldTailTests` prima della modifica fallisce sul cappuccio visibile del frame
`071000.gray`. Dopo la modifica verifica la forma, 18 combinazioni di corsia e
luminosità, due pressioni consecutive con callback 0/5/17 ms, il rilascio fra
le due e l'assenza di una continuazione vuota da inviare ad Android.

La simulazione dei gesti inizia dopo 70,4 s, quando la precedente pressione
risulta già rilasciata nella traccia reale (70317 ms). Non viene inventato uno
stato iniziale dai pixel incompleti della nota precedente.

**L'MP4 non è identico ai pixel originali analizzati sul telefono.** Per la coda
mancata, il vecchio riconoscitore trova nel video compresso un terzo campione
che manca nel JSON dal vivo. Un semplice replay dell'MP4 nasconde quindi il
problema. Il test combina separatamente i due campioni reali del JSON con i
successivi campioni luminosi recuperati dai frame: produce un solo rilascio,
aggiornato a 71507 ms. Questa combinazione è una regressione del tracciamento,
non una ricostruzione perfetta del framebuffer originale.

Viene verificata anche la coda seguente: nel JSON i suoi primi intervalli sono
incoerenti e portano a una previsione troppo tarda. Le osservazioni luminose
la aggiornano a 72225 ms mantenendo lo stesso ID. Se invece esiste già una
previsione coerente, il recupero non ne modifica i tempi. I campioni di codice
sintetici non definiscono la finestra Perfect+ del gioco.

Riferimento del caso senza eventi: [MotionEventInjector AOSP](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/MotionEventInjector.java).
Una continuazione stazionaria senza nuovi DOWN, UP o spostamenti non genera
eventi, e la richiesta termina con esito negativo. Conservare il contatto e
attendere il prossimo evento evita quella cancellazione. Se non viene mai
riconosciuta una coda valida, questo filtro da solo non recupera la nota.

Esecuzione: `python3 tools/check.py`. Non richiede né simula un telefono.
