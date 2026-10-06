package com.springknight3.anticheat.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import com.springknight3.anticheat.config.ConfigManager;

import java.io.IOException;
import java.util.Locale;

public final class ACManageCommand implements CommandExecutor {
    private final JavaPlugin plugin;
    private final ConfigManager configs;

    public ACManageCommand(JavaPlugin plugin, ConfigManager configs) {
        this.plugin = plugin;
        this.configs = configs;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(configs.getMessage("usage-acmanage",
                    "&cUsage: /acmanage <enable|disable|set> <check> [property] [value]"));
            return true;
        }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "enable", "disable" -> {
                    if (args.length != 2) {
                        sender.sendMessage(configs.getMessage("usage-acmanage-toggle",
                                "&cUsage: /acmanage <enable|disable> <check>"));
                        return true;
                    }
                    configs.setCheckEnabled(args[1], args[0].equalsIgnoreCase("enable"));
                    sender.sendMessage(configs.formatMessage("check-updated", "&aUpdated <check>.",
                            java.util.Map.of("check", args[1])));
                }
                case "set" -> {
                    if (args.length < 4) {
                        sender.sendMessage(configs.getMessage("usage-acmanage-set",
                                "&cUsage: /acmanage set <check> <property> <value>"));
                        return true;
                    }
                    configs.setCheckValue(args[1], args[2], String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)));
                    sender.sendMessage(configs.formatMessage("check-property-updated",
                            "&aUpdated <check>.<property>.",
                            java.util.Map.of("check", args[1], "property", args[2])));
                }
                default -> sender.sendMessage(configs.getMessage("unknown-operation", "&cUnknown operation."));
            }
        } catch (IllegalArgumentException | IOException exception) {
            plugin.getLogger().warning("Configuration update failed: " + exception.getMessage());
            sender.sendMessage(configs.formatMessage("config-update-failed", "&c<error>",
                    java.util.Map.of("error", exception.getMessage())));
        }
        return true;
    }
}
