package com.mystipixel.royalbazaar.hooks;

import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.market.PricingEngine;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion so live prices are usable anywhere (holograms, scoreboards, other menus).
 * Format: {@code %royalbazaar_buy_<itemId>%}, {@code %royalbazaar_sell_<itemId>%},
 * {@code %royalbazaar_mid_<itemId>%}. Item ids keep their namespace, e.g.
 * {@code %royalbazaar_buy_ecoitem:enchanted_cobblestone%}.
 */
public final class BazaarPlaceholderExpansion extends PlaceholderExpansion {

    private final MarketManager market;
    private final VaultHook vault;
    private final String version;

    public BazaarPlaceholderExpansion(MarketManager market, VaultHook vault, String version) {
        this.market = market;
        this.vault = vault;
        this.version = version;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "royalbazaar";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Mystipixel";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        int sep = params.indexOf('_');
        if (sep < 0) {
            return null;
        }
        String type = params.substring(0, sep);
        String itemId = params.substring(sep + 1);
        MarketItem item = market.get(itemId);
        if (item == null) {
            return "";
        }
        return switch (type) {
            case "buy" -> vault.format(PricingEngine.buyPrice(item));
            case "sell" -> vault.format(PricingEngine.sellPrice(item));
            case "mid" -> vault.format(item.mid());
            default -> null;
        };
    }
}
