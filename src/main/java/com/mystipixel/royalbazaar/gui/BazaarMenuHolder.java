package com.mystipixel.royalbazaar.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marks an inventory as a bazaar menu. Click handling checks this, not only the per-player view map,
 * because the map can go stale and an untracked menu would otherwise act as a real chest.
 */
public final class BazaarMenuHolder implements InventoryHolder {

    private Inventory inventory;

    private BazaarMenuHolder() {
    }

    public static Inventory create(int size, Component title) {
        BazaarMenuHolder holder = new BazaarMenuHolder();
        holder.inventory = Bukkit.createInventory(holder, size, title);
        return holder.inventory;
    }

    public static boolean isMenu(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof BazaarMenuHolder;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
