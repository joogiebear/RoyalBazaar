package com.mystipixel.royalbazaar.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextInputTest {

    @Test
    void typedTextIsCleanedAndCappedServerSide() {
        assertEquals("", TextInput.sanitize(null));
        assertEquals("casque", TextInput.sanitize("  casque \n"));
        assertEquals("ab", TextInput.sanitize("a\u0000\u0007b"));
        assertEquals("' OR '1'='1; DROP TABLE x; --", TextInput.sanitize("' OR '1'='1; DROP TABLE x; --"),
                "Kept verbatim: callers bind it, never build SQL from it");
        assertEquals(TextInput.MAX_LENGTH, TextInput.sanitize("x".repeat(10_000)).length());
        String emoji = "\uD83D\uDE00".repeat(TextInput.MAX_LENGTH + 5);
        assertEquals(TextInput.MAX_LENGTH, TextInput.sanitize(emoji).codePointCount(0, TextInput.sanitize(emoji).length()),
                "Never splits a surrogate pair");
    }

    @Test
    void hintsBecomeOneTitleWithoutTheSignArrow() {
        assertEquals("&8Type an item &8name to search",
                TextInput.titleOf(List.of("&8^^^^^^^^^^^^^^^", "&8Type an item", "&8name to search")));
    }
}
