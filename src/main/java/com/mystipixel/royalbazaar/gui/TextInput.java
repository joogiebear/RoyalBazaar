package com.mystipixel.royalbazaar.gui;

import com.mystipixel.royalbazaar.message.MessageManager;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
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
 * Asks a player for one line of text in a Paper dialog with Done and Cancel buttons. Nothing is
 * placed in the world.
 *
 * <p>The callback runs exactly once per request, on the main thread: the cleaned text on Done
 * (possibly empty), or {@code null} when no answer is coming (Cancel, Escape, quit, death, another
 * inventory opened, a newer request for the same player, or the timeout). After {@link #shutdown()}
 * nothing calls back.
 */
public final class TextInput implements Listener {

    static final int MAX_LENGTH = 64;
    // Paper reports nothing when the client drops a dialog by itself, so unanswered prompts expire
    static final Duration TIMEOUT = Duration.ofMinutes(5);

    // the server side of a prompt, replaced by a fake in tests
    interface Host {
        boolean enabled();

        void nextTick(Runnable task);

        // returns what cancels the task
        Runnable later(Duration delay, Runnable task);

        // done gets the raw typed text; cancel runs on Cancel or Escape
        void show(Player player, List<String> lines, Consumer<String> done, Runnable cancel);

        void close(Player player);
    }

    private record Pending(Player player, long token, Consumer<String> callback, Runnable cancelTimeout,
                           boolean dismissed) {
    }

    private final Host host;
    private final Map<UUID, Pending> pending = new HashMap<>(); // main thread only
    private long lastToken;
    // read from click callbacks, which may arrive off the main thread
    private volatile boolean closed;

    public TextInput(JavaPlugin plugin, MessageManager messages) {
        this(new PaperHost(plugin, messages));
    }

    TextInput(Host host) {
        this.host = host;
    }

    /**
     * Ask for one line of text. The first line is the dialog title, any others are shown under it.
     * The callback runs exactly once, on the main thread: the text, or null if no answer is coming.
     */
    public void request(Player player, List<String> titleLines, Consumer<String> callback) {
        if (closed) {
            return;
        }
        UUID id = player.getUniqueId();
        long token = ++lastToken;
        Runnable cancelTimeout = host.later(TIMEOUT, () -> {
            if (isCurrent(id, token)) {
                host.close(player);
                finish(id, token, null);
            }
        });
        Pending previous = pending.put(id, new Pending(player, token, callback, cancelTimeout, false));
        if (previous != null) {
            // registered first, so the old caller sees isWaiting and leaves the screen alone
            previous.cancelTimeout().run();
            previous.callback().accept(null);
            if (!isCurrent(id, token)) {
                return;                  // that callback asked again and replaced this prompt
            }
        }
        player.closeInventory();
        host.show(player, titleLines,
                raw -> answer(id, token, sanitize(raw)),
                () -> answer(id, token, null));
    }

    /** True while the player has a prompt that has not been answered yet. */
    public boolean isWaiting(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    /** Close every open prompt without calling back. Call first thing in onDisable. */
    public void shutdown() {
        closed = true;
        for (Pending p : pending.values()) {
            p.cancelTimeout().run();
            host.close(p.player());
        }
        pending.clear();
    }

    // button callbacks may not arrive on the main thread, so all state is touched a tick later
    private void answer(UUID id, long token, String text) {
        if (closed || !host.enabled()) {
            return;
        }
        host.nextTick(() -> finish(id, token, text));
    }

    private boolean isCurrent(UUID id, long token) {
        Pending p = pending.get(id);
        return p != null && p.token() == token;
    }

    private void finish(UUID id, long token, String text) {
        if (!isCurrent(id, token)) {
            return;                      // superseded, expired or already answered
        }
        Pending p = pending.remove(id);
        p.cancelTimeout().run();
        p.callback().accept(p.dismissed() ? null : text);
    }

    // next tick: these come from inside events, and the caller should see the player gone, dead or
    // in the other menu before deciding whether to reopen its own
    private void dismiss(UUID id) {
        Pending p = pending.get(id);
        if (p != null && !closed) {
            // An answer may already be queued; invalidate it before the next tick can deliver it.
            pending.put(id, new Pending(p.player(), p.token(), p.callback(), p.cancelTimeout(), true));
            long token = p.token();
            host.nextTick(() -> finish(id, token, null));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        dismiss(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (isWaiting(player)) {
            host.close(player);
            dismiss(player.getUniqueId());
        }
    }

    // an inventory replaces the dialog on the client, so its buttons can no longer be pressed
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        dismiss(event.getPlayer().getUniqueId());
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

    private static final class PaperHost implements Host {

        private static final String FIELD = "input";
        private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

        private final JavaPlugin plugin;
        private final MessageManager messages;

        PaperHost(JavaPlugin plugin, MessageManager messages) {
            this.plugin = plugin;
            this.messages = messages;
        }

        // Plugin-specific: how a message key and a colour-coded line are read. Keep the fallbacks
        // identical to the defaults shipped in messages.yml.
        private String label(String key, String fallback) {
            return messages.get(key, fallback);
        }

        private static Component color(String line) {
            return AMP.deserialize(line == null ? "" : line);
        }

        @Override
        public boolean enabled() {
            return plugin.isEnabled();
        }

        @Override
        public void nextTick(Runnable task) {
            try {
                Bukkit.getScheduler().runTask(plugin, task);
            } catch (IllegalPluginAccessException disabledMeanwhile) {
                // a click raced the disable; there is nobody left to answer
            }
        }

        @Override
        public Runnable later(Duration delay, Runnable task) {
            BukkitTask scheduled = Bukkit.getScheduler().runTaskLater(plugin, task, delay.toMillis() / 50);
            return scheduled::cancel;
        }

        @Override
        public void show(Player player, List<String> lines, Consumer<String> done, Runnable cancel) {
            Component title = color(lines.isEmpty() ? "" : lines.get(0));
            List<DialogBody> body = lines.stream().skip(1)
                    .<DialogBody>map(line -> DialogBody.plainMessage(color(line)))
                    .toList();
            ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(TIMEOUT).build();
            Dialog dialog = Dialog.create(factory -> factory.empty()
                    .base(DialogBase.builder(title)
                            .canCloseWithEscape(true)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(body)
                            .inputs(List.of(DialogInput.text(FIELD, title)
                                    .labelVisible(false)
                                    .maxLength(MAX_LENGTH)
                                    .build()))
                            .build())
                    // a confirmation dialog's exit action is its second button, so Escape counts as Cancel
                    .type(DialogType.confirmation(
                            ActionButton.builder(color(label("input.confirm", "&aDone")))
                                    .action(DialogAction.customClick(
                                            (response, audience) -> done.accept(response.getText(FIELD)), once))
                                    .build(),
                            ActionButton.builder(color(label("input.cancel", "&cCancel")))
                                    .action(DialogAction.customClick((response, audience) -> cancel.run(), once))
                                    .build())));
            player.showDialog(dialog);
        }

        @Override
        public void close(Player player) {
            if (player.isOnline()) {
                player.closeDialog();
            }
        }
    }
}
