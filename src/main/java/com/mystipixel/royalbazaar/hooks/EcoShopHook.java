package com.mystipixel.royalbazaar.hooks;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Reads EcoShop's NPC prices ({@code buy.value} is the natural ceiling, {@code sell.value} the floor)
 * from {@code plugins/EcoShop/categories/**.yml} once at load. Read-only and best-effort; when EcoShop
 * is absent every {@code auto}/{@code npc_*} option falls back to its config default.
 */
public final class EcoShopHook {

    /** NPC prices for one item; either may be null if EcoShop only defines one side. */
    public record ShopPrice(Double buy, Double sell) {
    }

    private static final Set<String> COIN_TYPES = Set.of("coins", "coin", "money", "vault");

    private final boolean present;
    private final Map<String, ShopPrice> byId = new HashMap<>();

    public EcoShopHook(File pluginsFolder, Logger logger) {
        // read the files directly: RoyalBazaar may enable before EcoShop
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

    private void load(File dir, Logger logger) {
        List<File> files = new ArrayList<>();
        collect(dir, files);
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
        logger.info("EcoShop detected: anchored prices available for " + byId.size() + " items"
                + " (from " + files.size() + " category file(s)).");
    }

    // recursive: admins often file categories into subfolders (categories/npc/). Files starting
    // with _ are EcoShop's own examples.
    private void collect(File dir, List<File> out) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File f : entries) {
            if (f.isDirectory()) {
                collect(f, out);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".yml") && !f.getName().startsWith("_")) {
                out.add(f);
            }
        }
    }

    // non-coin prices (XP, levels, EcoBits) say nothing about a Vault price, so they're ignored
    private Double valueOf(Object side) {
        if (side instanceof Map<?, ?> m) {
            Object type = m.get("type");
            if (type != null && !COIN_TYPES.contains(String.valueOf(type).toLowerCase(Locale.ROOT))) {
                return null;
            }
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

    // first token only (drops item modifiers), lowercased, vanilla namespaced to minecraft:
    private String normalize(String lookup) {
        String first = lookup.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (first.contains(":")) {
            return first;
        }
        return "minecraft:" + first;
    }
}
