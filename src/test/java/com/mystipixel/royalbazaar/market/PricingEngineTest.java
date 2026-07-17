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
}
