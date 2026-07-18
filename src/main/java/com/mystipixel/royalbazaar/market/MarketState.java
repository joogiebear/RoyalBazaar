package com.mystipixel.royalbazaar.market;

/**
 * An immutable copy of the persisted fields of one {@link MarketItem}.
 *
 * <p>Market state is owned by the main thread and holds no locks, so the write-behind flush and the
 * history snapshot must not read live {@code MarketItem}s from a background thread. They capture these
 * detached values on the main thread instead, then write them off-thread.
 */
public record MarketState(String id, double mid, double midYesterday, long updatedAt) {

    public static MarketState of(MarketItem item) {
        return new MarketState(item.id(), item.mid(), item.midYesterday(), item.updatedAt());
    }
}
