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
    private final AtomicReference<ItemStack[]> storage = new AtomicReference<>();
    private final ItemMeta customMetadata = mock(ItemMeta.class);
    private final MarketItem item = new MarketItem("ecoitem:test", "test", null, "Custom item",
            100, 0.05, 1000, 0.01, 1, 1000);
    private BazaarService service;

    @BeforeEach
    void setup() {
        service = new BazaarService(plugin, market, db, vault, eco, guard, mock(PluginConfig.class));
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
}
