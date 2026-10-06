package com.springknight3.anticheat.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest {
    @Test
    void migratesLegacyKickActionsToPunishmentsAndKeepsAlerts() {
        YamlConfiguration checks = new YamlConfiguration();
        checks.set("checks.movement.nofall.actions", List.of(
                "5:ac alert %player% NoFall", "30:kick %player% Unfair Advantage"));

        boolean migrated = ConfigManager.migrateLegacyPunishments(checks, 0);

        assertTrue(migrated);
        assertEquals(List.of("5:ac alert %player% NoFall"),
                checks.getStringList("checks.movement.nofall.actions"));
        assertEquals(List.of("30:kick %player% Unfair Advantage"),
                checks.getStringList("checks.movement.nofall.punishments"));
        assertEquals(2, checks.getInt("checks-version"));
    }

    @Test
    void doesNotMigratePunishmentsAgainForCurrentConfiguration() {
        YamlConfiguration checks = new YamlConfiguration();
        checks.set("checks.movement.nofall.actions", List.of("5:ac alert %player% NoFall"));

        assertFalse(ConfigManager.migrateLegacyPunishments(checks, 2));
        assertEquals(List.of("5:ac alert %player% NoFall"),
                checks.getStringList("checks.movement.nofall.actions"));
    }

    @Test
    void requestedChecksAreEnabledInBundledDefaults() throws Exception {
        YamlConfiguration checks;
        try (InputStream input = ConfigManagerTest.class.getResourceAsStream("/checks.yml")) {
            assertTrue(input != null);
            checks = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        }

        for (String check : List.of(
                "combat.aimbot",
                "packet.badpackets",
                "chat.spam",
                "combat.hitbox",
                "packet.selfinteract",
                "world.airplace",
                "world.baritone")) {
            assertTrue(checks.getBoolean("checks." + check + ".enabled"), check);
        }
    }
}
