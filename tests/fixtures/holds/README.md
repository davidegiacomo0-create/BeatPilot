# Note lunghe — regressione 0.1.7

Fonte: `BeatPilot_20260917_103223_549.mp4` e JSON, app 0.1.6-precision, Samsung SM-S948B / API 36. Registrazione di circa 9,96 s con 562 frame MP4. Il bot avvia il livello, non preme la prima nota lunga e il gioco mostra FAILED TO HOLD.

`sequence.csv` associa 41 fotogrammi fra 5,86 e 7,13 s a tempi video, cattura e fine analisi. I `.gray` sono 480 × 1040 byte, conversione `(77R+150G+29B)>>8`, estratti dai fotogrammi MP4 senza interpolazione. Le immagini non rappresentano i pixel originali prima della compressione.

La barra iniziale della prima HOLD sinistra incontra la linea intorno a 6645 ms. Il cappuccio superiore della stessa nota raggiunge la linea intorno a 7090 ms. La 0.1.7 mantiene il contatto sotto la linea e programma il rilascio quando il cappuccio oltrepassa quel punto: previsione finale 7143 ms. Non è una misura del giudizio del gioco o del rilascio eseguito sul telefono. Il TAP centrale resta previsto a 7123 ms.

Il replay completo prevede HOLD_START a 6645 ms; il campionamento ridotto del test a 6644 ms. La tolleranza di 8 ms non definisce la finestra Perfect+.

I test verificano riconoscimento separato di testa/coda, assenza di un TAP duplicato, continuità dello stesso puntatore, tocco nell’altra corsia prima del rilascio e rilascio finale. Simulano callback con ritardi 0, 5 e 17 ms. Altri casi sintetici coprono tre corsie, testa o coda tagliate, note corte/lunghe, due pressioni simultanee, una freccia durante il mantenimento, pressione di oltre 10 s, nota successiva nella stessa corsia e azzeramento allo Stop. I 117 precedenti fotogrammi reali non generano false HOLD.

Non sono presenti esempi di pressione riuscita, coda durante una pressione attiva, freccia finale o combinazioni trascinate. Il test non certifica il completamento della canzone.
