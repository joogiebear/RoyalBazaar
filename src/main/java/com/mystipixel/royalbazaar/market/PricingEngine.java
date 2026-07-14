package com.mystipixel.royalbazaar.market;

/**
 * The pure math of the bazaar. Stateless — every method is a function of a {@link MarketItem}'s
 * current {@code mid} plus its config. See the design doc for derivations.
 *
 * <p>Model: an AMM-style price curve. The server is always the counterparty with infinite depth,
 * so there is no order book. Buying quantity {@code q} walks the price <em>up</em> the curve
 * ({@code mid' = mid · e^(q/E)}); selling walks it down. Cost/proceeds are the integral along that
 * curve, so large orders are priced progressively worse — which is what makes round-trips and
 * whale-draining unprofitable by construction.
 */
public final class PricingEngine {

    private PricingEngine() {
    }

    // ---- instantaneous quotes (what the menu shows) ----

    public static double buyPrice(MarketItem i) {
        return i.mid() * (1.0 + i.spread() / 2.0);
    }

    public static double sellPrice(MarketItem i) {
        return i.mid() * (1.0 - i.spread() / 2.0);
    }

    // ---- order cost / proceeds (integral along the curve, spread applied) ----

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

    // ---- market impact (new mid after a trade, clamped to caps) ----

    public static double midAfterBuy(MarketItem i, long q) {
        return clamp(i.mid() * Math.exp(q / i.elasticity()), i.floor(), i.ceiling());
    }

    public static double midAfterSell(MarketItem i, long q) {
        return clamp(i.mid() * Math.exp(-q / i.elasticity()), i.floor(), i.ceiling());
    }

    // ---- mean reversion (applied on the tick) ----

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
