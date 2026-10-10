package com.mystipixel.royalbazaar.config;

import com.mystipixel.royalbazaar.hooks.EcoShopHook;
import com.mystipixel.royalbazaar.market.MarketItem;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * One {@code categories/*.yml} file. Items inherit the {@code defaults:} block and override only what
 * they set. Optional {@code groups:} add a middle menu (category, group, product); items without a
 * {@code group} sit directly in the category.
 */
public final class CategoryConfig {

    /** One item family inside a category; {@code order} sets its position in the group grid. */
    public record Group(String id, String name, String icon, int order, int slot) {}

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
    private final int slot;         // where this category sits on the category rail / icon grid
    private final Defaults defaults;
    private final List<Group> groups;
    private final ConfigurationSection itemsSection;
    private final Logger logger;
    private final boolean npcArbitrageGuard;
    private final boolean skipNpcConflicts;

    private CategoryConfig(String id, String displayName, String icon, int slot, Defaults defaults,
                           List<Group> groups, ConfigurationSection itemsSection, Logger logger,
                           boolean npcArbitrageGuard, boolean skipNpcConflicts) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.slot = slot;
        this.defaults = defaults;
        this.groups = groups;
        this.itemsSection = itemsSection;
        this.logger = logger;
        this.npcArbitrageGuard = npcArbitrageGuard;
        this.skipNpcConflicts = skipNpcConflicts;
    }

    public static CategoryConfig load(String id, ConfigurationSection root, Logger logger,
                                      boolean npcArbitrageGuard, boolean skipNpcConflicts) {
        String display = root.getString("name", id);
        String icon = root.getString("icon", "minecraft:chest");
        int slot = root.getInt("slot", 0);
        Defaults defaults = Defaults.from(root.getConfigurationSection("defaults"));
        return new CategoryConfig(id, display, icon, slot, defaults,
                loadGroups(root.getConfigurationSection("groups")),
                root.getConfigurationSection("items"), logger, npcArbitrageGuard, skipNpcConflicts);
    }

    // declared order wins; ties keep config order
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

    // slot: is a raw inventory index, row:/column: are 1-indexed; -1 means flow into the next free slot
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

    /** Declared groups in display order; empty when the category shows its items directly. */
    public List<Group> groups() { return groups; }

    public boolean hasGroups() { return !groups.isEmpty(); }

    /**
     * Build every configured item with defaults and EcoShop anchoring ({@code base_price: auto} uses the
     * EcoShop buy value, {@code npc_floor}/{@code npc_ceiling} pin to its sell/buy values, falling back to
     * the {@code *_pct} defaults). With {@code trading.npc-arbitrage-guard} on, items EcoShop also trades
     * are kept inside its bracket. Items with unworkable tuning are skipped with a warning, as are items
     * EcoShop leaves no safe range for when {@code trading.npc-arbitrage-conflict} is {@code skip}.
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
                logger.warning("[category " + id + "] item '" + key + "' has no 'item:' lookup; skipping.");
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
            if (floor >= ceiling) {
                floor = base * defaults.floorPct();
                ceiling = base * defaults.ceilingPct();
            }

            double spread = is.getDouble("spread", defaults.spread());
            double elasticity = is.getDouble("elasticity", defaults.elasticity());
            double reversion = is.getDouble("reversion_rate", defaults.reversionRate());
            if (npcArbitrageGuard) {
                double[] bracketed = bracket(shop, itemId, spread, floor, ceiling, key);
                if (bracketed == null) {
                    continue; // bracket already logged
                }
                floor = bracketed[0];
                ceiling = bracketed[1];
            }
            String problem = tuningProblem(spread, elasticity, reversion, floor, ceiling);
            if (problem != null) {
                logger.warning("[category " + id + "] item '" + key + "' " + problem + "; skipping.");
                continue;
            }

            MarketItem item = new MarketItem(
                    itemId,
                    id,
                    resolveGroup(is, key),
                    is.getString("display", ""),
                    base,
                    spread,
                    elasticity,
                    reversion,
                    floor,
                    ceiling);
            item.setPinnedSlot(slotOf(is));
            out.add(item);
        }
        return out;
    }

    // narrow [floor, ceiling] so mid·(1+spread/2) >= NPC sell and mid·(1-spread/2) <= NPC buy at every mid.
    // Each EcoShop price bounds only its own side. Returns null when the item should not be listed.
    private double[] bracket(EcoShopHook shop, String itemId, double spread, double floor, double ceiling,
                             String key) {
        if (spread < 0 || spread >= 1 || !(floor < ceiling)) {
            return new double[]{floor, ceiling};   // tuningProblem reports it
        }
        Double npcSell = positiveOrNull(shop.sellValue(itemId));
        Double npcBuy = positiveOrNull(shop.buyValue(itemId));
        double[] bounds = npcBounds(spread, floor, ceiling, npcBuy, npcSell);
        if (bounds[0] < bounds[1]) {
            return bounds;
        }

        String where = "[category " + id + "] item '" + key + "' (" + itemId + "): ";
        String why = "EcoShop's prices (buy " + fmt(npcBuy) + ", sell " + fmt(npcSell) + ") and spread "
                + fmt(spread) + " need the mid between " + fmt(bounds[0]) + " and " + fmt(bounds[1])
                + ", which leaves no arbitrage-free range inside floor " + fmt(floor) + " and ceiling "
                + fmt(ceiling) + ".";
        if (skipNpcConflicts) {
            logger.warning(where + why + " Not listing it (trading.npc-arbitrage-conflict: skip)."
                    + " Fix the EcoShop entry or the item's floor/ceiling.");
            return null;
        }
        List<String> open = new ArrayList<>();
        if (npcSell != null && floor * (1.0 + spread / 2.0) < npcSell) {
            open.add("buy here and sell to EcoShop");
        }
        if (npcBuy != null && ceiling * (1.0 - spread / 2.0) > npcBuy) {
            open.add("buy from EcoShop and sell here");
        }
        logger.warning(where + why + " It stays listed with its configured floor and ceiling and no guard,"
                + " so players can " + String.join(", or ", open) + " at a profit. Fix the EcoShop entry or"
                + " the item's floor/ceiling, or set trading.npc-arbitrage-conflict: skip to unlist it.");
        return new double[]{floor, ceiling};
    }

    // {lowest, highest} mid at which the bazaar's prices stay inside EcoShop's; a null price leaves that side alone
    static double[] npcBounds(double spread, double floor, double ceiling, Double npcBuy, Double npcSell) {
        double lo = npcSell == null ? floor : Math.max(floor, npcSell / (1.0 + spread / 2.0));
        double hi = npcBuy == null ? ceiling : Math.min(ceiling, npcBuy / (1.0 - spread / 2.0));
        return new double[]{lo, hi};
    }

    private static Double positiveOrNull(Double value) {
        return value != null && value > 0 ? value : null;
    }

    private static String fmt(Double value) {
        if (value == null) {
            return "none";
        }
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    // why this tuning can't make a working market, or null if it can
    static String tuningProblem(double spread, double elasticity, double reversion, double floor,
                                double ceiling) {
        if (!(elasticity > 0) || Double.isInfinite(elasticity)) {
            return "has elasticity " + elasticity + "; it must be a positive number";
        }
        if (!(spread >= 0 && spread < 1)) {
            return "has spread " + spread + "; it must be a fraction in [0, 1)";
        }
        if (!(reversion >= 0 && reversion <= 1)) {
            return "has reversion_rate " + reversion + "; it must be a fraction in [0, 1]";
        }
        if (!(floor >= 0) || !(floor < ceiling) || Double.isInfinite(ceiling)) {
            return "has floor " + floor + " and ceiling " + ceiling + "; it needs 0 <= floor < ceiling";
        }
        return null;
    }

    // an undeclared group falls back to null, otherwise the item would sit in a group no menu opens
    private String resolveGroup(ConfigurationSection is, String key) {
        String group = is.getString("group");
        if (group == null || group.isBlank()) {
            return null;
        }
        if (groups.stream().noneMatch(g -> g.id().equals(group))) {
            logger.warning("[category " + id + "] item '" + key + "' references group '" + group
                    + "', which isn't declared under groups:. Showing it directly in the category.");
            return null;
        }
        return group;
    }

    // base_price is a positive number or auto (the EcoShop buy value)
    private double resolveBase(ConfigurationSection is, String itemId, EcoShopHook shop, String key) {
        String raw = is.getString("base_price", "");
        if ("auto".equalsIgnoreCase(raw.trim())) {
            Double anchored = shop.buyValue(itemId);
            if (anchored == null || anchored <= 0) {
                logger.warning("[category " + id + "] item '" + key + "' uses base_price: auto but EcoShop has no"
                        + " price for '" + itemId + "'; skipping.");
                return -1;
            }
            return anchored;
        }
        double base = is.getDouble("base_price", -1);
        if (base <= 0) {
            logger.warning("[category " + id + "] item '" + key + "' has no positive base_price; skipping.");
        }
        return base;
    }

    private double orElse(Double value, double fallback) {
        return (value == null || value <= 0) ? fallback : value;
    }
}
