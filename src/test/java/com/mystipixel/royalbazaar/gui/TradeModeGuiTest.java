package com.mystipixel.royalbazaar.gui;

import com.mystipixel.royalbazaar.gui.menu.*;
import com.mystipixel.royalbazaar.hooks.EcoHook;
import com.mystipixel.royalbazaar.market.*;
import com.mystipixel.royalbazaar.message.MessageManager;
import com.mystipixel.royalbazaar.service.BazaarService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TradeModeGuiTest {
    private final MarketManager market = mock(MarketManager.class);
    private final BazaarService service = mock(BazaarService.class);
    private final MenuManager menus = mock(MenuManager.class);
    private final EcoHook eco = mock(EcoHook.class);
    private final Player player = mock(Player.class);
    private final MessageManager messages = mock(MessageManager.class);
    private final SignInput input = mock(SignInput.class);

    private MarketItem item(TradeMode mode) {
        MarketItem item = new MarketItem("minecraft:stone", "test", null, "Stone",
                10, 0.05, 1000, 0.02, 1, 100, mode);
        when(market.get(item.id())).thenReturn(item);
        return item;
    }

    @Test
    void productHidesDisabledButtonsAndKeepsTheOtherClickOnAMixedButton() {
        MarketItem item = item(TradeMode.SELL_ONLY);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Map<String, String> ph = Map.of("rbazaar_item", item.id());
        when(service.placeholders(item, player)).thenReturn(ph);
        MenuEffect buy = new MenuEffect("rbazaar_open_buy", Map.of("item", "%rbazaar_item%"));
        MenuEffect sell = new MenuEffect("rbazaar_sell", Map.of("item", "%rbazaar_item%", "amount", "all"));
        ItemSpec spec = mock(ItemSpec.class);
        ItemStack icon = mock(ItemStack.class);
        when(spec.build(eq(eco), eq(ph), anyList())).thenReturn(icon);
        MenuTemplate template = mock(MenuTemplate.class);
        when(template.size()).thenReturn(36);
        when(template.title()).thenReturn("Product");
        when(template.slots()).thenReturn(List.of(
                new MenuSlot(11, spec, List.of(), List.of(buy), List.of()),
                new MenuSlot(15, spec, List.of(), List.of(sell), List.of()),
                new MenuSlot(13, spec, List.of(), List.of(buy), List.of(sell))));
        when(menus.get("bazaar_product")).thenReturn(template);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(36);
        GuiManager gui = new GuiManager(menus, market, service, eco);
        try (var holder = mockStatic(BazaarMenuHolder.class)) {
            holder.when(() -> BazaarMenuHolder.create(36, com.mystipixel.royalbazaar.util.Text.color("Product"))).thenReturn(inventory);
            gui.openBuy(player, item.id()); // direct navigation also redirects to product
        }
        verify(inventory, never()).setItem(eq(11), any());
        verify(inventory).setItem(15, icon);
        verify(inventory).setItem(13, icon);
        assertEquals("bazaar_product", gui.viewOf(player).menuId());
        assertNull(gui.viewOf(player).left(11));
        assertNull(gui.viewOf(player).left(13));
        assertEquals(item.id(), gui.viewOf(player).right(13).getFirst().argString("item", null));
        verify(menus, never()).get("bazaar_buy");
    }

    @ParameterizedTest
    @ValueSource(strings = {"rbazaar_buy", "rbazaar_open_buy", "rbazaar_buy_prompt", "rbazaar_buy_amount_prompt",
            "rbazaar_sell", "rbazaar_sell_prompt"})
    void renderingAndStaleClicksBlockEveryDisabledTradeAction(String action) {
        TradeSide side = new MenuEffect(action, Map.of()).tradeSide();
        TradeMode mode = side == TradeSide.BUY ? TradeMode.SELL_ONLY : TradeMode.BUY_ONLY;
        MarketItem item = item(mode);
        MenuEffect effect = new MenuEffect(action, Map.of("item", item.id(), "amount", "fill"));
        var click = List.of(effect, new MenuEffect("play_sound", Map.of()));
        GuiManager renderer = new GuiManager(menus, market, service, eco);
        assertTrue(renderer.resolveEffects(click, Map.of()).isEmpty());
        GuiManager gui = mock(GuiManager.class);
        AmountPrompt prompt = mock(AmountPrompt.class);
        TradeResult denied = TradeResult.fail(TradeResult.Status.DISABLED, side, item.id(), "disabled");
        when(service.tradeRestriction(item.id(), side)).thenReturn(denied);
        new EffectDispatcher(gui, service, prompt, messages, market, input).run(player, click);
        verify(service, never()).buy(any(), anyString(), anyLong());
        verify(service, never()).sell(any(), anyString(), anyLong());
        verify(service, never()).fillAmount(any(), anyString());
        verifyNoInteractions(input, prompt);
        verify(player, never()).playSound(any(org.bukkit.Location.class), any(org.bukkit.Sound.class), anyFloat(), anyFloat());
        verify(messages).send(player, "trade.failed", "&c{reason}", Map.of("reason", "disabled"));
        verify(gui).refresh(player);
    }

    @Test
    void promptSubmittedAfterModeChangeUsesTheCurrentServiceDecision() {
        GuiManager gui = mock(GuiManager.class);
        String id = "minecraft:stone";
        var callback = new java.util.concurrent.atomic.AtomicReference<Consumer<String>>();
        doAnswer(inv -> { callback.set(inv.getArgument(2)); return null; })
                .when(input).request(eq(player), anyList(), any());
        AmountPrompt prompt = new AmountPrompt(service, gui, messages, input);
        prompt.begin(player, id, true);
        assertNotNull(callback.get());
        when(service.buy(player, id, 64)).thenReturn(
                TradeResult.fail(TradeResult.Status.DISABLED, TradeSide.BUY, id, "disabled after reload"));
        callback.get().accept("64");
        verify(messages).send(player, "trade.failed", "&c{reason}", Map.of("reason", "disabled after reload"));
        verify(gui).openProduct(player, id);
    }

    @Test
    void directDisabledPromptDoesNotOpenInput() {
        GuiManager gui = mock(GuiManager.class);
        when(service.tradeRestriction("test", TradeSide.SELL)).thenReturn(
                TradeResult.fail(TradeResult.Status.DISABLED, TradeSide.SELL, "test", "disabled"));
        new AmountPrompt(service, gui, messages, input).begin(player, "test", false);
        verifyNoInteractions(input);
        verify(gui).openProduct(player, "test");
    }
}
