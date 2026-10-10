package com.mystipixel.royalbazaar.market;

/**
 * One tradeable commodity. Pricing parameters are immutable and come from {@code categories/*.yml};
 * the live state ({@code mid}, volume, stats) is main-thread only. Quotes are {@code mid} plus or
 * minus half the spread.
 */
public final class MarketItem {

    private final String id;            // eco lookup key, e.g. "ecoitem:enchanted_cobblestone"
    private final String categoryId;
    private final String groupId;       // null means the item sits directly in the category
    private final String displayName;   // optional override; empty = use the item's own name
    private final double basePrice;
    private final double spread;        // fraction, e.g. 0.05 == 5%
    private final double elasticity;    // "E": units to move the price by a factor of e
    private final double reversionRate; // per tick, 0..1
    private final double floor;         // absolute = basePrice * floor_pct
    private final double ceiling;       // absolute = basePrice * ceiling_pct

    private double mid;
    private double midYesterday;
    private double emaShort;            // short EMA of mid, for the trend arrow
    private long updatedAt;
    private boolean dirty;

    // admin freeze: no trades, no reversion. In-memory only on purpose, so a forgotten freeze ends on restart.
    private boolean frozen;

    // cache of rb_history, never persisted; 0 means no snapshot old enough yet
    private double midWeekAgo;
    private double weekLow;
    private double weekHigh;

    // grid position from config, or -1 to flow into the next free slot
    private int pinnedSlot = -1;

    // Not final: a reload hands the live window to the item's replacement (see carryOver).
    private VolumeWindow volume = new VolumeWindow();

    public MarketItem(String id, String categoryId, String groupId, String displayName, double basePrice,
                      double spread, double elasticity, double reversionRate, double floor, double ceiling) {
        this.id = id;
        this.categoryId = categoryId;
        this.groupId = groupId;
        this.displayName = displayName;
        this.basePrice = basePrice;
        this.spread = spread;
        this.elasticity = elasticity;
        this.reversionRate = reversionRate;
        this.floor = floor;
        this.ceiling = ceiling;
        // clamped: floor/ceiling can sit above or below base (EcoShop bracketing, custom *_pct)
        this.mid = PricingEngine.clamp(basePrice, floor, ceiling);
        this.midYesterday = this.mid;
        this.emaShort = this.mid;
    }

    public void loadState(double mid, double midYesterday, long updatedAt) {
        this.mid = PricingEngine.clamp(mid, floor, ceiling);
        this.midYesterday = midYesterday <= 0 ? this.mid : midYesterday;
        this.emaShort = this.mid;
        this.updatedAt = updatedAt;
        this.dirty = false;
    }

    /**
     * Take over the live state of the item this one replaces on reload (not from the database, which
     * lags the last flush and has no freeze). The mid is re-clamped since the bounds may have moved.
     */
    public void carryOver(MarketItem old) {
        this.mid = PricingEngine.clamp(old.mid, floor, ceiling);
        this.midYesterday = old.midYesterday;
        this.emaShort = old.emaShort;
        this.updatedAt = old.updatedAt;
        this.dirty = old.dirty || this.mid != old.mid;
        this.frozen = old.frozen;
        this.midWeekAgo = old.midWeekAgo;
        this.weekLow = old.weekLow;
        this.weekHigh = old.weekHigh;
        this.volume = old.volume;
    }

    public int pinnedSlot() { return pinnedSlot; }

    public void setPinnedSlot(int pinnedSlot) { this.pinnedSlot = pinnedSlot; }

    public String id() { return id; }
    public String categoryId() { return categoryId; }
    public String groupId() { return groupId; }
    public String displayName() { return displayName; }
    public double basePrice() { return basePrice; }
    public double spread() { return spread; }
    public double elasticity() { return elasticity; }
    public double reversionRate() { return reversionRate; }
    public double floor() { return floor; }
    public double ceiling() { return ceiling; }

    public double mid() { return mid; }
    public double midYesterday() { return midYesterday; }
    public double emaShort() { return emaShort; }
    public long updatedAt() { return updatedAt; }
    public boolean dirty() { return dirty; }
    public VolumeWindow volume() { return volume; }
    public double midWeekAgo() { return midWeekAgo; }
    public double weekLow() { return weekLow; }
    public double weekHigh() { return weekHigh; }
    public boolean frozen() { return frozen; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }

    public void setWeekStats(double midWeekAgo, double weekLow, double weekHigh) {
        this.midWeekAgo = midWeekAgo;
        this.weekLow = weekLow;
        this.weekHigh = weekHigh;
    }

    /** {@code newMid} must already be clamped. */
    public void setMid(double newMid) {
        this.mid = newMid;
        this.updatedAt = System.currentTimeMillis();
        this.dirty = true;
    }

    public void updateEma(double alpha) {
        this.emaShort = alpha * mid + (1 - alpha) * emaShort;
    }

    // not marked dirty: the baseline is derived from rb_history
    public void setMidYesterday(double midYesterday) {
        if (midYesterday > 0) {
            this.midYesterday = midYesterday;
        }
    }

    public void clearDirty() { this.dirty = false; }

    public void markDirty() { this.dirty = true; }
}
