package com.springknight3.anticheat.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import com.springknight3.anticheat.data.PlayerData;
import com.springknight3.anticheat.data.PlayerDataManager;
import com.springknight3.anticheat.config.ConfigManager;

import java.util.Locale;

public final class ACViolationsCommand implements CommandExecutor {
    private final PlayerDataManager playerData;
    private final ConfigManager configs;

    public ACViolationsCommand(PlayerDataManager playerData, ConfigManager configs) {
        this.playerData = playerData;
        this.configs = configs;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(configs.getMessage("usage-acviolations",
                    "&cUsage: /acviolations <player> [check]"));
            sender.sendMessage(configs.getMessage("violations-top-title", "&eTop online players by violation level:"));
            Bukkit.getOnlinePlayers().stream()
                    .map(player -> java.util.Map.entry(player, playerData.get(player.getUniqueId()).getTotalViolationLevel()))
                    .filter(entry -> entry.getValue() > 0)
                    .sorted(java.util.Map.Entry.<Player, Double>comparingByValue().reversed())
                    .limit(10)
                    .forEach(entry -> sender.sendMessage(configs.formatMessage("violations-top-entry",
                            "&7<player>: <VL>", java.util.Map.of(
                                    "player", entry.getKey().getName(),
                                    "VL", String.format(Locale.ROOT, "%.1f", entry.getValue())))));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(configs.getMessage("player-not-online", "&cPlayer is not online."));
            return true;
        }
        String checkFilter = args.length > 1 ? args[1] : null;
        PlayerData data = playerData.get(target.getUniqueId());
        sender.sendMessage(configs.formatMessage("violations-title", "&e<player> violations:",
                java.util.Map.of("player", target.getName())));
        data.getViolationLevels().entrySet().stream()
                .filter(entry -> checkFilter == null || entry.getKey().toLowerCase(Locale.ROOT)
                        .contains(checkFilter.toLowerCase(Locale.ROOT)))
                .forEach(entry -> sender.sendMessage(configs.formatMessage("violations-check",
                        "&7- <check>: <VL>", java.util.Map.of(
                                "check", entry.getKey(),
                                "VL", String.format(Locale.ROOT, "%.1f", entry.getValue())))));
        data.getRecentViolations(checkFilter).stream().skip(Math.max(0, data.getRecentViolations(checkFilter).size() - 10))
                .forEach(record -> sender.sendMessage(configs.formatMessage("violations-detail",
                        "&8<check> | <info> | VL <VL>", java.util.Map.of(
                                "check", record.check(),
                                "info", record.info(),
                                "VL", String.format(Locale.ROOT, "%.1f", record.level())))));
        return true;
    }
}
