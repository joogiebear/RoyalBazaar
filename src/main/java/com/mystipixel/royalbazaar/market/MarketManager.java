package com.mystipixel.royalbazaar.market;

import com.mystipixel.royalbazaar.config.CategoryConfig;
import com.mystipixel.royalbazaar.hooks.EcoShopHook;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * In-memory registry of every {@link MarketItem}, keyed by eco lookup id, plus the ordered category
 * listing used to build the menus. Loads pricing config from {@code categories/*.yml}, runs the
 * reversion/stat tick, and hands dirty items to the database for write-behind.
 *
 * <p>All reads/writes of item state happen on the main thread; the manager itself holds no locks.
 */
public final class MarketManager {

    private final Logger logger;

    // insertion-ordered so menu paging is stable
    private final Map<String, MarketItem> byId = new LinkedHashMap<>();
    private final Map<String, CategoryConfig> categories = new LinkedHashMap<>();

    // EMA smoothing for the trend arrow; small alpha = smoother.
    private double emaAlpha = 0.2;

    public MarketManager(Logger logger) {
        this.logger = logger;
    }

    /** (Re)load categories + items from the loaded {@link CategoryConfig} list. Keeps live state. */
    public void load(List<CategoryConfig> loaded, double emaAlpha, EcoShopHook shop) {
        this.emaAlpha = emaAlpha;
        // Preserve current mids across a reload so a soft /bazaar reload doesn't reset the market.
        Map<String, MarketItem> previous = new LinkedHashMap<>(byId);
        byId.clear();
        categories.clear();

        for (CategoryConfig cat : loaded) {
            categories.put(cat.id(), cat);
            for (MarketItem fresh : cat.buildItems(shop)) {
                MarketItem old = previous.get(fresh.id());
                if (old != null) {
                    fresh.loadState(old.mid(), old.midYesterday(), old.updatedAt());
                }
                byId.put(fresh.id(), fresh);
            }
        }
        logger.info("Loaded " + categories.size() + " bazaar categories, " + byId.size() + " items.");
    }

    public MarketItem get(String id) {
        return id == null ? null : byId.get(id);
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

    /** Items belonging to a category, in config order (for the category grid). */
    public List<MarketItem> itemsIn(String categoryId) {
        List<MarketItem> out = new ArrayList<>();
        for (MarketItem item : byId.values()) {
            if (item.categoryId().equals(categoryId)) {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * Items shown directly on a category's own grid: everything for a flat category, or only the
     * un-grouped leftovers for a grouped one (grouped items live behind their group icon instead).
     */
    public List<MarketItem> ungroupedItemsIn(String categoryId) {
        List<MarketItem> out = new ArrayList<>();
        for (MarketItem item : byId.values()) {
            if (item.categoryId().equals(categoryId) && item.groupId() == null) {
                out.add(item);
            }
        }
        return out;
    }

    /** Items in one group of a category, in config order (for the group grid). */
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

    // ------------------------------------------------------------------ tick

    /** Reversion + stat bookkeeping for every item. Main thread. */
    public void tick() {
        for (MarketItem item : byId.values()) {
            double reverted = PricingEngine.revert(item);
            if (reverted != item.mid()) {
                item.setMid(reverted);
            }
            item.updateEma(emaAlpha);
            item.volume().tick();
        }
    }

    /** Snapshot the current mid as "yesterday" for %change_24h% — call on the 24h schedule. */
    public void rollDaily() {
        for (MarketItem item : byId.values()) {
            item.rollDaily();
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
     * Capture the dirty items' persisted values and clear their flags in one main-thread pass.
     *
     * <p>Capturing and clearing together is the point: clearing from the writer thread meant a trade
     * landing between the read and the clear had its flag wiped, so that price change was never
     * written and silently reverted on the next restart. Anything changed after this call simply
     * flags itself dirty again and goes out with the next flush.
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

    /** Detached copies of every item, for the history snapshot writer. Main thread. */
    public List<MarketState> allState() {
        List<MarketState> out = new ArrayList<>(byId.size());
        for (MarketItem item : byId.values()) {
            out.add(MarketState.of(item));
        }
        return out;
    }

    /** Re-flag items whose write failed, so a transient database error retries instead of losing state. */
    public void remarkDirty(Collection<String> ids) {
        for (String id : ids) {
            MarketItem item = byId.get(id);
            if (item != null) {
                item.markDirty();
            }
        }
    }
}
