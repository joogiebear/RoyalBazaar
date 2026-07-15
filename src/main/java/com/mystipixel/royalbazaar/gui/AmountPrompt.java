package com.mystipixel.royalbazaar.gui;

import com.mystipixel.royalbazaar.market.TradeResult;
import com.mystipixel.royalbazaar.message.MessageManager;
import com.mystipixel.royalbazaar.service.BazaarService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the "custom amount" flow: close the menu, ask the player to type a number in chat, then run
 * the trade and reopen the product menu. Chat runs off-thread on Paper, so the actual trade is bounced
 * back to the main thread.
 */
public final class AmountPrompt implements Listener {

    private record Pending(String itemId, boolean buy) {
    }

    private final JavaPlugin plugin;
    private final BazaarService service;
    private final GuiManager gui;
    private final MessageManager messages;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public AmountPrompt(JavaPlugin plugin, BazaarService service, GuiManager gui, MessageManager messages) {
        this.plugin = plugin;
        this.service = service;
        this.gui = gui;
        this.messages = messages;
    }

    public void begin(Player player, String itemId, boolean buy) {
        if (itemId == null) {
            return;
        }
        pending.put(player.getUniqueId(), new Pending(itemId, buy));
        player.closeInventory();
        messages.send(player, "prompt-amount", "&eType an amount in chat &7(or 'cancel')&e.");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Pending p = pending.remove(player.getUniqueId());
        if (p == null) {
            return;
        }
        event.setCancelled(true);
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (raw.equalsIgnoreCase("cancel")) {
            messages.send(player, "cancelled", "&7Cancelled.");
            plugin.getServer().getScheduler().runTask(plugin, () -> gui.openProduct(player, p.itemId()));
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            messages.send(player, "not-a-number", "&cNot a number.");
            return;
        }
        if (amount <= 0) {
            messages.send(player, "amount-positive", "&cAmount must be positive.");
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            TradeResult r = p.buy() ? service.buy(player, p.itemId(), amount) : service.sell(player, p.itemId(), amount);
            if (r.ok()) {
                messages.send(player, "trade.done", "&aDone: &f{amount} &7for &e${total}",
                        java.util.Map.of("amount", String.valueOf(r.filled()),
                                "total", String.format("%,.2f", r.total())));
            } else {
                messages.send(player, "trade.failed", "&c{reason}",
                        java.util.Map.of("reason", r.message() == null ? "Trade failed." : r.message()));
            }
            gui.openProduct(player, p.itemId());
        });
    }
}
