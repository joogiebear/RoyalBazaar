package com.mystipixel.royalbazaar;

import com.mystipixel.royalbazaar.command.BazaarCommand;
import com.mystipixel.royalbazaar.config.PluginConfig;
import com.mystipixel.royalbazaar.data.BazaarDatabase;
import com.mystipixel.royalbazaar.gui.AmountPrompt;
import com.mystipixel.royalbazaar.gui.BazaarGuiListener;
import com.mystipixel.royalbazaar.gui.EffectDispatcher;
import com.mystipixel.royalbazaar.gui.GuiManager;
import com.mystipixel.royalbazaar.gui.SignInput;
import com.mystipixel.royalbazaar.gui.menu.MenuManager;
import com.mystipixel.royalbazaar.gui.menu.MenuTemplate;
import com.mystipixel.royalbazaar.hooks.BazaarPlaceholderExpansion;
import com.mystipixel.royalbazaar.hooks.EconGuardHook;
import com.mystipixel.royalbazaar.hooks.EcoHook;
import com.mystipixel.royalbazaar.hooks.EcoShopHook;
import com.mystipixel.royalbazaar.hooks.VaultHook;
import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketState;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.service.BazaarService;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public final class RoyalBazaarPlugin extends JavaPlugin {

    private PluginConfig config;
    private com.mystipixel.royalbazaar.message.MessageManager messages;
    private VaultHook vault;
    private EcoHook eco;
    private EcoShopHook ecoShop;
    private EconGuardHook guard;
    private MarketManager market;
    private BazaarDatabase database;
    private BazaarService service;
    private MenuManager menus;
    private GuiManager gui;

    private BukkitTask tickTask;
    private BukkitTask flushTask;
    private BukkitTask historyTask;
    private BazaarPlaceholderExpansion placeholderExpansion;
    private boolean fullyEnabled;

    @Override
    public void onEnable() {
        this.config = new PluginConfig(this);
        new com.mystipixel.royalbazaar.config.ConfigValidator(this, config).validate();
        this.messages = new com.mystipixel.royalbazaar.message.MessageManager(this);
        this.vault = new VaultHook();

        this.eco = new EcoHook();
        MenuTemplate.EcoHookHolder.set(eco);
        if (eco.isPresent()) {
            getLogger().info("eco detected — custom item ids (ecoitem:...) enabled.");
        }
        this.guard = new EconGuardHook();
        if (guard.isPresent()) {
            getLogger().info("EconGuard detected — pre-trade vetoes enabled.");
        }

        this.ecoShop = new EcoShopHook(getDataFolder().getParentFile(), getLogger());
        this.market = new MarketManager(getLogger());
        market.load(config.loadCategories(), config.emaAlpha(), ecoShop);

        this.database = new BazaarDatabase(getDataFolder(), config.storageSection(), getLogger());
        try {
            database.init();
            restoreState();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialise storage — disabling RoyalBazaar.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Vault is a hard dependency, but the economy *provider* (EssentialsX, CMI, an EcoBits currency
        // with vault:true, ...) is a separate plugin and can register after we enable. Disabling here
        // would kill the plugin on a perfectly good server purely because of plugin load order, so wait.
        if (vault.setup()) {
            finishEnable();
        } else {
            getLogger().warning("No Vault economy provider found yet. RoyalBazaar is waiting for one to"
                    + " register (install an economy plugin, e.g. EssentialsX). /bazaar is unavailable until then.");
            getServer().getPluginManager().registerEvents(new EconomyWaiter(), this);
            // Fallback, in case the provider registered before our listener was active.
            getServer().getScheduler().runTaskLater(this, this::tryLateEnable, 100L);
        }
    }

    /** Everything that needs a working economy. Idempotent — runs once, whenever the provider shows up. */
    private void finishEnable() {
        if (fullyEnabled) {
            return;
        }
        fullyEnabled = true;

        this.service = new BazaarService(this, market, database, vault, eco, guard, config);
        this.menus = new MenuManager(this);
        this.gui = new GuiManager(menus, market, service, eco);

        AmountPrompt prompt = new AmountPrompt(this, service, gui, messages);
        SignInput signInput = new SignInput(this);
        getServer().getPluginManager().registerEvents(signInput, this);
        EffectDispatcher dispatcher = new EffectDispatcher(gui, service, prompt, messages, market, signInput);
        getServer().getPluginManager().registerEvents(new BazaarGuiListener(gui, dispatcher), this);
        getServer().getPluginManager().registerEvents(prompt, this);

        BazaarCommand command = new BazaarCommand(this, gui, market);
        if (getCommand("bazaar") != null) {
            getCommand("bazaar").setExecutor(command);
            getCommand("bazaar").setTabCompleter(command);
        }

        scheduleTasks();

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.placeholderExpansion = new BazaarPlaceholderExpansion(market, vault, getPluginMeta().getVersion());
            placeholderExpansion.register();
            getLogger().info("Registered PlaceholderAPI expansion.");
        }

        getLogger().info("RoyalBazaar enabled.");
    }

    private void tryLateEnable() {
        if (fullyEnabled) {
            return;
        }
        if (vault.setup()) {
            finishEnable();
        } else {
            getLogger().severe("Still no Vault economy provider after waiting. Install an economy plugin"
                    + " (e.g. EssentialsX) and restart. RoyalBazaar is loaded but inactive.");
        }
    }

    /** Completes startup if the economy provider registers after we enabled. */
    private final class EconomyWaiter implements Listener {
        @EventHandler
        public void onServiceRegister(ServiceRegisterEvent event) {
            if (!fullyEnabled && event.getProvider().getService() == Economy.class && vault.setup()) {
                getLogger().info("Vault economy provider detected. Finishing RoyalBazaar startup.");
                finishEnable();
            }
        }
    }

    @Override
    public void onDisable() {
        cancelTasks();
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }
        if (database != null && market != null) {
            try {
                database.flushState(market.allState()); // final synchronous flush
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed final state flush", e);
            }
            database.close();
        }
    }

    private void restoreState() throws Exception {
        Map<String, double[]> saved = database.loadState();
        for (MarketItem item : market.all()) {
            double[] row = saved.get(item.id());
            if (row != null) {
                item.loadState(row[0], row[1], (long) row[2]);
            }
        }
    }

    private void scheduleTasks() {
        cancelTasks();
        long tick = config.tickIntervalTicks();
        this.tickTask = getServer().getScheduler().runTaskTimer(this, () -> market.tick(), tick, tick);

        // These timers are SYNC on purpose: market state is main-thread-owned and unlocked, so the
        // capture must happen here. Only the database write is pushed off-thread, with an immutable
        // copy. Running the whole thing async raced with trades and /bazaar reload.
        long flush = config.flushIntervalTicks();
        this.flushTask = getServer().getScheduler().runTaskTimer(this, this::flushDirty, flush, flush);

        long history = config.historyIntervalTicks();
        this.historyTask = getServer().getScheduler().runTaskTimer(this, this::snapshot, history, history);
    }

    private void flushDirty() {
        // Capture values and clear the dirty flags in one main-thread pass, then write off-thread.
        List<MarketState> dirty = market.drainDirtyState();
        if (dirty.isEmpty()) {
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                database.flushState(dirty);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Write-behind flush failed — re-queueing for the next flush", e);
                // Flags were already cleared, so without this the failed prices would be lost for good.
                List<String> ids = dirty.stream().map(MarketState::id).toList();
                getServer().getScheduler().runTask(this, () -> market.remarkDirty(ids));
            }
        });
    }

    private void snapshot() {
        List<MarketState> items = market.allState();   // detached copy on the main thread
        long ts = System.currentTimeMillis();
        int retentionDays = config.historyRetentionDays();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                database.snapshot(items, ts);
                if (retentionDays > 0) {
                    // Prune with the write so history stays bounded instead of growing forever.
                    database.pruneHistory(ts - (long) retentionDays * 24L * 60L * 60L * 1000L);
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "History snapshot failed", e);
            }
        });
    }

    private void cancelTasks() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (flushTask != null) {
            flushTask.cancel();
        }
        if (historyTask != null) {
            historyTask.cancel();
        }
    }

    public com.mystipixel.royalbazaar.message.MessageManager messages() {
        return messages;
    }

    /** Reload config, categories and menus. Storage-backend changes still need a restart. */
    public void reloadEverything() {
        config.reload();
        messages.reload();
        this.ecoShop = new EcoShopHook(getDataFolder().getParentFile(), getLogger());
        market.load(config.loadCategories(), config.emaAlpha(), ecoShop);
        try {
            restoreState();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "State restore during reload failed", e);
        }
        menus.reload();
        scheduleTasks();
    }
}
