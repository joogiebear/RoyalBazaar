package com.mystipixel.royalbazaar.gui.menu;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads (and, on first run, writes out) every {@code gui/*.yml} template. Reloadable. */
public final class MenuManager {

    private final JavaPlugin plugin;
    private final Map<String, MenuTemplate> byId = new LinkedHashMap<>();

    public MenuManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        byId.clear();
        // No bazaar_main: /bazaar opens a category directly and the category rail is the navigation,
        // so the old hub was removed rather than left as a menu nothing points at.
        byId.put("bazaar_category", load("bazaar_category.yml", "&8Bazaar", 6));
        byId.put("bazaar_group", load("bazaar_group.yml", "&8Bazaar", 6));
        byId.put("bazaar_product", load("bazaar_product.yml", "&8Bazaar", 6));
        byId.put("bazaar_buy", load("bazaar_buy.yml", "&8Instant Buy", 3));
    }

    private MenuTemplate load(String fileName, String defaultTitle, int defaultRows) {
        File file = new File(plugin.getDataFolder(), "gui/" + fileName);
        if (!file.exists()) {
            plugin.saveResource("gui/" + fileName, false);
        }
        return MenuTemplate.load(file, defaultTitle, defaultRows);
    }

    public MenuTemplate get(String id) {
        return byId.get(id);
    }
}
