package com.springknight3.anticheat.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import com.springknight3.anticheat.checks.ViolationEngine;
import com.springknight3.anticheat.config.ConfigManager;
import com.springknight3.anticheat.data.ExemptionManager;
import com.springknight3.anticheat.data.PlayerData;
import com.springknight3.anticheat.data.PlayerDataManager;
import com.springknight3.anticheat.data.ViolationStorage;
import com.springknight3.anticheat.config.MessageFormatter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ACCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final PlayerDataManager playerData;
    private final ExemptionManager exemptions;
    private final ViolationEngine violations;
    private final ViolationStorage storage;

    public ACCommand(JavaPlugin plugin, ConfigManager configs, PlayerDataManager playerData,
                     ExemptionManager exemptions, ViolationEngine violations, ViolationStorage storage) {
        this.plugin = plugin;
        this.configs = configs;
        this.playerData = playerData;
        this.exemptions = exemptions;
        this.violations = violations;
        this.storage = storage;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(configs.getMessage("usage-ac",
                    "&eUsage: /ac <violations|exempt|reset|add|config|alert|setback>"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "violations" -> {
                if (args.length < 2) {
                    sender.sendMessage(configs.getMessage("usage-ac-violations", "&cUsage: /ac violations <player>"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(configs.getMessage("player-not-online", "&cPlayer is not online."));
                    return true;
                }
                PlayerData data = playerData.get(target.getUniqueId());
                sender.sendMessage(configs.formatMessage("violations-total", "&e<player> total VL: <VL>",
                        Map.of("player", target.getName(),
                                "VL", String.format(Locale.ROOT, "%.1f", data.getTotalViolationLevel()))));
                data.getViolationLevels().forEach((check, level) ->
                        sender.sendMessage(configs.formatMessage("violations-check", "&7- <check>: <VL>",
                                Map.of("check", check, "VL", String.format(Locale.ROOT, "%.1f", level)))));
                data.getRecentViolations(null).stream().skip(Math.max(0,
                                data.getRecentViolations(null).size() - 5))
                        .forEach(record -> sender.sendMessage(configs.formatMessage("violations-entry",
                                "&8<check> VL <VL>: <info>",
                                Map.of("check", record.check(),
                                        "VL", String.format(Locale.ROOT, "%.1f", record.level()),
                                        "info", record.info()))));
                return true;
            }
            case "exempt" -> {
                if (args.length < 2) {
                    sender.sendMessage(configs.getMessage("usage-ac-exempt", "&cUsage: /ac exempt <player>"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(configs.getMessage("player-not-online", "&cPlayer is not online."));
                    return true;
                }
                boolean exempt = !configs.isConfiguredPlayerExempt(
                        target.getName(), target.getUniqueId().toString());
                try {
                    configs.setPlayerExempt(target.getName(), target.getUniqueId().toString(), exempt);
                } catch (IOException exception) {
                    plugin.getLogger().warning("Could not save player exemption: " + exception.getMessage());
                    sender.sendMessage(configs.getMessage("exemption-save-failed", "&cCould not save exemption."));
                    return true;
                }
                exemptions.refresh(target);
                String key = exempt ? "exempt-enabled" : "exempt-disabled";
                sender.sendMessage(configs.formatMessage(key, "<prefix>&aExemption updated for <player>.",
                        Map.of("prefix", configs.getMessage("prefix", ""),
                                "player", target.getName())));
                return true;
            }
            case "reset" -> {
                Player target = findPlayer(sender, args, 1);
                if (target != null) {
                    PlayerData data = playerData.get(target.getUniqueId());
                    data.resetViolations();
                    violations.resetPunishmentState(target.getUniqueId());
                    if (data.areViolationsLoaded()) {
                        storage.savePlayerLevels(target.getUniqueId().toString(), data.getViolationLevels());
                    }
                    sender.sendMessage(configs.formatMessage("reset-success",
                            "<prefix>&aReset violation history for <player>.",
                            Map.of("prefix", configs.getMessage("prefix", ""),
                                    "player", target.getName())));
                }
                return true;
            }
            case "add" -> {
                if (args.length < 4) {
                    sender.sendMessage(configs.getMessage("usage-ac-add",
                            "&cUsage: /ac add <player> <check> <vl>"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(configs.getMessage("player-not-online", "&cPlayer is not online."));
                    return true;
                }
                double amount;
                try {
                    amount = Double.parseDouble(args[3]);
                    if (!Double.isFinite(amount) || amount <= 0) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException exception) {
                    sender.sendMessage(configs.getMessage("vl-invalid", "&cVL must be a positive finite number."));
                    return true;
                }
                String check;
                try {
                    check = configs.canonicalCheck(args[2]);
                } catch (IllegalArgumentException exception) {
                    sender.sendMessage(configs.formatMessage("unknown-check", "&cUnknown check: <check>",
                            Map.of("check", exception.getMessage())));
                    return true;
                }
                violations.addViolation(target.getUniqueId(), check, amount, "Manually added");
                sender.sendMessage(configs.formatMessage("vl-added", "&aAdded <amount> VL to <player>.",
                        Map.of("amount", amount, "player", target.getName())));
                return true;
            }
            case "config" -> {
                if (args.length != 2 || !args[1].equalsIgnoreCase("reload")) {
                    sender.sendMessage(configs.getMessage("usage-ac-config", "&cUsage: /ac config reload"));
                    return true;
                }
                try {
                    configs.reload();
                    storage.start();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        exemptions.refresh(player);
                    }
                    sender.sendMessage(configs.getMessage("config-reloaded", "&aAnti-cheat configuration reloaded."));
                } catch (IOException exception) {
                    plugin.getLogger().warning("Could not reload configuration: " + exception.getMessage());
                    sender.sendMessage(configs.formatMessage("config-reload-failed",
                            "&cConfiguration reload failed: <error>", Map.of("error", exception.getMessage())));
                    if (!storage.isRunning()) {
                        plugin.getLogger().severe("Violation storage is unavailable; disabling AntiCheat.");
                        plugin.getServer().getPluginManager().disablePlugin(plugin);
                    }
                }
                return true;
            }
            case "alert" -> {
                if (args.length < 2) {
                    sender.sendMessage(configs.getMessage("usage-ac-alert", "&cUsage: /ac alert <message>"));
                    return true;
                }
                String message = String.join(" ", args).substring(args[0].length()).trim();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (configs.isBroadcastGlobal() || player.hasPermission("anticheat.alert")) {
                        player.sendMessage(MessageFormatter.format(configs.getMessage("prefix", "") + message,
                                Map.of()));
                    }
                }
                if (configs.isBroadcastToConsole()) {
                    plugin.getLogger().info(MessageFormatter.format(message, Map.of()));
                }
                return true;
            }
            case "setback" -> {
                if (args.length < 2) {
                    sender.sendMessage(configs.getMessage("usage-ac-setback", "&cUsage: /ac setback <player>"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target != null) {
                    violations.setback(target);
                }
                return true;
            }
            default -> {
                sender.sendMessage(configs.getMessage("unknown-subcommand", "&cUnknown subcommand."));
                return true;
            }
        }
    }

    private Player findPlayer(CommandSender sender, String[] args, int nameIndex) {
        if (args.length <= nameIndex) {
            sender.sendMessage(configs.getMessage("usage-ac-reset", "&cUsage: /ac reset <player>"));
            return null;
        }
        Player target = Bukkit.getPlayerExact(args[nameIndex]);
        if (target == null) {
            sender.sendMessage(configs.getMessage("player-not-online", "&cPlayer is not online."));
        }
        return target;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("violations", "exempt", "reset", "add", "config")
                    .stream().filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && List.of("violations", "exempt", "reset", "add").contains(
                args[0].toLowerCase(Locale.ROOT))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }

}
