package com.mystipixel.royalbazaar.hooks;

import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.market.PricingEngine;
import com.mystipixel.royalbazaar.market.TradeSide;
import com.mystipixel.royalbazaar.message.MessageManager;
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
    private final MessageManager messages;

    public BazaarPlaceholderExpansion(MarketManager market, VaultHook vault, String version) {
        this(market, vault, version, null);
    }

    public BazaarPlaceholderExpansion(MarketManager market, VaultHook vault, String version, MessageManager messages) {
        this.messages = messages;
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

    private String unavailablePrice() {
        return messages == null ? "N/A" : messages.get("price-unavailable", "N/A");
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
            case "buy" -> item.tradeMode().allows(TradeSide.BUY)
                    ? vault.format(PricingEngine.buyPrice(item)) : unavailablePrice();
            case "sell" -> item.tradeMode().allows(TradeSide.SELL)
                    ? vault.format(PricingEngine.sellPrice(item)) : unavailablePrice();
            case "mid" -> vault.format(item.mid());
            // Plain-text percentages (no colour codes): scoreboards and holograms style themselves.
            case "change24h" -> item.midYesterday() <= 0 ? "0.0%"
                    : String.format("%+.1f%%", (item.mid() - item.midYesterday()) / item.midYesterday() * 100.0);
            case "change7d" -> item.midWeekAgo() <= 0 ? "0.0%"
                    : String.format("%+.1f%%", (item.mid() - item.midWeekAgo()) / item.midWeekAgo() * 100.0);
            default -> null;
        };
    }
}
