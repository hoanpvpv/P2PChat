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
            logger.info("Database schema initialized");
        } catch (SQLException | IOException e) {
            logger.severe("Failed to initialize schema: " + e.getMessage());
        }
    }
}
