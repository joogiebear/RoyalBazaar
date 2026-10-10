package com.mystipixel.royalbazaar.market;

/**
 * Immutable copy of a {@link MarketItem}'s persisted fields. Market state is main-thread only, so
 * capture this on the main thread and hand it to the background writer.
 */
public record MarketState(String id, double mid, double midYesterday, long updatedAt) {

    public static MarketState of(MarketItem item) {
        return new MarketState(item.id(), item.mid(), item.midYesterday(), item.updatedAt());
    }
}
