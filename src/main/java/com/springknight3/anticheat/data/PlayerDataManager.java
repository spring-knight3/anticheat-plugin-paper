package com.springknight3.anticheat.data;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class PlayerDataManager {
    private final ConcurrentMap<UUID, PlayerData> players = new ConcurrentHashMap<>();

    public PlayerData get(UUID uuid) {
        return players.computeIfAbsent(uuid, ignored -> new PlayerData());
    }

    public PlayerData find(UUID uuid) {
        return players.get(uuid);
    }

    public void remove(UUID uuid) {
        players.remove(uuid);
    }
}
