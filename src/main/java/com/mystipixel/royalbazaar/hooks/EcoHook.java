package com.mystipixel.royalbazaar.hooks;

import com.willfp.eco.core.items.Items;
import com.willfp.eco.core.items.TestableItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Item resolution. A bazaar id is an eco lookup key ({@code minecraft:diamond},
 * {@code ecoitem:enchanted_cobblestone}). eco types are only touched behind the {@link #present} guard,
 * so without eco the JVM never links {@code com.willfp.*} and this works vanilla-only.
 */
public final class EcoHook {

    private final boolean present;

    public EcoHook() {
        this.present = Bukkit.getPluginManager().isPluginEnabled("eco");
    }

    public boolean isPresent() {
        return present;
    }

    /** A fresh display stack for the id, or null if unknown. */
    public ItemStack resolve(String id, int amount) {
        // vanilla ids go straight to Bukkit: eco's lookup doesn't reliably resolve minecraft:
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

    /** Vanilla ids match by material (plain stacks only), custom ids by eco. */
    public boolean matches(String id, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        Material vanilla = vanillaMaterial(id);
        if (vanilla != null) {
            if (stack.getType() != vanilla || !isPlain(stack)) {
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

    // a vanilla listing prices the plain item, so sell must never take an enchanted, damaged,
    // renamed or plugin-tagged stack at that price
    static boolean isPlain(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return true;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta.hasDisplayName() || meta.hasItemName() || meta.hasLore() || meta.hasEnchants()
                || meta.hasCustomModelData() || !meta.getPersistentDataContainer().isEmpty()) {
            return false;
        }
        if (meta instanceof Damageable damageable && damageable.hasDamage()) {
            return false;
        }
        if (meta instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchants()) {
            return false;
        }
        if (meta instanceof ArmorMeta armor && armor.hasTrim()) {
            return false;
        }
        return !(meta instanceof BlockStateMeta blockState && blockState.hasBlockState());
    }

    public int countHeld(Player player, String id) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (matches(id, stack)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private Material vanillaMaterial(String id) {
        String raw = id;
        if (id.contains(":")) {
            String ns = id.substring(0, id.indexOf(':'));
            if (!ns.equalsIgnoreCase("minecraft")) {
                return null; // custom namespace, resolved via eco
            }
            raw = id.substring(id.indexOf(':') + 1);
        }
        Material material = Material.matchMaterial(raw);
        return (material != null && !material.isAir()) ? material : null;
    }
}
