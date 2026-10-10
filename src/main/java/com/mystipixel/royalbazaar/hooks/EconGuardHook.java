package com.mystipixel.royalbazaar.hooks;

import com.mystipixel.royalbazaar.market.TradeSide;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Optional EconGuard integration, by reflection against its static {@code EconGuard.record}/{@code allowTrade}
 * bridge so there's no build-time dependency. Every call is a no-op when EconGuard is absent or too old.
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
                    // predates the veto bridge: reporting still works, allow() permits
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

    public boolean hasVeto() {
        return veto != null;
    }

    /**
     * Pre-trade veto. False only when EconGuard flags the player and enforcement is on; any failure
     * permits, so an EconGuard outage never closes the bazaar.
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

    /** Post-trade report to EconGuard. Fire-and-forget: a failure never affects a committed trade. */
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
