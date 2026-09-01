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
 * for its ledger and heuristics, and every trade first passes {@link #allow} — EconGuard's
 * {@code allowTrade} veto, which refuses flagged players when its
 * {@code enforcement.block-flagged-trades} is on and permits everyone otherwise. On an EconGuard old
 * enough to lack the veto bridge, {@link #allow} simply permits, as before.
 */
public final class EconGuardHook {

    private static final String SOURCE_BAZAAR = "bazaar";

    private final Method bridge;
    private final Method veto;

    public EconGuardHook() {
        Method resolvedRecord = null;
        Method resolvedVeto = null;
        if (Bukkit.getPluginManager().isPluginEnabled("EconGuard")) {
            try {
                Class<?> econGuard = Class.forName("com.mystipixel.econguard.api.EconGuard");
                resolvedRecord = econGuard.getMethod("record",
                        UUID.class, String.class, String.class, String.class,
                        double.class, boolean.class, double.class,
                        UUID.class, String.class, String.class, String.class);
                try {
                    resolvedVeto = econGuard.getMethod("allowTrade", UUID.class);
                } catch (NoSuchMethodException oldEconGuard) {
                    // Predates the veto bridge — reporting still works, allow() permits.
                }
            } catch (Throwable ignored) {
                // EconGuard missing or predates the bridge - stay a no-op.
            }
        }
        this.bridge = resolvedRecord;
        this.veto = resolvedVeto;
    }

    public boolean isPresent() {
        return bridge != null;
    }

    /** Whether the installed EconGuard exposes the pre-trade veto (its enforcement decides the rest). */
    public boolean hasVeto() {
        return veto != null;
    }

    /**
     * The pre-trade check. False only when EconGuard both flags this player and has enforcement
     * switched on; any failure to answer permits — an audit core outage must not close the bazaar.
     */
    public boolean allow(Player player, TradeSide side, String itemId, long quantity, double total) {
        if (veto == null) {
            return true;
        }
        try {
            return (boolean) veto.invoke(null, player.getUniqueId());
        } catch (Throwable ignored) {
            return true;
        }
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
