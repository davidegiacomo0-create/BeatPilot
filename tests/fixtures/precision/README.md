# Regressioni 0.1.6

I CSV `*_observations.csv` conservano le osservazioni effettive dei tre JSON 0.1.5 del 16–17 settembre. Corsie numerate da zero, tempi relativi allo zero della registrazione. `ready_ms` è tempo evento vision più analysis_ms. I CSV `*_opening.csv` riportano presenza della coppia e stato del gioco: contengono la risposta ai vecchi tocchi, non permettono di simulare la risposta ai nuovi.

`lost_arrow.csv` associa 20 fotogrammi MP4 consecutivi intorno a 84 s del secondo Flight alla presenza/assenza della LEFT destra nel JSON. Le `.gray` contengono 480×1040 byte, conversione `(77R+150G+29B)>>8`. Le immagini sono estratte per indice/PTS, senza interpolazione.

Punto importante: H.264 cambia i pixel. Il replay della 0.1.5 sul solo MP4 riconosce già la freccia persa sul telefono, mentre il JSON documenta sei osservazioni consecutive mancanti vicino alla linea. La prova di regressione conserva quella lacuna reale e usa il fotogramma compresso soltanto per verificare il recupero della sagoma. Comprende anche controlli con pixel vuoti e con direzione attesa errata: una previsione da sola non deve generare note.

La previsione recuperata è a 84057 ms, pronta a 84024 ms; l’allineamento visibile è circa 84058 ms. La tolleranza di 8 ms non definisce la finestra Perfect+ del gioco.

Le osservazioni di Du hast intorno a 9,15 s documentano una posizione ripetuta. Il test impedisce che il frame ripetuto produca un tocco anticipato e verifica l’allineamento vicino a 9209 ms. `duhast_frozen_encoded.csv` conserva inoltre le 18 osservazioni del replay MP4 intorno a 70,9 s, con tempi del JSON. In quel passaggio il frame fermo arriva dopo che il tocco è già stato previsto: deve poterlo rinviare, e il successivo movimento deve aggiornarlo di nuovo. Le previsioni finali sono 70961 ms sui riconoscimenti live e 70962 ms sul video compresso, vicino all’allineamento visibile di circa 70961 ms. La stessa nota non deve essere duplicata.

Le altre prove coprono moto prospettico, duplicati sintetici, coppia iniziale ritardata, TAP al confine di un piano e continuazione di una freccia.
