package com.springknight3.anticheat.data;

import org.bukkit.plugin.java.JavaPlugin;
import com.springknight3.anticheat.config.ConfigManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class ViolationStorage implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private ExecutorService writer;
    private Connection connection;
    private Path flatFile;
    private Path levelsFile;
    private String backend;

    public ViolationStorage(JavaPlugin plugin, ConfigManager configs) {
        this.plugin = plugin;
        this.configs = configs;
    }

    public synchronized void start() throws IOException {
        close();
        backend = configs.getString("settings.storage-backend", "FLATFILE").toUpperCase(java.util.Locale.ROOT);
        try {
            switch (backend) {
                case "FLATFILE" -> {
                    flatFile = plugin.getDataFolder().toPath().resolve("violations.log");
                    levelsFile = plugin.getDataFolder().toPath().resolve("player-violations.properties");
                    Files.createDirectories(flatFile.getParent());
                    if (!Files.exists(flatFile)) {
                        Files.createFile(flatFile);
                    }
                }
                case "SQLITE" -> {
                    Class.forName("org.sqlite.JDBC");
                    Path database = plugin.getDataFolder().toPath().resolve("violations.db");
                    Files.createDirectories(database.getParent());
                    connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                    initializeSchema();
                }
                case "MYSQL" -> {
                    Class.forName("com.mysql.cj.jdbc.Driver");
                    String host = configs.getString("settings.storage.mysql.host", "localhost");
                    int port = configs.getInt("settings.storage.mysql.port", 3306);
                    String database = configs.getString("settings.storage.mysql.database", "anticheat");
                    String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                            + "?useSSL=" + configs.getBoolean("settings.storage.mysql.use-ssl", true)
                            + "&connectTimeout=5000&socketTimeout=5000";
                    connection = DriverManager.getConnection(url,
                            configs.getString("settings.storage.mysql.username", "anticheat"),
                            configs.getString("settings.storage.mysql.password", ""));
                    initializeSchema();
                }
                default -> throw new IOException("Unsupported storage backend: " + backend);
            }
        } catch (ClassNotFoundException | SQLException exception) {
            close();
            throw new IOException("Could not initialize " + backend + " violation storage", exception);
        } catch (IOException exception) {
            close();
            throw exception;
        }
        writer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "anticheat-storage");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void initializeSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS violations (
                        %s,
                        player_uuid VARCHAR(36) NOT NULL,
                        player_name VARCHAR(16) NOT NULL,
                        check_name VARCHAR(128) NOT NULL,
                        violation_level DOUBLE NOT NULL,
                        info TEXT NOT NULL,
                        created_at VARCHAR(40) NOT NULL
                    )
                    """.formatted(backend.equals("MYSQL")
                    ? "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"
                    : "id INTEGER PRIMARY KEY AUTOINCREMENT"));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_violation_levels (
                        player_uuid VARCHAR(36) NOT NULL,
                        check_name VARCHAR(128) NOT NULL,
                        violation_level DOUBLE NOT NULL,
                        PRIMARY KEY (player_uuid, check_name)
                    )
                    """);
        }
    }

    public CompletableFuture<java.util.Map<String, Double>> loadPlayerLevels(String uuid) {
        ExecutorService currentWriter = writer;
        if (currentWriter == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Violation storage is not running."));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (backend.equals("FLATFILE")) {
                    java.util.Properties properties = new java.util.Properties();
                    if (Files.exists(levelsFile)) {
                        try (var input = Files.newInputStream(levelsFile)) {
                            properties.load(input);
                        }
                    }
                    java.util.Map<String, Double> levels = new java.util.HashMap<>();
                    String prefix = uuid + ".";
                    for (String key : properties.stringPropertyNames()) {
                        if (key.startsWith(prefix)) {
                            levels.put(key.substring(prefix.length()),
                                    parseStoredLevel(properties.getProperty(key), key));
                        }
                    }
                    return java.util.Map.copyOf(levels);
                }
                java.util.Map<String, Double> levels = new java.util.HashMap<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT check_name, violation_level FROM player_violation_levels WHERE player_uuid = ?")) {
                    statement.setString(1, uuid);
                    try (var result = statement.executeQuery()) {
                        while (result.next()) {
                            levels.put(result.getString("check_name"), result.getDouble("violation_level"));
                        }
                    }
                }
                return java.util.Map.copyOf(levels);
            } catch (IOException | SQLException exception) {
                throw new CompletionException("Could not load violation levels for " + uuid, exception);
            }
        }, currentWriter);
    }

    public void savePlayerLevels(String uuid, java.util.Map<String, Double> levels) {
        ExecutorService currentWriter = writer;
        if (currentWriter == null) {
            plugin.getLogger().warning("Player violation levels were not saved because storage is not running.");
            return;
        }
        java.util.Map<String, Double> snapshot = java.util.Map.copyOf(levels);
        try {
            currentWriter.execute(() -> {
                try {
                    if (backend.equals("FLATFILE")) {
                        java.util.Properties properties = new java.util.Properties();
                        if (Files.exists(levelsFile)) {
                            try (var input = Files.newInputStream(levelsFile)) {
                                properties.load(input);
                            }
                        }
                        String prefix = uuid + ".";
                        properties.stringPropertyNames().stream().filter(key -> key.startsWith(prefix))
                                .toList().forEach(properties::remove);
                        snapshot.forEach((check, level) ->
                                properties.setProperty(prefix + check, Double.toString(level)));
                        Path temporary = levelsFile.resolveSibling(levelsFile.getFileName() + ".tmp");
                        try (var output = Files.newOutputStream(temporary)) {
                            properties.store(output, "Persistent anti-cheat violation levels");
                        }
                        try {
                            Files.move(temporary, levelsFile, StandardCopyOption.REPLACE_EXISTING,
                                    StandardCopyOption.ATOMIC_MOVE);
                        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                            Files.move(temporary, levelsFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } else {
                        connection.setAutoCommit(false);
                        try {
                            try (PreparedStatement delete = connection.prepareStatement(
                                    "DELETE FROM player_violation_levels WHERE player_uuid = ?")) {
                                delete.setString(1, uuid);
                                delete.executeUpdate();
                            }
                            try (PreparedStatement insert = connection.prepareStatement(
                                    "INSERT INTO player_violation_levels (player_uuid, check_name, violation_level) "
                                            + "VALUES (?, ?, ?)")) {
                                for (var entry : snapshot.entrySet()) {
                                    insert.setString(1, uuid);
                                    insert.setString(2, entry.getKey());
                                    insert.setDouble(3, entry.getValue());
                                    insert.addBatch();
                                }
                                insert.executeBatch();
                            }
                            connection.commit();
                        } catch (SQLException exception) {
                            connection.rollback();
                            throw exception;
                        } finally {
                            connection.setAutoCommit(true);
                        }
                    }
                } catch (IOException | SQLException exception) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to save violation levels for " + uuid, exception);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            plugin.getLogger().log(Level.SEVERE, "Violation storage is shutting down; levels were not saved for "
                    + uuid, exception);
        }
    }

    private double parseStoredLevel(String value, String key) throws IOException {
        try {
            double level = Double.parseDouble(value);
            if (!Double.isFinite(level) || level < 0) {
                throw new NumberFormatException("Stored VL is not finite and nonnegative");
            }
            return level;
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid stored violation level for " + key, exception);
        }
    }

    public void record(String uuid, String playerName, PlayerData.ViolationRecord violation) {
        ExecutorService currentWriter = writer;
        if (currentWriter == null) {
            plugin.getLogger().warning("Violation was not persisted because storage is not running.");
            return;
        }
        try {
            currentWriter.execute(() -> {
            try {
                if (backend.equals("FLATFILE")) {
                    String line = Instant.ofEpochMilli(violation.timestamp()) + "\t" + uuid + "\t"
                            + clean(playerName) + "\t" + clean(violation.check()) + "\t"
                            + violation.level() + "\t" + clean(violation.info()) + System.lineSeparator();
                    Files.writeString(flatFile, line, StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.APPEND);
                } else {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO violations (player_uuid, player_name, check_name, violation_level, info, created_at) "
                                    + "VALUES (?, ?, ?, ?, ?, ?)")) {
                        statement.setString(1, uuid);
                        statement.setString(2, playerName);
                        statement.setString(3, violation.check());
                        statement.setDouble(4, violation.level());
                        statement.setString(5, violation.info());
                        statement.setString(6, Instant.ofEpochMilli(violation.timestamp()).toString());
                        statement.executeUpdate();
                    }
                }
            } catch (IOException | SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to persist a violation record", exception);
            }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            plugin.getLogger().log(Level.SEVERE, "Violation storage is shutting down; record was not persisted", exception);
        }
    }

    public synchronized boolean isRunning() {
        return writer != null && !writer.isShutdown();
    }

    private String clean(String value) {
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    @Override
    public synchronized void close() {
        if (writer != null) {
            writer.shutdown();
            try {
                if (!writer.awaitTermination(15, TimeUnit.SECONDS)) {
                    plugin.getLogger().severe("Violation storage writer did not stop within 15 seconds.");
                    writer.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                writer.shutdownNow();
            }
            writer = null;
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not close violation storage connection", exception);
            }
            connection = null;
        }
    }
}
