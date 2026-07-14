package com.mystipixel.royalbazaar.hooks;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reads EcoShop's per-item prices so RoyalBazaar can anchor to them (the eco-suite's single source of
 * truth for an item's value). EcoShop brackets each item with a {@code buy.value} (what a player pays
 * the NPC — the natural ceiling) and a {@code sell.value} (what the NPC pays the player — the natural
 * floor). We parse {@code plugins/EcoShop/categories/*.yml} once at load; if EcoShop is absent this
 * hook is simply empty and every {@code auto}/{@code npc_*} option falls back to its config default.
 *
 * <p>This is a read-only, best-effort file parse — RoyalBazaar never writes EcoShop, and a parse miss
 * for one item never blocks loading.
 */
public final class EcoShopHook {

    /** NPC prices for one item; either may be null if EcoShop only defines one side. */
    public record ShopPrice(Double buy, Double sell) {
    }

    private final boolean present;
    private final Map<String, ShopPrice> byId = new HashMap<>();

    public EcoShopHook(File pluginsFolder, Logger logger) {
        // Read EcoShop's config files directly — this doesn't require EcoShop to be *enabled* yet
        // (RoyalBazaar may enable before it), only that its category folder exists on disk.
        File dir = new File(pluginsFolder, "EcoShop/categories");
        this.present = dir.isDirectory();
        if (present) {
            load(dir, logger);
        }
    }

    public boolean isPresent() {
        return present;
    }

    public Double buyValue(String bazaarId) {
        ShopPrice p = byId.get(normalize(bazaarId));
        return p == null ? null : p.buy();
    }

    public Double sellValue(String bazaarId) {
        ShopPrice p = byId.get(normalize(bazaarId));
        return p == null ? null : p.sell();
    }

    // ------------------------------------------------------------------ loading

    private void load(File dir, Logger logger) {
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml") && !n.startsWith("_"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            List<Map<?, ?>> items = cfg.getMapList("items");
            for (Map<?, ?> item : items) {
                Object lookup = item.get("item");
                if (lookup == null) {
                    continue;
                }
                Double buy = valueOf(item.get("buy"));
                Double sell = valueOf(item.get("sell"));
                if (buy == null && sell == null) {
                    continue;
                }
                byId.put(normalize(String.valueOf(lookup)), new ShopPrice(buy, sell));
            }
        }
        logger.info("EcoShop detected — anchored prices available for " + byId.size() + " items.");
    }

    /** Pull {@code value} out of a {@code buy:}/{@code sell:} sub-map. */
    private Double valueOf(Object side) {
        if (side instanceof Map<?, ?> m) {
            Object v = m.get("value");
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v != null) {
                try {
                    return Double.parseDouble(String.valueOf(v));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Canonical key for matching a RoyalBazaar id against an EcoShop {@code item:} lookup: first token
     * only (drop item modifiers), lowercased, vanilla materials namespaced to {@code minecraft:}.
     */
    private String normalize(String lookup) {
        String first = lookup.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (first.contains(":")) {
            return first;
        }
        return "minecraft:" + first;
    }
}
