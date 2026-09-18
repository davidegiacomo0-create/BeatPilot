package it.dave.beatpilot.core;

public enum Kind {
    TAP("Tocco"), CHECKPOINT("Checkpoint"), HOLD_START("Inizio pressione"), HOLD_END("Fine pressione"),
    UP("Freccia su"), DOWN("Freccia giù"), LEFT("Freccia sinistra"), RIGHT("Freccia destra");
    public final String label;
    Kind(String label) { this.label = label; }
}
