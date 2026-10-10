package com.mystipixel.royalbazaar.message;

import com.mystipixel.royalbazaar.RoyalBazaarPlugin;
import com.mystipixel.royalbazaar.config.PluginConfig;
import com.mystipixel.royalbazaar.data.BazaarDatabase;
import com.mystipixel.royalbazaar.hooks.*;
import com.mystipixel.royalbazaar.market.*;
import com.mystipixel.royalbazaar.service.BazaarService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradeModeMessagesTest {
    @Test
    void bundledMessagesSurviveYamlParsing() throws Exception {
        var messages = new YamlConfiguration();
        try (var stream = getClass().getResourceAsStream("/messages.yml")) {
            assertNotNull(stream);
            messages.loadFromString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        for (String key : new String[]{"price-unavailable", "trade.reasons.buy-disabled", "trade.reasons.sell-disabled"}) {
            assertNotNull(messages.getString(key), key);
            assertFalse(messages.getString(key).isBlank(), key);
        }
    }

    @Test
    void refusalsUseLiveTranslationsAndFallbackForExistingMessageFiles(@TempDir Path tmp) throws Exception {
        RoyalBazaarPlugin plugin = mock(RoyalBazaarPlugin.class);
        when(plugin.getDataFolder()).thenReturn(tmp.toFile());
        Path file = tmp.resolve("messages.yml");
        Files.writeString(file, "price-unavailable: Indisponible\ntrade:\n  reasons:\n    sell-disabled: Vente interdite\n");
        MessageManager messages = new MessageManager(plugin);
        when(plugin.messages()).thenReturn(messages);
        MarketManager market = mock(MarketManager.class);
        var item = new MarketItem("minecraft:stone", "test", null, "", 10, 0.05, 1000, 0.02, 1, 100, TradeMode.BUY_ONLY);
        when(market.get(item.id())).thenReturn(item);
        BazaarService service = new BazaarService(plugin, market, mock(BazaarDatabase.class), mock(VaultHook.class),
                mock(EcoHook.class), mock(EconGuardHook.class), mock(PluginConfig.class));
        assertEquals("Vente interdite", service.sell(null, item.id(), 64).message());
        assertEquals("Indisponible", service.unavailablePrice());
        Files.writeString(file, "prefix: ''\n"); // old server config without the new keys
        messages.reload();
        assertEquals("You cannot sell this item.", service.sell(null, item.id(), 64).message());
        assertEquals("N/A", service.unavailablePrice());
    }
}
