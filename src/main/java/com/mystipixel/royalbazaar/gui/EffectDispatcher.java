package com.mystipixel.royalbazaar.gui;

import com.mystipixel.royalbazaar.gui.menu.MenuEffect;
import com.mystipixel.royalbazaar.market.TradeResult;
import com.mystipixel.royalbazaar.market.TradeSide;
import com.mystipixel.royalbazaar.message.MessageManager;
import com.mystipixel.royalbazaar.service.BazaarService;
import com.mystipixel.royalbazaar.util.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Runs the effect list bound to a clicked slot. Covers the EcoMenus-style effects the bazaar menus
 * use ({@code open_menu}, {@code close_inventory}, {@code play_sound}, {@code send_message}) plus the
 * RoyalBazaar effects ({@code rbazaar_open_product}, {@code rbazaar_buy}, {@code rbazaar_sell},
 * {@code rbazaar_buy_prompt}, page nav). Unknown ids are ignored so authors can't crash a menu.
 */
public final class EffectDispatcher {

    private final GuiManager gui;
    private final BazaarService service;
    private final AmountPrompt prompt;
    private final MessageManager messages;

    public EffectDispatcher(GuiManager gui, BazaarService service, AmountPrompt prompt, MessageManager messages) {
        this.gui = gui;
        this.service = service;
        this.prompt = prompt;
        this.messages = messages;
    }

    public void run(Player player, List<MenuEffect> effects) {
        if (effects == null) {
            return;
        }
        for (MenuEffect effect : effects) {
            dispatch(player, effect);
        }
    }

    private void dispatch(Player player, MenuEffect e) {
        switch (e.id().toLowerCase(Locale.ROOT)) {
            case "close_inventory" -> player.closeInventory();
            case "open_menu" -> openMenu(player, e.argString("menu", "bazaar_main"), e.argString("category", null));
            case "play_sound" -> playSound(player, e);
            case "send_message" -> player.sendMessage(Text.chat(e.argString("message", "")));

            case "rbazaar_open_product" -> gui.openProduct(player, e.argString("item", null));
            case "rbazaar_buy" -> trade(player, e, true);
            case "rbazaar_sell" -> trade(player, e, false);
            case "rbazaar_buy_prompt" -> prompt.begin(player, e.argString("item", null), true);
            case "rbazaar_sell_prompt" -> prompt.begin(player, e.argString("item", null), false);

            case "rbazaar_next_page" -> gui.openCategory(player, current(player), page(player) + 1);
            case "rbazaar_prev_page" -> gui.openCategory(player, current(player), Math.max(1, page(player) - 1));

            default -> { /* unknown effect id — ignore */ }
        }
    }

    private void trade(Player player, MenuEffect e, boolean buy) {
        String item = e.argString("item", null);
        if (item == null) {
            return;
        }
        String amountArg = e.argString("amount", "1");
        long amount = "all".equalsIgnoreCase(amountArg) ? Long.MAX_VALUE : e.argLong("amount", 1);
        TradeResult result = buy ? service.buy(player, item, amount) : service.sell(player, item, amount);
        sendFeedback(player, result);
        gui.refresh(player); // prices moved — re-render
    }

    private void openMenu(Player player, String menu, String category) {
        switch (menu) {
            case "bazaar_main" -> gui.openMain(player);
            case "bazaar_category" -> gui.openCategory(player, category, 1);
            default -> gui.openMain(player);
        }
    }

    private void playSound(Player player, MenuEffect e) {
        try {
            Sound sound = Sound.valueOf(e.argString("sound", "UI_BUTTON_CLICK").toUpperCase(Locale.ROOT));
            float pitch = (float) toDouble(e.argString("pitch", "1"), 1.0);
            float volume = (float) toDouble(e.argString("volume", "1"), 1.0);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ignored) {
            // unknown sound name — skip
        }
    }

    private void sendFeedback(Player player, TradeResult r) {
        if (r.ok()) {
            boolean bought = r.side() == TradeSide.BUY;
            messages.send(player, bought ? "trade.bought" : "trade.sold",
                    bought ? "&aBought &f{amount} &afor &e${total}" : "&aSold &f{amount} &afor &e${total}",
                    java.util.Map.of("amount", String.valueOf(r.filled()),
                            "total", String.format("%,.2f", r.total())));
        } else {
            messages.send(player, "trade.failed", "&c{reason}",
                    java.util.Map.of("reason", r.message() == null ? "Trade failed." : r.message()));
        }
    }

    private String current(Player player) {
        OpenView v = gui.viewOf(player);
        return v == null ? null : v.categoryId();
    }

    private int page(Player player) {
        OpenView v = gui.viewOf(player);
        return v == null ? 1 : v.page();
    }

    private double toDouble(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
