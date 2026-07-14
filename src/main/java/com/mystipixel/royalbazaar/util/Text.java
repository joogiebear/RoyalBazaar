package com.mystipixel.royalbazaar.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Small helper around Adventure so the rest of the plugin keeps using friendly legacy '&' colour
 * strings from config while producing modern {@link Component}s.
 */
public final class Text {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    /** Colourise a legacy string, disabling the default italic Adventure applies to item names. */
    public static Component color(String input) {
        return AMP.deserialize(input == null ? "" : input).decoration(TextDecoration.ITALIC, false);
    }

    /** Colourise a legacy string, leaving italics as authored (for chat messages). */
    public static Component chat(String input) {
        return AMP.deserialize(input == null ? "" : input);
    }
}
