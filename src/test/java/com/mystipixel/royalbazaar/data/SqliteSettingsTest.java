package com.mystipixel.royalbazaar.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// reads both settings back off a real connection
class SqliteSettingsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a pooled connection, configured as BazaarDatabase configures it, has both pragmas")
    void everyPragmaReachesAPooledConnection() throws Exception {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(url("pooled.db"));
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setMaximumPoolSize(SqliteSettings.POOL_SIZE);
        hikari.setDataSourceProperties(SqliteSettings.properties());

        try (HikariDataSource dataSource = new HikariDataSource(hikari);
             Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            assertAllPragmas(st);
        }
    }

    @Test
    @DisplayName("every new connection gets them, not just the first")
    void everyPragmaIsAppliedToEachNewConnection() throws Exception {
        for (int i = 0; i < 2; i++) {
            try (Connection c = DriverManager.getConnection(url("direct.db"), SqliteSettings.properties());
                 Statement st = c.createStatement()) {
                assertAllPragmas(st);
            }
        }
    }

    @Test
    @DisplayName("foreign_keys is enforced, not merely reported as on")
    void foreignKeysAreEnforced() throws Exception {
        try (Connection c = DriverManager.getConnection(url("fk.db"), SqliteSettings.properties());
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE parent (id TEXT PRIMARY KEY)");
            st.executeUpdate("CREATE TABLE child (id TEXT, parent_id TEXT REFERENCES parent(id))");
            SQLException rejected = assertThrows(SQLException.class,
                    () -> st.executeUpdate("INSERT INTO child VALUES ('c', 'missing')"),
                    "a row referencing a missing parent must be rejected");
            assertTrue(rejected.getMessage().toLowerCase().contains("foreign key"),
                    "expected a foreign key violation, got: " + rejected.getMessage());
        }
    }

    // pins the driver behaviour SqliteSettings works around (Statement#execute runs only the first
    // statement); if a future sqlite-jdbc runs them all this fails and the choice can be revisited
    @Test
    @DisplayName("the old multi-statement init string stops after its first pragma")
    void multiStatementInitSqlStopsAfterTheFirstPragma() throws Exception {
        try (Connection c = DriverManager.getConnection(url("legacy.db"));
             Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;");
            assertEquals("wal", pragma(st, "journal_mode").toLowerCase(), "the first pragma applies");
            assertEquals("0", pragma(st, "foreign_keys"), "the second one does not");
        }
    }

    private static void assertAllPragmas(Statement st) throws Exception {
        assertEquals("wal", pragma(st, "journal_mode").toLowerCase());
        assertEquals("1", pragma(st, "foreign_keys"), "foreign_keys must be ON");
    }

    private String url(String file) {
        return "jdbc:sqlite:" + dir.resolve(file);
    }

    private static String pragma(Statement st, String name) throws Exception {
        try (ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
