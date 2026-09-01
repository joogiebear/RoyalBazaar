package com.mystipixel.royalbazaar.command;

import com.mystipixel.royalbazaar.RoyalBazaarPlugin;
import com.mystipixel.royalbazaar.gui.GuiManager;
import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.market.PricingEngine;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** {@code /bazaar} — open the bazaar, reload config, or inspect an item's live price. */
public final class BazaarCommand implements CommandExecutor, TabCompleter {

    private final RoyalBazaarPlugin plugin;
    private final GuiManager gui;
    private final MarketManager market;

    public BazaarCommand(RoyalBazaarPlugin plugin, GuiManager gui, MarketManager market) {
        this.plugin = plugin;
        this.gui = gui;
        this.market = market;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                gui.openDefault(player);
            } else {
                plugin.messages().send(sender, "players-only", "Only players can open the bazaar.");
            }
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                if (!sender.hasPermission("royalbazaar.admin")) {
                    plugin.messages().send(sender, "no-permission", "&cNo permission.");
                    return true;
                }
                plugin.reloadEverything();
                plugin.messages().send(sender, "reloaded", "&aRoyalBazaar reloaded.");
            }
            case "price" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "price-usage", "&cUsage: /bazaar price <item>");
                    return true;
                }
                MarketItem item = market.get(args[1]);
                if (item == null) {
                    plugin.messages().send(sender, "unknown-item", "&cUnknown item: {item}",
                            java.util.Map.of("item", args[1]));
                    return true;
                }
                plugin.messages().send(sender, "price-line",
                        "&e{item}&7: buy &a${buy} &7sell &e${sell} &7(mid {mid})",
                        java.util.Map.of(
                                "item", item.id(),
                                "buy", fmt(PricingEngine.buyPrice(item)),
                                "sell", fmt(PricingEngine.sellPrice(item)),
                                "mid", fmt(item.mid())));
            }
            case "admin" -> admin(sender, args);
            default -> {
                if (sender instanceof Player player) {
                    gui.openDefault(player);
                }
            }
        }
        return true;
    }

    /**
     * Incident tooling: pin, freeze or reset a price without touching the database by hand.
     * {@code set}/{@code reset} go through {@code setMid}, so the change is flagged dirty and
     * persisted by the normal write-behind flush; {@code freeze} is deliberately in-memory only
     * (it clears on restart) so a forgotten freeze cannot quietly outlive its incident.
     */
    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("royalbazaar.admin")) {
            plugin.messages().send(sender, "no-permission", "&cNo permission.");
            return;
        }
        if (args.length < 3) {
            plugin.messages().send(sender, "admin-usage",
                    "&cUsage: /bazaar admin <set|freeze|unfreeze|reset> <item> [mid]");
            return;
        }
        MarketItem item = market.get(args[2]);
        if (item == null) {
            plugin.messages().send(sender, "unknown-item", "&cUnknown item: {item}",
                    java.util.Map.of("item", args[2]));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "set" -> {
                if (args.length < 4) {
                    plugin.messages().send(sender, "admin-usage",
                            "&cUsage: /bazaar admin set <item> <mid>");
                    return;
                }
                double mid;
                try {
                    mid = Double.parseDouble(args[3].replace(",", ""));
                } catch (NumberFormatException bad) {
                    plugin.messages().send(sender, "not-a-number", "&cNot a number.");
                    return;
                }
                if (!Double.isFinite(mid) || mid <= 0) {
                    plugin.messages().send(sender, "not-a-number", "&cThe mid must be a positive number.");
                    return;
                }
                double clamped = PricingEngine.clamp(mid, item.floor(), item.ceiling());
                double old = item.mid();
                item.setMid(clamped);
                plugin.messages().send(sender, "admin-set",
                        "&aSet &f{item}&a mid: &e{old} &7-> &e{new}" + (clamped != mid
                                ? " &7(clamped to this item's floor/ceiling)" : ""),
                        java.util.Map.of("item", item.id(), "old", fmt(old), "new", fmt(clamped)));
            }
            case "reset" -> {
                double old = item.mid();
                item.setMid(PricingEngine.clamp(item.basePrice(), item.floor(), item.ceiling()));
                plugin.messages().send(sender, "admin-reset",
                        "&aReset &f{item}&a to its base price: &e{old} &7-> &e{new}",
                        java.util.Map.of("item", item.id(), "old", fmt(old), "new", fmt(item.mid())));
            }
            case "freeze" -> {
                item.setFrozen(true);
                plugin.messages().send(sender, "admin-freeze",
                        "&eFroze &f{item}&e: trades refused, price held. Clears on unfreeze or restart.",
                        java.util.Map.of("item", item.id()));
            }
            case "unfreeze" -> {
                item.setFrozen(false);
                plugin.messages().send(sender, "admin-unfreeze",
                        "&aUnfroze &f{item}&a: trading and reversion resumed.",
                        java.util.Map.of("item", item.id()));
            }
            default -> plugin.messages().send(sender, "admin-usage",
                    "&cUsage: /bazaar admin <set|freeze|unfreeze|reset> <item> [mid]");
        }
    }

    private String fmt(double v) {
        return String.format("%,.2f", v);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if ("reload".startsWith(args[0].toLowerCase()) && sender.hasPermission("royalbazaar.admin")) {
                out.add("reload");
            }
            if ("admin".startsWith(args[0].toLowerCase()) && sender.hasPermission("royalbazaar.admin")) {
                out.add("admin");
            }
            if ("price".startsWith(args[0].toLowerCase())) {
                out.add("price");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("price")) {
            for (MarketItem item : market.all()) {
                if (item.id().startsWith(args[1])) {
                    out.add(item.id());
                }
            }
        } else if (args[0].equalsIgnoreCase("admin") && sender.hasPermission("royalbazaar.admin")) {
            if (args.length == 2) {
                for (String verb : List.of("set", "freeze", "unfreeze", "reset")) {
                    if (verb.startsWith(args[1].toLowerCase())) {
                        out.add(verb);
                    }
                }
            } else if (args.length == 3) {
                for (MarketItem item : market.all()) {
                    if (item.id().startsWith(args[2])) {
                        out.add(item.id());
                    }
                }
            }
        }
        return out;
    }
}
