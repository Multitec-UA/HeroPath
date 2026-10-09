package com.multitec.heropath;

import java.util.Locale;

/**
 * One line of a player's history file: a position, or an event at a position.
 *
 * <p>The file format is one tab-separated line per sample, append-only:
 * {@code epochMillis  kind  world  x  y  z  [detail]}. The seventh column is optional: only
 * events carry it (a death's cause, an advancement's name), so files written before it
 * existed still parse. Plain text on purpose: it survives a crash
 * mid-write (at worst one torn last line, which {@link #parse} skips), it can be read
 * with {@code cat}, and it needs no database next to the game.
 */
public record Sample(long time, Kind kind, String world, double x, double y, double z, String detail) {

    /** A sample with no detail: positions, joins, quits and jumps. */
    public Sample(long time, Kind kind, String world, double x, double y, double z) {
        this(time, kind, world, x, y, z, null);
    }

    public enum Kind {
        /** A position while moving. */
        POINT('P'),
        /** The player joined; starts a new segment. */
        JOIN('J'),
        /** The player left; ends the segment. */
        QUIT('Q'),
        /** The player died here. */
        DEATH('D'),
        /** A jump the player did not walk: teleport, respawn, or a different world. */
        JUMP('T'),
        /** An advancement was completed here. Detail: {@code frame|title}. */
        ADVANCEMENT('A'),
        /** The player arrived in this dimension from another one. Detail: where from. */
        DIMENSION('W');

        final char code;

        Kind(char code) {
            this.code = code;
        }

        static Kind of(char c) {
            for (Kind k : values()) if (k.code == c) return k;
            return null;
        }

        /** Kinds that start a new line on the map: nothing connects across them. */
        boolean breaksSegment() {
            return this == JOIN || this == JUMP;
        }
    }

    public String serialize() {
        String base = String.format(Locale.ROOT, "%d\t%c\t%s\t%.1f\t%.1f\t%.1f",
                time, kind.code, world, x, y, z);
        return detail == null || detail.isBlank() ? base : base + "\t" + clean(detail);
    }

    /** One line, no tabs, bounded: a detail must never break the file format. */
    static String clean(String s) {
        String c = s.replaceAll("[\\t\\r\\n]+", " ").strip();
        return c.length() > 200 ? c.substring(0, 200) : c;
    }

    /** Returns null for a line that is not a complete sample (a torn last write, a blank). */
    public static Sample parse(String line) {
        String[] p = line.split("\t");
        if ((p.length != 6 && p.length != 7) || p[1].length() != 1) return null;
        Kind kind = Kind.of(p[1].charAt(0));
        if (kind == null) return null;
        try {
            return new Sample(Long.parseLong(p[0]), kind, p[2],
                    Double.parseDouble(p[3]), Double.parseDouble(p[4]), Double.parseDouble(p[5]),
                    p.length == 7 ? p[6] : null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public double distanceSq(Sample o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
