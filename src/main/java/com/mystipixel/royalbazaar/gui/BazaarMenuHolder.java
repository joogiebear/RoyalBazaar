package com.mystipixel.royalbazaar.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marks an inventory as a bazaar menu. Click handling keys off this rather than only the per-player
 * view map: the map can go stale (another plugin cancelling the open, a disable mid-session), and a
 * menu it has lost track of would otherwise be a chest of real item stacks.
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
