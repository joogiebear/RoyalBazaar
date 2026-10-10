package com.mystipixel.royalbazaar.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TextInputTest {

    private static final List<String> TITLE = List.of("&fSearch the bazaar", "&7Type an item name");

    private FakeScreen screen;
    private TextInput input;
    private Player player;
    private final List<String> answers = new ArrayList<>();
    private int calls;

    @BeforeEach
    void setUp() {
        screen = new FakeScreen();
        input = new TextInput(screen);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    }

    private Consumer<String> record() {
        return text -> {
            calls++;
            answers.add(text);
        };
    }

    @Test
    void typedTextIsCleanedAndCappedServerSide() {
        assertEquals("", TextInput.sanitize(null));
        assertEquals("casque", TextInput.sanitize("  casque \n"));
        assertEquals("ab", TextInput.sanitize("a\u0000\u0007b"));
        assertEquals("' OR '1'='1; DROP TABLE x; --", TextInput.sanitize("' OR '1'='1; DROP TABLE x; --"),
                "Kept verbatim: callers bind it, never build SQL from it");
        assertEquals(TextInput.MAX_LENGTH, TextInput.sanitize("x".repeat(10_000)).length());
        String emoji = "😀".repeat(TextInput.MAX_LENGTH + 5);
        String cut = TextInput.sanitize(emoji);
        assertEquals(TextInput.MAX_LENGTH, cut.codePointCount(0, cut.length()), "Never splits a surrogate pair");
        assertTrue(Character.isLowSurrogate(cut.charAt(cut.length() - 1)));
    }

    @Test
    void doneAnswersOnceWithCleanedText() {
        input.request(player, TITLE, record());
        verify(player).closeInventory();
        assertEquals(TITLE, screen.lastTitle);

        screen.done.accept("  diamond\u0000 ");
        screen.done.accept("again");
        screen.tick();

        assertEquals(List.of("diamond"), answers);
        assertEquals(1, calls);
        assertTrue(screen.timeoutsCancelled >= 1);
    }

    @Test
    void doneWithBlankTextAnswersEmpty() {
        input.request(player, TITLE, record());
        screen.done.accept("   ");
        screen.tick();
        assertEquals(List.of(""), answers);
    }

    @Test
    void cancelOrEscapeAnswersNull() {
        input.request(player, TITLE, record());
        screen.cancel.run();
        screen.done.accept("late");
        screen.tick();
        assertEquals(1, calls);
        assertEquals(null, answers.get(0));
    }

    @Test
    void quitAnswersNullAndLaterClickIsIgnored() {
        input.request(player, TITLE, record());
        Consumer<String> done = screen.done;
        input.onQuit(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        screen.tick();
        done.accept("late");
        screen.tick();
        assertEquals(1, calls);
        assertEquals(null, answers.get(0));
    }

    @Test
    void newRequestSupersedesTheOldOne() {
        List<String> first = new ArrayList<>();
        input.request(player, TITLE, first::add);
        Consumer<String> staleDone = screen.done;

        input.request(player, TITLE, record());
        assertEquals(1, first.size(), "Old prompt is answered before the new one opens");
        assertEquals(null, first.get(0));

        staleDone.accept("stale");
        screen.tick();
        assertEquals(1, first.size());
        assertEquals(0, calls, "A click from the old prompt does not answer the new one");

        screen.done.accept("fresh");
        screen.tick();
        assertEquals(List.of("fresh"), answers);
    }

    @Test
    void timeoutAnswersNullAndClosesTheDialog() {
        input.request(player, TITLE, record());
        Consumer<String> done = screen.done;
        assertEquals(TextInput.TIMEOUT, screen.lastDelay);

        screen.fireTimeout();
        screen.tick();
        assertEquals(1, calls);
        assertEquals(null, answers.get(0));
        assertEquals(1, screen.closed);

        done.accept("too late");
        screen.tick();
        assertEquals(1, calls);
    }

    @Test
    void shutdownClosesDialogsWithoutCallingBack() {
        input.request(player, TITLE, record());
        Consumer<String> done = screen.done;
        input.shutdown();
        assertEquals(1, screen.closed);

        done.accept("after disable");
        screen.cancel.run();
        screen.tick();
        assertEquals(0, calls);
        assertEquals(0, screen.queued(), "Nothing is scheduled on a disabled plugin");
    }

    @Test
    void clickAfterPluginDisabledDoesNothing() {
        input.request(player, TITLE, record());
        screen.active = false;
        screen.done.accept("x");
        assertEquals(0, screen.queued());
        assertEquals(0, calls);
    }

    @Test
    void screenFreeIsFalseForOfflinePlayers() {
        when(player.isOnline()).thenReturn(false);
        assertEquals(false, TextInput.screenFree(player));
        verify(player, never()).getOpenInventory();
    }

    private static final class FakeScreen implements TextInput.Screen {
        boolean active = true;
        List<String> lastTitle;
        Consumer<String> done;
        Runnable cancel;
        Duration lastDelay;
        Runnable timeout;
        int timeoutsCancelled;
        int closed;
        private final List<Runnable> nextTick = new ArrayList<>();

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public void runNextTick(Runnable task) {
            nextTick.add(task);
        }

        @Override
        public Runnable runLater(Duration delay, Runnable task) {
            lastDelay = delay;
            timeout = task;
            return () -> {
                timeoutsCancelled++;
                if (timeout == task) {
                    timeout = null;
                }
            };
        }

        @Override
        public void show(Player player, List<String> titleLines, Consumer<String> done, Runnable cancel) {
            this.lastTitle = titleLines;
            this.done = done;
            this.cancel = cancel;
        }

        @Override
        public void close(UUID player) {
            closed++;
        }

        void fireTimeout() {
            timeout.run();
        }

        int queued() {
            return nextTick.size();
        }

        void tick() {
            List<Runnable> tasks = new ArrayList<>(nextTick);
            nextTick.clear();
            tasks.forEach(Runnable::run);
        }
    }
}
