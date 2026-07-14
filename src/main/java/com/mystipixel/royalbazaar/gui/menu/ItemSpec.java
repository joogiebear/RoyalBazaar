package com.mystipixel.royalbazaar.gui.menu;

import com.mystipixel.royalbazaar.hooks.EcoHook;
import com.mystipixel.royalbazaar.util.Text;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the EcoMenus inline item syntax, e.g.
 * <pre>golden_horse_armor hide_enchants hide_attributes name:"&fCity Projects"</pre>
 * The first token is an eco lookup id (vanilla or {@code ecoitem:...}); the rest are flags
 * ({@code hide_enchants}, {@code hide_attributes}) and {@code key:"value"} modifiers ({@code name}).
 * Lore is supplied separately from the slot's {@code lore:} list.
 *
 * <p>Modifiers may contain {placeholders}; call {@link #build} with the placeholder map at render time.
 */
public final class ItemSpec {

    private final String lookupId;
    private final String rawName;      // may be null
    private final boolean hideEnchants;
    private final boolean hideAttributes;

    private ItemSpec(String lookupId, String rawName, boolean hideEnchants, boolean hideAttributes) {
        this.lookupId = lookupId;
        this.rawName = rawName;
        this.hideEnchants = hideEnchants;
        this.hideAttributes = hideAttributes;
    }

    public String lookupId() {
        return lookupId;
    }

    public static ItemSpec parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ItemSpec("minecraft:stone", null, false, false);
        }
        List<String> tokens = tokenize(raw.trim());
        String lookup = tokens.isEmpty() ? "minecraft:stone" : tokens.get(0);
        String name = null;
        boolean hideEnch = false;
        boolean hideAttr = false;
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equalsIgnoreCase("hide_enchants")) {
                hideEnch = true;
            } else if (t.equalsIgnoreCase("hide_attributes")) {
                hideAttr = true;
            } else if (t.regionMatches(true, 0, "name:", 0, 5)) {
                name = stripQuotes(t.substring(5));
            }
            // other key:value modifiers (unbreaking:1, etc.) can be added here as needed
        }
        return new ItemSpec(lookup, name, hideEnch, hideAttr);
    }

    /** Build the stack, resolving the lookup via eco and filling {placeholders} in name/lore. */
    public ItemStack build(EcoHook eco, java.util.Map<String, String> placeholders, List<String> lore) {
        ItemStack item = eco.resolve(apply(lookupId, placeholders), 1);
        if (item == null) {
            item = new ItemStack(org.bukkit.Material.STONE);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (rawName != null) {
                meta.displayName(Text.color(apply(rawName, placeholders)));
            }
            if (lore != null && !lore.isEmpty()) {
                List<net.kyori.adventure.text.Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    lines.add(Text.color(apply(line, placeholders)));
                }
                meta.lore(lines);
            }
            if (hideEnchants) {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            if (hideAttributes) {
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    // ---- token helpers ----

    /** Split on spaces but keep quoted segments (so name:"a b c" stays one token). */
    private static List<String> tokenize(String raw) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
                cur.append(ch);
            } else if (ch == ' ' && !inQuotes) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String apply(String input, java.util.Map<String, String> placeholders) {
        if (input == null) {
            return "";
        }
        String result = input;
        for (java.util.Map.Entry<String, String> e : placeholders.entrySet()) {
            result = result.replace("%" + e.getKey() + "%", e.getValue());
        }
        return result;
    }
}
