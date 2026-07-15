package com.mystipixel.royalbazaar.hooks;

import com.mystipixel.royalbazaar.market.TradeSide;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Optional anti-abuse integration with EconGuard.
 *
 * <p>Wired by reflection against EconGuard's flat {@code EconGuard.record(...)} bridge, so RoyalBazaar
 * carries no build-time dependency on EconGuard and builds standalone. Resolved once at construction;
 * when EconGuard is absent (or too old to expose the bridge) every call is a safe no-op.
 *
 * <p>The bazaar is the server's biggest faucet/sink, so every completed trade is reported to EconGuard
 * for its ledger and heuristics. EconGuard's current API is record-only, so {@link #allow} stays a
 * permissive seam for a future pre-trade veto; {@link #observe} does the reporting.
 */
public final class EconGuardHook {

    private static final String SOURCE_BAZAAR = "bazaar";

    private final Method bridge;

    public EconGuardHook() {
        Method resolved = null;
        if (Bukkit.getPluginManager().isPluginEnabled("EconGuard")) {
            try {
                Class<?> econGuard = Class.forName("com.mystipixel.econguard.api.EconGuard");
                resolved = econGuard.getMethod("record",
                        UUID.class, String.class, String.class, String.class,
                        double.class, boolean.class, double.class,
                        UUID.class, String.class, String.class, String.class);
            } catch (Throwable ignored) {
                // EconGuard missing or predates the bridge - stay a no-op.
            }
        }
        this.bridge = resolved;
    }

    public boolean isPresent() {
        return bridge != null;
    }

    /**
     * Pre-trade veto seam. EconGuard exposes no veto today, so this permits when present; kept so the
     * call sites don't change if EconGuard later adds a pre-trade check.
     */
    public boolean allow(Player player, TradeSide side, String itemId, long quantity, double total) {
        return true;
    }

    /**
     * Post-trade report to EconGuard's ledger / heuristics. A buy debits the player (outgoing); a sell
     * credits them (incoming). Fire-and-forget: an audit failure must never affect a committed trade.
     */
    public void observe(Player player, TradeSide side, String itemId, long quantity, double total) {
        if (bridge == null) {
            return;
        }
        boolean incoming = side == TradeSide.SELL;
        String action = side == TradeSide.SELL ? "sell" : "buy";
        try {
            bridge.invoke(null, player.getUniqueId(), player.getName(), SOURCE_BAZAAR, action,
                    total, incoming, Double.NaN, null, null, itemId, null);
        } catch (Throwable ignored) {
        }
    }
}
