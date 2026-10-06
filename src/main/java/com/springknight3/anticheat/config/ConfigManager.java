package com.springknight3.anticheat.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import com.springknight3.anticheat.AntiCheatPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ConfigManager {
    private final AntiCheatPlugin plugin;
    private final File configFile;
    private final File checksFile;
    private final File messagesFile;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), Map.of());
    private YamlConfiguration config;
    private YamlConfiguration checks;
    private YamlConfiguration messages;

    public ConfigManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        configFile = new File(plugin.getDataFolder(), "config.yml");
        checksFile = new File(plugin.getDataFolder(), "checks.yml");
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
    }

    public synchronized void reload() throws IOException {
        plugin.getDataFolder().mkdirs();
        copyDefault("config.yml", configFile);
        copyDefault("checks.yml", checksFile);
        copyDefault("messages.yml", messagesFile);
        int previousConfigVersion = YamlConfiguration.loadConfiguration(configFile).getInt("config-version", 0);
        int previousChecksVersion = YamlConfiguration.loadConfiguration(checksFile).getInt("checks-version", 0);
        config = loadMerged(configFile, "config.yml");
        checks = loadMerged(checksFile, "checks.yml");
        messages = loadMerged(messagesFile, "messages.yml");
        migrateConfig(previousConfigVersion);
        migratePunishments(previousChecksVersion);
        snapshot = new Snapshot(flatten(config), flatten(checks), flatten(messages));
    }

    private YamlConfiguration loadMerged(File file, String resource) throws IOException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        try (InputStream input = plugin.getResource(resource)) {
            if (input == null) {
                throw new IOException("Missing bundled default resource: " + resource);
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            yaml.setDefaults(defaults);
            yaml.options().copyDefaults(true);
        }
        yaml.save(file);
        return yaml;
    }

    private void migrateConfig(int previousConfigVersion) throws IOException {
        if (previousConfigVersion < 2) {
            if (Double.compare(config.getDouble("vl-settings.decay-amount", 0.1), 1.0) == 0) {
                config.set("vl-settings.decay-amount", 0.1);
                plugin.getLogger().info("Updated the default violation decay from 1.0 to 0.1 for sustained VL tracking.");
            }
            config.set("config-version", 2);
            config.save(configFile);
        }
    }

    private void migratePunishments(int previousChecksVersion) throws IOException {
        if (migrateLegacyPunishments(checks, previousChecksVersion)) {
            checks.save(checksFile);
            plugin.getLogger().info("Migrated configured kick/ban commands to per-check punishments.");
        }
    }

    static boolean migrateLegacyPunishments(YamlConfiguration checks, int previousChecksVersion) {
        if (previousChecksVersion >= 2) {
            return false;
        }
        ConfigurationSection checksSection = checks.getConfigurationSection("checks");
        if (checksSection == null) {
            return false;
        }
        for (String path : checksSection.getKeys(true)) {
            if (!path.endsWith(".actions")) {
                continue;
            }
            String actionsPath = "checks." + path;
            if (!checks.isList(actionsPath)) {
                continue;
            }
            String checkPath = path.substring(0, path.length() - ".actions".length());
            String punishmentsPath = "checks." + checkPath + ".punishments";
            List<String> remainingActions = new ArrayList<>();
            List<String> punishments = new ArrayList<>(checks.getStringList(punishmentsPath));
            for (String action : checks.getStringList(actionsPath)) {
                if (isPunishmentAction(action)) {
                    if (!punishments.contains(action)) {
                        punishments.add(action);
                    }
                } else {
                    remainingActions.add(action);
                }
            }
            checks.set(actionsPath, remainingActions);
            if (!punishments.isEmpty()) {
                checks.set(punishmentsPath, punishments);
            }
        }
        checks.set("checks-version", 2);
        return true;
    }

    private static boolean isPunishmentAction(String action) {
        int separator = action.indexOf(':');
        if (separator <= 0) {
            return false;
        }
        String command = action.substring(separator + 1).stripLeading();
        String firstWord = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return switch (firstWord) {
            case "kick", "ban", "ban-ip", "banip", "ipban", "tempban", "tempbanip" -> true;
            default -> false;
        };
    }

    private void copyDefault(String resource, File file) throws IOException {
        if (!file.exists() && !plugin.saveResourceIfAbsent(resource)) {
            throw new IOException("Could not create default " + resource);
        }
    }

    private Map<String, Object> flatten(YamlConfiguration yaml) {
        Map<String, Object> values = new HashMap<>();
        yaml.getValues(true).forEach((key, value) -> {
            if (!(value instanceof ConfigurationSection)) {
                values.put(key.toLowerCase(Locale.ROOT), value instanceof List<?> list
                        ? List.copyOf(list) : value);
            }
        });
        return Map.copyOf(values);
    }

    public boolean getBoolean(String path, boolean fallback) {
        Object value = snapshot.config().get(path.toLowerCase(Locale.ROOT));
        return value instanceof Boolean bool ? bool : fallback;
    }

    public double getDouble(String path, double fallback) {
        Object value = snapshot.config().get(path.toLowerCase(Locale.ROOT));
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    public int getInt(String path, int fallback) {
        Object value = snapshot.config().get(path.toLowerCase(Locale.ROOT));
        return value instanceof Number number ? number.intValue() : fallback;
    }

    public String getString(String path, String fallback) {
        Object value = snapshot.config().get(path.toLowerCase(Locale.ROOT));
        return value instanceof String string ? string : fallback;
    }

    public String getMessage(String path, String fallback) {
        Object value = snapshot.messages().get(path.toLowerCase(Locale.ROOT));
        return value instanceof String string ? string : fallback;
    }

    public String formatMessage(String path, String fallback, Map<String, ?> placeholders) {
        return MessageFormatter.format(getMessage(path, fallback), placeholders);
    }

    public boolean isBroadcastToConsole() {
        return getBoolean("broadcast.BROADCAST TO CONSOLE", false);
    }

    public boolean isBroadcastGlobal() {
        return getBoolean("broadcast.BROADCAST GLOBAL", false);
    }

    public List<String> getStringList(String path) {
        Object value = snapshot.config().get(path.toLowerCase(Locale.ROOT));
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof String string) {
                strings.add(string);
            }
        }
        return List.copyOf(strings);
    }

    public boolean isCheckEnabled(String check) {
        Object value = snapshot.checks().get(("checks." + check + ".enabled").toLowerCase(Locale.ROOT));
        return value instanceof Boolean enabled && enabled;
    }

    public double getCheckDouble(String check, String property, double fallback) {
        Object value = snapshot.checks().get(("checks." + check + "." + property).toLowerCase(Locale.ROOT));
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    public int getCheckInt(String check, String property, int fallback) {
        Object value = snapshot.checks().get(("checks." + check + "." + property).toLowerCase(Locale.ROOT));
        return value instanceof Number number ? number.intValue() : fallback;
    }

    public List<String> getCheckActions(String check) {
        Object value = snapshot.checks().get(("checks." + check + ".actions").toLowerCase(Locale.ROOT));
        return getStringList(value);
    }

    public List<String> getCheckPunishments(String check) {
        Object value = snapshot.checks().get(("checks." + check + ".punishments").toLowerCase(Locale.ROOT));
        return getStringList(value);
    }

    private List<String> getStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> actions = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof String action) {
                actions.add(action);
            }
        }
        return List.copyOf(actions);
    }

    public synchronized void setCheckEnabled(String check, boolean enabled) throws IOException {
        ensureLoaded();
        checks.set("checks." + resolveCheck(check) + ".enabled", enabled);
        saveChecks();
    }

    public synchronized void setCheckValue(String check, String property, String rawValue) throws IOException {
        ensureLoaded();
        String path = "checks." + resolveCheck(check) + "." + property;
        Object current = checks.get(path);
        Object parsed;
        try {
            if (current instanceof Boolean) {
                if (!rawValue.equalsIgnoreCase("true") && !rawValue.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("Value must be true or false");
                }
                parsed = Boolean.parseBoolean(rawValue);
            } else if (current instanceof Integer || current instanceof Long) {
                parsed = Integer.parseInt(rawValue);
            } else if (current instanceof Number) {
                parsed = Double.parseDouble(rawValue);
                if (!Double.isFinite((Double) parsed)) {
                    throw new IllegalArgumentException("Value must be finite");
                }
            } else {
                if (current == null) {
                    throw new IllegalArgumentException("Unknown check property: " + property);
                }
                parsed = rawValue;
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid value for " + property + ": " + rawValue, exception);
        }
        checks.set(path, parsed);
        saveChecks();
    }

    private String resolveCheck(String check) {
        String requested = check.toLowerCase(Locale.ROOT).replace(" ", "");
        if (requested.startsWith("checks.")) {
            requested = requested.substring("checks.".length());
        }
        String requestedPath = "checks." + requested + ".enabled";
        if (snapshot.checks().containsKey(requestedPath)) {
            return requested;
        }
        String leaf = requested.substring(requested.lastIndexOf('.') + 1);
        String match = snapshot.checks().keySet().stream()
                .filter(path -> path.startsWith("checks.") && path.endsWith(".enabled"))
                .map(path -> path.substring("checks.".length(), path.length() - ".enabled".length()))
                .filter(path -> path.substring(path.lastIndexOf('.') + 1).equals(leaf))
                .findFirst()
                .orElse(null);
        if (match == null) {
            throw new IllegalArgumentException("Unknown check: " + check);
        }
        return match;
    }

    public String canonicalCheck(String check) {
        return resolveCheck(check);
    }

    private void saveChecks() throws IOException {
        checks.save(checksFile);
        snapshot = new Snapshot(flatten(config), flatten(checks), flatten(messages));
    }

    private void ensureLoaded() throws IOException {
        if (config == null || checks == null) {
            reload();
        }
    }

    public synchronized void setPlayerExempt(String playerName, String uuid, boolean exempt) throws IOException {
        ensureLoaded();
        List<String> players = new ArrayList<>(config.getStringList("exemptions.players"));
        players.removeIf(name -> name.equalsIgnoreCase(playerName) || name.equalsIgnoreCase(uuid));
        if (exempt) {
            players.add(playerName);
        }
        config.set("exemptions.players", players);
        config.save(configFile);
        snapshot = new Snapshot(flatten(config), flatten(checks), flatten(messages));
    }

    public synchronized boolean isConfiguredPlayerExempt(String playerName, String uuid) {
        List<String> exemptions = getStringList("exemptions.players");
        return exemptions.stream().anyMatch(entry ->
                entry.equalsIgnoreCase(playerName) || entry.equalsIgnoreCase(uuid));
    }

    public synchronized void saveConfig() throws IOException {
        ensureLoaded();
        config.save(configFile);
        checks.save(checksFile);
        snapshot = new Snapshot(flatten(config), flatten(checks), flatten(messages));
    }

    private record Snapshot(Map<String, Object> config, Map<String, Object> checks, Map<String, Object> messages) {
    }
}
