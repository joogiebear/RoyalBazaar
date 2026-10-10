package com.mystipixel.royalbazaar.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemNamesTest {

    @Test
    void matchesTranslatedNamesIgnoringCaseAndAccents() {
        ItemNames names = new ItemNames();
        names.load(List.of(Map.of(
                "item.minecraft.rotten_flesh", "Chair putréfiée",
                "block.minecraft.stone_bricks", "Pierres taillées",
                "item.minecraft.diamond_sword.desc", "ignored")));

        String needle = ItemNames.normalize("Putrefiee");
        assertTrue(names.translatedNameContains("rotten_flesh", needle));
        assertTrue(names.translatedNameContains("minecraft:STONE_BRICKS", ItemNames.normalize("pierres")));
        assertFalse(names.translatedNameContains("stone_bricks", needle));
        assertFalse(names.translatedNameContains("diamond_sword", ItemNames.normalize("ignored")));
        assertEquals("epee en diamant", ItemNames.normalize(" Épée en Diamant "));
    }

    @Test
    void searchTextIsLiteralNeverAPatternOrWildcard() {
        ItemNames names = new ItemNames();
        names.load(List.of(Map.of("item.minecraft.rotten_flesh", "Chair putréfiée")));

        for (String hostile : List.of(".*", "%", "_", "' OR '1'='1", "[a-z]+", "\\")) {
            assertFalse(names.translatedNameContains("rotten_flesh", ItemNames.normalize(hostile)), hostile);
        }
    }
}
