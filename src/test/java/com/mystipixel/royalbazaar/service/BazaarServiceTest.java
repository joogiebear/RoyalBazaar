package com.mystipixel.royalbazaar.service;

import com.mystipixel.royalbazaar.config.PluginConfig;
import com.mystipixel.royalbazaar.data.BazaarDatabase;
import com.mystipixel.royalbazaar.hooks.*;
import com.mystipixel.royalbazaar.market.*;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Provider failure tests drive the real service, inventory planning, pricing and audit path. */
class BazaarServiceTest {
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final MarketManager market = mock(MarketManager.class);
    private final BazaarDatabase db = mock(BazaarDatabase.class);
    private final VaultHook vault = mock(VaultHook.class);
    private final EcoHook eco = mock(EcoHook.class);
    private final EconGuardHook guard = mock(EconGuardHook.class);
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final PluginConfig config = mock(PluginConfig.class);
    private final AtomicReference<ItemStack[]> storage = new AtomicReference<>();
    private final ItemMeta customMetadata = mock(ItemMeta.class);
    private final MarketItem item = new MarketItem("ecoitem:test", "test", null, "Custom item",
            100, 0.05, 1000, 0.01, 1, 1000);
    private BazaarService service;

    @BeforeEach
    void setup() {
        service = new BazaarService(plugin, market, db, vault, eco, guard, config);
        when(market.get(item.id())).thenReturn(item);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        // Storage arrays are copied, but their stacks are shared, just as a shallow snapshot can be.
        when(inventory.getStorageContents()).thenAnswer(i -> storage.get().clone());
        doAnswer(i -> { storage.set(i.getArgument(0)); return null; })
                .when(inventory).setStorageContents(any(ItemStack[].class));
        when(eco.matches(eq(item.id()), any())).thenAnswer(i -> {
            ItemStack stack = i.getArgument(1);
            return stack != null && stack.getItemMeta() == customMetadata;
        });
        when(eco.countHeld(player, item.id())).thenAnswer(i -> {
            int count = 0;
            for (ItemStack stack : storage.get()) {
                if (eco.matches(item.id(), stack)) count += stack.getAmount();
            }
            return count;
        });
        when(guard.allow(eq(player), eq(TradeSide.SELL), eq(item.id()), anyLong(), anyDouble()))
                .thenReturn(true);
        storage.set(new ItemStack[] { stack(5, customMetadata), stack(9, customMetadata),
                stack(3, mock(ItemMeta.class)), null });
    }

    // Stateful stack doubles avoid booting Paper. clone preserves metadata and isolates quantity.
    private ItemStack stack(int amount, ItemMeta metadata) {
        ItemStack stack = mock(ItemStack.class);
        AtomicInteger count = new AtomicInteger(amount);
        when(stack.getAmount()).thenAnswer(i -> count.get());
        doAnswer(i -> { count.set(i.getArgument(0)); return null; }).when(stack).setAmount(anyInt());
        when(stack.getItemMeta()).thenReturn(metadata);
        when(stack.clone()).thenAnswer(i -> stack(count.get(), metadata));
        return stack;
    }

    private void assertNoSaleRecorded() {
        assertEquals(100, item.mid());
        assertEquals(0, item.volume().sold24h());
        verify(guard, never()).observe(any(), any(), anyString(), anyLong(), anyDouble());
        verifyNoInteractions(scheduler, db);
    }

    @Test
    void rejectedPaymentDoesNotConsumeAnyCustomItemsOrMoveTheMarket() {
        ItemStack[] before = storage.get().clone();
        when(vault.deposit(eq(player), anyDouble())).thenReturn(false);
        TradeResult result = service.sell(player, item.id(), 8);
        assertEquals(TradeResult.Status.ERROR, result.status());
        assertEquals(0, result.filled());
        assertEquals(0, result.total());
        assertArrayEquals(before, storage.get());
        assertEquals(5, before[0].getAmount());
        assertEquals(9, before[1].getAmount());
        verify(inventory, times(2)).setStorageContents(any());
        assertNoSaleRecorded();
    }

    @Test
    void acceptedPaymentRemovesExactlyTheQuotedQuantityAndPreservesMetadata() {
        ItemStack[] before = storage.get().clone();
        double quote = PricingEngine.sellProceeds(item, 8);
        when(vault.deposit(player, quote)).thenAnswer(i -> {
            assertNull(storage.get()[0], "sold items are reserved during the provider call");
            assertEquals(6, storage.get()[1].getAmount());
            return true;
        });
        TradeResult result = service.sell(player, item.id(), 8);
        assertTrue(result.ok());
        assertEquals(8, result.filled());
        assertEquals(quote, result.total());
        assertNull(storage.get()[0]);
        assertEquals(6, storage.get()[1].getAmount());
        assertSame(customMetadata, storage.get()[1].getItemMeta());
        assertEquals(3, storage.get()[2].getAmount());
        assertSame(before[2].getItemMeta(), storage.get()[2].getItemMeta());
        assertEquals(8, item.volume().sold24h());
        assertTrue(item.mid() < 100);
        verify(vault).deposit(player, quote);
        verify(guard).observe(player, TradeSide.SELL, item.id(), 8, quote);
        verify(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
    }

    @Test
    void failedSellAllIsNotReportedAsSoldAndCanBeRetriedOnceProviderRecovers() {
        when(vault.deposit(eq(player), anyDouble())).thenReturn(false, true);
        var first = service.sellAll(player, List.of(item));
        assertTrue(first.soldNothing());
        assertEquals(0, first.units());
        assertEquals(0, first.proceeds());
        assertNoSaleRecorded();
        var second = service.sellAll(player, List.of(item));
        assertEquals(1, second.distinctItems());
        assertEquals(14, second.units());
        assertNull(storage.get()[0]);
        assertNull(storage.get()[1]);
        assertEquals(3, storage.get()[2].getAmount());
        assertEquals(14, item.volume().sold24h());
    }

    @Test
    void nonPositiveSaleAmountsNeverReachEconomyOrInventoryMutation() {
        assertEquals(TradeResult.Status.ERROR, service.sell(player, item.id(), 0).status());
        assertEquals(TradeResult.Status.ERROR, service.sell(player, item.id(), -8).status());
        verifyNoInteractions(vault);
        verify(inventory, never()).setStorageContents(any());
        assertNoSaleRecorded();
    }

    @Test
    void partialStackRejectionPreservesTheOriginalQuantityAndMetadata() {
        ItemStack original = storage.get()[0];
        when(vault.deposit(eq(player), anyDouble())).thenReturn(false);
        assertFalse(service.sell(player, item.id(), 2).ok());
        assertSame(original, storage.get()[0]);
        assertEquals(5, original.getAmount());
        assertSame(customMetadata, original.getItemMeta());
        assertNoSaleRecorded();
    }

    @Test
    void staleInventoryCountDoesNotPayForItemsThatCannotBeReserved() {
        doReturn(100).when(eco).countHeld(player, item.id());
        assertEquals(TradeResult.Status.INSUFFICIENT_ITEMS,
                service.sell(player, item.id(), 100).status());
        verifyNoInteractions(vault);
        verify(inventory, never()).setStorageContents(any());
        assertNoSaleRecorded();
    }

    @Test
    void exceptionIsNotTreatedAsAnExplicitRejectionOrAutomaticallyRetried() {
        // An exception may occur after a provider moved money. Do not return items automatically
        // and turn an unknown outcome into a duplicate; this path still needs manual reconciliation.
        when(vault.deposit(eq(player), anyDouble())).thenThrow(new IllegalStateException("unknown outcome"));
        assertThrows(IllegalStateException.class, () -> service.sell(player, item.id(), 8));
        verify(vault).deposit(eq(player), anyDouble());
        verify(inventory).setStorageContents(any());
        assertNull(storage.get()[0]);
        assertEquals(6, storage.get()[1].getAmount());
        assertNoSaleRecorded();
    }

    @Test
    void guardRejectionNeverChargesOrConsumesItems() {
        when(guard.allow(eq(player), eq(TradeSide.SELL), eq(item.id()), anyLong(), anyDouble()))
                .thenReturn(false);
        assertEquals(TradeResult.Status.REJECTED_BY_GUARD, service.sell(player, item.id(), 8).status());
        verifyNoInteractions(vault);
        verify(inventory, never()).setStorageContents(any());
        assertNoSaleRecorded();
    }

    @Test
    void buyOverTheOrderCapIsRefusedBeforeAnyMoneyMoves() {
        when(config.maxOrder()).thenReturn(64L);
        TradeResult result = service.buy(player, item.id(), 65);
        assertEquals(TradeResult.Status.ERROR, result.status());
        verifyNoInteractions(vault);
        assertEquals(100, item.mid());
    }

    @Test
    void buyOfAnItemThatNoLongerResolvesIsRefusedNotThrown() {
        when(guard.allow(any(), any(), anyString(), anyLong(), anyDouble())).thenReturn(true);
        when(eco.resolve(item.id(), 1)).thenReturn(null);
        TradeResult result = service.buy(player, item.id(), 5);
        assertEquals(TradeResult.Status.DISABLED, result.status());
        verifyNoInteractions(vault);
        assertEquals(100, item.mid());
        assertEquals(0, service.fillAmount(player, item.id()));
    }

    private MarketItem withMode(TradeMode mode) {
        MarketItem fresh = new MarketItem(item.id(), "test", null, "Custom item",
                100, 0.05, 1000, 0.01, 1, 1000, mode);
        when(market.get(item.id())).thenReturn(fresh);
        return fresh;
    }

    @ParameterizedTest
    @EnumSource(value = TradeMode.class, names = {"BUY_ONLY", "SELL_ONLY"})
    void forbiddenTradesHaveNoSideEffectsEvenWhenRepeated(TradeMode mode) {
        MarketItem fresh = withMode(mode);
        ItemStack[] before = storage.get().clone();
        for (long amount : new long[]{1, 64, Long.MAX_VALUE, 0, -1, 64}) {
            TradeResult result = mode == TradeMode.BUY_ONLY
                    ? service.sell(player, item.id(), amount) : service.buy(player, item.id(), amount);
            assertEquals(TradeResult.Status.DISABLED, result.status());
            assertEquals(0, result.filled());
            assertEquals(0, result.total());
        }
        assertEquals(100, fresh.mid());
        assertEquals(0, fresh.volume().bought24h());
        assertEquals(0, fresh.volume().sold24h());
        assertFalse(fresh.dirty());
        assertArrayEquals(before, storage.get());
        verifyNoInteractions(vault, eco, guard, inventory, scheduler, db);
    }

    @Test
    void sellOnlyFillDoesNotInspectBalanceOrInventory() {
        withMode(TradeMode.SELL_ONLY);
        assertEquals(0, service.fillAmount(player, item.id()));
        verifyNoInteractions(vault, eco, inventory);
    }

    @Test
    void sellAllSkipsBuyOnlyItemsEvenWithAPreReloadScope() {
        withMode(TradeMode.BUY_ONLY);
        var result = service.sellAll(player, List.of(item));
        assertTrue(result.soldNothing());
        assertEquals(0, result.units());
        assertEquals(0, result.proceeds());
        assertEquals(0, result.blocked(), "configured untradeable items are silently skipped");
        verifyNoInteractions(vault, eco, guard, inventory, scheduler, db);
    }

    @Test
    void mixedSellAllTradesTheAllowedItemOnlyAndCannotSellItTwice() {
        MarketItem allowed = withMode(TradeMode.SELL_ONLY);
        MarketItem buyOnly = new MarketItem("minecraft:stone_bricks", "test", null, "",
                10, 0.05, 1000, 0.01, 1, 100, TradeMode.BUY_ONLY);
        when(market.get(buyOnly.id())).thenReturn(buyOnly);
        when(vault.deposit(eq(player), anyDouble())).thenReturn(true);
        var scope = List.of(buyOnly, item);
        var first = service.sellAll(player, scope);
        assertEquals(1, first.distinctItems());
        assertEquals(14, first.units());
        assertEquals(0, first.blocked());
        assertEquals(14, allowed.volume().sold24h());
        assertTrue(allowed.mid() < 100);
        assertTrue(service.sellAll(player, scope).soldNothing());
        verify(vault, times(1)).deposit(eq(player), anyDouble());
        verify(eco, never()).countHeld(player, buyOnly.id());
        assertEquals(10, buyOnly.mid());
    }

    @Test
    void buyOnlyUsesTheNormalChargeDeliveryAndMarketImpact() {
        MarketItem allowed = withMode(TradeMode.BUY_ONLY);
        storage.set(new ItemStack[4]);
        ItemStack prototype = mock(ItemStack.class);
        when(prototype.getMaxStackSize()).thenReturn(64);
        when(eco.resolve(eq(item.id()), anyInt())).thenReturn(prototype);
        when(guard.allow(eq(player), eq(TradeSide.BUY), eq(item.id()), anyLong(), anyDouble())).thenReturn(true);
        when(vault.has(eq(player), anyDouble())).thenReturn(true);
        when(vault.withdraw(eq(player), anyDouble())).thenReturn(true);
        double cost = PricingEngine.buyCost(allowed, 64);
        double nextMid = PricingEngine.midAfterBuy(allowed, 64);
        TradeResult result = service.buy(player, item.id(), 64);
        assertTrue(result.ok());
        assertEquals(cost, result.total());
        assertEquals(64, result.filled());
        assertEquals(nextMid, allowed.mid());
        assertEquals(64, allowed.volume().bought24h());
        verify(vault).withdraw(player, cost);
        verify(inventory).addItem(prototype);
        verify(guard).observe(player, TradeSide.BUY, item.id(), 64, cost);
        verify(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
    }

    @Test
    void sellOnlyStillHonorsFreezeGuardAndPaymentRejection() {
        MarketItem allowed = withMode(TradeMode.SELL_ONLY);
        allowed.setFrozen(true);
        assertEquals(TradeResult.Status.DISABLED, service.sell(player, item.id(), 8).status());
        allowed.setFrozen(false);
        when(guard.allow(eq(player), eq(TradeSide.SELL), eq(item.id()), anyLong(), anyDouble())).thenReturn(false);
        assertEquals(TradeResult.Status.REJECTED_BY_GUARD, service.sell(player, item.id(), 8).status());
        when(guard.allow(eq(player), eq(TradeSide.SELL), eq(item.id()), anyLong(), anyDouble())).thenReturn(true);
        ItemStack[] before = storage.get().clone();
        assertEquals(TradeResult.Status.ERROR, service.sell(player, item.id(), 8).status());
        assertArrayEquals(before, storage.get());
        assertEquals(100, allowed.mid());
        assertEquals(0, allowed.volume().sold24h());
    }

    @Test
    void menuQuotesAndGroupRangesExcludeTheUnavailableSide() {
        MarketItem buyOnly = withMode(TradeMode.BUY_ONLY);
        when(vault.formatPrice(anyDouble())).thenAnswer(i -> Double.toString(i.getArgument(0)));
        var ph = service.placeholders(buyOnly, player);
        for (String key : List.of("sell_price", "sell_value_1", "sell_value_64", "sell_value_all")) {
            assertEquals("N/A", ph.get("rbazaar_" + key));
        }
        assertNotEquals("N/A", ph.get("rbazaar_buy_price"));
        MarketItem sellOnly = withMode(TradeMode.SELL_ONLY);
        ph = service.placeholders(sellOnly, player);
        for (String key : List.of("buy_price", "buy_cost_1", "buy_cost_64")) {
            assertEquals("N/A", ph.get("rbazaar_" + key));
        }
        assertNotEquals("N/A", ph.get("rbazaar_sell_price"));
        var cat = mock(com.mystipixel.royalbazaar.config.CategoryConfig.class);
        var group = new com.mystipixel.royalbazaar.config.CategoryConfig.Group("test", "Test", "stone", 0, -1);
        var onlySell = service.groupPlaceholders(cat, group, List.of(sellOnly));
        assertEquals("N/A", onlySell.get("rbazaar_group_min_buy"));
        assertEquals("N/A", onlySell.get("rbazaar_group_max_buy"));
        sellOnly.setMid(1); // must not lower the mixed group's advertised buy range
        var mixed = service.groupPlaceholders(cat, group, List.of(sellOnly, buyOnly));
        assertEquals(Double.toString(PricingEngine.buyPrice(buyOnly)), mixed.get("rbazaar_group_min_buy"));
    }
}
