package com.mystipixel.royalbazaar.market;

import com.mystipixel.royalbazaar.config.CategoryConfig;
import com.mystipixel.royalbazaar.hooks.EcoShopHook;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Category loading and {@code /bazaar reload}: what survives, what is refused, what gets bracketed. */
class MarketManagerTest {

    private static final Logger LOG = Logger.getLogger("test");

    private static CategoryConfig category(String id, String yaml, boolean guard) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return CategoryConfig.load(id, cfg, LOG, guard);
    }

    private static EcoShopHook noShop(Path tmp) {
        return new EcoShopHook(tmp.toFile(), LOG);   // no EcoShop/ folder → empty
    }

    private static final String FARMING = """
            items:
              wheat:
                item: "minecraft:wheat"
                base_price: 10
              carrot:
                item: "minecraft:carrot"
                base_price: 5
            """;

    @Test
    void reloadKeepsLiveStateAndOnlyReportsNewItems(@TempDir Path tmp) throws Exception {
        MarketManager market = new MarketManager(LOG);
        Set<String> first = market.load(List.of(category("farming", FARMING, true)), 0.2, noShop(tmp));
        assertEquals(Set.of("minecraft:wheat", "minecraft:carrot"), first);

        MarketItem wheat = market.get("minecraft:wheat");
        wheat.setMid(12.5);               // a trade not yet flushed
        wheat.setFrozen(true);            // an admin freeze
        wheat.volume().recordBuy(40);

        String withPotato = FARMING + """
                  potato:
                    item: "minecraft:potato"
                    base_price: 4
                """;
        Set<String> second = market.load(List.of(category("farming", withPotato, true)), 0.2, noShop(tmp));
        assertEquals(Set.of("minecraft:potato"), second, "only the new item needs seeding from storage");

        MarketItem reloaded = market.get("minecraft:wheat");
        assertNotSame(wheat, reloaded);
        assertEquals(12.5, reloaded.mid());
        assertTrue(reloaded.frozen(), "a reload must not silently lift a freeze");
        assertTrue(reloaded.dirty(), "the unflushed price must still be written");
        assertEquals(40, reloaded.volume().bought24h());
    }

    @Test
    void reloadReclampsACarriedPriceIntoANarrowerRange(@TempDir Path tmp) throws Exception {
        MarketManager market = new MarketManager(LOG);
        market.load(List.of(category("farming", FARMING, true)), 0.2, noShop(tmp));
        market.get("minecraft:wheat").setMid(25);
        market.drainDirtyState();          // flushed

        String capped = FARMING.replace("base_price: 10", "base_price: 10\n    ceiling_pct: 2.0");
        market.load(List.of(category("farming", capped, true)), 0.2, noShop(tmp));
        MarketItem wheat = market.get("minecraft:wheat");
        assertEquals(20, wheat.mid(), 1e-9);
        assertTrue(wheat.dirty(), "the clamped price differs from the stored one");
    }

    @Test
    void sameItemListedTwiceIsLoadedOnce(@TempDir Path tmp) throws Exception {
        String other = """
                items:
                  bare_wheat:
                    item: "wheat"
                    base_price: 30
                """;
        MarketManager market = new MarketManager(LOG);
        market.load(List.of(category("farming", FARMING, true), category("other", other, true)), 0.2,
                noShop(tmp));
        assertNotNull(market.get("minecraft:wheat"));
        assertNull(market.get("wheat"), "a second listing would be a second, arbitrageable price");
        assertSame(market.get("minecraft:wheat"), market.lookup("WHEAT"));
    }

    @Test
    void brokenTuningIsSkipped(@TempDir Path tmp) throws Exception {
        String yaml = """
                items:
                  zero_e:
                    item: "minecraft:stone"
                    base_price: 1
                    elasticity: 0
                  negative_e:
                    item: "minecraft:dirt"
                    base_price: 1
                    elasticity: -500
                  negative_spread:
                    item: "minecraft:sand"
                    base_price: 1
                    spread: -0.2
                  wild_reversion:
                    item: "minecraft:gravel"
                    base_price: 1
                    reversion_rate: 1.5
                  fine:
                    item: "minecraft:cobblestone"
                    base_price: 1
                """;
        MarketManager market = new MarketManager(LOG);
        Set<String> loaded = market.load(List.of(category("mining", yaml, true)), 0.2, noShop(tmp));
        assertEquals(Set.of("minecraft:cobblestone"), loaded);
    }

    @Test
    void ecoShopItemsStayInsideTheNpcBracket(@TempDir Path tmp) throws Exception {
        Path shopFile = tmp.resolve("EcoShop/categories/shop.yml");
        Files.createDirectories(shopFile.getParent());
        Files.writeString(shopFile, """
                items:
                - id: iron
                  item: iron_ingot
                  buy:
                    type: coins
                    value: 100
                  sell:
                    type: coins
                    value: 60
                """);
        EcoShopHook shop = new EcoShopHook(tmp.toFile(), LOG);
        String yaml = """
                items:
                  iron:
                    item: "minecraft:iron_ingot"
                    base_price: 80
                    spread: 0.04
                    floor_pct: 0.1
                    ceiling_pct: 10
                """;

        MarketManager guarded = new MarketManager(LOG);
        guarded.load(List.of(category("mining", yaml, true)), 0.2, shop);
        MarketItem iron = guarded.get("minecraft:iron_ingot");
        iron.setMid(PricingEngine.midAfterSell(iron, 10_000_000));    // dump to the floor
        assertTrue(PricingEngine.buyPrice(iron) >= 60 - 1e-9, "buying here must never beat selling to the NPC");
        iron.setMid(PricingEngine.midAfterBuy(iron, 10_000_000));     // pump to the ceiling
        assertTrue(PricingEngine.sellPrice(iron) <= 100 + 1e-9, "selling here must never beat buying from the NPC");

        MarketManager open = new MarketManager(LOG);
        open.load(List.of(category("mining", yaml, false)), 0.2, shop);
        assertEquals(8, open.get("minecraft:iron_ingot").floor(), 1e-9, "guard off: config range as written");
    }
}
