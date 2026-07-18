package com.mystipixel.royalbazaar.gui.menu;

import com.mystipixel.royalbazaar.hooks.EcoHook;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A single menu loaded from a {@code gui/*.yml} file in the EcoMenus dialect: {@code title},
 * {@code rows}, page {@code mask} (items + pattern), fixed {@code slots} (each with a row/column
 * location and left/right-click effect lists), the paging arrows, and — RoyalBazaar's one extension —
 * a {@code content:} block whose template fills the mask's {@code 0} slots with live bazaar items.
 *
 * <p>Positions in the file are EcoMenus-style {@code row}/{@code column} (both 1-based); they are
 * converted to 0-based inventory indices here via {@code (row-1)*9 + (column-1)}.
 */
public final class MenuTemplate {

    /** The paging arrow config (item + resolved index). */
    public record Arrow(ItemSpec item, int index, boolean enabled) {
    }

    /**
     * The category rail: a strip of slots, one per configured category, so a player can jump straight
     * between categories without leaving the menu. Slots are listed as row/column pairs and drawn
     * over whatever the mask put there.
     */
    public record Rail(List<Integer> indices, List<String> lore, boolean glintSelected) {
    }

    /** The content region: template item spec, its lore, and the click effects each generated item gets. */
    public record Content(ItemSpec template, List<String> lore, List<MenuEffect> leftClick, List<MenuEffect> rightClick) {
    }

    private final String title;
    private final int rows;
    private final ItemStack maskFiller;          // null when the mask is all-0
    private final List<Integer> contentSlots;    // indices marked 0 in the mask
    private final List<MenuSlot> slots;
    private final Arrow forwards;
    private final Arrow backwards;
    private final Content content;               // null for static menus (main)
    private final Content groupContent;          // null unless the menu can render group icons
    private final Rail rail;                     // null when the menu shows no category rail

    private MenuTemplate(String title, int rows, ItemStack maskFiller, List<Integer> contentSlots,
                         List<MenuSlot> slots, Arrow forwards, Arrow backwards, Content content,
                         Content groupContent, Rail rail) {
        this.title = title;
        this.rows = rows;
        this.maskFiller = maskFiller;
        this.contentSlots = contentSlots;
        this.slots = slots;
        this.forwards = forwards;
        this.backwards = backwards;
        this.content = content;
        this.groupContent = groupContent;
        this.rail = rail;
    }

    // ------------------------------------------------------------------ loading

    public static MenuTemplate load(File file, String defaultTitle, int defaultRows) {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        String title = cfg.getString("title", defaultTitle);
        int rows = Math.max(1, Math.min(6, cfg.getInt("rows", defaultRows)));
        int size = rows * 9;

        // --- mask (first page only; multi-page paging handled by the content region) ---
        ItemStack filler = null;
        List<Integer> contentSlots = new ArrayList<>();
        ConfigurationSection page1 = firstPageMask(cfg);
        if (page1 != null) {
            List<String> maskItems = page1.getStringList("items");
            List<String> pattern = page1.getStringList("pattern");
            String fillerLookup = maskItems.isEmpty() ? null : maskItems.get(0);
            if (fillerLookup != null) {
                filler = ItemSpec.parse(fillerLookup + " name:\" \"").build(EcoHookHolder.get(), Map.of(), List.of());
            }
            for (int r = 0; r < pattern.size() && r < rows; r++) {
                String line = pattern.get(r);
                for (int c = 0; c < 9 && c < line.length(); c++) {
                    if (line.charAt(c) == '0') {
                        contentSlots.add(r * 9 + c);
                    }
                }
            }
        }

        // --- fixed slots ---
        List<MenuSlot> slots = new ArrayList<>();
        List<Map<?, ?>> rawSlots = cfg.getMapList("slots");
        for (Map<?, ?> raw : rawSlots) {
            MenuSlot slot = parseSlot(raw, size);
            if (slot != null) {
                slots.add(slot);
            }
        }

        // --- arrows ---
        Arrow forwards = parseArrow(cfg.getConfigurationSection("forwards-arrow"), size);
        Arrow backwards = parseArrow(cfg.getConfigurationSection("backwards-arrow"), size);

        // --- content regions (RoyalBazaar extension) ---
        // 'content' fills the mask's 0-slots with products; 'group-content' fills them with group
        // icons instead, when the category being rendered declares groups.
        Content content = parseContent(cfg.getConfigurationSection("content"));
        Content groupContent = parseContent(cfg.getConfigurationSection("group-content"));

        Rail rail = parseRail(cfg.getConfigurationSection("category-rail"), size);

        return new MenuTemplate(title, rows, filler, contentSlots, slots, forwards, backwards,
                content, groupContent, rail);
    }

    /**
     * Read the category rail. Accepts either an explicit {@code slots:} list of row/column pairs, or the
     * shorthand of one {@code column:} plus a list of {@code rows:} — the common case of a vertical strip
     * down one side.
     */
    private static Rail parseRail(ConfigurationSection sec, int size) {
        if (sec == null || !sec.getBoolean("enabled", true)) {
            return null;
        }
        List<Integer> indices = new ArrayList<>();
        List<Map<?, ?>> explicit = getMapList(sec, "slots");
        if (!explicit.isEmpty()) {
            for (Map<?, ?> entry : explicit) {
                int index = toIndex(entry.get("row"), entry.get("column"));
                if (index >= 0 && index < size) {
                    indices.add(index);
                }
            }
        } else {
            int column = sec.getInt("column", 1);
            for (int row : sec.getIntegerList("rows")) {
                int index = toIndex(row, column);
                if (index >= 0 && index < size) {
                    indices.add(index);
                }
            }
        }
        if (indices.isEmpty()) {
            return null;
        }
        return new Rail(List.copyOf(indices), sec.getStringList("lore"), sec.getBoolean("glint-selected", true));
    }

    /** Row/column (both 1-indexed) to a raw inventory index; -1 when either is missing or out of range. */
    private static int toIndex(Object row, Object column) {
        if (!(row instanceof Number r) || !(column instanceof Number c)) {
            return -1;
        }
        return toIndex(r.intValue(), c.intValue());
    }

    private static int toIndex(int row, int column) {
        if (row < 1 || column < 1 || column > 9) {
            return -1;
        }
        return (row - 1) * 9 + (column - 1);
    }

    private static Content parseContent(ConfigurationSection sec) {
        if (sec == null) {
            return null;
        }
        ConfigurationSection tmpl = sec.getConfigurationSection("template");
        if (tmpl == null) {
            return null;
        }
        return new Content(
                ItemSpec.parse(buildInlineItem(tmpl)),
                tmpl.getStringList("lore"),
                MenuEffect.parseList(getMapList(tmpl, "left-click")),
                MenuEffect.parseList(getMapList(tmpl, "right-click")));
    }

    private static ConfigurationSection firstPageMask(FileConfiguration cfg) {
        List<Map<?, ?>> pages = cfg.getMapList("pages");
        if (pages.isEmpty()) {
            return null;
        }
        Object mask = pages.get(0).get("mask");
        if (mask instanceof ConfigurationSection cs) {
            return cs;
        }
        if (mask instanceof Map<?, ?> m) {
            YamlConfiguration tmp = new YamlConfiguration();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                tmp.set(String.valueOf(e.getKey()), e.getValue());
            }
            return tmp;
        }
        return null;
    }

    private static MenuSlot parseSlot(Map<?, ?> raw, int size) {
        Object itemObj = raw.get("item");
        if (itemObj == null) {
            return null;
        }
        int index = slotIndex(raw, size);
        if (index < 0) {
            return null;
        }
        List<String> lore = new ArrayList<>();
        Object loreObj = raw.get("lore");
        if (loreObj instanceof List<?> list) {
            for (Object o : list) {
                lore.add(String.valueOf(o));
            }
        }
        return new MenuSlot(index, ItemSpec.parse(String.valueOf(itemObj)), lore,
                MenuEffect.parseList(castMapList(raw.get("left-click"))),
                MenuEffect.parseList(castMapList(raw.get("right-click"))));
    }

    private static Arrow parseArrow(ConfigurationSection sec, int size) {
        if (sec == null) {
            return null;
        }
        Object rowObj = sec.get("row");
        Object colObj = sec.get("column");
        if (rowObj == null && colObj == null) {
            ConfigurationSection loc = sec.getConfigurationSection("location");
            if (loc != null) {
                rowObj = loc.get("row");
                colObj = loc.get("column");
            }
        }
        return new Arrow(ItemSpec.parse(sec.getString("item", "arrow")), toIndex(rowObj, colObj, size),
                sec.getBoolean("enabled", true));
    }

    /**
     * Resolve a slot's 1-based {@code row}/{@code column} into a 0-based inventory index. Row/column sit
     * directly on the slot (the eco-menus convention); a legacy nested {@code location: {row, column}}
     * is still accepted as a fallback.
     */
    private static int slotIndex(Map<?, ?> raw, int size) {
        Object rowObj = raw.get("row");
        Object colObj = raw.get("column");
        if (rowObj == null && colObj == null) {
            Object loc = raw.get("location");
            if (loc instanceof ConfigurationSection cs) {
                rowObj = cs.get("row");
                colObj = cs.get("column");
            } else if (loc instanceof Map<?, ?> m) {
                rowObj = m.get("row");
                colObj = m.get("column");
            }
        }
        return toIndex(rowObj, colObj, size);
    }

    private static int toIndex(Object rowObj, Object colObj, int size) {
        int index = (intOf(rowObj, 1) - 1) * 9 + (intOf(colObj, 1) - 1);
        return index >= 0 && index < size ? index : -1;
    }

    // ------------------------------------------------------------------ rendering

    /** The category rail, or null when this menu doesn't show one. */
    public Rail rail() {
        return rail;
    }

    public String title() {
        return title;
    }

    public int rows() {
        return rows;
    }

    public int size() {
        return rows * 9;
    }

    public List<Integer> contentSlots() {
        return contentSlots;
    }

    public List<MenuSlot> slots() {
        return slots;
    }

    public Arrow forwards() {
        return forwards;
    }

    public Arrow backwards() {
        return backwards;
    }

    public Content content() {
        return content;
    }

    /** Template for group icons; null when this menu can't render groups. */
    public Content groupContent() {
        return groupContent;
    }

    /** Paint the mask filler across every non-content slot. */
    public void applyFiller(Inventory inv) {
        if (maskFiller == null) {
            return;
        }
        for (int i = 0; i < inv.getSize(); i++) {
            if (!contentSlots.contains(i)) {
                inv.setItem(i, maskFiller.clone());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<Map<?, ?>> getMapList(ConfigurationSection sec, String key) {
        return sec.getMapList(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> castMapList(Object o) {
        if (o instanceof List<?> list) {
            List<Map<?, ?>> out = new ArrayList<>();
            for (Object e : list) {
                if (e instanceof Map<?, ?> m) {
                    out.add(m);
                }
            }
            return out;
        }
        return List.of();
    }

    /** Rebuild the inline item string (material + name) from a content/template section. */
    private static String buildInlineItem(ConfigurationSection tmpl) {
        String item = tmpl.getString("item", "minecraft:stone");
        String name = tmpl.getString("name");
        return name == null ? item : item + " name:\"" + name + "\"";
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Holds the plugin's {@link EcoHook} so static template loading can resolve mask filler items.
     * Set once during plugin enable.
     */
    public static final class EcoHookHolder {
        private static EcoHook eco;

        private EcoHookHolder() {
        }

        public static void set(EcoHook hook) {
            eco = hook;
        }

        public static EcoHook get() {
            return eco;
        }
    }
}
