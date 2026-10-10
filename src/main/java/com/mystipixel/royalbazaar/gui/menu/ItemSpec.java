package com.mystipixel.royalbazaar.gui.menu;

import com.mystipixel.royalbazaar.hooks.EcoHook;
import com.mystipixel.royalbazaar.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parses the EcoMenus inline item syntax: an eco lookup id, then flags and {@code key:"value"} modifiers.
 * <pre>golden_horse_armor hide_enchants hide_attributes name:"&fCity Projects"
 * player_head texture:&lt;base64&gt;
 * player_head head:%player%</pre>
 * Modifiers may contain {@code %placeholders%}, filled in by {@link #build}.
 */
public final class ItemSpec {

    private final String lookupId;
    private final String rawName;      // may be null
    private final String texture;      // base64 head texture, may be null
    private final String head;         // player-head owner name/%placeholder%, may be null
    private final boolean hideEnchants;
    private final boolean hideAttributes;

    private ItemSpec(String lookupId, String rawName, String texture, String head,
                     boolean hideEnchants, boolean hideAttributes) {
        this.lookupId = lookupId;
        this.rawName = rawName;
        this.texture = texture;
        this.head = head;
        this.hideEnchants = hideEnchants;
        this.hideAttributes = hideAttributes;
    }

    public String lookupId() {
        return lookupId;
    }

    public static ItemSpec parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ItemSpec("minecraft:stone", null, null, null, false, false);
        }
        List<String> tokens = tokenize(raw.trim());
        String lookup = tokens.isEmpty() ? "minecraft:stone" : tokens.get(0);
        String name = null;
        String texture = null;
        String head = null;
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
            } else if (t.regionMatches(true, 0, "texture:", 0, 8)) {
                texture = stripQuotes(t.substring(8));
            } else if (t.regionMatches(true, 0, "head:", 0, 5)) {
                head = stripQuotes(t.substring(5));
            }
        }
        return new ItemSpec(lookup, name, texture, head, hideEnch, hideAttr);
    }

    public ItemStack build(EcoHook eco, Map<String, String> placeholders, List<String> lore) {
        ItemStack item = eco.resolve(apply(lookupId, placeholders), 1);
        if (item == null) {
            item = new ItemStack(Material.STONE);
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
            applyHeadTexture(item, meta, placeholders);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void applyHeadTexture(ItemStack item, ItemMeta meta, Map<String, String> placeholders) {
        if (item.getType() != Material.PLAYER_HEAD || !(meta instanceof SkullMeta skull)) {
            return;
        }
        try {
            if (texture != null && !texture.isBlank()) {
                com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", texture));
                skull.setPlayerProfile(profile);
            } else if (head != null && !head.isBlank()) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(apply(head, placeholders)));
            }
        } catch (Throwable ignored) {
            // a malformed texture must never break the menu
        }
    }

    // split on spaces, keeping quoted segments together
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
