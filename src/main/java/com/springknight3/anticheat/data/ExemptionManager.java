package com.springknight3.anticheat.data;

import org.bukkit.entity.Player;
import com.springknight3.anticheat.config.ConfigManager;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ExemptionManager {
    private final ConfigManager config;
    private final Set<UUID> exemptPlayers = ConcurrentHashMap.newKeySet();

    public ExemptionManager(ConfigManager config) {
        this.config = config;
    }

    public void refresh(Player player) {
        InetSocketAddress address = player.getAddress();
        boolean exempt = config.getBoolean("settings.op-always-exempt", true) && player.isOp()
                || config.isConfiguredPlayerExempt(player.getName(), player.getUniqueId().toString())
                || address != null && address.getAddress() != null && config.getStringList("exemptions.ips")
                .contains(address.getAddress().getHostAddress())
                || player.hasPermission("anticheat.exempt");
        setExempt(player.getUniqueId(), exempt);
    }

    public boolean isExempt(UUID uuid) {
        return exemptPlayers.contains(uuid);
    }

    public void setExempt(UUID uuid, boolean exempt) {
        if (exempt) {
            exemptPlayers.add(uuid);
        } else {
            exemptPlayers.remove(uuid);
        }
    }
}
