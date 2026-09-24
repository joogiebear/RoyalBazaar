package com.mystipixel.royalbazaar.market;

/**
 * One tradeable commodity. The pricing parameters ({@code base}, {@code spread}, {@code elasticity},
 * {@code reversionRate}, {@code floor}, {@code ceiling}) are immutable and come from
 * {@code categories/*.yml}. The live state ({@code mid}, volume, snapshots) is owned by the plugin,
 * mutated on the main thread only, and persisted by {@link com.mystipixel.royalbazaar.data.BazaarDatabase}.
 *
 * <p>{@code mid} is the authoritative in-memory price. Buy/sell quotes are derived from it by applying
 * half the spread on each side (see {@link PricingEngine}).
 */
public final class MarketItem {

    // ---- identity / config (immutable) ----
    private final String id;            // eco lookup key, e.g. "ecoitem:enchanted_cobblestone"
    private final String categoryId;
    private final String groupId;       // nullable — null means the item sits directly in the category
    private final String displayName;   // optional override; empty = use the item's own name
    private final double basePrice;
    private final double spread;        // fraction, e.g. 0.05 == 5%
    private final double elasticity;    // "E": units to move the price by a factor of e
    private final double reversionRate; // per tick, 0..1
    private final double floor;         // absolute = basePrice * floor_pct
    private final double ceiling;       // absolute = basePrice * ceiling_pct

    // ---- runtime state (mutable, persisted) ----
    private double mid;
    private double midYesterday;
    private double emaShort;            // short EMA of mid, for the trend arrow
    private long updatedAt;
    private boolean dirty;

    /**
     * Frozen by an admin: trades are refused and reversion stops, so the price holds exactly where it
     * was put while an incident is investigated. Deliberately in-memory only — a freeze is a manual
     * intervention, and one that silently outlives a restart is a trap for whoever forgot it.
     */
    private boolean frozen;

    // ---- rolling week stats (transient) ----
    // Computed off-thread from rb_history and applied on the main thread; 0 means "no snapshot old
    // enough yet". Never persisted — the history table is the durable record, this is its cache.
    private double midWeekAgo;
    private double weekLow;
    private double weekHigh;

    /**
     * Explicit grid position from config ({@code row}/{@code column} or {@code slot}), or -1 to let the
     * item flow into the next free content slot. Set once while categories load, like the pricing fields.
     */
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
        // Seed defaults; overwritten by loadState() if a persisted row exists. Clamped because the
        // floor/ceiling can sit above or below base (EcoShop bracketing, custom *_pct values).
        this.mid = PricingEngine.clamp(basePrice, floor, ceiling);
        this.midYesterday = this.mid;
        this.emaShort = this.mid;
    }

    /** Restore persisted state on startup. */
    public void loadState(double mid, double midYesterday, long updatedAt) {
        this.mid = PricingEngine.clamp(mid, floor, ceiling);
        this.midYesterday = midYesterday <= 0 ? this.mid : midYesterday;
        this.emaShort = this.mid;
        this.updatedAt = updatedAt;
        this.dirty = false;
    }

    /**
     * Take over the live state of the item this one replaces on {@code /bazaar reload}: price, trend,
     * volume, week stats, pending write and an admin freeze. Reading it back from the database instead
     * rolled prices back to the last flush and silently lifted freezes. The mid is re-clamped, since
     * the reload may have moved the floor or ceiling, and marked dirty if that moved it.
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

    // ---- config accessors ----
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

    // ---- state accessors ----
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

    /** Apply the latest history-derived week stats. Main thread. */
    public void setWeekStats(double midWeekAgo, double weekLow, double weekHigh) {
        this.midWeekAgo = midWeekAgo;
        this.weekLow = weekLow;
        this.weekHigh = weekHigh;
    }

    // ---- state mutators (main thread only) ----

    /** Set a new mid (already clamped by the engine), stamp the time and mark dirty. */
    public void setMid(double newMid) {
        this.mid = newMid;
        this.updatedAt = System.currentTimeMillis();
        this.dirty = true;
    }

    public void updateEma(double alpha) {
        this.emaShort = alpha * mid + (1 - alpha) * emaShort;
    }

    /**
     * Apply the 24h baseline taken from price history. Main thread. Not marked dirty: the baseline is
     * derived from rb_history, so persisting it on its own would only add writes.
     */
    public void setMidYesterday(double midYesterday) {
        if (midYesterday > 0) {
            this.midYesterday = midYesterday;
        }
    }

    public void clearDirty() { this.dirty = false; }

    /** Re-flag after a failed write, so the state is retried rather than lost. Main thread. */
    public void markDirty() { this.dirty = true; }
}
