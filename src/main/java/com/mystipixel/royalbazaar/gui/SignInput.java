package com.mystipixel.royalbazaar.gui;

import com.mystipixel.royalbazaar.util.Text;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Sign-based text entry (Hypixel-style): a throwaway sign is placed at the player's feet, opened
 * with Paper's {@code openSign} API, and whatever they type on the top line is read back via
 * {@link SignChangeEvent}. The original block is always restored. Uses only official Paper API so
 * it survives version changes better than NMS/packet approaches.
 *
 * The callback runs on the main thread. It receives the typed text, or {@code null} if the sign
 * could not be opened (so callers can fall back).
 */
public final class SignInput implements Listener {

    private record Pending(UUID player, BlockData original, Consumer<String> callback) {
    }

    private final JavaPlugin plugin;
    private final Map<Location, Pending> pending = new ConcurrentHashMap<>();

    public SignInput(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Open a sign editor for {@code player}. {@code hints} fill lines 2-4 (the input is line 1). */
    public void request(Player player, List<String> hints, Consumer<String> callback) {
        // Drop any earlier prompt still open for this player.
        pending.values().removeIf(p -> p.player().equals(player.getUniqueId()));
        // Close the current menu, then open the sign a tick later (opening a sign editor while a
        // chest inventory is open is unreliable otherwise).
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> openNow(player, hints, callback));
    }

    private void openNow(Player player, List<String> hints, Consumer<String> callback) {
        if (!player.isOnline()) {
            return;
        }
        Block block = player.getLocation().getBlock();
        Location loc = block.getLocation();
        BlockData original = block.getBlockData();
        block.setType(Material.OAK_SIGN, false);
        if (!(block.getState() instanceof Sign sign)) {
            block.setBlockData(original, false);
            callback.accept(null);
            return;
        }
        for (int i = 0; i < hints.size() && i < 3; i++) {
            sign.getSide(Side.FRONT).line(i + 1, Text.chat(hints.get(i)));
        }
        sign.update(true, false);
        pending.put(loc, new Pending(player.getUniqueId(), original, callback));
        player.openSign(sign, Side.FRONT);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        Pending p = pending.remove(event.getBlock().getLocation());
        if (p == null) {
            return;
        }
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.line(0)).trim();
        plugin.getLogger().fine("[sign-input] received from " + p.player() + ": '" + input + "'");
        Block block = event.getBlock();
        Bukkit.getScheduler().runTask(plugin, () -> {
            block.setBlockData(p.original(), false);
            Player player = Bukkit.getPlayer(p.player());
            if (player != null) {
                p.callback().accept(input);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.entrySet().removeIf(entry -> {
            if (entry.getValue().player().equals(id)) {
                entry.getKey().getBlock().setBlockData(entry.getValue().original(), false);
                return true;
            }
            return false;
        });
    }
}
