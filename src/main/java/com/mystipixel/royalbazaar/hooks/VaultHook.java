package com.mystipixel.royalbazaar.hooks;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Wrapper over the Vault economy (RoyalBank registers as the provider). Call from the main thread.
 */
public final class VaultHook {

    private Economy economy;

    public boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        this.economy = rsp.getProvider();
        return economy != null;
    }

    public boolean isReady() {
        return economy != null;
    }

    public double balance(OfflinePlayer player) {
        return economy.getBalance(player);
    }

    public boolean has(OfflinePlayer player, double amount) {
        return economy.has(player, amount);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        EconomyResponse r = economy.withdrawPlayer(player, amount);
        return r.transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        EconomyResponse r = economy.depositPlayer(player, amount);
        return r.transactionSuccess();
    }

    /** Format a balance the way the server's economy does (whole coins, for most economies). */
    public String format(double amount) {
        return economy == null ? String.format("%,.2f", amount) : economy.format(amount);
    }

    /**
     * Format a bazaar price. Not {@link #format}: economies round to whole coins, which hides the
     * spread on cheap goods. Two decimals below 100, whole coins above.
     */
    public String formatPrice(double amount) {
        if (Math.abs(amount) < 100) {
            return String.format("%,.2f", amount);
        }
        return String.format("%,.0f", amount);
    }
}
