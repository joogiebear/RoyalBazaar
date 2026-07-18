package com.mystipixel.royalbazaar.config;

import com.mystipixel.royalbazaar.hooks.EcoShopHook;
import com.mystipixel.royalbazaar.market.MarketItem;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * One {@code categories/*.yml} file: display metadata for the main-menu icon plus a {@code defaults:}
 * block and the item list. Each item inherits the category defaults and overrides only the fields it
 * cares about — so 300 items don't repeat the same five tuning lines.
 *
 * <p>A category may optionally declare {@code groups:} and tag items with {@code group: <id>}, which
 * inserts a middle menu: category → group → product (e.g. Mining → Iron → Enchanted Iron). Items with
 * no {@code group} render straight into the category as before, so a category can be flat or grouped
 * and existing configs keep working untouched.
 */
public final class CategoryConfig {

    /** One item family inside a category. {@code order} decides its position in the group grid. */
    public record Group(String id, String name, String icon, int order, int slot) {}

    /** Tuning that every item inherits unless it overrides the field. */
    public record Defaults(double spread, double elasticity, double reversionRate,
                           double floorPct, double ceilingPct) {

        static Defaults from(ConfigurationSection sec) {
            if (sec == null) {
                return new Defaults(0.05, 50_000, 0.02, 0.4, 3.0);
            }
            return new Defaults(
                    sec.getDouble("spread", 0.05),
                    sec.getDouble("elasticity", 50_000),
                    sec.getDouble("reversion_rate", 0.02),
                    sec.getDouble("floor_pct", 0.4),
                    sec.getDouble("ceiling_pct", 3.0));
        }
    }

    private final String id;
    private final String displayName;
    private final String icon;      // eco lookup id for the main-menu icon
    private final int slot;         // 1-based position in the main menu
    private final Defaults defaults;
    private final List<Group> groups;
    private final ConfigurationSection itemsSection;
    private final Logger logger;

    private CategoryConfig(String id, String displayName, String icon, int slot, Defaults defaults,
                           List<Group> groups, ConfigurationSection itemsSection, Logger logger) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.slot = slot;
        this.defaults = defaults;
        this.groups = groups;
        this.itemsSection = itemsSection;
        this.logger = logger;
    }

    public static CategoryConfig load(String id, ConfigurationSection root, Logger logger) {
        String display = root.getString("name", id);
        String icon = root.getString("icon", "minecraft:chest");
        int slot = root.getInt("slot", 0);
        Defaults defaults = Defaults.from(root.getConfigurationSection("defaults"));
        return new CategoryConfig(id, display, icon, slot, defaults,
                loadGroups(root.getConfigurationSection("groups")),
                root.getConfigurationSection("items"), logger);
    }

    /** Declared order wins; ties fall back to config order, so an un-ordered groups: block stays stable. */
    private static List<Group> loadGroups(ConfigurationSection sec) {
        if (sec == null) {
            return List.of();
        }
        List<Group> out = new ArrayList<>();
        int seq = 0;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection gs = sec.getConfigurationSection(key);
            if (gs == null) {
                continue;
            }
            out.add(new Group(key, gs.getString("name", key), gs.getString("icon", "minecraft:chest"),
                    gs.getInt("order", seq),
                    slotOf(gs)));
            seq++;
        }
        out.sort(Comparator.comparingInt(Group::order));
        return List.copyOf(out);
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public String icon() { return icon; }
    public int slot() { return slot; }

    /**
     * An explicit grid position for an entry, as either {@code slot:} (raw inventory index) or a
     * {@code row:}/{@code column:} pair (both 1-indexed, matching how menu buttons are placed).
     * Returns -1 when none is set, which means "flow into the next free slot" as before.
     */
    private static int slotOf(ConfigurationSection sec) {
        if (sec.contains("slot")) {
            return sec.getInt("slot", -1);
        }
        int row = sec.getInt("row", -1);
        int column = sec.getInt("column", -1);
        if (row < 1 || column < 1 || column > 9) {
            return -1;
        }
        return (row - 1) * 9 + (column - 1);
    }

    /** Declared groups, in display order. Empty = this category renders its items directly. */
    public List<Group> groups() { return groups; }

    public boolean hasGroups() { return !groups.isEmpty(); }

    /**
     * Materialise every configured item, applying default inheritance and EcoShop anchoring:
     * <ul>
     *   <li>{@code base_price: auto} → the EcoShop buy value (the item's canonical price)</li>
     *   <li>{@code npc_floor: true} → floor pinned to the EcoShop sell value (NPC never undercut)</li>
     *   <li>{@code npc_ceiling: true} → ceiling pinned to the EcoShop buy value</li>
     * </ul>
     * Each falls back to its {@code *_pct} default when EcoShop is absent or the item isn't listed.
     */
    public List<MarketItem> buildItems(EcoShopHook shop) {
        List<MarketItem> out = new ArrayList<>();
        if (itemsSection == null) {
            return out;
        }
        for (String key : itemsSection.getKeys(false)) {
            ConfigurationSection is = itemsSection.getConfigurationSection(key);
            if (is == null) {
                continue;
            }
            String itemId = is.getString("item");
            if (itemId == null || itemId.isBlank()) {
                logger.warning("[category " + id + "] item '" + key + "' has no 'item:' lookup — skipping.");
                continue;
            }

            double base = resolveBase(is, itemId, shop, key);
            if (base <= 0) {
                continue; // resolveBase already logged
            }

            double floor = is.getBoolean("npc_floor", false)
                    ? orElse(shop.sellValue(itemId), base * is.getDouble("floor_pct", defaults.floorPct()))
                    : base * is.getDouble("floor_pct", defaults.floorPct());
            double ceiling = is.getBoolean("npc_ceiling", false)
                    ? orElse(shop.buyValue(itemId), base * is.getDouble("ceiling_pct", defaults.ceilingPct()))
                    : base * is.getDouble("ceiling_pct", defaults.ceilingPct());
            // Guard against a mis-ordered anchor (floor must sit below ceiling).
            if (floor >= ceiling) {
                floor = base * defaults.floorPct();
                ceiling = base * defaults.ceilingPct();
            }

            MarketItem item = new MarketItem(
                    itemId,
                    id,
                    resolveGroup(is, key),
                    is.getString("display", ""),
                    base,
                    is.getDouble("spread", defaults.spread()),
                    is.getDouble("elasticity", defaults.elasticity()),
                    is.getDouble("reversion_rate", defaults.reversionRate()),
                    floor,
                    ceiling);
            item.setPinnedSlot(slotOf(is));
            out.add(item);
        }
        return out;
    }

    /**
     * The item's {@code group:}, or null if it belongs directly to the category. A group id that isn't
     * declared in {@code groups:} is dropped to null and warned about — a typo'd group would otherwise
     * make the item vanish from the GUI entirely (it'd belong to a group no menu ever opens).
     */
    private String resolveGroup(ConfigurationSection is, String key) {
        String group = is.getString("group");
        if (group == null || group.isBlank()) {
            return null;
        }
        if (groups.stream().noneMatch(g -> g.id().equals(group))) {
            logger.warning("[category " + id + "] item '" + key + "' references group '" + group
                    + "', which isn't declared under groups: — showing it directly in the category.");
            return null;
        }
        return group;
    }

    /** base_price is either a positive number or the literal {@code auto} (→ EcoShop buy value). */
    private double resolveBase(ConfigurationSection is, String itemId, EcoShopHook shop, String key) {
        String raw = is.getString("base_price", "");
        if ("auto".equalsIgnoreCase(raw.trim())) {
            Double anchored = shop.buyValue(itemId);
            if (anchored == null || anchored <= 0) {
                logger.warning("[category " + id + "] item '" + key + "' uses base_price: auto but EcoShop has no"
                        + " price for '" + itemId + "' — skipping.");
                return -1;
            }
            return anchored;
        }
        double base = is.getDouble("base_price", -1);
        if (base <= 0) {
            logger.warning("[category " + id + "] item '" + key + "' has no positive base_price — skipping.");
        }
        return base;
    }

    private double orElse(Double value, double fallback) {
        return (value == null || value <= 0) ? fallback : value;
    }
}
