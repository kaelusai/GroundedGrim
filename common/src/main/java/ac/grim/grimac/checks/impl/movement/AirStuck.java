package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.platform.api.world.PlatformWorld;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.chunks.Column;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.math.Location;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

@CheckData(name = "AirStuck", description = "Checks for players freezing in air without position packets", experimental = true)
public class AirStuck extends Check implements PacketReceiveListener {

    private static final double MIN_REAL_DISTANCE_SQ = 0.01 * 0.01;

    private long lastPositionTime = System.currentTimeMillis();
    private long lastSimTime = 0;
    private long lastFlagTime = 0;
    private double anchorX = Double.NaN;
    private double anchorY = Double.NaN;
    private double anchorZ = Double.NaN;
    private boolean enabled = true;
    private int maxMs = 2000;
    private int flagCooldownMs = 3000;
    private boolean debug = false;

    public AirStuck(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        enabled = config.getBooleanElse("AirStuck.enabled", true);
        maxMs = config.getIntElse("AirStuck.max-ticks", 40) * 50;
        flagCooldownMs = config.getIntElse("AirStuck.flag-cooldown-ticks", 60) * 50;
        debug = config.getBooleanElse("AirStuck.debug", false);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            if (player.packetStateData.lastPacketWasTeleport) {
                lastPositionTime = System.currentTimeMillis();
                anchorX = Double.NaN;
                return;
            }
            WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
            if (flying.hasPositionChanged()) {
                double x = flying.getLocation().getX();
                double y = flying.getLocation().getY();
                double z = flying.getLocation().getZ();
                if (Double.isNaN(anchorX)) {
                    anchorX = x;
                    anchorY = y;
                    anchorZ = z;
                }
                double dx = x - anchorX, dy = y - anchorY, dz = z - anchorZ;
                if (dx * dx + dy * dy + dz * dz > MIN_REAL_DISTANCE_SQ) {
                    lastPositionTime = System.currentTimeMillis();
                    anchorX = x;
                    anchorY = y;
                    anchorZ = z;
                }
            }
        }

        checkTimeout();
    }

    private void checkTimeout() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastPositionTime;

        if (elapsed <= maxMs || isExempt()) return;

        if (now - lastSimTime < 50) return;
        lastSimTime = now;

        if (now - lastFlagTime < flagCooldownMs) return;

        Vector3d landing = findLanding(maxDrop(elapsed));

        if (debug) {
            LogUtil.info(String.format("[AirStuck] %s startY=%.2f landing=%s",
                    player.getName(), player.y, landing));
        }

        if (landing == null || player.platformPlayer == null) return;

        boolean flagged = flagAndAlert("ms=" + elapsed + " y=" + String.format("%.1f", landing.getY()));
        lastFlagTime = now;
        lastPositionTime = now;

        if (!flagged || !canCorrect()) return;
        scheduleDescent(landing, this::canCorrect, () -> {});
    }

    private boolean canCorrect() {
        return enabled && shouldModifyPackets() && !isNoSetbackPermission() && !isExempt();
    }

    private boolean isExempt() {
        return player.inVehicle() || player.isFlying || player.isClimbing
                || player.isGliding || player.isInBed || !player.hasGravity || player.gravity <= 0
                || player.wasTouchingWater || player.wasTouchingLava
                || player.compensatedEntities.self.isDead
                || player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.LEVITATION).isPresent()
                || player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.SLOW_FALLING).isPresent()
                || !player.getSetbackTeleportUtil().hasAcceptedSpawnTeleport
                || player.getSetbackTeleportUtil().shouldBlockMovement()
                || System.currentTimeMillis() - player.joinTime < 5000;
    }

    public void correctElytraStall(long elapsed, BooleanSupplier shouldCorrect, Runnable onFallDistanceReset) {
        if (player.getSetbackTeleportUtil().shouldBlockMovement()) return;
        Vector3d destination = findDescent(maxDrop(elapsed), false);
        if (destination != null && player.platformPlayer != null) {
            scheduleDescent(destination, shouldCorrect, onFallDistanceReset);
        }
    }

    public boolean hasGroundSupport() {
        SimpleCollisionBox box = GetBoundingBox.getPlayerBoundingBox(player, player.x, player.y, player.z);
        if (!hasAcknowledgedChunks(box)) return false;
        List<SimpleCollisionBox> collisions = new ArrayList<>();
        Collisions.getCollisionBoxes(player, box.copy().expandToCoordinate(0, -0.03, 0), collisions, false);
        return collisions.stream().anyMatch(obstacle -> obstacle.collideY(box, -0.03) > -0.03);
    }

    private double maxDrop(long elapsed) {
        double drop = 0;
        double velocityY = 0;
        int ticks = (int) Math.min(elapsed / 50L, 200L);
        for (int i = 0; i < ticks; i++) {
            velocityY = (velocityY - player.gravity) * 0.98;
            drop -= velocityY;
        }
        return Math.min(drop, 256.0);
    }

    private void scheduleDescent(Vector3d landing, BooleanSupplier shouldCorrect, Runnable onFallDistanceReset) {
        var platformPlayer = player.platformPlayer;
        PlatformWorld world = platformPlayer.getWorld();
        Vector3d start = new Vector3d(player.x, player.y, player.z);
        double toleranceSquared = Math.pow(player.getMovementThreshold() + SimpleCollisionBox.COLLISION_EPSILON, 2);
        Map<Vector3i, Integer> blocks = captureBlocks(landing);
        Location destination = new Location(world, landing.x, landing.y, landing.z, player.yaw, player.pitch);

        GrimAPI.INSTANCE.getScheduler().getEntityScheduler().execute(
                platformPlayer, GrimAPI.INSTANCE.getGrimPlugin(), () -> {
                    // The server task may run after movement, a world change or a block update.
                    if (!shouldCorrect.getAsBoolean() || platformPlayer.isDead() || platformPlayer.isFlying() || platformPlayer.getVehicle() != null
                            || !world.equals(platformPlayer.getWorld())
                            || start.distanceSquared(platformPlayer.getPosition()) > toleranceSquared
                            || !matchesBlocks(world, blocks)) return;
                    platformPlayer.resetFallDistance();
                    onFallDistanceReset.run();
                    platformPlayer.teleportAsync(destination);
                }, null, 0);
    }

    @Nullable Vector3d findLanding(double maxDrop) {
        return findDescent(maxDrop, true);
    }

    private @Nullable Vector3d findDescent(double maxDrop, boolean requireLanding) {
        maxDrop = Math.min(maxDrop, player.y - player.compensatedWorld.getMinHeight() - 2);
        if (!Double.isFinite(maxDrop) || maxDrop <= 0) return null;

        // Use Grim's position, not the platform's: shapes come from the compensated world.
        SimpleCollisionBox box = GetBoundingBox.getPlayerBoundingBox(player, player.x, player.y, player.z);
        SimpleCollisionBox sweep = box.copy().expandToCoordinate(0, -maxDrop, 0);
        if (!hasAcknowledgedChunks(sweep)) return null;

        List<SimpleCollisionBox> collisions = new ArrayList<>();
        Collisions.getCollisionBoxes(player, sweep, collisions, false);
        Double y = findLandingY(box, maxDrop, collisions, requireLanding);
        return y == null ? null : new Vector3d(player.x, y, player.z);
    }

    private boolean hasAcknowledgedChunks(SimpleCollisionBox box) {
        int minChunkX = ((int) Math.floor(box.minX - SimpleCollisionBox.COLLISION_EPSILON) - 1) >> 4;
        int maxChunkX = ((int) Math.floor(box.maxX + SimpleCollisionBox.COLLISION_EPSILON) + 1) >> 4;
        int minChunkZ = ((int) Math.floor(box.minZ - SimpleCollisionBox.COLLISION_EPSILON) - 1) >> 4;
        int maxChunkZ = ((int) Math.floor(box.maxZ + SimpleCollisionBox.COLLISION_EPSILON) + 1) >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                Column column = player.compensatedWorld.getChunk(x, z);
                if (column == null || column.transaction() >= player.lastTransactionReceived.get()) return false;
            }
        }

        return true;
    }

    static @Nullable Double findLandingY(SimpleCollisionBox box, double maxDrop, List<SimpleCollisionBox> collisions) {
        return findLandingY(box, maxDrop, collisions, true);
    }

    private static @Nullable Double findLandingY(SimpleCollisionBox box, double maxDrop, List<SimpleCollisionBox> collisions, boolean requireLanding) {
        // collideY clips motion from outside an obstacle; it cannot recover a box already inside one.
        if (collisions.stream().anyMatch(obstacle -> obstacle.isIntersected(box))) return null;
        double movementY = -maxDrop;
        for (SimpleCollisionBox obstacle : collisions) movementY = obstacle.collideY(box, movementY);
        if ((requireLanding && movementY <= -maxDrop) || movementY >= -0.01) return null;
        SimpleCollisionBox landing = box.copy().offset(0, movementY, 0);
        if (collisions.stream().anyMatch(obstacle -> obstacle.isIntersected(landing))) return null;
        return landing.minY;
    }

    private Map<Vector3i, Integer> captureBlocks(Vector3d landing) {
        SimpleCollisionBox box = GetBoundingBox.getPlayerBoundingBox(player, player.x, player.y, player.z);
        Map<Vector3i, Integer> blocks = new HashMap<>();
        for (int x = (int) Math.floor(box.minX - SimpleCollisionBox.COLLISION_EPSILON) - 1;
             x <= (int) Math.floor(box.maxX + SimpleCollisionBox.COLLISION_EPSILON) + 1; x++) {
            for (int z = (int) Math.floor(box.minZ - SimpleCollisionBox.COLLISION_EPSILON) - 1;
                 z <= (int) Math.floor(box.maxZ + SimpleCollisionBox.COLLISION_EPSILON) + 1; z++) {
                for (int y = (int) Math.floor(landing.y - SimpleCollisionBox.COLLISION_EPSILON) - 1;
                     y <= (int) Math.floor(box.maxY + SimpleCollisionBox.COLLISION_EPSILON) + 1; y++) {
                    blocks.put(new Vector3i(x, y, z), player.compensatedWorld.getBlock(x, y, z).getGlobalId());
                }
            }
        }
        return blocks;
    }

    static boolean matchesBlocks(PlatformWorld world, Map<Vector3i, Integer> blocks) {
        for (Map.Entry<Vector3i, Integer> entry : blocks.entrySet()) {
            Vector3i pos = entry.getKey();
            if (!world.isChunkLoaded(pos.x >> 4, pos.z >> 4)
                    || world.getBlockAt(pos.x, pos.y, pos.z).getGlobalId() != entry.getValue()) return false;
        }
        return true;
    }
}
