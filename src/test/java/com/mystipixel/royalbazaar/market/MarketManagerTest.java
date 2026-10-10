package com.mystipixel.royalbazaar.market;

import com.mystipixel.royalbazaar.config.CategoryConfig;
import com.mystipixel.royalbazaar.hooks.EcoShopHook;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Test
    void missingModePreservesTwoWayTrading(@TempDir Path tmp) throws Exception {
        for (MarketItem item : category("farming", FARMING, true).buildItems(noShop(tmp))) {
            assertEquals(TradeMode.BOTH, item.tradeMode());
        }
    }

    @ParameterizedTest
    @EnumSource(TradeMode.class)
    void modeLoadsCaseInsensitivelyAndReloadKeepsLiveMarket(TradeMode mode, @TempDir Path tmp) throws Exception {
        MarketManager market = new MarketManager(LOG);
        market.load(List.of(category("farming", FARMING, true)), 0.2, noShop(tmp));
        MarketItem old = market.get("minecraft:wheat");
        old.setMid(12);
        old.setFrozen(true);
        old.volume().recordSell(8);
        old.setWeekStats(9, 7, 14);
        String changed = FARMING.replace("base_price: 10",
                "base_price: 10\n    trade_mode: ' " + mode.name().toLowerCase(java.util.Locale.ROOT) + " '");
        assertTrue(market.load(List.of(category("farming", changed, true)), 0.2, noShop(tmp)).isEmpty());
        MarketItem fresh = market.get(old.id());
        assertEquals(mode, fresh.tradeMode());
        assertEquals(12, fresh.mid());
        assertEquals(8, fresh.volume().sold24h());
        assertEquals(14, fresh.weekHigh());
        assertTrue(fresh.frozen());
        assertTrue(fresh.dirty());
        // Returning to both is a configuration change, not a state reset.
        market.load(List.of(category("farming", FARMING, true)), 0.2, noShop(tmp));
        assertEquals(TradeMode.BOTH, market.get(old.id()).tradeMode());
        assertEquals(12, market.get(old.id()).mid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sell_onyl", "none", "false", "123", "''", "[buy_only]", "{buy: false}"})
    void invalidModeRemovesListingInsteadOfReenablingBothSides(String mode, @TempDir Path tmp) throws Exception {
        MarketManager market = new MarketManager(LOG);
        market.load(List.of(category("farming", FARMING, true)), 0.2, noShop(tmp));
        String invalid = FARMING.replace("base_price: 10", "base_price: 10\n    trade_mode: " + mode);
        market.load(List.of(category("farming", invalid, true)), 0.2, noShop(tmp));
        assertNull(market.get("minecraft:wheat"));
        assertNotNull(market.get("minecraft:carrot"));
    }

    @ParameterizedTest
    @EnumSource(TradeMode.class)
    void npcGuardOnlyConstrainsExecutableDirections(TradeMode mode) throws Exception {
        EcoShopHook shop = org.mockito.Mockito.mock(EcoShopHook.class);
        org.mockito.Mockito.when(shop.sellValue("minecraft:iron_ingot")).thenReturn(60.0);
        org.mockito.Mockito.when(shop.buyValue("minecraft:iron_ingot")).thenReturn(100.0);
        String yaml = """
                items:
                  iron:
                    item: minecraft:iron_ingot
                    base_price: 80
                    spread: 0.04
                    floor_pct: 0.1
                    ceiling_pct: 10
                    trade_mode: %s
                """.formatted(mode);
        MarketItem item = category("mining", yaml, true).buildItems(shop).getFirst();
        assertEquals(mode.allows(TradeSide.BUY) ? 60 / 1.02 : 8, item.floor(), 1e-9);
        assertEquals(mode.allows(TradeSide.SELL) ? 100 / 0.98 : 800, item.ceiling(), 1e-9);
        // Total order prices remain safe even when the integral runs beyond the mid caps.
        for (long qty : new long[]{1, 64, 10_000, 1_000_000}) {
            if (mode.allows(TradeSide.BUY)) {
                item.setMid(item.floor());
                assertTrue(PricingEngine.buyCost(item, qty) >= 60 * qty - 1e-7);
            }
            if (mode.allows(TradeSide.SELL)) {
                item.setMid(item.ceiling());
                assertTrue(PricingEngine.sellProceeds(item, qty) <= 100 * qty + 1e-7);
            }
        }
        MarketItem unguarded = category("mining", yaml, false).buildItems(shop).getFirst();
        assertEquals(8, unguarded.floor());
        assertEquals(800, unguarded.ceiling());
    }

    @ParameterizedTest
    @EnumSource(TradeMode.class)
    void impossibleNpcBracketIsRejectedInsteadOfDroppingProtection(TradeMode mode) throws Exception {
        EcoShopHook shop = org.mockito.Mockito.mock(EcoShopHook.class);
        org.mockito.Mockito.when(shop.sellValue("minecraft:wheat")).thenReturn(1000.0);
        org.mockito.Mockito.when(shop.buyValue("minecraft:wheat")).thenReturn(0.1);
        String yaml = FARMING.replace("base_price: 10", "base_price: 10\n    trade_mode: " + mode);
        List<MarketItem> items = category("farming", yaml, true).buildItems(shop);
        assertEquals(List.of("minecraft:carrot"), items.stream().map(MarketItem::id).toList());
    }

    @ParameterizedTest
    @EnumSource(value = TradeMode.class, names = {"BUY_ONLY", "SELL_ONLY"})
    void absentRelevantNpcSideDoesNotRestrictTheOneWayMarket(TradeMode mode) throws Exception {
        EcoShopHook shop = org.mockito.Mockito.mock(EcoShopHook.class);
        if (mode == TradeMode.BUY_ONLY) {
            org.mockito.Mockito.when(shop.buyValue("minecraft:wheat")).thenReturn(0.1);
        } else {
            org.mockito.Mockito.when(shop.sellValue("minecraft:wheat")).thenReturn(1000.0);
        }
        String yaml = FARMING.replace("base_price: 10", "base_price: 10\n    trade_mode: " + mode);
        MarketItem item = category("farming", yaml, true).buildItems(shop).getFirst();
        assertEquals(4, item.floor());
        assertEquals(30, item.ceiling());
    }
}
