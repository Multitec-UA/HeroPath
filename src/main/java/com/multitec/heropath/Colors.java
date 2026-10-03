package com.multitec.heropath;

import java.util.UUID;

/**
 * A stable, bright colour per player.
 *
 * <p>Upstream BMTrails seeded {@code java.util.Random} with the name and took any 24-bit
 * value, which gives muddy browns and near-blacks that vanish on a dark map. Here the
 * UUID picks only the hue; saturation and value are fixed high, so every trail is vivid,
 * and the colour survives a name change.
 */
public final class Colors {

    private Colors() {
    }

    /** 0xRRGGBB. */
    public static int forPlayer(UUID uuid) {
        long h = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        float hue = (float) ((h >>> 11) % 360) / 360f;
        return hsvToRgb(hue, 0.85f, 1.0f);
    }

    public static String hex(int rgb) {
        return String.format("#%06x", rgb & 0xffffff);
    }

    static int hsvToRgb(float h, float s, float v) {
        float r, g, b;
        int i = (int) Math.floor(h * 6);
        float f = h * 6 - i, p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        switch (((i % 6) + 6) % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }
}
