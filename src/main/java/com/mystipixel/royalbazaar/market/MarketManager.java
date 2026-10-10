package com.mystipixel.royalbazaar.market;

import com.mystipixel.royalbazaar.config.CategoryConfig;
import com.mystipixel.royalbazaar.hooks.EcoShopHook;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Registry of every {@link MarketItem} and the ordered categories. Holds no locks: item state is
 * read and written on the main thread only.
 */
public final class MarketManager {

    private final Logger logger;

    // insertion-ordered so menu paging is stable
    private final Map<String, MarketItem> byId = new LinkedHashMap<>();
    private final Map<String, CategoryConfig> categories = new LinkedHashMap<>();

    // small alpha = smoother trend arrow
    private double emaAlpha = 0.2;

    public MarketManager(Logger logger) {
        this.logger = logger;
    }

    /**
     * Existing items keep their live state ({@link MarketItem#carryOver}). Returns the new ids, which
     * the caller seeds from the database.
     */
    public Set<String> load(List<CategoryConfig> loaded, double emaAlpha, EcoShopHook shop) {
        this.emaAlpha = emaAlpha;
        Map<String, MarketItem> previous = new LinkedHashMap<>(byId);
        byId.clear();
        categories.clear();

        Set<String> added = new HashSet<>();
        Map<String, String> ownerByKey = new HashMap<>();
        for (CategoryConfig cat : loaded) {
            categories.put(cat.id(), cat);
            for (MarketItem fresh : cat.buildItems(shop)) {
                // "wheat" and "minecraft:wheat" are one item; two listings would be an arbitrage
                String key = canonical(fresh.id());
                String owner = ownerByKey.putIfAbsent(key, cat.id());
                if (owner != null) {
                    logger.warning("[category " + cat.id() + "] item '" + fresh.id() + "' is already listed in"
                            + " category '" + owner + "'; skipping the duplicate.");
                    continue;
                }
                MarketItem old = previous.get(fresh.id());
                if (old != null) {
                    fresh.carryOver(old);
                } else {
                    added.add(fresh.id());
                }
                byId.put(fresh.id(), fresh);
            }
        }
        logger.info("Loaded " + categories.size() + " bazaar categories, " + byId.size() + " items.");
        return added;
    }

    // one key per physical item
    static String canonical(String id) {
        String lower = id.trim().toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "minecraft:" + lower;
    }

    public MarketItem get(String id) {
        return id == null ? null : byId.get(id);
    }

    /** {@link #get}, but ignoring case and a missing {@code minecraft:} namespace. */
    public MarketItem lookup(String typed) {
        MarketItem exact = get(typed);
        if (exact != null || typed == null) {
            return exact;
        }
        String key = canonical(typed);
        for (MarketItem item : byId.values()) {
            if (canonical(item.id()).equals(key)) {
                return item;
            }
        }
        return null;
    }

    public Collection<MarketItem> all() {
        return byId.values();
    }

    public Collection<CategoryConfig> categories() {
        return categories.values();
    }

    public CategoryConfig category(String id) {
        return id == null ? null : categories.get(id);
    }

    public List<MarketItem> itemsIn(String categoryId) {
        List<MarketItem> out = new ArrayList<>();
        for (MarketItem item : byId.values()) {
            if (item.categoryId().equals(categoryId)) {
                out.add(item);
            }
        }
        return out;
    }

    public List<MarketItem> ungroupedItemsIn(String categoryId) {
        List<MarketItem> out = new ArrayList<>();
        for (MarketItem item : byId.values()) {
            if (item.categoryId().equals(categoryId) && item.groupId() == null) {
                out.add(item);
            }
        }
        return out;
    }

    public List<MarketItem> itemsInGroup(String categoryId, String groupId) {
        List<MarketItem> out = new ArrayList<>();
        if (groupId == null) {
            return out;
        }
        for (MarketItem item : byId.values()) {
            if (item.categoryId().equals(categoryId) && groupId.equals(item.groupId())) {
                out.add(item);
            }
        }
        return out;
    }

    /** Reversion and stat bookkeeping. Main thread. */
    public void tick() {
        for (MarketItem item : byId.values()) {
            // frozen items don't revert, but the volume ring still ticks so stale hours drop off
            if (!item.frozen()) {
                double reverted = PricingEngine.revert(item);
                if (reverted != item.mid()) {
                    item.setMid(reverted);
                }
                item.updateEma(emaAlpha);
            }
            item.volume().tick();
        }
    }

    /** Every item flagged dirty since the last flush; caller clears the flags after persisting. */
    public List<MarketItem> drainDirty() {
        List<MarketItem> dirty = new ArrayList<>();
        for (MarketItem item : byId.values()) {
            if (item.dirty()) {
                dirty.add(item);
            }
        }
        return dirty;
    }

    /**
     * Capture dirty items and clear their flags in one main-thread pass. Don't clear from the writer
     * thread: a trade between read and clear would lose its write.
     */
    public List<MarketState> drainDirtyState() {
        List<MarketState> out = new ArrayList<>();
        for (MarketItem item : byId.values()) {
            if (item.dirty()) {
                out.add(MarketState.of(item));
                item.clearDirty();
            }
        }
        return out;
    }

    /** Detached copies of every item for the history writer. Main thread. */
    public List<MarketState> allState() {
        List<MarketState> out = new ArrayList<>(byId.size());
        for (MarketItem item : byId.values()) {
            out.add(MarketState.of(item));
        }
        return out;
    }

    /** Re-flag items whose write failed so it is retried. */
    public void remarkDirty(Collection<String> ids) {
        for (String id : ids) {
            MarketItem item = byId.get(id);
            if (item != null) {
                item.markDirty();
            }
        }
    }
}
