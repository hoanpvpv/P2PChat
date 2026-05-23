package com.mycompany.p2pchat.database;

import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class DatabaseInitializer {

    private static final Logger logger = LoggerUtil.getLogger(DatabaseInitializer.class.getName());

    public static void initSchema(Connection connection) {
        try {
            InputStream is = DatabaseInitializer.class.getClassLoader()
                    .getResourceAsStream("schema.sql");
            if (is == null) {
                logger.severe("Cannot find schema.sql");
                return;
            }
            String sql;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                sql = reader.lines().collect(Collectors.joining("\n"));
            }

            Statement stmt = connection.createStatement();
            for (String single : sql.split(";")) {
                String trimmed = single.trim();
                if (!trimmed.isEmpty()) {
                    stmt.execute(trimmed);
                }
            }
            stmt.close();
            migrateOutbox(connection);
            logger.info("Database schema initialized");
        } catch (SQLException | IOException e) {
            logger.severe("Failed to initialize schema: " + e.getMessage());
        }
    }

    private static void migrateOutbox(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "outbound_messages", "direct_attempt_count", "INTEGER DEFAULT 0");
        addColumnIfMissing(connection, "outbound_messages", "mailbox_attempt_count", "INTEGER DEFAULT 0");
        addColumnIfMissing(connection, "outbound_messages", "last_attempt_at", "BIGINT DEFAULT 0");
        addColumnIfMissing(connection, "outbound_messages", "first_failure_at", "BIGINT DEFAULT 0");
        addColumnIfMissing(connection, "outbound_messages", "failure_code", "TEXT");
    }

    private static void addColumnIfMissing(Connection connection, String table, String column, String type) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (SQLException e) {
            if (e.getMessage() == null || !e.getMessage().toLowerCase().contains("duplicate column")) {
                throw e;
            }
        }
    }
}
