package com.mystipixel.royalbazaar.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for the bazaar's AMM pricing curve. No Bukkit needed - {@link PricingEngine} is
 * stateless and operates only on a {@link MarketItem}'s numbers.
 */
class PricingEngineTest {

    private static final double BASE = 100.0;
    private static final double SPREAD = 0.06;      // 6%
    private static final double ELASTICITY = 1000;  // units to move price by a factor of e
    private static final double FLOOR = 10.0;
    private static final double CEILING = 1000.0;

    private static MarketItem item() {
        return new MarketItem("test:item", "cat", null, "", BASE, SPREAD, ELASTICITY, 0.10, FLOOR, CEILING);
    }

    @Test
    void quotesStraddleMidBySpread() {
        MarketItem i = item();
        assertEquals(BASE * (1 + SPREAD / 2), PricingEngine.buyPrice(i), 1e-9);
        assertEquals(BASE * (1 - SPREAD / 2), PricingEngine.sellPrice(i), 1e-9);
        assertTrue(PricingEngine.buyPrice(i) > PricingEngine.sellPrice(i));
    }

    @Test
    void singleUnitCostApproximatesInstantQuote() {
        MarketItem i = item();
        // For q << E the integral collapses to the instantaneous price.
        assertEquals(PricingEngine.buyPrice(i), PricingEngine.buyCost(i, 1), PricingEngine.buyPrice(i) * 0.005);
        assertEquals(PricingEngine.sellPrice(i), PricingEngine.sellProceeds(i, 1), PricingEngine.sellPrice(i) * 0.005);
    }

    @Test
    void roundTripLosesMoney() {
        MarketItem i = item();
        long q = 250;
        assertTrue(PricingEngine.buyCost(i, q) > PricingEngine.sellProceeds(i, q),
                "buying then selling the same quantity must not be profitable");
    }

    @Test
    void largeOrdersArePricedProgressively() {
        MarketItem i = item();
        // Doubling the order more than doubles the cost (curve steepens as you walk it).
        assertTrue(PricingEngine.buyCost(i, 400) > 2 * PricingEngine.buyCost(i, 200));
        // ...and yields progressively less per unit when selling.
        assertTrue(PricingEngine.sellProceeds(i, 400) < 2 * PricingEngine.sellProceeds(i, 200));
    }

    @Test
    void tradesMoveMidInTheRightDirection() {
        MarketItem i = item();
        assertTrue(PricingEngine.midAfterBuy(i, 100) > i.mid(), "buying pushes price up");
        assertTrue(PricingEngine.midAfterSell(i, 100) < i.mid(), "selling pushes price down");
    }

    @Test
    void midIsClampedToFloorAndCeiling() {
        MarketItem i = item();
        assertEquals(CEILING, PricingEngine.midAfterBuy(i, 1_000_000_000L), 1e-9);
        assertEquals(FLOOR, PricingEngine.midAfterSell(i, 1_000_000_000L), 1e-9);
    }

    @Test
    void reversionPullsTowardBase() {
        MarketItem high = item();
        high.setMid(400);
        double revertedHigh = PricingEngine.revert(high);
        assertTrue(revertedHigh < 400 && revertedHigh > BASE, "an inflated price drifts back down toward base");

        MarketItem low = item();
        low.setMid(40);
        double revertedLow = PricingEngine.revert(low);
        assertTrue(revertedLow > 40 && revertedLow < BASE, "a depressed price drifts back up toward base");
    }

    @Test
    void clampBounds() {
        assertEquals(5, PricingEngine.clamp(1, 5, 10), 1e-9);
        assertEquals(10, PricingEngine.clamp(99, 5, 10), 1e-9);
        assertEquals(7, PricingEngine.clamp(7, 5, 10), 1e-9);
    }

    @Test
    void oneWayMarketsKeepImpactReversionAndAsymmetricMidCaps() {
        MarketItem buyOnly = new MarketItem("minecraft:stone_bricks", "test", null, "",
                100, 0.06, 1000, 0.1, 100, 150, TradeMode.BUY_ONLY);
        MarketItem sellOnly = new MarketItem("minecraft:rotten_flesh", "test", null, "",
                100, 0.06, 1000, 0.1, 20, 100, TradeMode.SELL_ONLY);
        double previousCost = 0;
        double previousPayout = Double.MAX_VALUE;
        for (int n = 0; n < 100; n++) {
            double cost = PricingEngine.buyCost(buyOnly, 64);
            double payout = PricingEngine.sellProceeds(sellOnly, 64);
            assertTrue(Double.isFinite(cost) && cost >= previousCost);
            assertTrue(Double.isFinite(payout) && payout > 0 && payout <= previousPayout);
            previousCost = cost;
            previousPayout = payout;
            buyOnly.setMid(PricingEngine.midAfterBuy(buyOnly, 64));
            sellOnly.setMid(PricingEngine.midAfterSell(sellOnly, 64));
        }
        assertEquals(150, buyOnly.mid());
        assertEquals(20, sellOnly.mid());
        for (int n = 0; n < 100; n++) {
            double high = PricingEngine.revert(buyOnly);
            double low = PricingEngine.revert(sellOnly);
            assertTrue(high <= buyOnly.mid() && high >= 100);
            assertTrue(low >= sellOnly.mid() && low <= 100);
            buyOnly.setMid(high);
            sellOnly.setMid(low);
        }
        assertEquals(100, buyOnly.mid(), 0.01);
        assertEquals(100, sellOnly.mid(), 0.01);
    }

    @Test
    void capsApplyToTheFinalMidNotTheIntegratedOrderPrice() {
        MarketItem item = item();
        item.setMid(item.ceiling());
        assertTrue(PricingEngine.buyCost(item, 100) > PricingEngine.buyPrice(item) * 100);
        assertEquals(item.ceiling(), PricingEngine.midAfterBuy(item, 100));
        item.setMid(item.floor());
        assertTrue(PricingEngine.sellProceeds(item, 100) < PricingEngine.sellPrice(item) * 100);
        assertEquals(item.floor(), PricingEngine.midAfterSell(item, 100));
    }
}
