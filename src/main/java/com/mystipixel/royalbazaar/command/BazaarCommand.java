package com.mystipixel.royalbazaar.command;

import com.mystipixel.royalbazaar.RoyalBazaarPlugin;
import com.mystipixel.royalbazaar.config.CategoryConfig;
import com.mystipixel.royalbazaar.gui.GuiManager;
import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.market.PricingEngine;
import com.mystipixel.royalbazaar.service.BazaarService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** {@code /bazaar [reload|price|sellall|admin]}. */
public final class BazaarCommand implements CommandExecutor, TabCompleter {

    private final RoyalBazaarPlugin plugin;
    private final GuiManager gui;
    private final MarketManager market;
    private final BazaarService service;

    public BazaarCommand(RoyalBazaarPlugin plugin, GuiManager gui, MarketManager market, BazaarService service) {
        this.plugin = plugin;
        this.gui = gui;
        this.market = market;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // every way into the bazaar goes through this command, so royalbazaar.use is checked here
        if (!sender.hasPermission("royalbazaar.use") && !sender.hasPermission("royalbazaar.admin")) {
            plugin.messages().send(sender, "no-permission", "&cNo permission.");
            return true;
        }
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
                MarketItem item = market.lookup(args[1]);
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
            case "sellall" -> sellAll(sender, args);
            case "admin" -> admin(sender, args);
            default -> {
                if (sender instanceof Player player) {
                    gui.openDefault(player);
                }
            }
        }
        return true;
    }

    // /bazaar sellall [category]: same sell path as the menu button (price impact, audit, EconGuard veto)
    private void sellAll(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only", "Only players can sell to the bazaar.");
            return;
        }
        Collection<MarketItem> scope;
        String what;
        if (args.length >= 2) {
            CategoryConfig cat = market.category(args[1].toLowerCase());
            if (cat == null) {
                plugin.messages().send(sender, "unknown-category", "&cUnknown category: {category}",
                        java.util.Map.of("category", args[1]));
                return;
            }
            scope = market.itemsIn(cat.id());
            what = cat.displayName();
        } else {
            scope = market.all();
            what = "your inventory";
        }
        BazaarService.SellAllResult result = service.sellAll(player, scope);
        if (result.soldNothing()) {
            plugin.messages().send(player, "sell-all.nothing",
                    "&eNothing in " + what + " could be sold here.");
        } else {
            plugin.messages().send(player, "sell-all.done",
                    "&aSold &f" + result.units() + "&a from &f" + result.distinctItems()
                            + "&a item type(s) for &6" + String.format("%,.2f", result.proceeds()) + "&a.");
        }
        if (result.blocked() > 0) {
            plugin.messages().send(player, "sell-all.blocked",
                    "&c" + result.blocked() + " item type(s) were blocked and not sold.");
        }
    }

    // set/reset persist through the normal flush; freeze is in-memory only and clears on restart
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
        MarketItem item = market.lookup(args[2]);
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
            if ("sellall".startsWith(args[0].toLowerCase())) {
                out.add("sellall");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("sellall")) {
            for (CategoryConfig cat : market.categories()) {
                if (cat.id().startsWith(args[1].toLowerCase())) {
                    out.add(cat.id());
                }
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
