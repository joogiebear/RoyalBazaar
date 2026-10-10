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
 * Persistence for prices, history and the transaction log (SQLite or MySQL). In-memory prices are
 * authoritative. Every method blocks: call it off the main thread.
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
            hikari.setMaximumPoolSize(SqliteSettings.POOL_SIZE);
            // driver properties, not connectionInitSql (see SqliteSettings)
            hikari.setDataSourceProperties(SqliteSettings.properties());
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
            createIndexIfMissing(c, "idx_rb_tx_player", "rb_transactions", "player, ts");
            createIndexIfMissing(c, "idx_rb_tx_item", "rb_transactions", "item_id, ts");
        }
    }

    // MySQL has no CREATE INDEX IF NOT EXISTS, so ask the catalog first
    private void createIndexIfMissing(Connection c, String name, String table, String columns)
            throws SQLException {
        try (ResultSet rs = c.getMetaData().getIndexInfo(null, null, table, false, true)) {
            while (rs.next()) {
                if (name.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    return;
                }
            }
        }
        try (Statement s = c.createStatement()) {
            s.executeUpdate("CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
        }
    }

    private String autoIncrement() {
        return type == Type.MYSQL ? "AUTO_INCREMENT" : "AUTOINCREMENT";
    }

    /** Persisted rows keyed by item id, as {@code {mid, midYesterday, updatedAt}}. */
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

    /** Delete price history older than {@code cutoff}; returns the rows removed. */
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

    /**
     * {@code {midThen, low, high}} per item with a snapshot since {@code sinceTs}. With retention shorter
     * than the window, midThen is the oldest snapshot kept.
     */
    public Map<String, double[]> weekStats(long sinceTs) throws SQLException {
        Map<String, double[]> out = new HashMap<>();
        String range = "SELECT item_id, MIN(mid_price) lo, MAX(mid_price) hi "
                + "FROM rb_history WHERE ts >= ? GROUP BY item_id";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(range)) {
            ps.setLong(1, sinceTs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("item_id"),
                            new double[]{0.0, rs.getDouble("lo"), rs.getDouble("hi")});
                }
            }
        }
        for (Map.Entry<String, Double> then : earliestMidSince(sinceTs).entrySet()) {
            double[] row = out.get(then.getKey());
            if (row != null) {
                row[0] = then.getValue();
            }
        }
        return out;
    }

    /** Each item's mid at its earliest snapshot at or after {@code sinceTs}; items without one are absent. */
    public Map<String, Double> earliestMidSince(long sinceTs) throws SQLException {
        Map<String, Double> out = new HashMap<>();
        // PK (item_id, ts) makes the join unambiguous
        String sql = "SELECT h.item_id, h.mid_price FROM rb_history h "
                + "JOIN (SELECT item_id, MIN(ts) mts FROM rb_history WHERE ts >= ? GROUP BY item_id) x "
                + "ON x.item_id = h.item_id AND x.mts = h.ts";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, sinceTs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("item_id"), rs.getDouble("mid_price"));
                }
            }
        }
        return out;
    }

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
