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
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Sign-based text entry: a throwaway sign at the player's feet, opened with Paper's {@code openSign},
 * top line read back via {@link SignChangeEvent}; the original block is restored.
 *
 * <p>The callback runs on the main thread exactly once per opened prompt, with the typed text or
 * {@code null} when no answer can arrive (timeout, out of range, sign broken, another inventory opened).
 */
public final class SignInput implements Listener {

    private static final long TIMEOUT_MILLIS = 60_000L;
    // past roughly this distance the server discards the sign's update
    private static final double MAX_DISTANCE_SQUARED = 8.0 * 8.0;

    private record Pending(UUID player, BlockData original, Consumer<String> callback, long deadline) {
    }

    private final JavaPlugin plugin;
    private final Map<Location, Pending> pending = new ConcurrentHashMap<>();

    public SignInput(JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 10L, 10L);
    }

    /** Open a sign editor for {@code player}. {@code hints} fill lines 2-4 (the input is line 1). */
    public void request(Player player, List<String> hints, Consumer<String> callback) {
        // drop any earlier prompt for this player, taking its sign down too
        for (Map.Entry<Location, Pending> entry : List.copyOf(pending.entrySet())) {
            if (entry.getValue().player().equals(player.getUniqueId())
                    && pending.remove(entry.getKey(), entry.getValue())) {
                restore(entry.getKey(), entry.getValue());
            }
        }
        // open the sign a tick after closing the menu; opening it over a chest inventory is unreliable
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> openNow(player, hints, callback));
    }

    private void openNow(Player player, List<String> hints, Consumer<String> callback) {
        if (!player.isOnline()) {
            return;
        }
        Block block = signSpot(player);
        if (block == null) {
            callback.accept(null);
            return;
        }
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
        pending.put(loc, new Pending(player.getUniqueId(), original, callback,
                System.currentTimeMillis() + TIMEOUT_MILLIS));
        player.openSign(sign, Side.FRONT);
    }

    // only air or water is borrowed (no place event fires, so protection plugins never see it), and
    // never a spot another prompt is using
    private Block signSpot(Player player) {
        Block feet = player.getLocation().getBlock();
        Block head = feet.getRelative(org.bukkit.block.BlockFace.UP);
        Block above = head.getRelative(org.bukkit.block.BlockFace.UP);
        for (Block candidate : List.of(feet, head, above)) {
            if (pending.containsKey(candidate.getLocation())
                    || candidate.getY() < candidate.getWorld().getMinHeight()
                    || candidate.getY() >= candidate.getWorld().getMaxHeight()) {
                continue;
            }
            if (candidate.getType().isAir() || candidate.getType() == Material.WATER) {
                return candidate;
            }
        }
        return null;
    }

    // only over our sign or the air where it was broken; anything else was placed since
    private static void restore(Location loc, Pending p) {
        Block block = loc.getBlock();
        Material now = block.getType();
        if (now == Material.OAK_SIGN || now.isAir()) {
            block.setBlockData(p.original(), false);
        }
    }

    private void abandon(Location loc, Pending p) {
        if (!pending.remove(loc, p)) {
            return;                                  // answered or cleaned up in the meantime
        }
        restore(loc, p);
        Player player = Bukkit.getPlayer(p.player());
        if (player == null) {
            return;
        }
        // close a lingering sign editor, but not another plugin's menu that replaced it
        if (showingOwnInventory(player)) {
            player.closeInventory();
        }
        p.callback().accept(null);
    }

    /** True when nothing but the player's own inventory is open, i.e. no other menu is on screen. */
    public static boolean showingOwnInventory(Player player) {
        InventoryType type = player.getOpenInventory().getTopInventory().getType();
        return type == InventoryType.CRAFTING || type == InventoryType.CREATIVE;
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Location, Pending> entry : List.copyOf(pending.entrySet())) {
            Location loc = entry.getKey();
            Pending p = entry.getValue();
            Player player = Bukkit.getPlayer(p.player());
            // distance before the block lookup, so a far chunk isn't loaded every half second
            if (player == null
                    || now > p.deadline()
                    || !player.getWorld().equals(loc.getWorld())
                    || player.getLocation().distanceSquared(loc.clone().add(0.5, 0.5, 0.5))
                            > MAX_DISTANCE_SQUARED
                    || loc.getBlock().getType() != Material.OAK_SIGN) {
                abandon(loc, p);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        Location loc = event.getBlock().getLocation();
        Pending p = pending.get(loc);
        if (p == null) {
            return;
        }
        // our sign either way, so never let the edit through; only its owner's answer counts
        event.setCancelled(true);
        if (!event.getPlayer().getUniqueId().equals(p.player()) || !pending.remove(loc, p)) {
            return;
        }
        String input = PlainTextComponentSerializer.plainText().serialize(event.line(0)).trim();
        plugin.getLogger().fine("[sign-input] received from " + p.player() + ": '" + input + "'");
        Bukkit.getScheduler().runTask(plugin, () -> {
            restore(loc, p);
            Player player = Bukkit.getPlayer(p.player());
            if (player != null) {
                p.callback().accept(input);
            }
        });
    }

    // another inventory replaces the sign editor; abandon a tick later, once it is actually open
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (Map.Entry<Location, Pending> entry : List.copyOf(pending.entrySet())) {
            if (entry.getValue().player().equals(id)) {
                Bukkit.getScheduler().runTask(plugin, () -> abandon(entry.getKey(), entry.getValue()));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.entrySet().removeIf(entry -> {
            if (entry.getValue().player().equals(id)) {
                restore(entry.getKey(), entry.getValue());
                return true;
            }
            return false;
        });
    }

    /** Take every open prompt's sign down. Call on disable. */
    public void shutdown() {
        for (Map.Entry<Location, Pending> entry : List.copyOf(pending.entrySet())) {
            if (pending.remove(entry.getKey(), entry.getValue())) {
                restore(entry.getKey(), entry.getValue());
            }
        }
    }
}
