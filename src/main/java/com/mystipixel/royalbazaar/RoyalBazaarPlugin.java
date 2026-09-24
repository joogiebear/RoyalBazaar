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
import java.util.Locale;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import com.mystipixel.royalbazaar.gui.BazaarMenuHolder;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class RoyalBazaarPlugin extends JavaPlugin {

    /** bStats project id. Identifies the plugin, not the server, so it is fixed rather than configurable. */
    private static final int BSTATS_PLUGIN_ID = 32734;

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
    private SignInput signInput;

    private BukkitTask tickTask;
    private BukkitTask flushTask;
    private BukkitTask historyTask;
    private BazaarPlaceholderExpansion placeholderExpansion;
    private boolean fullyEnabled;

    /**
     * Every state flush, history snapshot and stats query runs on this one thread, in submission
     * order. With the shared async pool an older flush could land after a newer one and persist a
     * stale price, and shutdown had no way to wait for writes still in flight.
     */
    private ExecutorService dbWriter;

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
            getLogger().info("EconGuard detected — trades are reported to it"
                    + (guard.hasVeto()
                    ? "; flagged players are refused when EconGuard's enforcement.block-flagged-trades is on."
                    : "; this EconGuard predates the pre-trade veto, so nothing is blocked."));
        }

        this.ecoShop = new EcoShopHook(getDataFolder().getParentFile(), getLogger());
        this.market = new MarketManager(getLogger());
        market.load(config.loadCategories(), config.emaAlpha(), ecoShop);

        this.database = new BazaarDatabase(getDataFolder(), config.storageSection(), getLogger());
        this.dbWriter = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "RoyalBazaar-DB");
            t.setDaemon(true);
            return t;
        });
        try {
            database.init();
            restoreState(null);
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

        this.signInput = new SignInput(this);
        getServer().getPluginManager().registerEvents(signInput, this);
        // Sign-backed, so it no longer listens to chat and needs no event registration of its own.
        AmountPrompt prompt = new AmountPrompt(service, gui, messages, signInput);
        EffectDispatcher dispatcher = new EffectDispatcher(gui, service, prompt, messages, market, signInput);
        getServer().getPluginManager().registerEvents(new BazaarGuiListener(gui, dispatcher), this);

        BazaarCommand command = new BazaarCommand(this, gui, market, service);
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

        refreshWeekStats();
        setupMetrics();
        getLogger().info("RoyalBazaar enabled.");
    }

    /**
     * Recompute each item's history-derived stats: the rolling 7-day figures (mid-a-week-ago, low,
     * high) and the 24h baseline behind every "24h change". Queried off-thread, applied on the main
     * thread. Runs at startup, after a reload and after every history snapshot, so they drift at most
     * one snapshot interval behind.
     */
    private void refreshWeekStats() {
        long now = System.currentTimeMillis();
        long weekAgo = now - 7L * 24L * 60L * 60L * 1000L;
        long dayAgo = now - 24L * 60L * 60L * 1000L;
        submitDb(() -> {
            try {
                Map<String, double[]> stats = database.weekStats(weekAgo);
                Map<String, Double> day = database.earliestMidSince(dayAgo);
                runSync(() -> {
                    for (MarketItem item : market.all()) {
                        double[] row = stats.get(item.id());
                        if (row != null) {
                            item.setWeekStats(row[0], row[1], row[2]);
                        }
                        Double baseline = day.get(item.id());
                        if (baseline != null) {
                            item.setMidYesterday(baseline);
                        }
                    }
                });
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "History stats refresh failed", e);
            }
        });
    }

    /** Queue a database task on the writer thread; dropped quietly once shutdown has begun. */
    private void submitDb(Runnable task) {
        try {
            dbWriter.execute(task);
        } catch (RejectedExecutionException ignored) {
            // disabling — the final synchronous flush covers state
        }
    }

    /** Hop back to the main thread, unless the plugin has been disabled in the meantime. */
    private void runSync(Runnable task) {
        if (isEnabled()) {
            getServer().getScheduler().runTask(this, task);
        }
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
        // Menu icons are real item stacks. Once our click listener is gone, a menu left open is a
        // chest anyone can take them out of.
        for (Player player : getServer().getOnlinePlayers()) {
            if (BazaarMenuHolder.isMenu(player.getOpenInventory().getTopInventory())) {
                player.closeInventory();
            }
        }
        if (signInput != null) {
            signInput.shutdown();
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }
        if (dbWriter != null) {
            dbWriter.shutdown();
            try {
                if (!dbWriter.awaitTermination(10, TimeUnit.SECONDS)) {
                    getLogger().warning("Database writes still running after 10s; continuing shutdown.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (database != null && market != null) {
            try {
                database.flushState(market.allState()); // final synchronous flush, after queued writes
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed final state flush", e);
            }
            database.close();
        }
    }

    /** Seed items from their persisted rows; {@code only} limits it to those ids, null means all. */
    private void restoreState(Set<String> only) throws Exception {
        Map<String, double[]> saved = database.loadState();
        for (MarketItem item : market.all()) {
            if (only != null && !only.contains(item.id())) {
                continue;
            }
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
        submitDb(() -> {
            try {
                database.flushState(dirty);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Write-behind flush failed — re-queueing for the next flush", e);
                // Flags were already cleared, so without this the failed prices would be lost for good.
                List<String> ids = dirty.stream().map(MarketState::id).toList();
                runSync(() -> market.remarkDirty(ids));
            }
        });
    }

    private void snapshot() {
        List<MarketState> items = market.allState();   // detached copy on the main thread
        long ts = System.currentTimeMillis();
        int retentionDays = config.historyRetentionDays();
        submitDb(() -> {
            try {
                database.snapshot(items, ts);
                if (retentionDays > 0) {
                    // Prune with the write so history stays bounded instead of growing forever.
                    database.pruneHistory(ts - (long) retentionDays * 24L * 60L * 60L * 1000L);
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "History snapshot failed", e);
            }
            // A fresh snapshot just landed — recompute the history stats from it. This queues behind
            // the current task on the same writer thread.
            refreshWeekStats();
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
        // Items that survive the reload keep their live state; only newly listed ones are seeded from
        // the database, which can be up to one flush interval behind the market.
        Set<String> added = market.load(config.loadCategories(), config.emaAlpha(), ecoShop);
        if (!added.isEmpty()) {
            try {
                restoreState(added);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "State restore during reload failed", e);
            }
        }
        menus.reload();
        scheduleTasks();
        refreshWeekStats();
    }
    /**
     * Anonymous usage reporting via bStats.
     *
     * <p>Server owners who want no reporting disable it globally in plugins/bStats/config.yml, which
     * is the mechanism bStats provides; the id itself is fixed because it names this plugin's project.
     */
    private void setupMetrics() {
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("storage_backend",
                () -> getConfig().getString("storage.type", "SQLITE").toUpperCase(Locale.ROOT)));
        metrics.addCustomChart(new SimplePie("categories_configured",
                () -> String.valueOf(market == null ? 0 : market.categories().size())));
        metrics.addCustomChart(new SimplePie("default_category_set",
                () -> String.valueOf(!getConfig().getString("default-category", "").isBlank())));
    }

}
