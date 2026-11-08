package ac.grim.grimac.predictionengine;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.math.EntityPushMath;
import ac.grim.grimac.utils.team.EntityPredicates;
import ac.grim.grimac.utils.team.EntityTeam;
import ac.grim.grimac.utils.team.TeamHandler;
import ac.grim.grimac.utils.viaversion.ViaVersionUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.viaversion.viaversion.api.Via;

public final class EntityPushSimulator {
    private static final double INTERPOLATION_JITTER = 0.01;  // applied once per tick, not per entity
    private static final int ROOT_WALK_LIMIT = 16;            // guard against malformed riding loops

    private EntityPushSimulator() {}

    public static PushRange compute(GrimPlayer player) {
        PushRange range = new PushRange();

        boolean serverSupported = PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9);
        boolean hasEntityPushing = !(player.getClientVersion().isOlderThan(ClientVersion.V_1_9)
                || (!serverSupported && (!ViaVersionUtil.isAvailable || Via.getConfig().isPreventCollision())));
        if (!hasEntityPushing) return range;
        if (player.inVehicle() || player.gamemode == GameMode.SPECTATOR) return range;

        SimpleCollisionBox playerBox = player.boundingBox;
        if (playerBox == null) return range;
        double selfX = (playerBox.minX + playerBox.maxX) * 0.5;
        double selfZ = (playerBox.minZ + playerBox.maxZ) * 0.5;

        SimpleCollisionBox probeBox = playerBox.copy().expand(0.2);

        TeamHandler teamHandler = player.checkManager.get(TeamHandler.class);
        EntityTeam playerTeam = teamHandler != null ? teamHandler.getPlayerTeam() : null;

        double[] buf = new double[4];
        int safeCount = 0;
        boolean anySplit = false;

        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            SimpleCollisionBox entityBox = entity.getPossibleCollisionBoxes();
            if (!probeBox.isCollided(entityBox)) continue;
            if (!entity.isPushable()) continue;

            if (serverSupported) {
                EntityTeam entityTeam = teamHandler != null ? teamHandler.getEntityTeam(entity) : null;
                if (!EntityPredicates.canBePushedBy(entityTeam, playerTeam)) continue;
            }

            if (shareRootVehicle(player, entity)) continue;

            // Boats get their own ±1.2 leeway via PredictionEngine's lastHardCollidingLerpingEntity path.
            // Adding fallback here would double-count.
            if (entity.isBoat) continue;

            double halfW = Math.min(entityBox.maxX - entityBox.minX, entityBox.maxZ - entityBox.minZ) * 0.5;
            double entityMinX = entityBox.minX + halfW;
            double entityMaxX = entityBox.maxX - halfW;
            double entityMinZ = entityBox.minZ + halfW;
            double entityMaxZ = entityBox.maxZ - halfW;

            if (entityMaxX < entityMinX) {
                double mid = (entityBox.minX + entityBox.maxX) * 0.5;
                entityMinX = entityMaxX = mid;
            }
            if (entityMaxZ < entityMinZ) {
                double mid = (entityBox.minZ + entityBox.maxZ) * 0.5;
                entityMinZ = entityMaxZ = mid;
            }

            EntityPushMath.pushRange(selfX, selfZ, entityMinX, entityMaxX, entityMinZ, entityMaxZ, buf);

            range.minX += buf[0];
            range.maxX += buf[1];
            range.minZ += buf[2];
            range.maxZ += buf[3];

            if (entity.hasUnconfirmedInterpolation()) anySplit = true;

            safeCount++;
        }

        if (anySplit) {
            range.minX -= INTERPOLATION_JITTER;
            range.maxX += INTERPOLATION_JITTER;
            range.minZ -= INTERPOLATION_JITTER;
            range.maxZ += INTERPOLATION_JITTER;
        }

        // Require real skip evidence - lastPointThree resets on any look-only packet
        if (safeCount > 0 && (player.couldSkipTick || player.skippedTickInActualMovement)) {
            int ticksSinceSkip = player.uncertaintyHandler.lastPointThree.getTicksSince();
            double widen = Math.max(0.02, 0.08 / Math.max(1, ticksSinceSkip));
            range.minX -= widen;
            range.maxX += widen;
            range.minZ -= widen;
            range.maxZ += widen;
        }

        range.entityCount = safeCount;
        return range;
    }

    private static boolean shareRootVehicle(GrimPlayer player, PacketEntity entity) {
        PacketEntity selfRiding = player.compensatedEntities.self.getRiding();
        if (selfRiding == null) return false;
        PacketEntity playerRoot = rootVehicle(player.compensatedEntities.self);
        PacketEntity entityRoot = rootVehicle(entity);
        return playerRoot != null && playerRoot == entityRoot;
    }

    private static PacketEntity rootVehicle(PacketEntity entity) {
        PacketEntity current = entity;
        for (int i = 0; i < ROOT_WALK_LIMIT && current.riding != null; i++) {
            current = current.riding;
        }
        return current;
    }

    public static final class PushRange {
        public double minX = 0;
        public double maxX = 0;
        public double minZ = 0;
        public double maxZ = 0;
        public int entityCount = 0;

        public boolean isEmpty() {
            return entityCount == 0;
        }
    }
}
