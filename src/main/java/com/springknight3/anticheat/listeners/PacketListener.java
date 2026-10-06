package com.springknight3.anticheat.listeners;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.springknight3.anticheat.checks.ViolationEngine;
import com.springknight3.anticheat.config.ConfigManager;
import com.springknight3.anticheat.data.ExemptionManager;
import com.springknight3.anticheat.data.PlayerData;
import com.springknight3.anticheat.data.PlayerDataManager;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PacketListener extends PacketListenerAbstract {
    private final ConfigManager configs;
    private final PlayerDataManager playerData;
    private final ExemptionManager exemptions;
    private final ViolationEngine violations;
    private final Map<UUID, Deque<Long>> movementPackets = new ConcurrentHashMap<>();
    private final Map<UUID, PacketRateWindow> packetRates = new ConcurrentHashMap<>();
    private final Map<UUID, RoutePatternTracker> routePatterns = new ConcurrentHashMap<>();

    public PacketListener(ConfigManager configs, PlayerDataManager playerData,
                          ExemptionManager exemptions, ViolationEngine violations) {
        this.configs = configs;
        this.playerData = playerData;
        this.exemptions = exemptions;
        this.violations = violations;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        UUID uuid = event.getUser().getUUID();
        if (uuid == null || exemptions.isExempt(uuid)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            handleMovementPacket(event, uuid, now);
            return;
        }
        String type = event.getPacketType().toString().toUpperCase(java.util.Locale.ROOT);
        PlayerData data = playerData.get(uuid);
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interaction = new WrapperPlayClientInteractEntity(event);
            if (interaction.getEntityId() == event.getUser().getEntityId()
                    && configs.isCheckEnabled("packet.selfinteract")) {
                event.setCancelled(true);
                violations.flag(uuid, "packet.selfinteract", "Client attempted to interact with its own entity");
            }
            return;
        }
        if (type.contains("ANIMATION")) {
            long clickWindow = Math.max(100, configs.getCheckInt("click.autoclicker", "window-ms", 1000));
            data.recordClick(now, clickWindow);
            int cps = (int) Math.ceil(data.getClickCount(now, clickWindow) * 1000.0 / clickWindow);
            double deviation = data.getClickIntervalStandardDeviation();
            if (configs.isCheckEnabled("click.autoclicker")
                    && (cps > configs.getCheckInt("click.autoclicker", "max-cps", 18)
                    || deviation < configs.getCheckDouble("click.autoclicker", "min-std-deviation", 0.05))) {
                violations.flag(uuid, "click.autoclicker", "CPS=" + cps
                        + ", interval stddev=" + String.format(java.util.Locale.ROOT, "%.3f", deviation));
            }
        } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
            if (action == DiggingAction.START_DIGGING) {
                data.setLastBlockStartMillis(now);
            } else if (action == DiggingAction.FINISHED_DIGGING && !data.isMovementExempt()
                    && data.getLastBlockStartMillis() > 0
                    && now - data.getLastBlockStartMillis()
                    < configs.getCheckInt("world.fastbreak", "min-break-delay-ms", 50)) {
                violations.flag(uuid, "world.fastbreak", "Dig packet completed in "
                        + (now - data.getLastBlockStartMillis()) + "ms");
            }
        }
    }

    private void handleMovementPacket(PacketReceiveEvent event, UUID uuid, long now) {
        if (configs.isCheckEnabled("packet.badpackets")) {
            PacketRateWindow rateWindow = packetRates.computeIfAbsent(uuid, ignored -> new PacketRateWindow());
            PacketRateWindow.Result rate = rateWindow.record(now,
                    Math.max(20, configs.getCheckInt(
                            "packet.badpackets", "max-movement-packets-per-second", 120)));
            if (rate.overLimit()) {
                event.setCancelled(true);
                if (rate.firstOverLimit()) {
                    violations.flag(uuid, "packet.badpackets", "Movement packet rate exceeded configured limit");
                }
                return;
            }
        }
        PlayerData data = playerData.get(uuid);
        WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(event);
        data.setLastPacketGroundClaim(packet.isOnGround());
        PlayerData.MovementSample sample = null;
        if (packet.hasPositionChanged()) {
            var location = packet.getLocation();
            if (!Double.isFinite(location.getX()) || !Double.isFinite(location.getY())
                    || !Double.isFinite(location.getZ()) || !Float.isFinite(location.getYaw())
                    || !Float.isFinite(location.getPitch()) || location.getPitch() < -90
                    || location.getPitch() > 90
                    || Math.abs(location.getX()) > configs.getCheckDouble(
                    "packet.badpackets", "world-coordinate-limit", 30_000_000)
                    || Math.abs(location.getY()) > configs.getCheckDouble(
                    "packet.badpackets", "world-coordinate-limit", 30_000_000)
                    || Math.abs(location.getZ()) > configs.getCheckDouble(
                    "packet.badpackets", "world-coordinate-limit", 30_000_000)) {
                event.setCancelled(true);
                violations.flag(uuid, "packet.badpackets", "Non-finite or out-of-range movement payload");
                return;
            }
            sample = data.recordMovement(location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch(), "session", now);
        } else if (packet.hasRotationChanged()) {
            if (!Float.isFinite(packet.getLocation().getYaw()) || !Float.isFinite(packet.getLocation().getPitch())
                    || packet.getLocation().getPitch() < -90 || packet.getLocation().getPitch() > 90) {
                event.setCancelled(true);
                violations.flag(uuid, "packet.badpackets", "Non-finite or out-of-range rotation payload");
                return;
            }
            PlayerData.RotationSample rotation = data.recordRotation(
                    packet.getLocation().getYaw(), packet.getLocation().getPitch(), now);
            checkRotation(uuid, rotation.yawDelta(), rotation.pitchDelta());
        }

        Deque<Long> timestamps = movementPackets.computeIfAbsent(uuid, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            timestamps.addLast(now);
            int packetWindow = Math.clamp(configs.getCheckInt("world.timer", "sample-packets", 40), 5, 100);
            while (timestamps.size() > packetWindow) {
                timestamps.removeFirst();
            }
            if (timestamps.size() == packetWindow) {
                long span = timestamps.peekLast() - timestamps.peekFirst();
                double maxGameSpeed = Math.max(0.1,
                        configs.getCheckDouble("world.timer", "max-game-speed", 1.02));
                double tickDuration = configs.getCheckInt("world.timer", "tick-duration-ms", 50);
                double minimumSpan = (packetWindow - 1) * tickDuration / maxGameSpeed;
                if (span < minimumSpan && configs.isCheckEnabled("world.timer")) {
                    violations.flag(uuid, "world.timer", packetWindow + " movement packets in " + span + "ms");
                    timestamps.clear();
                }
            }
        }
        if (sample == null) {
            return;
        }
        if (data.isMovementExempt()) {
            checkRotation(uuid, sample.dyaw(), sample.dpitch());
            return;
        }
        boolean hovering = !data.isServerGrounded() && !data.isInLiquid() && !data.isMovementExempt()
                && Math.abs(sample.dy()) < 0.001;
        int hoveringPackets = configs.getCheckInt("movement.fly", "zero-motion-packet-limit", 8);
        if (data.updateHoveringPackets(hovering) == hoveringPackets && configs.isCheckEnabled("movement.fly")) {
            violations.flag(uuid, "movement.fly", "Repeated zero-vertical-motion packets while airborne");
        }
        double horizontal = Math.hypot(sample.dx(), sample.dz());
        double tickDuration = Math.max(1, configs.getCheckInt("movement.speed", "tick-duration-ms", 50));
        double elapsedTicks = Math.max(1.0, sample.elapsedMillis() / tickDuration);
        double speedLimit = (data.isServerGrounded()
                ? configs.getCheckDouble("movement.speed", "max-ground-speed", 0.32)
                : configs.getCheckDouble("movement.speed", "max-air-speed", 0.36)) * elapsedTicks;
        double speedMargin = configs.getCheckDouble("movement.speed", "tolerance-margin", 0.12);
        if (horizontal > speedLimit + speedMargin && configs.isCheckEnabled("movement.speed")) {
            violations.flag(uuid, "movement.speed", String.format(java.util.Locale.ROOT,
                    "horizontal=%.3f, allowed=%.3f", horizontal, speedLimit + speedMargin));
        }

        double flyTolerance = configs.getCheckDouble("movement.fly", "tolerance-margin", 0.08);
        if (!data.isServerGrounded()
                && sample.dy() > configs.getCheckDouble("movement.fly", "max-upward-speed", 0.42) + flyTolerance
                && configs.isCheckEnabled("movement.fly")) {
            violations.flag(uuid, "movement.fly", String.format(java.util.Locale.ROOT,
                    "unexpected upward delta=%.3f", sample.dy()));
        }
        if (packet.isOnGround() && !data.isServerGrounded()
                && sample.dy() < -configs.getCheckDouble("movement.nofall", "min-descending-speed", 0.1)
                && configs.isCheckEnabled("movement.nofall")) {
            violations.flag(uuid, "movement.nofall", "Client reported ground while descending");
        }
        if (sample.dy() > configs.getCheckDouble("movement.step", "max-step-height", 0.6)
                && !data.isServerGrounded() && configs.isCheckEnabled("movement.step")) {
            violations.flag(uuid, "movement.step", String.format(java.util.Locale.ROOT,
                    "vertical delta=%.3f", sample.dy()));
        }
        if (data.isUsingItem()) {
            double maxUseSpeed = configs.getCheckDouble("movement.speed", "max-ground-speed", 0.32)
                    * configs.getCheckDouble("movement.noslow", "max-item-use-speed-ratio", 0.2);
            if (horizontal > maxUseSpeed + speedMargin && configs.isCheckEnabled("movement.noslow")) {
                violations.flag(uuid, "movement.noslow", String.format(java.util.Locale.ROOT,
                        "item-use horizontal=%.3f, allowed=%.3f", horizontal, maxUseSpeed + speedMargin));
            }
        }

        PlayerData.VelocityResponse velocity = data.getVelocityResponse();
        if (velocity != null && configs.isCheckEnabled("combat.velocity")) {
            double expectedHorizontal = Math.hypot(velocity.expected().getX(), velocity.expected().getZ());
            double observedHorizontal = Math.hypot(velocity.observed().getX(), velocity.observed().getZ());
            double minHorizontal = expectedHorizontal
                    * (1.0 - configs.getCheckDouble("combat.velocity", "max-horizontal-reduction", 0.99));
            double minVertical = Math.abs(velocity.expected().getY())
                    * (1.0 - configs.getCheckDouble("combat.velocity", "max-vertical-reduction", 0.99));
            if (expectedHorizontal > 0.1 && observedHorizontal < minHorizontal
                    || Math.abs(velocity.expected().getY()) > 0.1
                    && Math.abs(velocity.observed().getY()) < minVertical) {
                violations.flag(uuid, "combat.velocity", String.format(java.util.Locale.ROOT,
                        "expected=%s, observed=%s", velocity.expected(), velocity.observed()));
            }
        }

        if (configs.isCheckEnabled("world.baritone")
                && routePatterns.computeIfAbsent(uuid, ignored -> new RoutePatternTracker())
                .record(sample, now, configs.getCheckInt("world.baritone", "minimum-pattern-length", 5))) {
            violations.flag(uuid, "world.baritone", "Repeated movement route pattern consistent with automation");
        }
        checkRotation(uuid, sample.dyaw(), sample.dpitch());
    }

    private void checkRotation(UUID uuid, double yaw, double pitch) {
        double rotationLimit = configs.getCheckDouble("combat.rotation", "max-snap-degrees", 180.0);
        if ((Math.abs(yaw) > rotationLimit || Math.abs(pitch) > rotationLimit)
                && configs.isCheckEnabled("combat.rotation")) {
            violations.flag(uuid, "combat.rotation", String.format(java.util.Locale.ROOT,
                    "yaw=%.1f, pitch=%.1f", yaw, pitch));
        }
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        UUID uuid = event.getUser().getUUID();
        movementPackets.remove(uuid);
        packetRates.remove(uuid);
        routePatterns.remove(uuid);
    }

    private static final class PacketRateWindow {
        private final Deque<Long> timestamps = new ArrayDeque<>();
        private boolean overLimit;

        private synchronized Result record(long now, int maximumPerSecond) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= 1000) {
                timestamps.removeFirst();
            }
            timestamps.addLast(now);
            boolean exceeded = timestamps.size() > maximumPerSecond;
            boolean first = exceeded && !overLimit;
            overLimit = exceeded;
            return new Result(exceeded, first);
        }

        private record Result(boolean overLimit, boolean firstOverLimit) {
        }
    }

    private static final class RoutePatternTracker {
        private final Deque<MovementSignature> history = new ArrayDeque<>();
        private long lastFlagMillis;

        private synchronized boolean record(PlayerData.MovementSample sample, long now, int minimumPatternLength) {
            double horizontal = Math.hypot(sample.dx(), sample.dz());
            if (horizontal < 0.02) {
                history.clear();
                return false;
            }
            int direction = (int) Math.round(Math.atan2(sample.dz(), sample.dx()) / (Math.PI / 8));
            int distance = (int) Math.round(horizontal * 20);
            int yaw = (int) Math.round(sample.dyaw() / 5);
            history.addLast(new MovementSignature(direction, distance, yaw));
            while (history.size() > 48) {
                history.removeFirst();
            }
            int minimum = Math.clamp(minimumPatternLength, 4, 8);
            List<MovementSignature> samples = List.copyOf(history);
            boolean repeatedRoute = false;
            for (int period = minimum; period <= Math.min(8, samples.size() / 3); period++) {
                int start = samples.size() - period * 3;
                boolean hasTurn = false;
                boolean matches = true;
                int firstDirection = samples.get(start).direction();
                for (int index = 0; index < period; index++) {
                    MovementSignature first = samples.get(start + index);
                    MovementSignature second = samples.get(start + period + index);
                    MovementSignature third = samples.get(start + period * 2 + index);
                    hasTurn |= first.direction() != firstDirection;
                    if (!first.equals(second) || !first.equals(third)) {
                        matches = false;
                        break;
                    }
                }
                if (matches && hasTurn) {
                    repeatedRoute = true;
                    break;
                }
            }
            if (repeatedRoute && now - lastFlagMillis >= 30_000) {
                lastFlagMillis = now;
                return true;
            }
            return false;
        }

        private record MovementSignature(int direction, int distance, int yaw) {
        }
    }
}
