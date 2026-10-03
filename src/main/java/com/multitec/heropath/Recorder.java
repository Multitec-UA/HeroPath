package com.multitec.heropath;

/**
 * Decides, for one player, which positions are worth writing down.
 *
 * <p>Sampling runs every few seconds, but a player standing at a crafting table for ten
 * minutes would otherwise write 120 identical lines. A position is kept when the player
 * has moved at least {@code minMove} blocks since the last kept one, or when
 * {@code keepAliveMillis} have passed (so the timeline still knows they were there).
 * A move longer than {@code jumpDistance} between two samples was not walked, and is
 * recorded as a {@link Sample.Kind#JUMP} so the map does not draw a line across it.
 * A change of world is always a jump.
 *
 * <p>Not thread-safe; one instance per player, used from the single IO thread.
 */
public final class Recorder {

    private final double minMoveSq;
    private final double jumpDistanceSq;
    private final long keepAliveMillis;
    private Sample last;

    public Recorder(double minMove, double jumpDistance, long keepAliveMillis) {
        this.minMoveSq = minMove * minMove;
        this.jumpDistanceSq = jumpDistance * jumpDistance;
        this.keepAliveMillis = keepAliveMillis;
    }

    /**
     * Offers a sampled position. Returns the sample to write, re-labelled as a JUMP when
     * it was not walked, or null when nothing needs writing.
     */
    public Sample offer(Sample s) {
        if (s.kind() != Sample.Kind.POINT) {
            // Events are always written, and they reset the reference point.
            last = s;
            return s;
        }
        if (last == null || !last.world().equals(s.world())) {
            Sample jump = new Sample(s.time(), Sample.Kind.JUMP, s.world(), s.x(), s.y(), s.z());
            last = jump;
            return jump;
        }
        double d = s.distanceSq(last);
        if (d > jumpDistanceSq) {
            Sample jump = new Sample(s.time(), Sample.Kind.JUMP, s.world(), s.x(), s.y(), s.z());
            last = jump;
            return jump;
        }
        if (d >= minMoveSq || s.time() - last.time() >= keepAliveMillis) {
            last = s;
            return s;
        }
        return null;
    }
}
