package com.mystipixel.royalbazaar.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * Routes clicks in a bazaar inventory to the effects bound on that slot. Any click inside a tracked
 * view is cancelled (the menus are read-only display surfaces), so items can never be extracted.
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
        OpenView view = gui.viewOf(player);
        if (view == null) {
            return;
        }
        // Any interaction with a bazaar menu is display-only.
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
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
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            gui.forget(player);
        }
    }
}
