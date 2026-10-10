package com.mystipixel.royalbazaar;

import com.mystipixel.royalbazaar.command.BazaarCommand;
import com.mystipixel.royalbazaar.config.PluginConfig;
import com.mystipixel.royalbazaar.data.BazaarDatabase;
import com.mystipixel.royalbazaar.gui.AmountPrompt;
import com.mystipixel.royalbazaar.gui.BazaarGuiListener;
import com.mystipixel.royalbazaar.gui.EffectDispatcher;
import com.mystipixel.royalbazaar.gui.GuiManager;
import com.mystipixel.royalbazaar.gui.TextInput;
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
import com.mystipixel.royalbazaar.util.ItemNames;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import java.io.File;
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

    // identifies the plugin, not the server, so it isn't configurable
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
    private final ItemNames itemNames = new ItemNames();
    private TextInput textInput;

    private BukkitTask tickTask;
    private BukkitTask flushTask;
    private BukkitTask historyTask;
    private BazaarPlaceholderExpansion placeholderExpansion;
    private boolean fullyEnabled;

    // one thread, in submission order, so an older flush can't land after a newer one and
    // shutdown can wait for writes in flight
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
            getLogger().info("eco detected: custom item ids (ecoitem:...) enabled.");
        }
        this.guard = new EconGuardHook();
        if (guard.isPresent()) {
            getLogger().info("EconGuard detected: trades are reported to it"
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
            getLogger().log(Level.SEVERE, "Failed to initialise storage, disabling RoyalBazaar.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // the economy provider is a separate plugin and may register after we enable, so wait for it
        if (vault.setup()) {
            finishEnable();
        } else {
            getLogger().warning("No Vault economy provider found yet. RoyalBazaar is waiting for one to"
                    + " register (install an economy plugin, e.g. EssentialsX). /bazaar is unavailable until then.");
            getServer().getPluginManager().registerEvents(new EconomyWaiter(), this);
            // in case the provider registered before our listener was active
            getServer().getScheduler().runTaskLater(this, this::tryLateEnable, 100L);
        }
    }

    // everything that needs a working economy; runs once, whenever the provider shows up
    private void finishEnable() {
        if (fullyEnabled) {
            return;
        }
        fullyEnabled = true;

        this.service = new BazaarService(this, market, database, vault, eco, guard, config);
        this.menus = new MenuManager(this);
        itemNames.reload(new File(getDataFolder(), "lang"), getLogger());
        this.gui = new GuiManager(menus, market, service, eco, itemNames);

        this.textInput = new TextInput(this, messages);
        getServer().getPluginManager().registerEvents(textInput, this);
        AmountPrompt prompt = new AmountPrompt(service, gui, messages, textInput);
        EffectDispatcher dispatcher = new EffectDispatcher(gui, service, prompt, messages, market, textInput);
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

    // 7-day stats and the 24h baseline: queried off-thread, applied on the main thread
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

    // dropped quietly once shutdown has begun
    private void submitDb(Runnable task) {
        try {
            dbWriter.execute(task);
        } catch (RejectedExecutionException ignored) {
            // disabling: the final synchronous flush covers state
        }
    }

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
        if (textInput != null) {
            textInput.shutdown();
        }
        cancelTasks();
        // menu icons are real item stacks; without our click listener an open menu is a free chest
        for (Player player : getServer().getOnlinePlayers()) {
            if (BazaarMenuHolder.isMenu(player.getOpenInventory().getTopInventory())) {
                player.closeInventory();
            }
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

    // only: limit to these ids, null means all
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

        // sync on purpose: market state is main-thread only, so capture here and write an immutable copy off-thread
        long flush = config.flushIntervalTicks();
        this.flushTask = getServer().getScheduler().runTaskTimer(this, this::flushDirty, flush, flush);

        long history = config.historyIntervalTicks();
        this.historyTask = getServer().getScheduler().runTaskTimer(this, this::snapshot, history, history);
    }

    private void flushDirty() {
        List<MarketState> dirty = market.drainDirtyState();
        if (dirty.isEmpty()) {
            return;
        }
        submitDb(() -> {
            try {
                database.flushState(dirty);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Write-behind flush failed, re-queueing for the next flush", e);
                // flags were already cleared
                List<String> ids = dirty.stream().map(MarketState::id).toList();
                runSync(() -> market.remarkDirty(ids));
            }
        });
    }

    private void snapshot() {
        List<MarketState> items = market.allState();
        long ts = System.currentTimeMillis();
        int retentionDays = config.historyRetentionDays();
        submitDb(() -> {
            try {
                database.snapshot(items, ts);
                if (retentionDays > 0) {
                    database.pruneHistory(ts - (long) retentionDays * 24L * 60L * 60L * 1000L);
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "History snapshot failed", e);
            }
            // queues behind this task on the writer thread
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
        // surviving items keep their live state; only new ones are seeded from the (lagging) database
        Set<String> added = market.load(config.loadCategories(), config.emaAlpha(), ecoShop);
        if (!added.isEmpty()) {
            try {
                restoreState(added);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "State restore during reload failed", e);
            }
        }
        menus.reload();
        itemNames.reload(new File(getDataFolder(), "lang"), getLogger());
        scheduleTasks();
        refreshWeekStats();
    }
    // opt out globally in plugins/bStats/config.yml
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
