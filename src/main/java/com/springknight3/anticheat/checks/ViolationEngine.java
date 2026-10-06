package com.springknight3.anticheat.checks;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import com.springknight3.anticheat.config.ConfigManager;
import com.springknight3.anticheat.data.ExemptionManager;
import com.springknight3.anticheat.data.PlayerData;
import com.springknight3.anticheat.data.PlayerDataManager;
import com.springknight3.anticheat.data.ViolationStorage;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.Set;
import java.util.HashMap;

public final class ViolationEngine {
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final PlayerDataManager playerData;
    private final ExemptionManager exemptions;
    private final ViolationStorage storage;
    private final Map<UUID, Long> lastAlerts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPlayerWarnings = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> firedPunishments = new ConcurrentHashMap<>();

    public ViolationEngine(JavaPlugin plugin, ConfigManager config,
                           PlayerDataManager playerData, ExemptionManager exemptions, ViolationStorage storage) {
        this.plugin = plugin;
        this.config = config;
        this.playerData = playerData;
        this.exemptions = exemptions;
        this.storage = storage;
    }

    public void flag(UUID uuid, String check, String info) {
        runOnMain(() -> flagOnMain(uuid, check, info));
    }

    private void flagOnMain(UUID uuid, String check, String info) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || exemptions.isExempt(uuid) || !config.isCheckEnabled(check)) {
            return;
        }
        PlayerData data = playerData.get(uuid);
        if (!data.areViolationsLoaded()) {
            return;
        }
        double previousLevel = data.getViolationLevel(check);
        long now = System.currentTimeMillis();
        double level = data.addViolation(check, 1.0, info, now);
        if (config.getBoolean("settings.debug-mode", false)) {
            plugin.getLogger().info(player.getName() + " failed " + check + ": " + info);
        }
        storage.record(uuid.toString(), player.getName(),
                new PlayerData.ViolationRecord(check, level, info, now));
        storage.savePlayerLevels(uuid.toString(), data.getViolationLevels());
        warnPlayer(player, check, level, info, now);
        int setbackVl = config.getCheckInt(check, "setback-vl", 0);
        if (setbackVl > 0 && previousLevel < setbackVl && level >= setbackVl) {
            setback(player);
        }

        executeThresholdActions(player, check, level, previousLevel, info, config.getCheckActions(check), false);
        if (config.getBoolean("punishments.enabled", true)) {
            executeThresholdActions(player, check, level, previousLevel, info,
                    config.getCheckPunishments(check), true);
        } else {
            clearBelowThresholdPunishments(uuid, check, level);
        }
    }

    private void executeThresholdActions(Player player, String check, double level, double previousLevel,
                                         String info, java.util.List<String> actions, boolean punishment) {
        for (String action : actions) {
            int separator = action.indexOf(':');
            if (separator <= 0) {
                plugin.getLogger().warning("Ignoring invalid action for " + check + ": " + action);
                continue;
            }
            double threshold;
            try {
                threshold = Double.parseDouble(action.substring(0, separator));
            } catch (NumberFormatException exception) {
                plugin.getLogger().warning("Ignoring invalid violation threshold for " + check + ": " + action);
                continue;
            }
            String command = action.substring(separator + 1);
            if (punishment) {
                String key = check + ":" + action;
                Set<String> fired = firedPunishments.computeIfAbsent(player.getUniqueId(),
                        ignored -> ConcurrentHashMap.newKeySet());
                if (level < threshold) {
                    fired.remove(key);
                } else if (fired.add(key)) {
                    executeAction(player, check, level, info, command);
                }
            } else if (previousLevel < threshold && level >= threshold
                    && (config.getBoolean("punishments.enabled", true) || !isPunishmentCommand(command))) {
                executeAction(player, check, level, info, command);
            }
        }
    }

    private void clearBelowThresholdPunishments(UUID uuid, String check, double level) {
        Set<String> fired = firedPunishments.get(uuid);
        if (fired == null) {
            return;
        }
        for (String action : config.getCheckPunishments(check)) {
            int separator = action.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            try {
                if (level < Double.parseDouble(action.substring(0, separator))) {
                    fired.remove(check + ":" + action);
                }
            } catch (NumberFormatException exception) {
                plugin.getLogger().warning("Ignoring invalid violation threshold for " + check + ": " + action);
            }
        }
    }

    private boolean isPunishmentCommand(String command) {
        String firstWord = command.stripLeading().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return switch (firstWord) {
            case "kick", "ban", "ban-ip", "banip", "ipban", "tempban", "tempbanip" -> true;
            default -> false;
        };
    }

    private void warnPlayer(Player player, String check, double level, String info, long now) {
        long cooldown = Math.max(0, config.getInt("vl-settings.player-warning-cooldown-ms", 5000));
        long previous = lastPlayerWarnings.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < cooldown) {
            return;
        }
        lastPlayerWarnings.put(player.getUniqueId(), now);
        player.sendMessage(config.formatMessage("warn-text",
                "&cWarning: unfair advantage detected (<flag>). Further violations may result in punishment.",
                placeholders(player, check, level, info)));
    }

    public void addViolation(UUID uuid, String check, double amount, String info) {
        runOnMain(() -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                return;
            }
            PlayerData data = playerData.get(uuid);
            if (!data.areViolationsLoaded()) {
                return;
            }
            double previousLevel = data.getViolationLevel(check);
            long now = System.currentTimeMillis();
            double level = data.addViolation(check, amount, info, now);
            storage.record(uuid.toString(), player.getName(),
                    new PlayerData.ViolationRecord(check, level, info, now));
            storage.savePlayerLevels(uuid.toString(), data.getViolationLevels());
            int setbackVl = config.getCheckInt(check, "setback-vl", 0);
            if (setbackVl > 0 && previousLevel < setbackVl && level >= setbackVl) {
                setback(player);
            }
            executeThresholdActions(player, check, level, previousLevel, info,
                    config.getCheckActions(check), false);
            if (config.getBoolean("punishments.enabled", true)) {
                executeThresholdActions(player, check, level, previousLevel, info,
                        config.getCheckPunishments(check), true);
            } else {
                clearBelowThresholdPunishments(uuid, check, level);
            }
        });
    }

    public void resetPunishmentState(UUID uuid) {
        firedPunishments.remove(uuid);
    }

    public void forgetPlayerState(UUID uuid) {
        lastAlerts.remove(uuid);
        lastPlayerWarnings.remove(uuid);
        firedPunishments.remove(uuid);
    }

    private void executeAction(Player player, String check, double level, String info, String command) {
        Map<String, Object> placeholders = placeholders(player, check, level, info);
        String expanded = command
                .replace("%player%", player.getName())
                .replace("%check%", check)
                .replace("%vl%", String.format(Locale.ROOT, "%.1f", level))
                .replace("%info%", info);
        if (expanded.regionMatches(true, 0, "ac alert ", 0, 9)) {
            sendAlert(player, check, level, expanded.substring(9));
        } else if (expanded.regionMatches(true, 0, "ac setback ", 0, 11)) {
            setback(player);
        } else if (isCommand(expanded, "warn")) {
            String warning = config.formatMessage("warn-text",
                    "&cWarning: unfair advantage detected.", placeholders);
            player.sendMessage(warning);
        } else if (isCommand(expanded, "kick")) {
            String reason = config.formatMessage("kick-text",
                    "&cYou were kicked for unfair advantage: <flag>", placeholders);
            broadcastPunishment("kick-alert", player, check, level, info);
            dispatchPlayerPunishment("kick", player, reason);
        } else if (isCommand(expanded, "ban") || isCommand(expanded, "ban-ip")
                || isCommand(expanded, "tempban") || isCommand(expanded, "tempbanip")
                || isCommand(expanded, "banip") || isCommand(expanded, "ipban")) {
            String reason = config.formatMessage("ban-text",
                    "&cYou were banned for unfair advantage: <flag>", placeholders);
            broadcastPunishment("ban-alert", player, check, level, info);
            dispatchPlayerPunishment(expanded, player, reason);
        } else {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), expanded);
        }
    }

    private boolean isCommand(String command, String name) {
        return command.equalsIgnoreCase(name) || command.regionMatches(true, 0, name + " ", 0, name.length() + 1);
    }

    private void dispatchPlayerPunishment(String configuredCommand, Player player, String configuredReason) {
        String[] parts = configuredCommand.strip().split("\\s+", 4);
        String command = parts[0];
        String extraArgument = switch (command.toLowerCase(Locale.ROOT)) {
            case "tempban", "tempbanip" -> parts.length > 2 ? parts[2] + " " : "";
            default -> "";
        };
        String reason = PlainTextComponentSerializer.plainText().serialize(
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                        .deserialize(configuredReason));
        String punishmentCommand = command + " " + player.getName() + " " + extraArgument + reason;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), punishmentCommand);
    }

    private void broadcastPunishment(String messageKey, Player player, String check, double level, String info) {
        String message = config.formatMessage(messageKey,
                "§4⚐ §c<player> punished for <flag> (violations: <VL>)",
                placeholders(player, check, level, info));
        broadcast(message, player);
    }

    private Map<String, Object> placeholders(Player player, String check, double level, String info) {
        Map<String, Object> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("flag", check);
        placeholders.put("check", check);
        placeholders.put("VL", String.format(Locale.ROOT, "%.1f", level));
        placeholders.put("vl", String.format(Locale.ROOT, "%.1f", level));
        placeholders.put("info", info);
        placeholders.put("prefix", config.getMessage("prefix", ""));
        return placeholders;
    }

    public void sendAlert(Player player, String check, double level, String info) {
        long now = System.currentTimeMillis();
        long cooldown = config.getInt("vl-settings.alert-cooldown-ms", 1000);
        long previous = lastAlerts.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < cooldown) {
            return;
        }
        lastAlerts.put(player.getUniqueId(), now);
        String message = config.formatMessage("flag-alert",
                "§4⚐ §c<player> flagged for <flag> (violations: <VL>)",
                placeholders(player, check, level, info));
        broadcast(message, player);
    }

    private void broadcast(String message, Player flaggedPlayer) {
        if (config.isBroadcastToConsole()) {
            String plain = PlainTextComponentSerializer.plainText().serialize(
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                            .deserialize(message));
            plugin.getLogger().info(plain);
        }
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (config.isBroadcastGlobal() || recipient.hasPermission("anticheat.alert")) {
                recipient.sendMessage(message);
            }
        }
    }

    public void setback(Player player) {
        PlayerData data = playerData.get(player.getUniqueId());
        Location safeLocation = data.getSafeLocation();
        if (safeLocation != null && safeLocation.getWorld() != null) {
            player.teleportAsync(safeLocation).exceptionally(throwable -> {
                plugin.getLogger().log(Level.WARNING, "Failed to setback " + player.getName(), throwable);
                return false;
            });
        }
    }

    private void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
