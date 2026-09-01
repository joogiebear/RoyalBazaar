package com.mystipixel.royalbazaar.gui;

import com.mystipixel.royalbazaar.market.TradeResult;
import com.mystipixel.royalbazaar.message.MessageManager;
import com.mystipixel.royalbazaar.service.BazaarService;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * The "custom amount" flow: ask for a number on a throwaway sign — the same input the search box and
 * the instant-buy screen already use — then run the trade and reopen the product page.
 *
 * <p>This used to prompt in chat. A sign is better on every axis the suite cares about: the amount
 * stays private instead of appearing in public chat, the player is never stranded staring at a chat
 * box with no menu, and {@link SignInput}'s callback arrives on the main thread so the trade runs
 * inline with no scheduling hop.
 */
public final class AmountPrompt {

    private final BazaarService service;
    private final GuiManager gui;
    private final MessageManager messages;
    private final SignInput signInput;

    public AmountPrompt(BazaarService service, GuiManager gui, MessageManager messages, SignInput signInput) {
        this.service = service;
        this.gui = gui;
        this.messages = messages;
        this.signInput = signInput;
    }

    public void begin(Player player, String itemId, boolean buy) {
        if (itemId == null) {
            return;
        }
        signInput.request(player,
                List.of("&8^^^^^^^^^^^^^^^", buy ? "&8Amount to buy" : "&8Amount to sell", "&8(or 'cancel')"),
                typed -> finish(player, itemId, buy, typed));
    }

    /** Runs on the main thread with what came off the sign (null when it could not be opened). */
    private void finish(Player player, String itemId, boolean buy, String typed) {
        if (typed == null || typed.isBlank() || typed.equalsIgnoreCase("cancel")) {
            gui.openProduct(player, itemId);      // cancelled — back where they were
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(typed.replace(",", "").trim());
        } catch (NumberFormatException e) {
            messages.send(player, "not-a-number", "&cNot a number.");
            gui.openProduct(player, itemId);
            return;
        }
        if (amount <= 0) {
            messages.send(player, "amount-positive", "&cAmount must be positive.");
            gui.openProduct(player, itemId);
            return;
        }
        TradeResult r = buy ? service.buy(player, itemId, amount) : service.sell(player, itemId, amount);
        if (r.ok()) {
            messages.send(player, "trade.done", "&aDone: &f{amount} &7for &e${total}",
                    Map.of("amount", String.valueOf(r.filled()),
                            "total", String.format("%,.2f", r.total())));
        } else {
            messages.send(player, "trade.failed", "&c{reason}",
                    Map.of("reason", r.message() == null ? "Trade failed." : r.message()));
        }
        gui.openProduct(player, itemId);
    }
}
