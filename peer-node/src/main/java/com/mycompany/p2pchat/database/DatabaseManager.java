package com.mycompany.p2pchat.database;

import com.mycompany.p2pchat.utils.Constants;
import com.mycompany.p2pchat.utils.LoggerUtil;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

public class DatabaseManager {

    private static final Logger logger = LoggerUtil.getLogger(DatabaseManager.class.getName());
    private final String dbPath;
    private Connection connection;

    public DatabaseManager(String username) {
        File dataDir = new File(Constants.DATA_DIR);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        this.dbPath = Constants.DATA_DIR + File.separator + username + Constants.DB_EXTENSION;
    }

    public Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            String url = "jdbc:sqlite:" + dbPath;
            connection = DriverManager.getConnection(url);
            try (var stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
            }
            logger.info("Database connected: " + dbPath);
        }
        return connection;
    }

    public void init() {
        try {
            Connection conn = getConnection();
            DatabaseInitializer.initSchema(conn);
        } catch (SQLException e) {
            logger.severe("Failed to initialize database: " + e.getMessage());
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logger.info("Database closed: " + dbPath);
            }
        } catch (SQLException e) {
            logger.severe("Failed to close database: " + e.getMessage());
        }
    }
}
