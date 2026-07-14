package com.mystipixel.royalbazaar.hooks;

import com.willfp.eco.core.items.Items;
import com.willfp.eco.core.items.TestableItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Item resolution via the eco platform. A bazaar item id is an eco lookup key —
 * {@code minecraft:diamond}, {@code ecoitem:enchanted_cobblestone}, etc. eco's {@link Items#lookup}
 * resolves the display stack <em>and</em> matches inventory stacks back to the id (reading the
 * {@code ecoitem:} PDC key, not the material), which is exactly what buy/sell need.
 *
 * <p>Every eco type is touched only after the {@link #present} guard, so if eco is absent the JVM
 * never links {@code com.willfp.*} and this class degrades to vanilla-only.
 */
public final class EcoHook {

    private final boolean present;

    public EcoHook() {
        this.present = Bukkit.getPluginManager().isPluginEnabled("eco");
    }

    public boolean isPresent() {
        return present;
    }

    /** Build a fresh display {@link ItemStack} of {@code amount} for the given id, or null if unknown. */
    public ItemStack resolve(String id, int amount) {
        // Vanilla ids (bare or minecraft:) go straight to Bukkit — eco's lookup doesn't reliably
        // resolve the "minecraft:" namespace, so we must not rely on it for vanilla materials.
        Material vanilla = vanillaMaterial(id);
        if (vanilla != null) {
            return new ItemStack(vanilla, amount);
        }
        if (present) {
            try {
                TestableItem test = Items.lookup(id);
                ItemStack item = test.getItem();
                if (item != null && !item.getType().isAir()) {
                    item = item.clone();
                    item.setAmount(amount);
                    return item;
                }
            } catch (Throwable ignored) {
                // unknown id
            }
        }
        return null;
    }

    /** Does this inventory stack match the bazaar id? Vanilla by material, custom by eco. */
    public boolean matches(String id, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        Material vanilla = vanillaMaterial(id);
        if (vanilla != null) {
            if (stack.getType() != vanilla) {
                return false;
            }
            // A custom eco item can sit on the same material (e.g. enchanted_wheat on WHEAT);
            // don't let it be sold as the plain vanilla item.
            if (present) {
                try {
                    if (Items.isCustomItem(stack)) {
                        return false;
                    }
                } catch (Throwable ignored) {
                    // treat as vanilla
                }
            }
            return true;
        }
        if (present) {
            try {
                return Items.lookup(id).matches(stack);
            } catch (Throwable ignored) {
                // unknown id
            }
        }
        return false;
    }

    /** Count how many matching units the player holds. */
    public int countHeld(Player player, String id) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (matches(id, stack)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * The vanilla {@link Material} for an id that is bare ({@code wheat}) or explicitly
     * {@code minecraft:}-namespaced, or {@code null} if the id is a custom (non-minecraft) namespace
     * or not a known material.
     */
    private Material vanillaMaterial(String id) {
        String raw = id;
        if (id.contains(":")) {
            String ns = id.substring(0, id.indexOf(':'));
            if (!ns.equalsIgnoreCase("minecraft")) {
                return null; // custom namespace (ecoitem:, etc.) — resolve via eco
            }
            raw = id.substring(id.indexOf(':') + 1);
        }
        Material material = Material.matchMaterial(raw);
        return (material != null && !material.isAir()) ? material : null;
    }
}
