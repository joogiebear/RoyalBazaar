package com.mystipixel.royalbazaar.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;

/**
 * Sanity-checks RoyalBazaar's config on load and warns about values that would silently misbehave.
 * Warn-only: {@link PluginConfig} already applies safe fallbacks, but an admin sees what looks wrong.
 */
public final class ConfigValidator {

    private static final Set<String> INVENTORY_POLICIES = Set.of("refund", "drop", "partial");

    private final JavaPlugin plugin;
    private final PluginConfig config;

    public ConfigValidator(JavaPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void validate() {
        double alpha = config.emaAlpha();
        if (alpha <= 0 || alpha > 1) {
            warn("engine.trend-ema-alpha is " + alpha + "; expected a fraction in (0, 1] (e.g. 0.2). "
                    + "The trend arrow will misbehave.");
        }

        String policy = config.inventoryFullPolicy();
        if (!INVENTORY_POLICIES.contains(policy)) {
            warn("trading.inventory-full is '" + policy + "'; expected one of refund, drop, partial. "
                    + "Buys that overflow the inventory may not behave as intended.");
        }

        if (config.loadCategories().isEmpty()) {
            warn("no categories/*.yml loaded; the bazaar will have nothing to trade.");
        }
    }

    private void warn(String message) {
        plugin.getLogger().warning("Config warning: " + message);
    }
}
