package com.mycompany.p2pchat.mailbox;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

public class MailboxDatabase {
    private final String dbPath;
    private Connection connection;

    public MailboxDatabase(String dbPath) {
        this.dbPath = dbPath;
    }

    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            File parent = new File(dbPath).getParentFile();
            if (parent != null) parent.mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA busy_timeout=5000");
            }
        }
        return connection;
    }

    public void init() {
        try {
            String sql;
            try (var input = getClass().getClassLoader().getResourceAsStream("schema.sql")) {
                if (input == null) throw new IllegalStateException("schema.sql not found");
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
                    sql = reader.lines().collect(Collectors.joining("\n"));
                }
            }
            try (Statement stmt = getConnection().createStatement()) {
                for (String part : sql.split(";")) {
                    String single = part.trim();
                    if (!single.isEmpty()) stmt.execute(single);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize mailbox database", e);
        }
    }

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {}
    }
}
