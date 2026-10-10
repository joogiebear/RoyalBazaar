package com.mystipixel.royalbazaar.gui;

import com.mystipixel.royalbazaar.message.MessageManager;
import com.mystipixel.royalbazaar.util.Text;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Asks a player for one line of text in a Paper dialog. Nothing is placed in the world.
 *
 * <p>The callback runs exactly once per request, on the main thread: the cleaned text on Done, or
 * {@code null} when no answer is coming (Cancel, Escape, quit, death, another inventory opened, a
 * newer request for the same player, or the timeout).
 */
public final class TextInput implements Listener {

    static final int MAX_LENGTH = 64;
    // Paper reports nothing when the client drops a dialog by itself, so unanswered prompts expire
    static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final String FIELD = "input";

    // the Paper side, replaced by a fake in tests
    interface Screen {
        boolean active();

        void runNextTick(Runnable task);

        // returns an action that cancels the task
        Runnable runLater(Duration delay, Runnable task);

        void show(Player player, List<String> titleLines, Consumer<String> done, Runnable cancel);

        void close(UUID player);
    }

    private record Pending(long token, Consumer<String> callback, Runnable cancelTimeout) {
    }

    private final Screen screen;
    private final Map<UUID, Pending> pending = new HashMap<>(); // main thread only
    private long lastToken;
    private volatile boolean shutDown;

    public TextInput(JavaPlugin plugin, MessageManager messages) {
        this(new PaperScreen(plugin, messages));
    }

    TextInput(Screen screen) {
        this.screen = screen;
    }

    /**
     * Ask for one line of text. The first title line is the dialog title, any others are shown under
     * it. The callback runs exactly once, on the main thread: the text, or null if no answer is coming.
     */
    public void request(Player player, List<String> titleLines, Consumer<String> callback) {
        UUID id = player.getUniqueId();
        // the old prompt answers now, not next tick, so a caller reopening its menu does it before
        // the new dialog goes up; looped in case that callback asked again
        Pending old;
        while ((old = pending.remove(id)) != null) {
            old.cancelTimeout().run();
            old.callback().accept(null);
        }
        long token = ++lastToken;
        Runnable cancelTimeout = screen.runLater(TIMEOUT, () -> expire(id, token));
        pending.put(id, new Pending(token, callback, cancelTimeout));
        player.closeInventory();
        screen.show(player, titleLines,
                text -> clicked(id, token, sanitize(text)),
                () -> clicked(id, token, null));
    }

    /** Close every open prompt without calling back. Call first thing in onDisable. */
    public void shutdown() {
        shutDown = true;
        for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
            entry.getValue().cancelTimeout().run();
            screen.close(entry.getKey());
        }
        pending.clear();
    }

    // button callbacks may not arrive on the main thread, so all state is touched a tick later
    private void clicked(UUID id, long token, String text) {
        if (shutDown || !screen.active()) {
            return;
        }
        screen.runNextTick(() -> {
            Pending p = pending.get(id);
            if (p == null || p.token() != token) {
                return;                  // superseded, expired or already answered
            }
            pending.remove(id);
            p.cancelTimeout().run();
            p.callback().accept(text);
        });
    }

    private void expire(UUID id, long token) {
        Pending p = pending.get(id);
        if (p != null && p.token() == token) {
            abandon(id, true);
        }
    }

    // callback a tick later: these come from inside events, where reopening a menu is unsafe
    private void abandon(UUID id, boolean closeDialog) {
        Pending p = pending.remove(id);
        if (p == null) {
            return;
        }
        p.cancelTimeout().run();
        if (closeDialog) {
            screen.close(id);
        }
        screen.runNextTick(() -> p.callback().accept(null));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        abandon(event.getPlayer().getUniqueId(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        abandon(event.getPlayer().getUniqueId(), true);
    }

    // an inventory replaces the dialog on the client, so its buttons can no longer be pressed
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        abandon(event.getPlayer().getUniqueId(), false);
    }

    /** True when the player is online, alive and has no other menu open, so a caller may reopen its own. */
    public static boolean screenFree(Player player) {
        if (!player.isOnline() || player.isDead()) {
            return false;
        }
        InventoryType type = player.getOpenInventory().getTopInventory().getType();
        return type == InventoryType.CRAFTING || type == InventoryType.CREATIVE;
    }

    // the dialog's max length is only a client hint; a modified client can send anything
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        raw.codePoints().filter(c -> !Character.isISOControl(c)).forEach(out::appendCodePoint);
        String text = out.toString().trim();
        return text.codePointCount(0, text.length()) <= MAX_LENGTH
                ? text : text.substring(0, text.offsetByCodePoints(0, MAX_LENGTH)).trim();
    }

    private static final class PaperScreen implements Screen {

        private final JavaPlugin plugin;
        private final MessageManager messages;

        PaperScreen(JavaPlugin plugin, MessageManager messages) {
            this.plugin = plugin;
            this.messages = messages;
        }

        @Override
        public boolean active() {
            return plugin.isEnabled();
        }

        @Override
        public void runNextTick(Runnable task) {
            try {
                Bukkit.getScheduler().runTask(plugin, task);
            } catch (IllegalPluginAccessException disabledMeanwhile) {
                // a click raced the disable; there is nobody left to answer
            }
        }

        @Override
        public Runnable runLater(Duration delay, Runnable task) {
            BukkitTask scheduled = Bukkit.getScheduler().runTaskLater(plugin, task, delay.toMillis() / 50);
            return scheduled::cancel;
        }

        @Override
        public void show(Player player, List<String> titleLines, Consumer<String> done, Runnable cancel) {
            Component title = Text.color(titleLines.isEmpty() ? "" : titleLines.get(0));
            List<? extends DialogBody> body = titleLines.stream().skip(1)
                    .map(line -> DialogBody.plainMessage(Text.color(line)))
                    .toList();
            ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(TIMEOUT).build();

            Dialog dialog = Dialog.create(factory -> factory.empty()
                    .base(DialogBase.builder(title)
                            .canCloseWithEscape(true)     // Escape runs the cancel button's action
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(body)
                            .inputs(List.of(DialogInput.text(FIELD, title)
                                    .labelVisible(false)
                                    .maxLength(MAX_LENGTH)
                                    .build()))
                            .build())
                    .type(DialogType.confirmation(
                            ActionButton.builder(Text.color(messages.get("input.confirm", "&aDone")))
                                    .action(DialogAction.customClick(
                                            (response, audience) -> done.accept(response.getText(FIELD)), once))
                                    .build(),
                            ActionButton.builder(Text.color(messages.get("input.cancel", "&cCancel")))
                                    .action(DialogAction.customClick((response, audience) -> cancel.run(), once))
                                    .build())));
            player.showDialog(dialog);
        }

        @Override
        public void close(UUID id) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.closeDialog();
            }
        }
    }
}
