package com.mystipixel.royalbazaar.service;

import com.mystipixel.royalbazaar.config.PluginConfig;
import com.mystipixel.royalbazaar.data.BazaarDatabase;
import com.mystipixel.royalbazaar.hooks.EconGuardHook;
import com.mystipixel.royalbazaar.hooks.EcoHook;
import com.mystipixel.royalbazaar.hooks.VaultHook;
import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.market.PricingEngine;
import com.mystipixel.royalbazaar.market.TradeResult;
import com.mystipixel.royalbazaar.market.TradeSide;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;

/**
 * The buy/sell transaction flow. Everything here runs on the main thread, so the price mutation,
 * balance move and inventory change are effectively atomic (no locks, no partial trades). The DB
 * audit write is dispatched async.
 *
 * <p>Order of operations mirrors the design doc: resolve → quote → EconGuard veto → funds/items
 * check → move money → move items → apply market impact → record.
 */
public final class BazaarService {

    private final JavaPlugin plugin;
    private final MarketManager market;
    private final BazaarDatabase db;
    private final VaultHook vault;
    private final EcoHook eco;
    private final EconGuardHook guard;
    private final PluginConfig config;

    public BazaarService(JavaPlugin plugin, MarketManager market, BazaarDatabase db, VaultHook vault,
                         EcoHook eco, EconGuardHook guard, PluginConfig config) {
        this.plugin = plugin;
        this.market = market;
        this.db = db;
        this.vault = vault;
        this.eco = eco;
        this.guard = guard;
        this.config = config;
    }

    // ------------------------------------------------------------------ buy

    public TradeResult buy(Player player, String itemId, long amount) {
        MarketItem item = market.get(itemId);
        if (item == null) {
            return TradeResult.fail(TradeResult.Status.UNKNOWN_ITEM, TradeSide.BUY, itemId, "Unknown item.");
        }
        if (amount <= 0) {
            return TradeResult.fail(TradeResult.Status.ERROR, TradeSide.BUY, itemId, "Invalid amount.");
        }

        double cost = PricingEngine.buyCost(item, amount);
        if (!guard.allow(player, TradeSide.BUY, itemId, amount, cost)) {
            return TradeResult.fail(TradeResult.Status.REJECTED_BY_GUARD, TradeSide.BUY, itemId, "Trade blocked.");
        }
        if (!vault.has(player, cost)) {
            return TradeResult.fail(TradeResult.Status.INSUFFICIENT_FUNDS, TradeSide.BUY, itemId, "Not enough money.");
        }

        // How many can actually fit? Policy decides what happens to the remainder.
        int fits = spaceFor(player, itemId, (int) Math.min(amount, Integer.MAX_VALUE));
        long fill = amount;
        if (fits < amount) {
            switch (config.inventoryFullPolicy()) {
                case "partial" -> fill = fits;
                case "drop" -> { /* give all; overflow dropped below */ }
                default -> {
                    return TradeResult.fail(TradeResult.Status.INVENTORY_FULL, TradeSide.BUY, itemId,
                            "Not enough inventory space.");
                }
            }
        }
        if (fill <= 0) {
            return TradeResult.fail(TradeResult.Status.INVENTORY_FULL, TradeSide.BUY, itemId, "Inventory full.");
        }

        double finalCost = fill == amount ? cost : PricingEngine.buyCost(item, fill);
        if (!vault.withdraw(player, finalCost)) {
            return TradeResult.fail(TradeResult.Status.INSUFFICIENT_FUNDS, TradeSide.BUY, itemId, "Payment failed.");
        }

        giveItems(player, itemId, (int) fill);
        item.setMid(PricingEngine.midAfterBuy(item, fill));
        item.volume().recordBuy(fill);
        record(player, itemId, TradeSide.BUY, fill, item.mid(), finalCost);
        return TradeResult.ok(TradeSide.BUY, itemId, fill, finalCost);
    }

    // ------------------------------------------------------------------ sell

    public TradeResult sell(Player player, String itemId, long amount) {
        MarketItem item = market.get(itemId);
        if (item == null) {
            return TradeResult.fail(TradeResult.Status.UNKNOWN_ITEM, TradeSide.SELL, itemId, "Unknown item.");
        }
        int held = eco.countHeld(player, itemId);
        if (held <= 0) {
            return TradeResult.fail(TradeResult.Status.INSUFFICIENT_ITEMS, TradeSide.SELL, itemId, "You have none to sell.");
        }
        long fill = Math.min(amount, held); // amount == Long.MAX_VALUE for "sell all"
        double proceeds = PricingEngine.sellProceeds(item, fill);

        if (!guard.allow(player, TradeSide.SELL, itemId, fill, proceeds)) {
            return TradeResult.fail(TradeResult.Status.REJECTED_BY_GUARD, TradeSide.SELL, itemId, "Trade blocked.");
        }

        removeItems(player, itemId, (int) fill);
        vault.deposit(player, proceeds);
        item.setMid(PricingEngine.midAfterSell(item, fill));
        item.volume().recordSell(fill);
        record(player, itemId, TradeSide.SELL, fill, item.mid(), proceeds);
        return TradeResult.ok(TradeSide.SELL, itemId, fill, proceeds);
    }

    // ------------------------------------------------------------------ inventory helpers

    /** Free capacity for this item across empty + partially-filled matching stacks. */
    private int spaceFor(Player player, String itemId, int wanted) {
        int max = eco.resolve(itemId, 1).getMaxStackSize();
        int space = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                space += max;
            } else if (eco.matches(itemId, stack)) {
                space += Math.max(0, max - stack.getAmount());
            }
            if (space >= wanted) {
                return wanted;
            }
        }
        return space;
    }

    private void giveItems(Player player, String itemId, int amount) {
        int max = eco.resolve(itemId, 1).getMaxStackSize();
        int remaining = amount;
        while (remaining > 0) {
            int stackSize = Math.min(max, remaining);
            ItemStack stack = eco.resolve(itemId, stackSize);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            remaining -= stackSize;
        }
    }

    private void removeItems(Player player, String itemId, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (!eco.matches(itemId, stack)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            remaining -= take;
            if (stack.getAmount() <= 0) {
                contents[i] = null;
            }
        }
        player.getInventory().setStorageContents(contents);
    }

    // ------------------------------------------------------------------ audit

    private void record(Player player, String itemId, TradeSide side, long qty, double unitMid, double total) {
        guard.observe(player, side, itemId, qty, total);
        long ts = System.currentTimeMillis();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> db.logTransaction(player.getUniqueId(), itemId, side, qty, unitMid, total, ts));
    }

    // ------------------------------------------------------------------ quote helpers (menus / commands)

    public Map<String, String> placeholders(MarketItem item, Player viewer) {
        Map<String, String> p = new HashMap<>();
        p.put("rbazaar_item", item.id());
        p.put("rbazaar_item_display", displayNameFor(item));
        p.put("rbazaar_buy_price", vault.format(PricingEngine.buyPrice(item)));
        p.put("rbazaar_sell_price", vault.format(PricingEngine.sellPrice(item)));
        p.put("rbazaar_spread_pct", String.format("%.1f", item.spread() * 100));
        p.put("rbazaar_buy_cost_1", vault.format(PricingEngine.buyCost(item, 1)));
        p.put("rbazaar_buy_cost_64", vault.format(PricingEngine.buyCost(item, 64)));
        p.put("rbazaar_sell_value_1", vault.format(PricingEngine.sellProceeds(item, 1)));
        p.put("rbazaar_sell_value_64", vault.format(PricingEngine.sellProceeds(item, 64)));
        p.put("rbazaar_volume_24h", String.valueOf(item.volume().bought24h() + item.volume().sold24h()));
        p.put("rbazaar_change_24h", changePct(item));
        p.put("rbazaar_trend", trendArrow(item));
        if (viewer != null) {
            int held = eco.countHeld(viewer, item.id());
            p.put("rbazaar_held_amount", String.valueOf(held));
            p.put("rbazaar_sell_value_all", vault.format(PricingEngine.sellProceeds(item, Math.max(1, held))));
        }
        return p;
    }

    /** Config {@code display:} override, else the resolved item's own name, else a prettified id. */
    private String displayNameFor(MarketItem item) {
        if (item.displayName() != null && !item.displayName().isBlank()) {
            return item.displayName();
        }
        ItemStack stack = eco.resolve(item.id(), 1);
        if (stack != null && stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
            String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(stack.getItemMeta().displayName());
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        String raw = item.id().contains(":") ? item.id().substring(item.id().indexOf(':') + 1) : item.id();
        String[] words = raw.replace('_', ' ').trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    private String changePct(MarketItem item) {
        if (item.midYesterday() <= 0) {
            return "0.0%";
        }
        double pct = (item.mid() - item.midYesterday()) / item.midYesterday() * 100.0;
        return String.format("%s%.1f%%", pct >= 0 ? "&a+" : "&c", pct);
    }

    private String trendArrow(MarketItem item) {
        double d = item.mid() - item.emaShort();
        double eps = item.emaShort() * 0.001;
        if (d > eps) {
            return "&a▲";
        }
        if (d < -eps) {
            return "&c▼";
        }
        return "&7▬";
    }
}
