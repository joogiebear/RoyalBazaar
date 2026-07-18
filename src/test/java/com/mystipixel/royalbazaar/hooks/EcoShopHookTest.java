package com.mystipixel.royalbazaar.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EcoShop prices drive every {@code base_price: auto} and {@code npc_floor}/{@code npc_ceiling}.
 * When this hook finds nothing it fails <em>silently</em> — items fall back to their defaults or get
 * skipped — so the failure mode is a quietly wrong economy rather than a crash. These tests pin the
 * parse down.
 */
class EcoShopHookTest {

    private static final String CATEGORY = """
            item: iron_pickaxe name:"&6Mining Merchant"
            items:
            - id: coal
              item: coal
              buy:
                type: coins
                value: 12
              sell:
                type: coins
                value: 3
            - id: iron_block
              item: iron_block
              buy:
                type: coins
                value: 270
              sell:
                type: coins
                value: 72
            """;

    private static void write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    /**
     * The regression this exists for: EcoShop lets categories live in subfolders, and servers commonly keep
     * every one under categories/npc/. A non-recursive listing found zero files and reported
     * "anchored prices available for 0 items" while looking perfectly healthy.
     */
    @Test
    void findsCategoriesInSubfolders(@TempDir Path tmp) throws Exception {
        write(tmp.resolve("EcoShop/categories/npc/mining_merchant.yml"), CATEGORY);
        EcoShopHook hook = new EcoShopHook(tmp.toFile(), Logger.getLogger("test"));

        assertTrue(hook.isPresent());
        assertEquals(12.0, hook.buyValue("minecraft:coal"));
        assertEquals(3.0, hook.sellValue("minecraft:coal"));
    }

    @Test
    void findsCategoriesAtTheTopLevelToo(@TempDir Path tmp) throws Exception {
        write(tmp.resolve("EcoShop/categories/mining_merchant.yml"), CATEGORY);
        EcoShopHook hook = new EcoShopHook(tmp.toFile(), Logger.getLogger("test"));

        assertEquals(270.0, hook.buyValue("minecraft:iron_block"));
    }

    /** A bare vanilla id in EcoShop must match the namespaced id RoyalBazaar configs use. */
    @Test
    void matchesBareEcoShopIdsAgainstNamespacedBazaarIds(@TempDir Path tmp) throws Exception {
        write(tmp.resolve("EcoShop/categories/npc/mining_merchant.yml"), CATEGORY);
        EcoShopHook hook = new EcoShopHook(tmp.toFile(), Logger.getLogger("test"));

        assertEquals(12.0, hook.buyValue("minecraft:coal"));
        assertEquals(12.0, hook.buyValue("coal"), "an un-namespaced lookup should normalise the same way");
    }

    /** EcoShop's own _example.yml is a template, not real prices. */
    @Test
    void skipsUnderscorePrefixedExamples(@TempDir Path tmp) throws Exception {
        write(tmp.resolve("EcoShop/categories/_example.yml"), CATEGORY);
        EcoShopHook hook = new EcoShopHook(tmp.toFile(), Logger.getLogger("test"));

        assertNull(hook.buyValue("minecraft:coal"));
    }

    @Test
    void isAbsentWhenEcoShopIsNotInstalled(@TempDir Path tmp) {
        EcoShopHook hook = new EcoShopHook(tmp.toFile(), Logger.getLogger("test"));

        assertTrue(!hook.isPresent());
        assertNull(hook.buyValue("minecraft:coal"));
    }
}
