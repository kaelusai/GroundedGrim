package ac.grim.grimac.checks.impl.elytra;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.movement.AirStuck;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

@CheckData(name = "ElytraN", description = "Repeated elytra reactivation without movement", setback = 0)
public class ElytraN extends Check implements PacketReceiveListener {
    private static final long STALL_MS = 1000;
    private static final long CORRECTION_INTERVAL_MS = 1000;
    private static final double MOVEMENT_DISTANCE_SQUARED = 0.01 * 0.01;

    private long lastMovementTime = System.currentTimeMillis();
    private Vector3d anchor;
    private int activationsWithoutMovement;
    private long lastActivationTime;
    private volatile boolean blockingHits;
    private volatile boolean fallDistanceReset;
    private long stallStartedAt;
    private long lastCorrectionTime;
    private long lastFlagTime;

    public ElytraN(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (blockingHits && isExempt()) {
            blockingHits = false;
            fallDistanceReset = false;
            resetMovement();
            return;
        }
        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            if (event.isCancelled()) return;
            if (player.packetStateData.lastPacketWasTeleport) {
                if (fallDistanceReset) {
                    blockingHits = false;
                    fallDistanceReset = false;
                    resetMovement();
                } else if (anchor == null || anchor.distanceSquared(new Vector3d(player.x, player.y, player.z)) > MOVEMENT_DISTANCE_SQUARED) {
                    resetMovement();
                }
            } else {
                WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
                if (flying.hasPositionChanged()) {
                    Vector3d position = flying.getLocation().getPosition();
                    if (anchor == null || position.distanceSquared(anchor) > MOVEMENT_DISTANCE_SQUARED) {
                        lastMovementTime = System.currentTimeMillis();
                        activationsWithoutMovement = 0;
                        anchor = position;
                    }
                }
            }
            if ((blockingHits || activationsWithoutMovement > 0)
                    && player.onGround && player.lastOnGround && player.fallDistance == 0
                    && player.checkManager.get(AirStuck.class).hasGroundSupport()) {
                blockingHits = false;
                fallDistanceReset = false;
                resetMovement();
            }
        }

        if (!blockingHits || !shouldModifyPackets()) return;
        if (isAttack(event)) event.setCancelled(true);
        correctStall();
    }

    // Called before PacketEntityAction changes Grim's gliding state.
    public boolean onStartGliding(PacketReceiveEvent event) {
        if (!isApplicable()) return false;
        long now = System.currentTimeMillis();
        if (anchor == null) anchor = new Vector3d(player.x, player.y, player.z);
        if (!blockingHits && player.getSetbackTeleportUtil().shouldBlockMovement()) return false;
        if (isExempt()) {
            blockingHits = false;
            fallDistanceReset = false;
            resetMovement();
            return false;
        }
        if (!blockingHits) {
            if (now - lastActivationTime >= 50) {
                activationsWithoutMovement = Math.min(activationsWithoutMovement + 1, 3);
                lastActivationTime = now;
            }
            if (activationsWithoutMovement < 3
                    || now - lastMovementTime <= STALL_MS) return false;
            if (!flagAndAlert("acts=" + activationsWithoutMovement + " noMove="
                    + (now - lastMovementTime) + "ms") || !shouldModifyPackets()) return false;
            blockingHits = true;
            stallStartedAt = lastMovementTime;
            lastFlagTime = now;
        } else if (now - lastFlagTime >= CORRECTION_INTERVAL_MS) {
            if (!flagAndAlert("elytra stall")) return false;
            lastFlagTime = now;
        }

        if (!shouldModifyPackets()) return false;
        event.setCancelled(true);
        player.resyncGlidingState();
        correctStall();
        return true;
    }

    private boolean isExempt() {
        return (!blockingHits && (player.onGround || player.lastOnGround) && player.checkManager.get(AirStuck.class).hasGroundSupport())
                || player.isFlying || player.inVehicle()
                || player.isClimbing || player.isInBed || !player.hasGravity || player.gravity <= 0
                || player.wasTouchingWater || player.wasTouchingLava || player.compensatedEntities.self.isDead
                || player.stuckSpeedMultiplier.getY() < 0.99
                || player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.LEVITATION).isPresent()
                || player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.SLOW_FALLING).isPresent()
                || !player.getSetbackTeleportUtil().hasAcceptedSpawnTeleport
                || System.currentTimeMillis() - player.joinTime < 5000;
    }

    private void correctStall() {
        long now = System.currentTimeMillis();
        if (now - lastCorrectionTime < CORRECTION_INTERVAL_MS) return;
        lastCorrectionTime = now;
        // Position updates and same-position resyncs do not discard the server fall distance
        // used by mace smash attacks, so keep blocking hits until correction is acknowledged.
        if (shouldSetback()) {
            player.checkManager.get(AirStuck.class).correctElytraStall(now - stallStartedAt,
                    () -> blockingHits && shouldModifyPackets() && shouldSetback() && !isExempt(),
                    () -> fallDistanceReset = true);
        }
    }

    private void resetMovement() {
        lastMovementTime = System.currentTimeMillis();
        activationsWithoutMovement = 0;
        lastActivationTime = 0;
        anchor = new Vector3d(player.x, player.y, player.z);
    }

    private static boolean isAttack(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) return true;
        return event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY
                && new WrapperPlayClientInteractEntity(event).getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
    }
}
