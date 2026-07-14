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
 * A single menu loaded from a {@code menus/*.yml} file in the EcoMenus dialect: {@code title},
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

    private MenuTemplate(String title, int rows, ItemStack maskFiller, List<Integer> contentSlots,
                         List<MenuSlot> slots, Arrow forwards, Arrow backwards, Content content) {
        this.title = title;
        this.rows = rows;
        this.maskFiller = maskFiller;
        this.contentSlots = contentSlots;
        this.slots = slots;
        this.forwards = forwards;
        this.backwards = backwards;
        this.content = content;
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

        // --- content region (RoyalBazaar extension) ---
        Content content = null;
        ConfigurationSection contentSec = cfg.getConfigurationSection("content");
        if (contentSec != null) {
            ConfigurationSection tmpl = contentSec.getConfigurationSection("template");
            if (tmpl != null) {
                ItemSpec spec = ItemSpec.parse(buildInlineItem(tmpl));
                content = new Content(
                        spec,
                        tmpl.getStringList("lore"),
                        MenuEffect.parseList(getMapList(tmpl, "left-click")),
                        MenuEffect.parseList(getMapList(tmpl, "right-click")));
            }
        }

        return new MenuTemplate(title, rows, filler, contentSlots, slots, forwards, backwards, content);
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
        int index = locationIndex(raw.get("location"), size);
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
        int index = locationIndex(sec.get("location"), size);
        return new Arrow(ItemSpec.parse(sec.getString("item", "arrow")), index, sec.getBoolean("enabled", true));
    }

    /** Resolve an EcoMenus {@code location: {row, column}} (1-based) into a 0-based inventory index. */
    private static int locationIndex(Object locationObj, int size) {
        int row = 1;
        int column = 1;
        if (locationObj instanceof ConfigurationSection cs) {
            row = cs.getInt("row", 1);
            column = cs.getInt("column", 1);
        } else if (locationObj instanceof Map<?, ?> m) {
            row = intOf(m.get("row"), 1);
            column = intOf(m.get("column"), 1);
        }
        int index = (row - 1) * 9 + (column - 1);
        return index >= 0 && index < size ? index : -1;
    }

    // ------------------------------------------------------------------ rendering

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
