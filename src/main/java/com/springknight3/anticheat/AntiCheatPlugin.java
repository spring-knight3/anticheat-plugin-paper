package com.springknight3.anticheat;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import com.springknight3.anticheat.checks.ViolationEngine;
import com.springknight3.anticheat.commands.ACCommand;
import com.springknight3.anticheat.commands.ACManageCommand;
import com.springknight3.anticheat.commands.ACViolationsCommand;
import com.springknight3.anticheat.config.ConfigManager;
import com.springknight3.anticheat.data.ExemptionManager;
import com.springknight3.anticheat.data.PlayerData;
import com.springknight3.anticheat.data.PlayerDataManager;
import com.springknight3.anticheat.data.ViolationStorage;
import com.springknight3.anticheat.listeners.GameplayListener;
import com.springknight3.anticheat.listeners.PacketListener;

import java.io.File;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.io.InputStream;
import java.util.logging.Level;

public final class AntiCheatPlugin extends JavaPlugin {
    private ConfigManager configs;
    private PlayerDataManager playerData;
    private ExemptionManager exemptions;
    private ViolationEngine violations;
    private ViolationStorage storage;
    private PacketListener packetListener;

    @Override
    public void onEnable() {
        configs = new ConfigManager(this);
        try {
            configs.reload();
        } catch (IOException exception) {
            getLogger().severe("Could not load plugin configuration: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        playerData = new PlayerDataManager();
        exemptions = new ExemptionManager(configs);
        if (configs.getBoolean("settings.enable-metrics", false)) {
            getLogger().warning("Metrics are enabled in config, but no metrics provider is configured.");
        }
        storage = new ViolationStorage(this, configs);
        try {
            storage.start();
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not initialize violation storage", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        violations = new ViolationEngine(this, configs, playerData, exemptions, storage);
        GameplayListener gameplayListener = new GameplayListener(this, configs, playerData, exemptions, violations);
        getServer().getPluginManager().registerEvents(gameplayListener, this);

        packetListener = new PacketListener(configs, playerData, exemptions, violations);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);

        ACCommand acCommand = new ACCommand(this, configs, playerData, exemptions, violations, storage);
        getCommand("ac").setExecutor(acCommand);
        getCommand("acmanage").setExecutor(new ACManageCommand(this, configs));
        getCommand("acviolations").setExecutor(new ACViolationsCommand(playerData, configs));
        getCommand("ac").setTabCompleter(acCommand);

        for (Player player : Bukkit.getOnlinePlayers()) {
            initializePlayer(player);
            loadPlayerViolations(player);
        }
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            private long ticks;

            @Override
            public void run() {
                ticks++;
                long decayInterval = Math.max(1, configs.getInt("vl-settings.decay-interval-ticks", 20));
                if (ticks % decayInterval == 0) {
                    double decayAmount = configs.getDouble("vl-settings.decay-amount", 1.0);
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        PlayerData data = playerData.find(player.getUniqueId());
                        if (data != null) {
                            data.decay(decayAmount);
                        }
                    }
                }
                int saveInterval = Math.max(20,
                        configs.getInt("settings.storage.save-interval-ticks", 1200));
                if (ticks % saveInterval == 0) {
                    saveOnlinePlayerViolations();
                }
            }
        }, 1L, 1L);
        getLogger().info("AntiCheat enabled with PacketEvents packet monitoring.");
    }

    @Override
    public void onDisable() {
        if (packetListener != null && PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
        if (storage != null) {
            saveOnlinePlayerViolations();
            storage.close();
        }
    }

    public boolean saveResourceIfAbsent(String resource) throws IOException {
        File target = new File(getDataFolder(), resource);
        if (target.exists()) {
            return true;
        }
        Files.createDirectories(getDataFolder().toPath());
        try (InputStream input = getResource(resource)) {
            if (input == null) {
                return false;
            }
            Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    public void initializePlayer(Player player) {
        PlayerData data = playerData.get(player.getUniqueId());
        data.setServerGrounded(player.isOnGround());
        data.setInVehicle(player.isInsideVehicle());
        data.setInLiquid(player.getLocation().getBlock().isLiquid());
        data.setMovementExempt(player.getAllowFlight() || player.isGliding()
                || player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR);
        if (player.isOnGround() && player.getLocation().getBlock().getRelative(0, -1, 0).getType().isSolid()) {
            data.setSafeLocation(player.getLocation());
        }
        exemptions.refresh(player);
    }

    public void loadPlayerViolations(Player player) {
        PlayerData data = playerData.get(player.getUniqueId());
        data.setViolationsLoaded(false);
        storage.loadPlayerLevels(player.getUniqueId().toString()).whenComplete((levels, failure) ->
                {
                    if (!isEnabled()) {
                        return;
                    }
                    Bukkit.getScheduler().runTask(this, () -> {
                        PlayerData current = playerData.find(player.getUniqueId());
                        if (current != data || !player.isOnline()) {
                            return;
                        }
                        if (failure != null) {
                            getLogger().log(Level.SEVERE,
                                    "Could not load persistent violation levels for " + player.getUniqueId(), failure);
                            return;
                        }
                        data.loadViolationLevels(levels);
                        data.setViolationsLoaded(true);
                        if (!levels.isEmpty()) {
                            getLogger().info("Restored " + levels.size() + " violation check levels for "
                                    + player.getName() + ".");
                        }
                    });
                });
    }

    public void saveOnlinePlayerViolations() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = playerData.find(player.getUniqueId());
            if (data != null && data.areViolationsLoaded()) {
                storage.savePlayerLevels(player.getUniqueId().toString(), data.getViolationLevels());
            }
        }
    }

    public void savePlayerViolations(java.util.UUID uuid, java.util.Map<String, Double> levels) {
        storage.savePlayerLevels(uuid.toString(), levels);
    }

    public ConfigManager configs() {
        return configs;
    }

    public PlayerDataManager playerData() {
        return playerData;
    }

    public ExemptionManager exemptions() {
        return exemptions;
    }

    public ViolationEngine violations() {
        return violations;
    }
}
