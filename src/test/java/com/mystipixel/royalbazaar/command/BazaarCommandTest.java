package com.mystipixel.royalbazaar.command;

import com.mystipixel.royalbazaar.RoyalBazaarPlugin;
import com.mystipixel.royalbazaar.gui.GuiManager;
import com.mystipixel.royalbazaar.market.*;
import com.mystipixel.royalbazaar.message.MessageManager;
import com.mystipixel.royalbazaar.service.BazaarService;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BazaarCommandTest {
    @ParameterizedTest
    @EnumSource(TradeMode.class)
    void priceCommandMarksOnlyTheUnavailableSide(TradeMode mode) {
        RoyalBazaarPlugin plugin = mock(RoyalBazaarPlugin.class);
        MessageManager messages = mock(MessageManager.class);
        when(plugin.messages()).thenReturn(messages);
        BazaarService service = mock(BazaarService.class);
        when(service.unavailablePrice()).thenReturn("Indisponible");
        MarketManager market = mock(MarketManager.class);
        MarketItem item = new MarketItem("minecraft:stone_bricks", "test", null, "",
                10, 0.05, 1000, 0.02, 1, 100, mode);
        when(market.lookup("stone_bricks")).thenReturn(item);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("royalbazaar.use")).thenReturn(true);
        doAnswer(inv -> {
            Map<String, String> values = inv.getArgument(3);
            assertEquals(!mode.allows(TradeSide.BUY), values.get("buy").equals("Indisponible"));
            assertEquals(!mode.allows(TradeSide.SELL), values.get("sell").equals("Indisponible"));
            assertNotEquals("Indisponible", values.get("mid"));
            return null;
        }).when(messages).send(eq(sender), eq("price-line"), anyString(), anyMap());
        assertTrue(new BazaarCommand(plugin, mock(GuiManager.class), market, service)
                .onCommand(sender, null, "bazaar", new String[]{"price", "stone_bricks"}));
        verify(messages).send(eq(sender), eq("price-line"), anyString(), anyMap());
    }
}
