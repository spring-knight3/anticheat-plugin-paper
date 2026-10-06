package com.springknight3.anticheat.data;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PlayerDataTest {
    @Test
    void movementYawUsesShortestDeltaAcrossPositiveBoundary() {
        PlayerData data = new PlayerData();
        data.recordMovement(0, 0, 0, 179, 0, "world", 1_000);

        PlayerData.MovementSample sample = data.recordMovement(0, 0, 0, -179, 0, "world", 1_050);

        assertEquals(2.0, sample.dyaw(), 0.001);
    }

    @Test
    void movementYawUsesShortestDeltaAcrossNegativeBoundary() {
        PlayerData data = new PlayerData();
        data.recordMovement(0, 0, 0, -179, 0, "world", 1_000);

        PlayerData.MovementSample sample = data.recordMovement(0, 0, 0, 179, 0, "world", 1_050);

        assertEquals(-2.0, sample.dyaw(), 0.001);
    }

    @Test
    void recentRotationIsAvailableOnlyWithinTheConfiguredWindow() {
        PlayerData data = new PlayerData();
        data.recordMovement(0, 0, 0, 0, 0, "world", 1_000);

        PlayerData.RotationSample rotation = data.recordRotation(130, 15, 1_050);

        assertEquals(130.0, rotation.yawDelta(), 0.001);
        assertEquals(15.0, rotation.pitchDelta(), 0.001);
        assertNotNull(data.getRecentRotation(1_100, 150));
        assertNull(data.getRecentRotation(1_201, 150));
    }

    @Test
    void restoredViolationLevelsAreAvailableForTheReturningPlayer() {
        PlayerData data = new PlayerData();

        data.loadViolationLevels(Map.of("movement.nofall", 49.2, "movement.speed", 37.2));
        data.setViolationsLoaded(true);

        assertEquals(49.2, data.getViolationLevel("movement.nofall"), 0.001);
        assertEquals(37.2, data.getViolationLevel("movement.speed"), 0.001);
        assertEquals(86.4, data.getTotalViolationLevel(), 0.001);
        assertEquals(true, data.areViolationsLoaded());
    }
}
