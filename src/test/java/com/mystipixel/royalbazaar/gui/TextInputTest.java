package com.mystipixel.royalbazaar.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TextInputTest {

    private static final List<String> TITLE = List.of("&fName it", "&7Leave it blank to cancel");
    private static final List<String> NULL_ANSWER = Arrays.asList((String) null);

    private FakeHost host;
    private TextInput input;
    private Player player;
    private final List<String> answers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new FakeHost();
        input = new TextInput(host);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
    }

    private void ask() {
        input.request(player, TITLE, answers::add);
    }

    @Test
    void typedTextIsCleanedAndCappedServerSide() {
        assertEquals("", TextInput.sanitize(null));
        assertEquals("casque", TextInput.sanitize("  casque \n"));
        assertEquals("ab", TextInput.sanitize("a\u0000\u0007b"));
        assertEquals("ab", TextInput.sanitize("a\u0085\u009Fb"));
        assertEquals("' OR '1'='1; DROP TABLE x; --", TextInput.sanitize("' OR '1'='1; DROP TABLE x; --"),
                "Kept verbatim: callers bind it, never build SQL from it");
        assertEquals(TextInput.MAX_LENGTH, TextInput.sanitize("x".repeat(10_000)).length());
        String emoji = TextInput.sanitize("😀".repeat(TextInput.MAX_LENGTH + 5));
        assertEquals(TextInput.MAX_LENGTH, emoji.codePointCount(0, emoji.length()), "Never splits a surrogate pair");
        assertTrue(Character.isLowSurrogate(emoji.charAt(emoji.length() - 1)));
        String mixed = TextInput.sanitize("a".repeat(TextInput.MAX_LENGTH - 1) + "😀😀");
        assertFalse(Character.isHighSurrogate(mixed.charAt(mixed.length() - 1)));
        assertTrue(mixed.endsWith("😀"));
    }

    @Test
    void doneAnswersOnceWithCleanedTextOnTheNextTick() {
        ask();
        verify(player).closeInventory();
        assertEquals(TITLE, host.lastLines);
        assertEquals(TextInput.TIMEOUT, host.lastDelay);

        host.done.get(0).accept("  diamond\u0000 ");
        assertTrue(answers.isEmpty(), "Answer waits for the main thread");
        host.runTicks();
        assertEquals(List.of("diamond"), answers);
        assertTrue(host.timeouts.isEmpty(), "Timeout cancelled");
        assertFalse(input.isWaiting(player));

        host.done.get(0).accept("again");
        host.cancel.get(0).run();
        host.runTicks();
        assertEquals(List.of("diamond"), answers);
    }

    @Test
    void doneWithBlankTextAnswersEmpty() {
        ask();
        host.done.get(0).accept("   ");
        host.runTicks();
        assertEquals(List.of(""), answers);
    }

    @Test
    void cancelOrEscapeAnswersNull() {
        ask();
        host.cancel.get(0).run();
        host.done.get(0).accept("late");
        host.runTicks();
        assertEquals(NULL_ANSWER, answers);
    }

    @Test
    void quitAnswersNullAndALaterClickIsIgnored() {
        ask();
        input.onQuit(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        host.runTicks();
        assertEquals(NULL_ANSWER, answers);
        host.done.get(0).accept("late");
        host.runTicks();
        assertEquals(1, answers.size());
    }

    @Test
    void deathClosesTheDialogAndAnswersNull() {
        ask();
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(player);
        input.onDeath(death);
        host.runTicks();
        assertEquals(List.of(player), host.closed);
        assertEquals(NULL_ANSWER, answers);
    }

    @Test
    void openingAnotherInventoryAnswersNull() {
        ask();
        InventoryOpenEvent open = mock(InventoryOpenEvent.class);
        when(open.getPlayer()).thenReturn(player);
        input.onInventoryOpen(open);
        assertTrue(input.isWaiting(player), "Still waiting until the next tick");
        host.runTicks();
        assertEquals(NULL_ANSWER, answers);
    }

    @Test
    void quitCancelsAnAnswerAlreadyQueued() {
        assertQueuedAnswerCancelled(() -> input.onQuit(new PlayerQuitEvent(
                player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED)));
    }

    @Test
    void deathCancelsAnAnswerAlreadyQueued() {
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(player);
        assertQueuedAnswerCancelled(() -> input.onDeath(death));
    }

    @Test
    void anotherInventoryCancelsAnAnswerAlreadyQueued() {
        InventoryOpenEvent open = mock(InventoryOpenEvent.class);
        when(open.getPlayer()).thenReturn(player);
        assertQueuedAnswerCancelled(() -> input.onInventoryOpen(open));
    }

    private void assertQueuedAnswerCancelled(Runnable dismiss) {
        ask();
        host.done.get(0).accept("64");
        dismiss.run();
        assertTrue(answers.isEmpty(), "Cancellation still waits until the event has finished");
        host.runTicks();
        assertEquals(NULL_ANSWER, answers, "A queued answer cannot survive dismissal");
        assertTrue(host.timeouts.isEmpty());
        assertFalse(input.isWaiting(player));
        host.done.get(0).accept("late");
        host.cancel.get(0).run();
        host.runTicks();
        assertEquals(NULL_ANSWER, answers, "Completion is delivered once");
    }

    @Test
    void cancelledAnswerCanStartANewPromptWithoutTheOldDismissalCancellingIt() {
        List<String> first = new ArrayList<>();
        input.request(player, TITLE, text -> {
            first.add(text);
            if (text == null) input.request(player, TITLE, answers::add);
        });
        host.done.get(0).accept("old");
        InventoryOpenEvent open = mock(InventoryOpenEvent.class);
        when(open.getPlayer()).thenReturn(player);
        input.onInventoryOpen(open);
        host.runTicks();
        assertEquals(NULL_ANSWER, first);
        assertTrue(answers.isEmpty());
        assertTrue(input.isWaiting(player));
        host.done.get(1).accept("fresh");
        host.runTicks();
        assertEquals(List.of("fresh"), answers);
        assertEquals(NULL_ANSWER, first);
    }

    @Test
    void newRequestSupersedesTheOldOneAndItsButtonsGoDead() {
        List<String> first = new ArrayList<>();
        List<Boolean> waitingWhenTold = new ArrayList<>();
        input.request(player, List.of("a"), text -> {
            first.add(text);
            waitingWhenTold.add(input.isWaiting(player));
        });
        ask();
        assertEquals(NULL_ANSWER, first, "Old prompt is answered before the new one opens");
        assertEquals(List.of(true), waitingWhenTold, "The old caller can tell a newer prompt is up");
        assertEquals(1, host.timeouts.size(), "Old timeout cancelled");

        host.done.get(0).accept("stale");
        host.runTicks();
        assertEquals(1, first.size());
        assertTrue(answers.isEmpty(), "A click from the old prompt does not answer the new one");

        host.done.get(1).accept("fresh");
        host.runTicks();
        assertEquals(List.of("fresh"), answers);
    }

    @Test
    void callbackThatAsksAgainWinsOverTheRequestThatSupersededIt() {
        List<String> second = new ArrayList<>();
        input.request(player, List.of("a"), text -> input.request(player, List.of("c"), answers::add));
        input.request(player, List.of("b"), second::add);
        assertEquals(NULL_ANSWER, second);
        assertEquals(2, host.done.size(), "The replaced prompt is never shown");
        host.done.get(1).accept("x");
        host.runTicks();
        assertEquals(List.of("x"), answers);
    }

    @Test
    void dismissalQueuedBeforeANewPromptDoesNotAnswerIt() {
        ask();
        host.done.get(0).accept("old");
        InventoryOpenEvent open = mock(InventoryOpenEvent.class);
        when(open.getPlayer()).thenReturn(player);
        input.onInventoryOpen(open);
        List<String> second = new ArrayList<>();
        input.request(player, List.of("b"), second::add);
        host.runTicks();
        assertTrue(second.isEmpty());
        assertTrue(input.isWaiting(player));
        host.done.get(1).accept("fresh");
        host.runTicks();
        assertEquals(List.of("fresh"), second);
        assertEquals(NULL_ANSWER, answers);
    }

    @Test
    void timeoutClosesTheDialogAndAnswersNull() {
        ask();
        host.timeouts.get(0).run();
        assertEquals(List.of(player), host.closed);
        assertEquals(NULL_ANSWER, answers);
        host.done.get(0).accept("too late");
        host.runTicks();
        assertEquals(1, answers.size());
    }

    @Test
    void shutdownClosesDialogsWithoutCallingBack() {
        ask();
        host.done.get(0).accept("queued");
        input.shutdown();
        host.runTicks();
        assertEquals(List.of(player), host.closed);
        assertTrue(host.timeouts.isEmpty());
        assertFalse(input.isWaiting(player));

        host.enabled = false;
        assertDoesNotThrow(() -> host.done.get(0).accept("after disable"));
        assertDoesNotThrow(() -> host.cancel.get(0).run());
        assertTrue(host.ticks.isEmpty(), "Nothing is scheduled on a disabled plugin");
        assertTrue(answers.isEmpty());

        ask();
        assertEquals(1, host.done.size(), "No new prompts after shutdown");
    }

    @Test
    void clickAfterThePluginIsDisabledDoesNothing() {
        ask();
        host.enabled = false;
        host.done.get(0).accept("late");
        assertTrue(host.ticks.isEmpty());
        assertTrue(answers.isEmpty());
    }

    private static final class FakeHost implements TextInput.Host {
        boolean enabled = true;
        List<String> lastLines;
        Duration lastDelay;
        final List<Runnable> ticks = new ArrayList<>();
        final List<Runnable> timeouts = new ArrayList<>();
        final List<Consumer<String>> done = new ArrayList<>();
        final List<Runnable> cancel = new ArrayList<>();
        final List<Player> closed = new ArrayList<>();

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public void nextTick(Runnable task) {
            ticks.add(task);
        }

        @Override
        public Runnable later(Duration delay, Runnable task) {
            lastDelay = delay;
            timeouts.add(task);
            return () -> timeouts.remove(task);
        }

        @Override
        public void show(Player player, List<String> lines, Consumer<String> done, Runnable cancel) {
            this.lastLines = lines;
            this.done.add(done);
            this.cancel.add(cancel);
        }

        @Override
        public void close(Player player) {
            closed.add(player);
        }

        void runTicks() {
            while (!ticks.isEmpty()) {
                List<Runnable> now = new ArrayList<>(ticks);
                ticks.clear();
                now.forEach(Runnable::run);
            }
        }
    }
}
