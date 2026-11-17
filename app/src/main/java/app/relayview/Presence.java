package app.relayview;

import java.nio.FloatBuffer;

/** The two independent presence latches belong to one camera. */
final class Presence {
    static final int PERSON = 1, VEHICLE = 2;
    private static final int CLEAR_FRAMES = 3;
    private int active, personMisses, vehicleMisses;

    int entered(int seen) {
        int entered = seen & ~active;
        active |= seen;
        personMisses = (seen & PERSON) != 0 ? 0 : Math.min(CLEAR_FRAMES, personMisses + 1);
        vehicleMisses = (seen & VEHICLE) != 0 ? 0 : Math.min(CLEAR_FRAMES, vehicleMisses + 1);
        if (personMisses == CLEAR_FRAMES) active &= ~PERSON;
        if (vehicleMisses == CLEAR_FRAMES) active &= ~VEHICLE;
        return entered;
    }

    static int classify(FloatBuffer rows) {
        final int stride = 85;
        final float threshold = 0.40f;
        int seen = 0;
        for (int row = 0; row < rows.limit(); row += stride) {
            float objectness = rows.get(row + 4);
            if (objectness < threshold) continue;
            int bestClass = 0;
            float best = rows.get(row + 5);
            for (int c = 1; c < 80; c++) {
                float score = rows.get(row + 5 + c);
                if (score > best) { best = score; bestClass = c; }
            }
            if (objectness * best < threshold) continue;
            if (bestClass == 0) seen |= PERSON;
            if (bestClass >= 1 && bestClass <= 8) seen |= VEHICLE;
            if (seen == (PERSON | VEHICLE)) break;
        }
        return seen;
    }
}
