package it.dave.beatpilot.core;

/** Reject brief reappearances of the playing field inside exit/menu animations. */
public final class GameplayGate {
    // Keep predictions across a short uncertain image, while dispatch remains
    // suspended. This is NOT permission to touch a screen with an absent HUD.
    private static final long STATE_GRACE_MS = 80;
    private long missingSince = -1, stableSince = -1, pairSince = -1, previous = -1;
    private boolean played, recovering, preserveNotes;

    public boolean preserveNotes() { return preserveNotes; }

    public void reset() {
        missingSince = stableSince = pairSince = previous = -1;
        played = recovering = preserveNotes = false;
    }

    public boolean update(boolean hudVisible, boolean openingPairVisible, long now) {
        preserveNotes = false;
        if (previous >= 0 && (now < previous || now - previous > 250)) {
            stableSince = pairSince = -1;
            if (played) recovering = true;
        }
        previous = now;
        if (!hudVisible) {
            stableSince = -1;
            if (missingSince < 0) missingSince = now;
            if (played && now - missingSince >= 400) recovering = true;
            if (!openingPairVisible) pairSince = -1;
            else {
                if (pairSince < 0) pairSince = now;
                // A verified new song's stationary opening pair needs no recovery delay.
                if (now - pairSince >= 180) {
                    played = recovering = false;
                    missingSince = -1;
                }
            }
            preserveNotes = played && !recovering && missingSince >= 0
                    && now - missingSince < STATE_GRACE_MS;
            return false;
        }
        pairSince = -1;
        if (played && missingSince >= 0 && now - missingSince >= 400) recovering = true;
        missingSince = -1;
        if (recovering) {
            if (stableSince < 0) stableSince = now;
            if (now - stableSince < 600) return false;
            recovering = false;
        }
        played = true;
        return true;
    }
}
