package com.mystipixel.royalbazaar.command;

import com.mystipixel.royalbazaar.RoyalBazaarPlugin;
import com.mystipixel.royalbazaar.gui.GuiManager;
import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.market.PricingEngine;
import com.mystipixel.royalbazaar.util.Text;
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
                sender.sendMessage("Only players can open the bazaar.");
            }
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                if (!sender.hasPermission("royalbazaar.admin")) {
                    sender.sendMessage(Text.chat("&cNo permission."));
                    return true;
                }
                plugin.reloadEverything();
                sender.sendMessage(Text.chat("&aRoyalBazaar reloaded."));
            }
            case "price" -> {
                if (args.length < 2) {
                    sender.sendMessage(Text.chat("&cUsage: /bazaar price <item>"));
                    return true;
                }
                MarketItem item = market.get(args[1]);
                if (item == null) {
                    sender.sendMessage(Text.chat("&cUnknown item: " + args[1]));
                    return true;
                }
                sender.sendMessage(Text.chat("&e" + item.id() + "&7: buy &a$" + fmt(PricingEngine.buyPrice(item))
                        + " &7sell &e$" + fmt(PricingEngine.sellPrice(item)) + " &7(mid " + fmt(item.mid()) + ")"));
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
