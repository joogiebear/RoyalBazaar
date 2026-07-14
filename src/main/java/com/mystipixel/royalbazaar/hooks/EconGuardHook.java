package com.mystipixel.royalbazaar.hooks;

import com.mystipixel.royalbazaar.market.TradeSide;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Optional anti-abuse integration with EconGuard. The bazaar is the server's biggest faucet/sink, so
 * every trade is offered to EconGuard for a pre-trade veto (rate limits, circuit breakers, wash-trade
 * heuristics) before any money or items move.
 *
 * <p>Skeleton: until the EconGuard API surface is wired in, {@link #allow} always permits. Replace the
 * body with the real EconGuard service call — the seam is here so nothing else has to change.
 */
public final class EconGuardHook {

    private final boolean present;

    public EconGuardHook() {
        this.present = Bukkit.getPluginManager().isPluginEnabled("EconGuard");
    }

    public boolean isPresent() {
        return present;
    }

    /**
     * Pre-trade veto. Return true to allow the trade, false to block it.
     *
     * @param player   the trader
     * @param side     buy or sell
     * @param itemId   eco lookup id
     * @param quantity units
     * @param total    cash that will move (spread included)
     */
    public boolean allow(Player player, TradeSide side, String itemId, long quantity, double total) {
        if (!present) {
            return true;
        }
        // TODO: call EconGuard's pre-trade check once its API is exposed:
        //   return EconGuardAPI.get().checkTrade(player.getUniqueId(), itemId, side, quantity, total);
        return true;
    }

    /** Post-trade notification for EconGuard's audit/heuristics. No-op until wired. */
    public void observe(Player player, TradeSide side, String itemId, long quantity, double total) {
        if (!present) {
            return;
        }
        // TODO: EconGuardAPI.get().recordTrade(...)
    }
}
