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

// when this hook finds nothing it fails silently (items fall back or get skipped), so pin the parse down
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

    // servers commonly keep every category under categories/npc/
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

    @Test
    void matchesBareEcoShopIdsAgainstNamespacedBazaarIds(@TempDir Path tmp) throws Exception {
        write(tmp.resolve("EcoShop/categories/npc/mining_merchant.yml"), CATEGORY);
        EcoShopHook hook = new EcoShopHook(tmp.toFile(), Logger.getLogger("test"));

        assertEquals(12.0, hook.buyValue("minecraft:coal"));
        assertEquals(12.0, hook.buyValue("coal"), "an un-namespaced lookup should normalise the same way");
    }

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
