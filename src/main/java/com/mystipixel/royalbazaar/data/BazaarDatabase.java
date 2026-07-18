package com.mystipixel.royalbazaar.data;

import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketState;
import com.mystipixel.royalbazaar.market.TradeSide;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * All persistence for RoyalBazaar. Prices live in memory (authoritative); this class only persists
 * them so they survive restarts, plus the history + volume + transaction audit tables. Every method
 * here is blocking and MUST be called off the main thread (the plugin schedules flushes async).
 *
 * <p>One JDBC/HikariCP implementation serves both SQLite (default, single server) and MySQL (networks).
 */
public final class BazaarDatabase {

    public enum Type { SQLITE, MYSQL }

    private final File dataFolder;
    private final ConfigurationSection config;
    private final Logger logger;

    private Type type;
    private HikariDataSource dataSource;

    public BazaarDatabase(File dataFolder, ConfigurationSection storageConfig, Logger logger) {
        this.dataFolder = dataFolder;
        this.config = storageConfig;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ lifecycle

    public void init() throws SQLException {
        String rawType = config.getString("type", "SQLITE").toUpperCase();
        this.type = "MYSQL".equals(rawType) ? Type.MYSQL : Type.SQLITE;

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("RoyalBazaar");

        if (type == Type.MYSQL) {
            ConfigurationSection my = config.getConfigurationSection("mysql");
            String host = my.getString("host", "localhost");
            int port = my.getInt("port", 3306);
            String database = my.getString("database", "royalbazaar");
            String props = my.getString("properties", "useSSL=false");
            registerDriver("com.mysql.cj.jdbc.Driver");
            hikari.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?" + props);
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikari.setUsername(my.getString("username", "root"));
            hikari.setPassword(my.getString("password", ""));
            hikari.setMaximumPoolSize(Math.max(1, my.getInt("pool-size", 10)));
        } else {
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                logger.warning("Could not create plugin data folder: " + dataFolder);
            }
            File db = new File(dataFolder, config.getString("sqlite-file", "bazaar.db"));
            registerDriver("org.sqlite.JDBC");
            hikari.setJdbcUrl("jdbc:sqlite:" + db.getAbsolutePath());
            hikari.setDriverClassName("org.sqlite.JDBC");
            hikari.setMaximumPoolSize(1);
            hikari.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;");
        }

        this.dataSource = new HikariDataSource(hikari);
        createSchema();
        logger.info("Connected to " + type + " storage.");
    }

    private void registerDriver(String driverClass) {
        try {
            Class.forName(driverClass, true, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.log(Level.WARNING, "JDBC driver not found on classpath: " + driverClass, e);
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private void createSchema() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS rb_state ("
                    + "item_id VARCHAR(96) PRIMARY KEY,"
                    + "mid_price DOUBLE PRECISION NOT NULL,"
                    + "mid_yesterday DOUBLE PRECISION NOT NULL,"
                    + "updated_at BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS rb_history ("
                    + "item_id VARCHAR(96) NOT NULL,"
                    + "ts BIGINT NOT NULL,"
                    + "mid_price DOUBLE PRECISION NOT NULL,"
                    + "PRIMARY KEY (item_id, ts))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS rb_transactions ("
                    + "id INTEGER PRIMARY KEY " + autoIncrement() + ","
                    + "ts BIGINT NOT NULL,"
                    + "player CHAR(36) NOT NULL,"
                    + "item_id VARCHAR(96) NOT NULL,"
                    + "side TINYINT NOT NULL,"
                    + "quantity INT NOT NULL,"
                    + "unit_mid DOUBLE PRECISION NOT NULL,"
                    + "total DOUBLE PRECISION NOT NULL)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rb_tx_player ON rb_transactions (player, ts)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rb_tx_item ON rb_transactions (item_id, ts)");
        }
    }

    private String autoIncrement() {
        return type == Type.MYSQL ? "AUTO_INCREMENT" : "AUTOINCREMENT";
    }

    // ------------------------------------------------------------------ state

    /** Load every persisted price row, keyed by item id, for startup seeding. */
    public Map<String, double[]> loadState() throws SQLException {
        Map<String, double[]> out = new HashMap<>();
        String sql = "SELECT item_id, mid_price, mid_yesterday, updated_at FROM rb_state";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString("item_id"), new double[]{
                        rs.getDouble("mid_price"),
                        rs.getDouble("mid_yesterday"),
                        rs.getLong("updated_at")});
            }
        }
        return out;
    }

    /** Write-behind flush of dirty items. UPSERT so first-run inserts and later updates both work. */
    public void flushState(Collection<MarketState> dirty) throws SQLException {
        if (dirty.isEmpty()) {
            return;
        }
        String sql = type == Type.MYSQL
                ? "INSERT INTO rb_state (item_id, mid_price, mid_yesterday, updated_at) VALUES (?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE mid_price=VALUES(mid_price), mid_yesterday=VALUES(mid_yesterday), updated_at=VALUES(updated_at)"
                : "INSERT INTO rb_state (item_id, mid_price, mid_yesterday, updated_at) VALUES (?,?,?,?) "
                + "ON CONFLICT(item_id) DO UPDATE SET mid_price=excluded.mid_price, mid_yesterday=excluded.mid_yesterday, updated_at=excluded.updated_at";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (MarketState item : dirty) {
                ps.setString(1, item.id());
                ps.setDouble(2, item.mid());
                ps.setDouble(3, item.midYesterday());
                ps.setLong(4, item.updatedAt());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Append a price snapshot for the graph / 24h stats. */
    /**
     * Delete price history older than {@code cutoff}. Without this rb_history only ever grows — one
     * row per item per snapshot, so a few hundred items reach millions of rows a year and every write
     * slows down. Served by the (item_id, ts) key. Returns how many rows went.
     */
    public int pruneHistory(long cutoff) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM rb_history WHERE ts < ?")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        }
    }

    public void snapshot(Collection<MarketState> items, long ts) throws SQLException {
        String sql = "INSERT INTO rb_history (item_id, ts, mid_price) VALUES (?,?,?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (MarketState item : items) {
                ps.setString(1, item.id());
                ps.setLong(2, ts);
                ps.setDouble(3, item.mid());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Append a trade to the audit log — EconGuard's feed and your dispute trail. */
    public void logTransaction(UUID player, String itemId, TradeSide side, long qty, double unitMid, double total, long ts) {
        String sql = "INSERT INTO rb_transactions (ts, player, item_id, side, quantity, unit_mid, total) VALUES (?,?,?,?,?,?,?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, ts);
            ps.setString(2, player.toString());
            ps.setString(3, itemId);
            ps.setInt(4, side == TradeSide.BUY ? 0 : 1);
            ps.setLong(5, qty);
            ps.setDouble(6, unitMid);
            ps.setDouble(7, total);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to log bazaar transaction", e);
        }
    }
}
