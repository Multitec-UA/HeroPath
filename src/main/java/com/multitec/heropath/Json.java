package com.multitec.heropath;

import java.util.Locale;

/** The few JSON pieces the exporter needs, so the pure classes need no library at all. */
final class Json {

    private Json() {
    }

    static String str(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    /** One decimal is a tenth of a block: more than the eye can see on the map. */
    static String num(double d) {
        if (d == Math.rint(d)) return Long.toString((long) d);
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
