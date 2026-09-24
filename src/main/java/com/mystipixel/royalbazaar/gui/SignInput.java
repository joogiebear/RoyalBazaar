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
 * Sign-based text entry (Hypixel-style): a throwaway sign is placed at the player's feet, opened
 * with Paper's {@code openSign} API, and whatever they type on the top line is read back via
 * {@link SignChangeEvent}. The original block is always restored. Uses only official Paper API so
 * it survives version changes better than NMS/packet approaches.
 *
 * The callback runs on the main thread, exactly once per prompt that opened. It receives the typed
 * text, or {@code null} if no answer is coming: the sign could not be placed, or the prompt was
 * abandoned (so callers can fall back).
 *
 * A prompt is abandoned when its answer can no longer arrive: it timed out, the player moved out of
 * the server's sign-edit range or changed world, the sign was broken, or another inventory replaced
 * the editor on their screen. Without that, one lost answer strands the caller's state for good and
 * leaves the sign in the world.
 */
public final class SignInput implements Listener {

    /** How long a prompt may stay open before it is treated as abandoned. */
    private static final long TIMEOUT_MILLIS = 60_000L;
    /** Past roughly this distance the server discards the sign's update, so no answer can come. */
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
        // Drop any earlier prompt still open for this player, taking its sign down too, or the old
        // spot is left as a permanent oak sign with the block it borrowed gone.
        for (Map.Entry<Location, Pending> entry : List.copyOf(pending.entrySet())) {
            if (entry.getValue().player().equals(player.getUniqueId())
                    && pending.remove(entry.getKey(), entry.getValue())) {
                restore(entry.getKey(), entry.getValue());
            }
        }
        // Close the current menu, then open the sign a tick later (opening a sign editor while a
        // chest inventory is open is unreliable otherwise).
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

    /**
     * Where to put the throwaway sign: the player's feet, else the block at their head. Putting the
     * original back only restores block data, not a block entity's contents, so a block with one (a
     * sign's text, a banner's patterns) is never borrowed. Nor is a block another player's prompt is
     * already using, since its "original" would then be that prompt's sign. Air is preferred, so
     * nothing visible changes. {@code null} if neither spot will do.
     */
    private Block signSpot(Player player) {
        Block feet = player.getLocation().getBlock();
        Block head = feet.getRelative(org.bukkit.block.BlockFace.UP);
        Block fallback = null;
        for (Block candidate : List.of(feet, head)) {
            if (pending.containsKey(candidate.getLocation())
                    || candidate.getY() < candidate.getWorld().getMinHeight()
                    || candidate.getY() >= candidate.getWorld().getMaxHeight()
                    || candidate.getState(false) instanceof org.bukkit.block.TileState) {
                continue;
            }
            if (candidate.getType().isAir()) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    /**
     * Put the borrowed block back — but only over our own sign, or the air left where it was broken.
     * Anything else there was placed since, and overwriting it would destroy it (a container's
     * contents with it, since block data carries none).
     */
    private static void restore(Location loc, Pending p) {
        Block block = loc.getBlock();
        Material now = block.getType();
        if (now == Material.OAK_SIGN || now.isAir()) {
            block.setBlockData(p.original(), false);
        }
    }

    /** Give up on a prompt: take the sign down and tell the caller no answer is coming. */
    private void abandon(Location loc, Pending p) {
        if (!pending.remove(loc, p)) {
            return;                                  // answered or cleaned up in the meantime
        }
        restore(loc, p);
        Player player = Bukkit.getPlayer(p.player());
        if (player == null) {
            return;
        }
        // Close a sign editor that may still be on screen (a timeout), but not another plugin's menu
        // that replaced it — the caller decides what to do about that.
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

    /** Abandon every prompt whose answer can no longer arrive. */
    private void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Location, Pending> entry : List.copyOf(pending.entrySet())) {
            Location loc = entry.getKey();
            Pending p = entry.getValue();
            Player player = Bukkit.getPlayer(p.player());
            // Distance before the block lookup, so a player who has walked away does not keep a far
            // chunk loading every half second.
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
        Pending p = pending.remove(loc);
        if (p == null) {
            return;
        }
        event.setCancelled(true);
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

    /**
     * Another inventory opening replaces the sign editor on the client, and the answer it would have
     * sent is gone. Abandoned a tick later, once that inventory is actually open, so the caller can
     * see it.
     */
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

    /**
     * Take every open prompt's sign down. Called on disable: a stop or reload mid-prompt would
     * otherwise leave the sign in the world for good, and the block it borrowed with it.
     */
    public void shutdown() {
        for (Map.Entry<Location, Pending> entry : List.copyOf(pending.entrySet())) {
            if (pending.remove(entry.getKey(), entry.getValue())) {
                restore(entry.getKey(), entry.getValue());
            }
        }
    }
}
