package com.kodeshi.pinauth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Small text helpers. */
final class Text {
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private Text() {
    }

    static Component legacy(String s) {
        return LEGACY.deserialize(s);
    }

    /** Component for item names/lore: no italics. */
    static Component item(String s) {
        return legacy(s).decoration(TextDecoration.ITALIC, false);
    }

    static String duration(long millis) {
        long total = Math.max(1, (millis + 999) / 1000);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (h > 0 || m > 0) sb.append(m).append("m ");
        sb.append(s).append("s");
        return sb.toString();
    }
}
