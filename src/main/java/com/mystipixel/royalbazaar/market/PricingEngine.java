package com.mystipixel.royalbazaar.market;

/**
 * Stateless bazaar pricing on an AMM-style curve: buying {@code q} moves {@code mid' = mid · e^(q/E)},
 * selling moves it down. Cost is the integral along the curve, so round-trips never profit.
 */
public final class PricingEngine {

    private PricingEngine() {
    }

    public static double buyPrice(MarketItem i) {
        return i.mid() * (1.0 + i.spread() / 2.0);
    }

    public static double sellPrice(MarketItem i) {
        return i.mid() * (1.0 - i.spread() / 2.0);
    }

    /** Total cost to buy {@code q} units at the current mid, including the buy-side spread. */
    public static double buyCost(MarketItem i, long q) {
        double e = i.elasticity();
        double raw = e * i.mid() * Math.expm1(q / e);          // E·mid·(e^(q/E) − 1)
        return raw * (1.0 + i.spread() / 2.0);
    }

    /** Total proceeds from selling {@code q} units at the current mid, less the sell-side spread. */
    public static double sellProceeds(MarketItem i, long q) {
        double e = i.elasticity();
        double raw = e * i.mid() * -Math.expm1(-q / e);        // E·mid·(1 − e^(−q/E))
        return raw * (1.0 - i.spread() / 2.0);
    }

    public static double midAfterBuy(MarketItem i, long q) {
        return clamp(i.mid() * Math.exp(q / i.elasticity()), i.floor(), i.ceiling());
    }

    public static double midAfterSell(MarketItem i, long q) {
        return clamp(i.mid() * Math.exp(-q / i.elasticity()), i.floor(), i.ceiling());
    }

    /** Pull mid a fraction {@code reversionRate} of the way back toward base, in log-space. */
    public static double revert(MarketItem i) {
        if (i.mid() <= 0 || i.basePrice() <= 0) {
            return i.basePrice();
        }
        double logMid = Math.log(i.mid());
        double logBase = Math.log(i.basePrice());
        double next = Math.exp(logMid + (logBase - logMid) * i.reversionRate());
        return clamp(next, i.floor(), i.ceiling());
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
