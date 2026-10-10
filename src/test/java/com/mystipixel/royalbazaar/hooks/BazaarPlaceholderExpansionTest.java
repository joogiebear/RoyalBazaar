package com.mystipixel.royalbazaar.hooks;

import com.mystipixel.royalbazaar.market.*;
import com.mystipixel.royalbazaar.message.MessageManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BazaarPlaceholderExpansionTest {
    @ParameterizedTest
    @EnumSource(TradeMode.class)
    void pricesOnlyAdvertiseEnabledDirectionsAndUseConfiguredUnavailableText(TradeMode mode) {
        MarketManager market = mock(MarketManager.class);
        VaultHook vault = mock(VaultHook.class);
        MessageManager messages = mock(MessageManager.class);
        when(messages.get("price-unavailable", "N/A")).thenReturn("Indisponible");
        when(vault.format(anyDouble())).thenAnswer(i -> "price:" + i.getArgument(0));
        MarketItem item = new MarketItem("minecraft:stone_bricks", "test", null, "",
                10, 0.05, 1000, 0.02, 1, 100, mode);
        when(market.get(item.id())).thenReturn(item);
        BazaarPlaceholderExpansion expansion = new BazaarPlaceholderExpansion(market, vault, "test", messages);
        assertEquals(mode.allows(TradeSide.BUY) ? "price:" + PricingEngine.buyPrice(item) : "Indisponible",
                expansion.onRequest(null, "buy_" + item.id()));
        assertEquals(mode.allows(TradeSide.SELL) ? "price:" + PricingEngine.sellPrice(item) : "Indisponible",
                expansion.onRequest(null, "sell_" + item.id()));
        assertEquals("price:10.0", expansion.onRequest(null, "mid_" + item.id()));
    }
}
