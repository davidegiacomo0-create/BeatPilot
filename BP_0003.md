# BeatPilot 0.1.10-reliability

Revisione di affidabilità derivata dalla base 0.1.9-holdtails.

Correzioni principali:
- un singolo frame HUD incerto non cancella più note già previste o contatti attivi;
- se la coda visiva perde la fine di una HOLD, una nuova nota sulla stessa corsia chiude il contatto HOLD rimasto stale senza scartare la nuova nota;
- gesti brevi sulla stessa corsia possono sovrapporsi nel flusso multi-pointer invece di far sparire la nota successiva;
- le collisioni UP/DOWN nello stesso millisecondo mantengono il DOWN al suo deadline e posticipano solo l'UP del minimo necessario.

`python3 tools/check.py` deve passare integralmente prima del packaging. I test offline non dimostrano Perfect+ sul dispositivo reale.
