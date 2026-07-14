package com.mystipixel.royalbazaar.market;

/**
 * Outcome of a buy/sell attempt. {@code total} is the cash moved (spread included); {@code filled}
 * is how many units actually transacted (may be less than requested if the inventory was full or the
 * player didn't hold enough on a sell).
 */
public record TradeResult(Status status, TradeSide side, String itemId, long filled, double total, String message) {

    public enum Status {
        OK,
        UNKNOWN_ITEM,
        DISABLED,
        INSUFFICIENT_FUNDS,
        INSUFFICIENT_ITEMS,
        INVENTORY_FULL,
        REJECTED_BY_GUARD,
        ERROR
    }

    public boolean ok() {
        return status == Status.OK;
    }

    public static TradeResult ok(TradeSide side, String itemId, long filled, double total) {
        return new TradeResult(Status.OK, side, itemId, filled, total, null);
    }

    public static TradeResult fail(Status status, TradeSide side, String itemId, String message) {
        return new TradeResult(status, side, itemId, 0, 0, message);
    }
}
