package com.mystipixel.royalbazaar.market;

/** Allowed trade directions from the player's perspective. Config-only; never persisted as state. */
public enum TradeMode {
    BOTH,
    BUY_ONLY,
    SELL_ONLY;

    public boolean allows(TradeSide side) {
        return switch (side) {
            case BUY -> this != SELL_ONLY;
            case SELL -> this != BUY_ONLY;
        };
    }
}
