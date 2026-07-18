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

    private final VolumeWindow volume = new VolumeWindow();

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
        // Seed defaults; overwritten by loadState() if a persisted row exists.
        this.mid = basePrice;
        this.midYesterday = basePrice;
        this.emaShort = basePrice;
    }

    /** Restore persisted state on startup. */
    public void loadState(double mid, double midYesterday, long updatedAt) {
        this.mid = PricingEngine.clamp(mid, floor, ceiling);
        this.midYesterday = midYesterday <= 0 ? this.mid : midYesterday;
        this.emaShort = this.mid;
        this.updatedAt = updatedAt;
        this.dirty = false;
    }

    // ---- config accessors ----
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

    public void rollDaily() {
        this.midYesterday = mid;
        this.dirty = true;
    }

    public void clearDirty() { this.dirty = false; }

    /** Re-flag after a failed write, so the state is retried rather than lost. Main thread. */
    public void markDirty() { this.dirty = true; }
}
