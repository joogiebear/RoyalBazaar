package com.mystipixel.royalbazaar.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Wraps {@code config.yml} plus the {@code categories/} folder. On first run the default config and
 * a couple of example category files are written out; thereafter everything is user-editable and
 * hot-reloadable via {@code /bazaar reload}.
 */
public final class PluginConfig {

    private static final String[] DEFAULT_CATEGORIES = {"farming.yml", "mining.yml"};

    private final JavaPlugin plugin;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        writeDefaultCategories();
    }

    public void reload() {
        plugin.reloadConfig();
    }

    // ---- config.yml ----

    public long tickIntervalTicks() {
        return Math.max(20L, plugin.getConfig().getLong("engine.tick-interval-seconds", 60L) * 20L);
    }

    public long historyIntervalTicks() {
        return Math.max(20L, plugin.getConfig().getLong("engine.history-interval-seconds", 300L) * 20L);
    }

    public long flushIntervalTicks() {
        return Math.max(20L, plugin.getConfig().getLong("engine.flush-interval-seconds", 30L) * 20L);
    }

    /** Days of price history to keep. 0 or less disables pruning (keep everything). */
    public int historyRetentionDays() {
        return plugin.getConfig().getInt("engine.history-retention-days", 30);
    }

    public double emaAlpha() {
        return plugin.getConfig().getDouble("engine.trend-ema-alpha", 0.2);
    }

    /** What to do when a buy can't fully fit in the player's inventory: refund | drop | partial. */
    /**
     * Category that {@code /bazaar} opens directly. Null falls back to the first configured category,
     * since the category rail is the navigation and there is no separate landing menu.
     */
    public String defaultCategory() {
        String id = plugin.getConfig().getString("default-category", "");
        return id == null || id.isBlank() ? null : id;
    }

    public String inventoryFullPolicy() {
        return plugin.getConfig().getString("trading.inventory-full", "refund").toLowerCase();
    }

    public ConfigurationSection storageSection() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("storage");
        return s != null ? s : plugin.getConfig().createSection("storage");
    }

    // ---- categories/ ----

    /** Load every {@code categories/*.yml} into a {@link CategoryConfig}, ordered by their {@code slot}. */
    public List<CategoryConfig> loadCategories() {
        List<CategoryConfig> out = new ArrayList<>();
        File dir = new File(plugin.getDataFolder(), "categories");
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".yml"));
        if (files == null) {
            plugin.getLogger().warning("No categories/ folder found.");
            return out;
        }
        for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - 4);
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            out.add(CategoryConfig.load(id, cfg, plugin.getLogger()));
        }
        out.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        return out;
    }

    private void writeDefaultCategories() {
        File dir = new File(plugin.getDataFolder(), "categories");
        if (dir.exists()) {
            return; // never clobber user edits
        }
        if (!dir.mkdirs()) {
            plugin.getLogger().warning("Could not create categories/ folder.");
            return;
        }
        for (String name : DEFAULT_CATEGORIES) {
            copyResource("categories/" + name, new File(dir, name));
        }
    }

    private void copyResource(String resourcePath, File target) {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                char[] buf = new char[1024];
                int read;
                while ((read = reader.read(buf)) != -1) {
                    sb.append(buf, 0, read);
                }
            }
            java.nio.file.Files.writeString(target.toPath(), sb.toString());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed writing default resource " + resourcePath, e);
        }
    }
}
