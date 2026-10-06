package com.springknight3.anticheat.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import com.springknight3.anticheat.AntiCheatPlugin;
import com.springknight3.anticheat.checks.ViolationEngine;
import com.springknight3.anticheat.config.ConfigManager;
import com.springknight3.anticheat.data.ExemptionManager;
import com.springknight3.anticheat.data.PlayerData;
import com.springknight3.anticheat.data.PlayerDataManager;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GameplayListener implements Listener {
    private final AntiCheatPlugin plugin;
    private final ConfigManager configs;
    private final PlayerDataManager playerData;
    private final ExemptionManager exemptions;
    private final ViolationEngine violations;
    private final Map<UUID, ChatRateWindow> chatRates = new ConcurrentHashMap<>();
    private final Map<UUID, AimState> aimStates = new ConcurrentHashMap<>();

    public GameplayListener(AntiCheatPlugin plugin, ConfigManager configs, PlayerDataManager playerData,
                            ExemptionManager exemptions, ViolationEngine violations) {
        this.plugin = plugin;
        this.configs = configs;
        this.playerData = playerData;
        this.exemptions = exemptions;
        this.violations = violations;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (exemptions.isExempt(player.getUniqueId()) || !configs.isCheckEnabled("chat.spam")) {
            return;
        }
        long now = System.currentTimeMillis();
        long windowMillis = Math.max(1000,
                configs.getCheckInt("chat.spam", "window-seconds", 5) * 1000L);
        int maximum = Math.max(1, configs.getCheckInt("chat.spam", "max-messages", 5));
        ChatRateWindow.Result result = chatRates.computeIfAbsent(player.getUniqueId(),
                        ignored -> new ChatRateWindow())
                .record(now, windowMillis, maximum);
        if (result.overLimit()) {
            event.setCancelled(true);
            if (result.firstOverLimit()) {
                violations.flag(player.getUniqueId(), "chat.spam",
                        "Exceeded " + maximum + " messages in " + windowMillis / 1000 + " seconds");
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.initializePlayer(event.getPlayer());
        plugin.loadPlayerViolations(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerData data = playerData.find(player.getUniqueId());
        if (data != null && data.areViolationsLoaded()) {
            pluginStorageSave(player, data);
        }
        playerData.remove(player.getUniqueId());
        exemptions.setExempt(player.getUniqueId(), false);
        chatRates.remove(player.getUniqueId());
        aimStates.remove(player.getUniqueId());
        violations.forgetPlayerState(player.getUniqueId());
    }

    private void pluginStorageSave(Player player, PlayerData data) {
        plugin.savePlayerViolations(player.getUniqueId(), data.getViolationLevels());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        playerData.get(event.getPlayer().getUniqueId()).resetPosition();
        plugin.initializePlayer(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null) {
            playerData.get(event.getPlayer().getUniqueId()).resetPosition();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Player player = event.getPlayer();
        PlayerData data = playerData.get(player.getUniqueId());
        data.setServerGrounded(player.isOnGround());
        data.setInVehicle(player.isInsideVehicle());
        data.setInLiquid(to.getBlock().isLiquid());
        data.setMovementExempt(player.getAllowFlight() || player.isGliding()
                || player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR);
        if (configs.isCheckEnabled("movement.jesus") && to.getBlock().isLiquid()
                && to.getBlock().getRelative(0, -1, 0).isLiquid()
                && player.isOnGround() && !player.isSwimming() && !player.isInsideVehicle()
                && !data.isMovementExempt()) {
            violations.flag(player.getUniqueId(), "movement.jesus", "Server reported ground contact in liquid");
        }
        if (player.isOnGround() && to.getBlock().getRelative(0, -1, 0).getType().isSolid()
                && !to.getBlock().isLiquid() && !player.isInsideVehicle()) {
            data.setSafeLocation(to);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        playerData.get(event.getPlayer().getUniqueId()).setExpectedVelocity(event.getVelocity());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getItem() == null) {
            return;
        }
        Material type = event.getItem().getType();
        if (type.isEdible() || type == Material.SHIELD || type == Material.BOW || type == Material.CROSSBOW
                || type == Material.POTION || type == Material.TRIDENT) {
            PlayerData data = playerData.get(event.getPlayer().getUniqueId());
            data.setUsingItem(true);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> data.setUsingItem(false), 40L);
        }
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        playerData.get(event.getPlayer().getUniqueId()).setUsingItem(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTotemResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player) {
            playerData.get(player.getUniqueId()).setLastTotemConsumeMillis(System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        playerData.get(event.getPlayer().getUniqueId()).setUsingItem(false);
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            playerData.get(player.getUniqueId()).setInventoryOpen(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            playerData.get(player.getUniqueId()).setInventoryOpen(false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || exemptions.isExempt(player.getUniqueId())) {
            return;
        }
        PlayerData data = playerData.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        long movementWindow = configs.getCheckInt("inventory.inventorymove", "movement-window-ms", 250);
        if (configs.isCheckEnabled("inventory.inventorymove")
                && now - data.getLastMoveMillis() < movementWindow) {
            violations.flag(player.getUniqueId(), "inventory.inventorymove", "Inventory click during movement");
        }
        if (data.isInventoryOpen() && data.getLastInventoryClickMillis() > 0
                && now - data.getLastInventoryClickMillis()
                < configs.getCheckInt("inventory.cheststealer", "min-click-delay-ms", 100)) {
            violations.flag(player.getUniqueId(), "inventory.cheststealer", "Container click interval too short");
        }
        data.setLastInventoryClickMillis(now);

        ItemStack current = event.getCurrentItem();
        if (current != null && current.getType() == Material.TOTEM_OF_UNDYING) {
            long consumedAt = data.getLastTotemConsumeMillis();
            if (consumedAt > 0 && now - consumedAt
                    < configs.getCheckInt("inventory.autototem", "min-swap-delay-ms", 60)
                    && configs.isCheckEnabled("inventory.autototem")) {
                violations.flag(player.getUniqueId(), "inventory.autototem", "Totem inventory action interval="
                        + (now - consumedAt) + "ms");
                data.clearLastTotemConsumeMillis();
            }
            data.setLastTotemSwapMillis(now);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (exemptions.isExempt(player.getUniqueId())) {
            return;
        }
        PlayerData data = playerData.get(player.getUniqueId());
        long consumedAt = data.getLastTotemConsumeMillis();
        if (consumedAt > 0 && (isTotem(event.getMainHandItem()) || isTotem(event.getOffHandItem()))) {
            long elapsed = System.currentTimeMillis() - consumedAt;
            if (elapsed < configs.getCheckInt("inventory.autototem", "min-swap-delay-ms", 60)
                    && configs.isCheckEnabled("inventory.autototem")) {
                violations.flag(player.getUniqueId(), "inventory.autototem",
                        "Totem hand swap interval=" + elapsed + "ms");
            }
            data.clearLastTotemConsumeMillis();
        }
    }

    private boolean isTotem(ItemStack item) {
        return item != null && item.getType() == Material.TOTEM_OF_UNDYING;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (exemptions.isExempt(player.getUniqueId())) {
            return;
        }
        PlayerData data = playerData.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        long previous = data.getLastPlaceMillis();
        if (previous > 0 && now - previous < configs.getCheckInt("click.fastplace", "min-place-delay-ms", 45)
                && configs.isCheckEnabled("click.fastplace")) {
            violations.flag(player.getUniqueId(), "click.fastplace", "Place interval=" + (now - previous) + "ms");
        }
        data.setLastPlaceMillis(now);
        if (configs.isCheckEnabled("world.airplace")) {
            Block against = event.getBlockAgainst();
            double maxDistance = configs.getCheckDouble("world.airplace", "max-place-distance", 5.0);
            double placeDistance = player.getEyeLocation().distance(
                    event.getBlockPlaced().getLocation().add(0.5, 0.5, 0.5));
            if (against.getType().isAir() || placeDistance > maxDistance) {
                violations.flag(player.getUniqueId(), "world.airplace", String.format(java.util.Locale.ROOT,
                        "against=%s, distance=%.2f, max=%.2f",
                        against.getType(), placeDistance, maxDistance));
            }
        }
        if (configs.isCheckEnabled("world.scaffold")) {
            Location eye = player.getEyeLocation();
            Location placedCenter = event.getBlockPlaced().getLocation().add(0.5, 0.5, 0.5);
            org.bukkit.util.Vector direction = eye.getDirection();
            org.bukkit.util.Vector toBlock = placedCenter.toVector().subtract(eye.toVector()).normalize();
            if (direction.dot(toBlock) < configs.getCheckDouble("world.scaffold", "min-facing-dot", -0.1)) {
                violations.flag(player.getUniqueId(), "world.scaffold", "Placed block behind player view");
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        playerData.get(event.getPlayer().getUniqueId()).setLastBlockStartMillis(System.currentTimeMillis());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        PlayerData data = playerData.get(player.getUniqueId());
        long started = data.getLastBlockStartMillis();
        long elapsed = System.currentTimeMillis() - started;
        if (started > 0 && elapsed < configs.getCheckInt("world.fastbreak", "min-break-delay-ms", 50)
                && player.getGameMode() != org.bukkit.GameMode.CREATIVE
                && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                && configs.isCheckEnabled("world.fastbreak")) {
            violations.flag(player.getUniqueId(), "world.fastbreak", "Block break completed in " + elapsed + "ms");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity target)
                || exemptions.isExempt(attacker.getUniqueId())) {
            return;
        }
        PlayerData data = playerData.get(attacker.getUniqueId());
        long now = System.currentTimeMillis();
        data.setLastAttackMillis(now);
        checkAimbot(attacker, data, now);
        double maximumReach = attacker.getGameMode().name().equals("CREATIVE")
                ? configs.getCheckDouble("combat.reach", "max-creative-reach", 5.0)
                : configs.getCheckDouble("combat.reach", "max-survival-reach", 3.0);
        double distance = distanceToBox(attacker.getEyeLocation(), target.getBoundingBox());
        if (configs.isCheckEnabled("combat.reach") && distance > maximumReach) {
            violations.flag(attacker.getUniqueId(), "combat.reach", String.format(java.util.Locale.ROOT,
                    "reach=%.3f, max=%.3f", distance, maximumReach));
        }
        if (configs.isCheckEnabled("combat.hitbox")) {
            double tolerance = configs.getCheckDouble("combat.hitbox", "tolerance-blocks", 0.45);
            Location eye = attacker.getEyeLocation();
            RayTraceResult rayHit = target.getBoundingBox().clone().expand(tolerance)
                    .rayTrace(eye.toVector(), eye.getDirection(), maximumReach + tolerance);
            if (rayHit == null) {
                violations.flag(attacker.getUniqueId(), "combat.hitbox",
                        "Attack direction did not intersect the target hitbox within reach");
            }
        }
        if (configs.isCheckEnabled("combat.criticals") && attacker.isOnGround()
                && !data.hasLastPacketGroundClaim() && attacker.getFallDistance() <= 0.0F
                && System.currentTimeMillis() - data.getLastMoveMillis() < 150) {
            violations.flag(attacker.getUniqueId(), "combat.criticals",
                    "Client claimed airborne while server collision state was grounded");
        }
    }

    private void checkAimbot(Player attacker, PlayerData data, long now) {
        if (!configs.isCheckEnabled("combat.aimbot")) {
            return;
        }
        long window = Math.max(1, configs.getCheckInt("combat.aimbot", "rotation-window-ms", 150));
        PlayerData.RotationSample rotation = data.getRecentRotation(now, window);
        AimState state = aimStates.computeIfAbsent(attacker.getUniqueId(), ignored -> new AimState());
        double limit = configs.getCheckDouble("combat.aimbot", "max-attack-rotation-degrees", 120.0);
        if (rotation == null) {
            state.reset();
            return;
        }
        double magnitude = Math.hypot(rotation.yawDelta(), rotation.pitchDelta());
        if (magnitude >= limit && state.lastRotationTimestamp != rotation.timestamp()) {
            state.lastRotationTimestamp = rotation.timestamp();
            state.consecutiveSnaps++;
            int required = Math.max(1, configs.getCheckInt("combat.aimbot", "snap-attacks-before-flag", 2));
            if (state.consecutiveSnaps >= required) {
                violations.flag(attacker.getUniqueId(), "combat.aimbot",
                        String.format(java.util.Locale.ROOT, "Repeated %.1f-degree attack rotation", magnitude));
                state.reset();
            }
        } else if (magnitude < limit) {
            state.reset();
        }
    }

    private double distanceToBox(Location origin, BoundingBox box) {
        double x = Math.max(box.getMinX(), Math.min(origin.getX(), box.getMaxX()));
        double y = Math.max(box.getMinY(), Math.min(origin.getY(), box.getMaxY()));
        double z = Math.max(box.getMinZ(), Math.min(origin.getZ(), box.getMaxZ()));
        return origin.distance(new Location(origin.getWorld(), x, y, z));
    }

    private static final class ChatRateWindow {
        private final Deque<Long> timestamps = new ArrayDeque<>();
        private boolean overLimit;

        private synchronized Result record(long now, long windowMillis, int maximum) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowMillis) {
                timestamps.removeFirst();
            }
            timestamps.addLast(now);
            boolean exceeded = timestamps.size() > maximum;
            boolean first = exceeded && !overLimit;
            overLimit = exceeded;
            return new Result(exceeded, first);
        }

        private record Result(boolean overLimit, boolean firstOverLimit) {
        }
    }

    private static final class AimState {
        private int consecutiveSnaps;
        private long lastRotationTimestamp;

        private void reset() {
            consecutiveSnaps = 0;
            lastRotationTimestamp = 0;
        }
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            playerData.get(player.getUniqueId()).setInVehicle(true);
        }
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            playerData.get(player.getUniqueId()).setInVehicle(false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        Entity vehicle = event.getVehicle();
        Player passenger = vehicle.getPassengers().stream().filter(Player.class::isInstance)
                .map(Player.class::cast).findFirst().orElse(null);
        if (passenger == null || exemptions.isExempt(passenger.getUniqueId())) {
            return;
        }
        PlayerData data = playerData.get(passenger.getUniqueId());
        double dy = event.getTo().getY() - event.getFrom().getY();
        long elapsed = data.recordVehicleMove(System.currentTimeMillis());
        if (vehicle instanceof Boat && !data.isInLiquid()
                && dy > configs.getCheckDouble("vehicle.boatfly", "max-vertical-speed", 0.1)
                && configs.isCheckEnabled("vehicle.boatfly")) {
            violations.flag(passenger.getUniqueId(), "vehicle.boatfly", "Boat vertical delta="
                    + String.format(java.util.Locale.ROOT, "%.3f", dy));
        }
        double horizontal = Math.hypot(event.getTo().getX() - event.getFrom().getX(),
                event.getTo().getZ() - event.getFrom().getZ());
        double maxMountSpeed = configs.getCheckDouble("vehicle.entityspeed", "max-mount-speed", 0.6)
                * Math.max(1, elapsed / 50.0);
        double mountTolerance = configs.getCheckDouble("vehicle.entityspeed", "tolerance-margin", 0.2);
        if (horizontal > maxMountSpeed + mountTolerance && configs.isCheckEnabled("vehicle.entityspeed")) {
            violations.flag(passenger.getUniqueId(), "vehicle.entityspeed", "Vehicle horizontal delta="
                    + String.format(java.util.Locale.ROOT, "%.3f", horizontal));
        }
        Block destination = event.getTo().getBlock();
        if (!destination.isPassable() && configs.isCheckEnabled("vehicle.phase")) {
            violations.flag(passenger.getUniqueId(), "vehicle.phase", "Vehicle destination intersects a solid block");
        }
    }
}
