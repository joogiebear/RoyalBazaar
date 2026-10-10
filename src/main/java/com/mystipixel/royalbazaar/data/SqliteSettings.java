package com.mystipixel.royalbazaar.data;

import java.util.Properties;

// Passed as driver connection properties, not connectionInitSql: sqlite-jdbc only runs the first
// statement of a multi-statement init string. SqliteSettingsTest reads the pragmas back.
final class SqliteSettings {

    // One connection serialises writes and avoids SQLITE_BUSY. Raise it only together with an
    // explicit busy_timeout (driver default is 3000 ms).
    static final int POOL_SIZE = 1;

    private SqliteSettings() {
    }

    static Properties properties() {
        Properties props = new Properties();
        props.setProperty("journal_mode", "WAL");
        props.setProperty("foreign_keys", "true");
        return props;
    }
}
