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
                gui.openMain(player);
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
            default -> {
                if (sender instanceof Player player) {
                    gui.openMain(player);
                }
            }
        }
        return true;
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
            if ("price".startsWith(args[0].toLowerCase())) {
                out.add("price");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("price")) {
            for (MarketItem item : market.all()) {
                if (item.id().startsWith(args[1])) {
                    out.add(item.id());
                }
            }
        }
        return out;
    }
}
