package com.mystipixel.royalbazaar.gui;

import com.mystipixel.royalbazaar.util.Text;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Text entry through a native dialog (Paper's Dialog API): a window with a text field and
 * Done / Cancel buttons, built per prompt and sent to that player only. Nothing is placed in the
 * world, so there is no block to borrow, guard or restore, whatever happens to the player meanwhile.
 *
 * <p>The callback runs on the main thread at most once: with the typed text (trimmed) on Done, or
 * with "" on Cancel or Escape (the confirmation dialog's "no" action is also its exit action), so
 * callers treat it as cancelled. If the player never answers (disconnect, another dialog), the
 * button callbacks simply expire.
 */
public final class TextInput {

    private static final String FIELD = "input";
    static final int MAX_LENGTH = 64;
    /** How long the buttons keep working; the prompt is dead after this anyway. */
    private static final Duration LIFETIME = Duration.ofMinutes(5);

    private final JavaPlugin plugin;
    private final Supplier<String> confirmLabel;
    private final Supplier<String> cancelLabel;

    public TextInput(JavaPlugin plugin, Supplier<String> confirmLabel, Supplier<String> cancelLabel) {
        this.plugin = plugin;
        this.confirmLabel = confirmLabel;
        this.cancelLabel = cancelLabel;
    }

    /**
     * Ask {@code player} for a line of text. {@code hints} describe what to type and become the
     * dialog's title (lines made only of '^' are dropped: they pointed at the old sign's input line).
     */
    public void request(Player player, List<String> hints, Consumer<String> callback) {
        String title = titleOf(hints);
        AtomicBoolean answered = new AtomicBoolean();
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(LIFETIME).build();

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Text.color(title))
                        .canCloseWithEscape(true)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .inputs(List.of(DialogInput.text(FIELD, Text.color(title))
                                .labelVisible(false)
                                .maxLength(MAX_LENGTH)
                                .build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Text.color(confirmLabel.get()))
                                .action(DialogAction.customClick((response, audience) -> {
                                    answer(answered, callback, sanitize(response.getText(FIELD)));
                                }, once))
                                .build(),
                        ActionButton.builder(Text.color(cancelLabel.get()))
                                .action(DialogAction.customClick((response, audience) ->
                                        answer(answered, callback, ""), once))
                                .build())));

        // Leave the chest menu first; the dialog replaces it on screen.
        player.closeInventory();
        player.showDialog(dialog);
    }

    /** The hint lines as one title; lines made only of '^' (the old sign's arrow) are dropped. */
    static String titleOf(List<String> hints) {
        return hints.stream()
                .filter(line -> !line.replaceAll("&[0-9a-fk-or]", "").matches("\\^*"))
                .collect(Collectors.joining(" "));
    }

    /**
     * What the player typed, as callers may use it. The client enforces {@link #MAX_LENGTH}, but a
     * modified one can send anything, so control characters are dropped and the length is capped here.
     */
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

    private void answer(AtomicBoolean answered, Consumer<String> callback, String text) {
        if (answered.compareAndSet(false, true)) {
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(text));
        }
    }

    /** True when nothing but the player's own inventory is open, i.e. no other menu is on screen. */
    public static boolean showingOwnInventory(Player player) {
        InventoryType type = player.getOpenInventory().getTopInventory().getType();
        return type == InventoryType.CRAFTING || type == InventoryType.CREATIVE;
    }
}
