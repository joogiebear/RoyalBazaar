package com.mystipixel.royalbazaar.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/**
 * Routes clicks in a bazaar inventory to the effects bound on that slot. Any click while a bazaar menu
 * is on screen is cancelled (the menus are read-only display surfaces), so items can never be
 * extracted. "On screen" is decided by the inventory's {@link BazaarMenuHolder}, not the view map alone.
 */
public final class BazaarGuiListener implements Listener {

    private final GuiManager gui;
    private final EffectDispatcher dispatcher;

    public BazaarGuiListener(GuiManager gui, EffectDispatcher dispatcher) {
        this.gui = gui;
        this.dispatcher = dispatcher;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        OpenView view = gui.viewOf(player);
        if (!BazaarMenuHolder.isMenu(top)) {
            // A view with no bazaar menu on screen is stale (e.g. another plugin cancelled the open).
            // Honouring it would cancel the player's own inventory clicks and run buttons on them.
            if (view != null) {
                gui.forget(player);
            }
            return;
        }
        // Any interaction with a bazaar menu is display-only.
        event.setCancelled(true);
        if (view == null || event.getClickedInventory() != top) {
            return;
        }
        int slot = event.getRawSlot();
        if (event.isRightClick()) {
            dispatcher.run(player, view.right(slot));
        } else if (event.isLeftClick()) {
            dispatcher.run(player, view.left(slot));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (BazaarMenuHolder.isMenu(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            gui.forget(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gui.forget(event.getPlayer());
    }
}
