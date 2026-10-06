package com.springknight3.anticheat.data;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PlayerData {
    private final Map<String, Double> violationLevels = new HashMap<>();
    private final Deque<ViolationRecord> violations = new ArrayDeque<>();
    private final Deque<Long> clickTimes = new ArrayDeque<>();
    private final Deque<Long> clickIntervals = new ArrayDeque<>();
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private String world;
    private long lastMoveMillis;
    private long lastPacketMillis;
    private long lastClickMillis;
    private long lastPlaceMillis;
    private long lastInventoryClickMillis;
    private long lastTotemSwapMillis;
    private long lastTotemConsumeMillis;
    private long lastAttackMillis;
    private long lastBlockStartMillis;
    private long lastVehicleMoveMillis;
    private long lastRotationMillis;
    private float lastYawDelta;
    private float lastPitchDelta;
    private boolean hasPosition;
    private boolean serverGrounded;
    private boolean usingItem;
    private boolean inventoryOpen;
    private boolean inVehicle;
    private boolean inLiquid;
    private boolean movementExempt;
    private boolean lastPacketGroundClaim;
    private boolean violationsLoaded;
    private int hoveringPackets;
    private Location safeLocation;
    private Vector expectedVelocity;
    private int velocityTicks;
    private double observedVelocityX;
    private double observedVelocityY;
    private double observedVelocityZ;
    private double timerBalance;

    public synchronized MovementSample recordMovement(
            double newX, double newY, double newZ, float newYaw, float newPitch, String newWorld, long now
    ) {
        if (!hasPosition || !newWorld.equals(world)) {
            x = newX;
            y = newY;
            z = newZ;
            yaw = newYaw;
            pitch = newPitch;
            world = newWorld;
            lastMoveMillis = now;
            lastPacketMillis = now;
            lastRotationMillis = now;
            lastYawDelta = 0;
            lastPitchDelta = 0;
            hasPosition = true;
            return null;
        }
        float yawDelta = angleDifference(newYaw, yaw);
        float pitchDelta = newPitch - pitch;
        MovementSample sample = new MovementSample(
                newX - x, newY - y, newZ - z, yawDelta, pitchDelta,
                Math.max(1L, now - lastMoveMillis), Math.max(1L, now - lastPacketMillis)
        );
        x = newX;
        y = newY;
        z = newZ;
        yaw = newYaw;
        pitch = newPitch;
        if (Math.abs(yawDelta) > 0.01F || Math.abs(pitchDelta) > 0.01F) {
            lastYawDelta = yawDelta;
            lastPitchDelta = pitchDelta;
            lastRotationMillis = now;
        }
        lastMoveMillis = now;
        lastPacketMillis = now;
        if (expectedVelocity != null) {
            observedVelocityX += sample.dx();
            observedVelocityY += sample.dy();
            observedVelocityZ += sample.dz();
            velocityTicks++;
        }
        return sample;
    }

    public synchronized void resetPosition() {
        hasPosition = false;
        lastMoveMillis = 0;
        hoveringPackets = 0;
    }

    public synchronized void recordPacket(long now) {
        lastPacketMillis = now;
    }

    public synchronized RotationSample recordRotation(float newYaw, float newPitch, long now) {
        float yawDelta = angleDifference(newYaw, yaw);
        float pitchDelta = newPitch - pitch;
        yaw = newYaw;
        pitch = newPitch;
        lastYawDelta = yawDelta;
        lastPitchDelta = pitchDelta;
        lastRotationMillis = now;
        return new RotationSample(yawDelta, pitchDelta, now);
    }

    public synchronized RotationSample getRecentRotation(long now, long maxAgeMillis) {
        if (lastRotationMillis == 0 || now - lastRotationMillis > maxAgeMillis) {
            return null;
        }
        return new RotationSample(lastYawDelta, lastPitchDelta, lastRotationMillis);
    }

    private float angleDifference(float current, float previous) {
        return (current - previous + 540.0f) % 360.0f - 180.0f;
    }

    public synchronized double updateTimerBalance(long intervalMillis) {
        timerBalance = Math.max(0, timerBalance + 50.0 - intervalMillis);
        return timerBalance;
    }

    public synchronized void resetTimerBalance() {
        timerBalance = 0;
    }

    public synchronized void recordClick(long now, long windowMillis) {
        if (lastClickMillis > 0) {
            clickIntervals.addLast(now - lastClickMillis);
            while (clickIntervals.size() > 40) {
                clickIntervals.removeFirst();
            }
        }
        lastClickMillis = now;
        clickTimes.addLast(now);
        prune(clickTimes, now - windowMillis);
    }

    public synchronized int getClickCount(long now, long windowMillis) {
        prune(clickTimes, now - windowMillis);
        return clickTimes.size();
    }

    public synchronized double getClickIntervalStandardDeviation() {
        if (clickIntervals.size() < 8) {
            return Double.MAX_VALUE;
        }
        double mean = clickIntervals.stream().mapToDouble(Long::doubleValue).average().orElse(0);
        double variance = clickIntervals.stream().mapToDouble(interval -> {
            double delta = interval - mean;
            return delta * delta;
        }).average().orElse(0);
        return Math.sqrt(variance);
    }

    private void prune(Deque<Long> values, long oldestAllowed) {
        while (!values.isEmpty() && values.peekFirst() < oldestAllowed) {
            values.removeFirst();
        }
    }

    public synchronized double addViolation(String check, double amount, String info, long now) {
        double level = violationLevels.merge(check, amount, Double::sum);
        violations.addLast(new ViolationRecord(check, level, info, now));
        while (violations.size() > 50) {
            violations.removeFirst();
        }
        return level;
    }

    public synchronized double getViolationLevel(String check) {
        return violationLevels.getOrDefault(check, 0.0);
    }

    public synchronized void loadViolationLevels(Map<String, Double> levels) {
        violationLevels.clear();
        levels.forEach((check, level) -> {
            if (Double.isFinite(level) && level > 0) {
                violationLevels.put(check, level);
            }
        });
    }

    public synchronized boolean areViolationsLoaded() {
        return violationsLoaded;
    }

    public synchronized void setViolationsLoaded(boolean loaded) {
        violationsLoaded = loaded;
    }

    public synchronized double getTotalViolationLevel() {
        return violationLevels.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    public synchronized Map<String, Double> getViolationLevels() {
        return Map.copyOf(violationLevels);
    }

    public synchronized List<ViolationRecord> getRecentViolations(String check) {
        return violations.stream()
                .filter(record -> check == null || record.check().equalsIgnoreCase(check))
                .toList();
    }

    public synchronized void resetViolations() {
        violationLevels.clear();
        violations.clear();
    }

    public synchronized void decay(double amount) {
        violationLevels.replaceAll((check, level) -> Math.max(0, level - amount));
    }

    public synchronized void setServerGrounded(boolean grounded) {
        serverGrounded = grounded;
    }

    public synchronized boolean isServerGrounded() {
        return serverGrounded;
    }

    public synchronized void setUsingItem(boolean usingItem) {
        this.usingItem = usingItem;
    }

    public synchronized boolean isUsingItem() {
        return usingItem;
    }

    public synchronized void setInventoryOpen(boolean inventoryOpen) {
        this.inventoryOpen = inventoryOpen;
    }

    public synchronized boolean isInventoryOpen() {
        return inventoryOpen;
    }

    public synchronized void setInVehicle(boolean inVehicle) {
        this.inVehicle = inVehicle;
    }

    public synchronized boolean isInVehicle() {
        return inVehicle;
    }

    public synchronized void setInLiquid(boolean inLiquid) {
        this.inLiquid = inLiquid;
    }

    public synchronized boolean isInLiquid() {
        return inLiquid;
    }

    public synchronized void setMovementExempt(boolean movementExempt) {
        this.movementExempt = movementExempt;
    }

    public synchronized boolean isMovementExempt() {
        return movementExempt;
    }

    public synchronized void setLastPacketGroundClaim(boolean grounded) {
        lastPacketGroundClaim = grounded;
    }

    public synchronized boolean hasLastPacketGroundClaim() {
        return lastPacketGroundClaim;
    }

    public synchronized int updateHoveringPackets(boolean hovering) {
        hoveringPackets = hovering ? hoveringPackets + 1 : 0;
        return hoveringPackets;
    }

    public synchronized void setSafeLocation(Location location) {
        safeLocation = location == null ? null : location.clone();
    }

    public synchronized Location getSafeLocation() {
        return safeLocation == null ? null : safeLocation.clone();
    }

    public synchronized void setExpectedVelocity(Vector velocity) {
        expectedVelocity = velocity == null ? null : velocity.clone();
        velocityTicks = 0;
        observedVelocityX = 0;
        observedVelocityY = 0;
        observedVelocityZ = 0;
    }

    public synchronized VelocityResponse getVelocityResponse() {
        if (expectedVelocity == null || velocityTicks < 3) {
            return null;
        }
        VelocityResponse response = new VelocityResponse(
                expectedVelocity.clone(), new Vector(observedVelocityX, observedVelocityY, observedVelocityZ)
        );
        expectedVelocity = null;
        return response;
    }

    public synchronized long getLastClickMillis() {
        return lastClickMillis;
    }

    public synchronized long getLastPlaceMillis() {
        return lastPlaceMillis;
    }

    public synchronized void setLastPlaceMillis(long lastPlaceMillis) {
        this.lastPlaceMillis = lastPlaceMillis;
    }

    public synchronized long getLastInventoryClickMillis() {
        return lastInventoryClickMillis;
    }

    public synchronized void setLastInventoryClickMillis(long now) {
        lastInventoryClickMillis = now;
    }

    public synchronized long getLastTotemSwapMillis() {
        return lastTotemSwapMillis;
    }

    public synchronized void setLastTotemSwapMillis(long now) {
        lastTotemSwapMillis = now;
    }

    public synchronized long getLastTotemConsumeMillis() {
        return lastTotemConsumeMillis;
    }

    public synchronized void setLastTotemConsumeMillis(long now) {
        lastTotemConsumeMillis = now;
    }

    public synchronized void clearLastTotemConsumeMillis() {
        lastTotemConsumeMillis = 0;
    }

    public synchronized long getLastAttackMillis() {
        return lastAttackMillis;
    }

    public synchronized void setLastAttackMillis(long now) {
        lastAttackMillis = now;
    }

    public synchronized long getLastBlockStartMillis() {
        return lastBlockStartMillis;
    }

    public synchronized void setLastBlockStartMillis(long now) {
        lastBlockStartMillis = now;
    }

    public synchronized long recordVehicleMove(long now) {
        long elapsed = lastVehicleMoveMillis == 0 ? 50 : Math.max(1, now - lastVehicleMoveMillis);
        lastVehicleMoveMillis = now;
        return elapsed;
    }

    public synchronized float getYaw() {
        return yaw;
    }

    public synchronized float getPitch() {
        return pitch;
    }

    public synchronized long getLastPacketMillis() {
        return lastPacketMillis;
    }

    public synchronized long getLastMoveMillis() {
        return lastMoveMillis;
    }

    public record MovementSample(double dx, double dy, double dz, double dyaw, double dpitch,
                                 long elapsedMillis, long packetElapsedMillis) {
    }

    public record RotationSample(float yawDelta, float pitchDelta, long timestamp) {
    }

    public record ViolationRecord(String check, double level, String info, long timestamp) {
    }

    public record VelocityResponse(Vector expected, Vector observed) {
    }
}
