package com.springknight3.anticheat.config;

import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageFormatterTest {
    @Test
    void formatsAngleAndPercentPlaceholdersWithoutChangingSectionColors() {
        String message = MessageFormatter.format(
                "§4⚐ §c<player> flagged for <flag> (violations: <VL>) %info%",
                Map.of("player", "Test", "flag", "NoFall", "VL", "12.0", "info", "ground spoof"));

        assertEquals("§4⚐ §cTest flagged for NoFall (violations: 12.0) ground spoof", message);
    }

    @Test
    void packagedMessageTemplatesAndBroadcastOptionsArePresent() throws Exception {
        YamlConfiguration messages = loadResource("messages.yml");
        YamlConfiguration config = loadResource("config.yml");

        assertEquals("§4⚐ §c<player> flagged for <flag> (violations: <VL>)",
                messages.getString("flag-alert"));
        assertEquals(true, config.getBoolean("broadcast.BROADCAST TO CONSOLE"));
        assertEquals(false, config.getBoolean("broadcast.BROADCAST GLOBAL"));
    }

    private YamlConfiguration loadResource(String name) throws Exception {
        InputStream input = getClass().getClassLoader().getResourceAsStream(name);
        if (input == null) {
            throw new IllegalStateException("Missing test resource " + name);
        }
        try (input) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }
}
