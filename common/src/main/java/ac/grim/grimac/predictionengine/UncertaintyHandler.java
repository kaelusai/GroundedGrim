package ac.grim.grimac.predictionengine;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.LastInstance;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.data.packetentity.PacketEntityRideable;
import ac.grim.grimac.utils.data.packetentity.PacketEntityStrider;
import ac.grim.grimac.utils.lists.EvictingQueue;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.BoundingBoxSize;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.world.BlockFace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;

public class UncertaintyHandler {
    private final GrimPlayer player;
    // Handles uncertainty when a piston could have pushed a player in a direction
    // Only the required amount of uncertainty is given
    public final EvictingQueue<Double> pistonX = new EvictingQueue<>(5);
    public final EvictingQueue<Double> pistonY = new EvictingQueue<>(5);
    public final EvictingQueue<Double> pistonZ = new EvictingQueue<>(5);
    // Did the player step onto a block?
    // This is needed because we don't know if a player jumped onto the step block or not
    // Jumping would set onGround to false while not would set it to true
    // Meaning no matter what, just trust the player's onGround status
    public boolean isStepMovement;
    // What directions could slime block pistons be pushing the player from
    public HashSet<BlockFace> slimePistonBounces;
    public Set<BlockPush> blockPushes = Set.of();
    // Handles general uncertainty such as entity pushing and the 1.14+ X Z collision bug where X momentum is maintained
    public double xNegativeUncertainty = 0;
    public double xPositiveUncertainty = 0;
    public double zNegativeUncertainty = 0;
    public double zPositiveUncertainty = 0;
    public double yNegativeUncertainty = 0;
    public double yPositiveUncertainty = 0;

    // Snapshot for post-prediction diagnostics: travel resets the live fields for the next tick.
    public double predictedXNegative, predictedXPositive, predictedYNegative, predictedYPositive, predictedZNegative, predictedZPositive;
    // Slime block bouncing
    public double thisTickSlimeBlockUncertainty = 0;
    public double nextTickSlimeBlockUncertainty = 0;
    // The player landed while jumping but without new position information because of 0.03
    public boolean onGroundUncertain = false;
    // Marks previous didGroundStatusChangeWithoutPositionPacket from last tick
    public boolean lastPacketWasGroundPacket = false;
    // Slime sucks in terms of bouncing and stuff.  Trust client onGround when on slime
    public boolean wasSteppingOnSlime = false;
    public boolean isSteppingOnSlime = false;
    public boolean isSteppingOnIce = false;
    public boolean isSteppingOnHoney = false;
    public boolean wasSteppingOnBouncyBlock = false;
    public boolean isSteppingOnBouncyBlock = false;
    public boolean isSteppingNearBubbleColumn = false;
    public boolean isSteppingNearScaffolding = false;
    public boolean isSteppingNearShulker = false;
    public boolean isNearGlitchyBlock = false;
    public boolean isOrWasNearGlitchyBlock = false;
    // Give horizontal lenience if the previous movement was 0.03 because their velocity is unknown
    public boolean lastMovementWasZeroPointZeroThree = false;
    // Give horizontal lenience if the last movement reset velocity because 0.03 becomes unknown then
    public boolean lastMovementWasUnknown003VectorReset = false;
    // Handles 0.03 vertical false where actual velocity is greater than predicted because of previous lenience
    public boolean wasZeroPointThreeVertically = false;
    // How many entities are within 0.5 blocks of the player's bounding box that are pushable?
    public final EvictingQueue<Integer> collidingEntities = new EvictingQueue<>(3);
    // How many entities are within 0.5 blocks of the player's bounding box? Should only exclude entities in spectator
    public final EvictingQueue<Integer> riptideEntities = new EvictingQueue<>(3);
    // Fishing rod pulling is another method of adding to a player's velocity
    public final List<Integer> fishingRodPulls = new ArrayList<>();
    public SimpleCollisionBox fireworksBox = null;
    public double fireworkResidualCap = 0.008;
    public double fireworkResidualFloor = 0.003;
    public SimpleCollisionBox fishingRodPullBox = null;
    public boolean shouldSimulateStuckSpeed = false;
    public int stuckSpeedMultiplierMask = StuckSpeed.NONE.getIndex();

    public final LastInstance lastFlyingTicks;
    public final LastInstance lastFlyingStatusChange;
    public final LastInstance lastUnderwaterFlyingHack;
    public final LastInstance lastStuckSpeedMultiplier;
    public final LastInstance lastHardCollidingLerpingEntity;
    public final LastInstance lastThirtyMillionHardBorder;
    public final LastInstance lastTeleportTicks;
    public final LastInstance lastPointThree;
    public final LastInstance stuckOnEdge;
    public final LastInstance lastStuckNorth;
    public final LastInstance lastStuckSouth;
    public final LastInstance lastStuckWest;
    public final LastInstance lastStuckEast;
    public final LastInstance lastVehicleSwitch;
    public final LastInstance lastGlidingChange;
    public final LastInstance lastShulkerBoxNearby;
    public final LastInstance lastPushableNear;
    public final LastInstance lastTouchingWater;
    public double lastHorizontalOffset = 0;
    public double lastVerticalOffset = 0;
    public EntityPushSimulator.PushRange pushRange = new EntityPushSimulator.PushRange();

    // Magnitudes recorded for the selected candidate rank sources; they do not sum to the box bounds.
    public static final String[] BOX_TERMS = {
            "0.03 horizontal", "0.03 vertical", "previous offset", "flight toggle", "underwater flight",
            "flying tolerance", "glide launch", "glide toggle", "hard entity", "piston", "knockback resync",
            "fluid push", "entity push"
    };
    public static final int TERM_POINT_THREE_H = 0, TERM_POINT_THREE_V = 1, TERM_LAST_OFFSET = 2,
            TERM_FLIGHT_TOGGLE = 3, TERM_UNDERWATER_FLIGHT = 4, TERM_FLYING = 5, TERM_GLIDE_LAUNCH = 6,
            TERM_GLIDE_TOGGLE = 7, TERM_HARD_ENTITY = 8, TERM_PISTON = 9, TERM_KB_RESYNC = 10,
            TERM_FLUID = 11, TERM_ENTITY_PUSH = 12;

    // These operations can overwrite earlier bounds, so diagnostics record names without magnitudes.
    public static final String[] BOX_MUTATORS = {
            "landing within 0.03", "elytra ground contact", "hidden gravity", "vertical fluid", "bubble column",
            "unpredictable gravity", "slime bounce", "swim hop", "levitation", "sneaking", "fireworks",
            "fishing rod", "block edge or slime", "hard entity collapse", "vehicle friction", "vehicle switch",
            "piston override", "shulker clamp (narrows)"
    };
    public static final int BOX_LANDING = 1, BOX_ELYTRA_GROUND = 1 << 1, BOX_HIDDEN_GRAVITY = 1 << 2,
            BOX_VERTICAL_FLUID = 1 << 3, BOX_BUBBLE = 1 << 4, BOX_UNKNOWN_GRAVITY = 1 << 5, BOX_SLIME = 1 << 6,
            BOX_SWIM_HOP = 1 << 7, BOX_LEVITATION = 1 << 8, BOX_SNEAKING = 1 << 9, BOX_FIREWORKS = 1 << 10,
            BOX_FISHING_ROD = 1 << 11, BOX_EDGE_OR_SLIME = 1 << 12, BOX_HARD_ENTITY_COLLAPSE = 1 << 13,
            BOX_VEHICLE_FRICTION = 1 << 14, BOX_VEHICLE_SWITCH = 1 << 15, BOX_PISTON_OVERRIDE = 1 << 16,
            BOX_SHULKER = 1 << 17;

    // PointThreeEstimator reuses scratch before candidate selection; pending preserves the candidate's data.
    public final double[] boxTermScratch = new double[BOX_TERMS.length];
    private final double[] boxTermPending = new double[BOX_TERMS.length];
    public final double[] boxTermBest = new double[BOX_TERMS.length];
    public int boxMutatorScratch;
    private int boxMutatorPending;
    public int boxMutatorBest;
    private final double[] boxBoundsScratch = new double[6];
    private final double[] boxBoundsPending = new double[6];
    private final double[] boxBoundsBest = new double[6];

    // Candidate XYZ followed by XYZ after uncertainty, before collisions.
    private final double[] buildPending = new double[6];
    private final double[] buildBest = new double[6];

    public double rawOffset, offsetX, offsetY, offsetZ, offsetReduction;
    public int offsetReductionMask;
    public static final String[] OFFSET_REDUCTIONS = {
            "hard entity", "firework in water", "glitchy block", "bouncy block", "vehicle boost"
    };

    public UncertaintyHandler(GrimPlayer player) {
        this.player = player;
        this.lastFlyingTicks = new LastInstance(player);
        this.lastFlyingStatusChange = new LastInstance(player);
        this.lastUnderwaterFlyingHack = new LastInstance(player);
        this.lastStuckSpeedMultiplier = new LastInstance(player);
        this.lastHardCollidingLerpingEntity = new LastInstance(player);
        this.lastThirtyMillionHardBorder = new LastInstance(player);
        this.lastTeleportTicks = new LastInstance(player);
        this.lastPointThree = new LastInstance(player);
        this.stuckOnEdge = new LastInstance(player);
        this.lastStuckNorth = new LastInstance(player);
        this.lastStuckSouth = new LastInstance(player);
        this.lastStuckWest = new LastInstance(player);
        this.lastStuckEast = new LastInstance(player);
        this.lastVehicleSwitch = new LastInstance(player);
        this.lastGlidingChange = new LastInstance(player);
        this.lastShulkerBoxNearby = new LastInstance(player);
        this.lastPushableNear = new LastInstance(player);
        this.lastTouchingWater = new LastInstance(player);
        tick();

        this.riptideEntities.add(0);
        this.collidingEntities.add(0);
    }

    public void tick() {
        pistonX.add(0d);
        pistonY.add(0d);
        pistonZ.add(0d);
        isStepMovement = false;

        isSteppingNearShulker = false;
        wasSteppingOnSlime = isSteppingOnSlime;
        wasSteppingOnBouncyBlock = isSteppingOnBouncyBlock;
        isSteppingOnSlime = false;
        isSteppingOnBouncyBlock = false;
        isSteppingOnIce = false;
        isSteppingOnHoney = false;
        isSteppingNearBubbleColumn = false;
        isSteppingNearScaffolding = false;

        slimePistonBounces = new HashSet<>();
        tickFireworksBox();
    }

    public boolean wasAffectedByStuckSpeed() {
        return lastStuckSpeedMultiplier.hasOccurredSince(5);
    }

    public void tickFireworksBox() {
        fishingRodPullBox = fishingRodPulls.isEmpty() ? null : new SimpleCollisionBox();
        fireworksBox = null;

        for (int owner : fishingRodPulls) {
            PacketEntity entity = player.compensatedEntities.getEntity(owner);
            if (entity == null) continue;

            SimpleCollisionBox entityBox = entity.getPossibleCollisionBoxes();
            final float scale = (float) entity.getAttributeValue(Attributes.SCALE);
            float width = BoundingBoxSize.getWidth(player, entity) * scale;
            float height = BoundingBoxSize.getHeight(player, entity) * scale;

            // Convert back to coordinates instead of hitbox
            entityBox.maxY -= height;
            entityBox.expand(-width / 2, 0, -width / 2);

            Vector3dm maxLocation = new Vector3dm(entityBox.maxX, entityBox.maxY, entityBox.maxZ);
            Vector3dm minLocation = new Vector3dm(entityBox.minX, entityBox.minY, entityBox.minZ);

            Vector3dm diff = minLocation.subtract(player.lastX, player.lastY + 0.8 * 1.8, player.lastZ).multiply(0.1);
            fishingRodPullBox.minX = Math.min(0, diff.getX());
            fishingRodPullBox.minY = Math.min(0, diff.getY());
            fishingRodPullBox.minZ = Math.min(0, diff.getZ());

            diff = maxLocation.subtract(player.lastX, player.lastY + 0.8 * 1.8, player.lastZ).multiply(0.1);
            fishingRodPullBox.maxX = Math.max(0, diff.getX());
            fishingRodPullBox.maxY = Math.max(0, diff.getY());
            fishingRodPullBox.maxZ = Math.max(0, diff.getZ());
        }

        fishingRodPulls.clear();

        if (player.wasTouchingWater) lastTouchingWater.reset();

        int maxFireworks = player.fireworks.getMaxFireworksAppliedPossible();
        if (maxFireworks <= 0 || (!player.isGliding && !player.wasGliding)) {
            return;
        }
        int magnitudeFireworks = player.fireworks.getMaxFireworksForMagnitude();

        Vector3dm currentLook = ReachUtils.getLook(player, player.yaw, player.pitch);
        Vector3dm lastLook = ReachUtils.getLook(player, player.lastYaw, player.lastPitch);

        // Boost is modeled in the prediction candidates (air + water); this box is only the look-bridge tolerance.
        double residualX = Math.min(fireworkResidualCap, Math.max(fireworkResidualFloor,
            0.5 * Math.abs(currentLook.getX() - lastLook.getX()) * 0.85 * magnitudeFireworks));
        double residualY = Math.min(fireworkResidualCap, Math.max(fireworkResidualFloor,
            0.5 * Math.abs(currentLook.getY() - lastLook.getY()) * 0.85 * magnitudeFireworks));
        double residualZ = Math.min(fireworkResidualCap, Math.max(fireworkResidualFloor,
            0.5 * Math.abs(currentLook.getZ() - lastLook.getZ()) * 0.85 * magnitudeFireworks));
        fireworksBox = new SimpleCollisionBox(
            -residualX, -residualY, -residualZ,
             residualX,  residualY,  residualZ
        );
    }

    public double getOffsetHorizontal(VectorData data) {
        double threshold = player.getMovementThreshold();

        boolean newVectorPointThree = player.couldSkipTick && data.isKnockback() && !data.isSetbackKb(player);
        boolean explicit003 = data.isZeroPointZeroThree() || lastMovementWasZeroPointZeroThree;
        boolean either003 = newVectorPointThree || explicit003;

        double pointThree = newVectorPointThree || lastMovementWasUnknown003VectorReset ? threshold : 0;

        // 0.91 * 0.6 * (offset * 2) = 0.03276 + 0.03 offset
        if (explicit003) {
            pointThree = 0.91 * 0.6 * (threshold * 2) + threshold;
        }

        // (offset * 2) * 0.91 * 0.8 = max + 0.03 offset
        if (either003 && (influencedByBouncyBlock() || isSteppingOnHoney))
            pointThree = 0.91 * 0.8 * (threshold * 2) + threshold;

        // (offset * 2) * 0.91 * 0.989 = max + 0.03 offset
        if (either003 && isSteppingOnIce)
            pointThree = 0.91 * 0.989 * (threshold * 2) + threshold;

        // Reduce second tick uncertainty by minimum friction amount (if not velocity uncertainty)
        if (pointThree > threshold)
            pointThree *= 0.91 * 0.989;

        // 0.06 * 0.91 = max + 0.03 offset
        if (either003 && (player.lastOnGround || player.isFlying))
            pointThree = 0.91 * (threshold * 2) + threshold;

        // Friction while gliding is 0.99 horizontally
        if (either003 && (player.isGliding || player.wasGliding)) {
            pointThree = (0.99 * (threshold * 2)) + threshold;
        }

        return pointThree;
    }

    public boolean influencedByBouncyBlock() {
        return isSteppingOnBouncyBlock || wasSteppingOnBouncyBlock;
    }

    public boolean influencedBySlime() {
        return isSteppingOnSlime || wasSteppingOnSlime;
    }

    public double getVerticalOffset(VectorData data) {
        // We don't know if the player was pressing jump or not
        if (player.uncertaintyHandler.wasSteppingOnBouncyBlock && (player.wasTouchingWater || player.wasTouchingLava))
            return 0.06;

        // Not worth my time to fix this because checking flying generally sucks - if player was flying in last 2 ticks
        if ((lastFlyingTicks.hasOccurredSince(5)) && Math.abs(data.vector.getY()) < (4.5 * player.flySpeed - 0.25))
            return 0.06;

        double pointThree = player.getMovementThreshold();
        // This swim hop could be 0.03-influenced movement
        if (data.isTrident())
            return pointThree * 2;

        // Velocity resets velocity, so we only have to give 0.03 uncertainty rather than 0.06
        if (player.couldSkipTick && (data.isKnockback() || player.isClimbing) && !data.isZeroPointZeroThree())
            return pointThree;

        if (player.pointThreeEstimator.controlsVerticalMovement()) {
            // 0.03 from last tick into 0.03 now = 0.06 (could reduce by friction in the future, only 0.91 at most though)
            if (data.isZeroPointZeroThree() || lastMovementWasZeroPointZeroThree)
                return pointThree * 2;
        }

        // Handle the player landing on this tick or the next tick
        if (wasZeroPointThreeVertically || player.uncertaintyHandler.onGroundUncertain || player.uncertaintyHandler.lastPacketWasGroundPacket)
            return pointThree;

        return 0;
    }

    public double reduceOffset(double offset) {
        // Boats are too glitchy to check.
        // Yes, they have caused an insane amount of uncertainty!
        // Even 1 block offset reduction isn't enough... damn it mojang
        boolean isElytraFlight = player.isGliding && player.wasGliding;
        offsetReductionMask = 0;

        if (player.uncertaintyHandler.lastHardCollidingLerpingEntity.hasOccurredSince(3)) {
            offset -= isElytraFlight ? 0.3 : 1.2;
            offsetReductionMask |= 1;
        }

        // Keep the water firework leniency for a few ticks AFTER leaving water: the up/down-through-water
        // crossing has a ~0.02 water<->air terminal gap the shrunk residual box no longer covers on the air side.
        if (player.uncertaintyHandler.lastTouchingWater.hasOccurredSince(3) && (player.isGliding || player.wasGliding)
                && player.fireworks.getMaxFireworksAppliedPossible() > 0) {
            offset -= 0.05;
            offsetReductionMask |= 1 << 1;
        }

        if (player.uncertaintyHandler.isOrWasNearGlitchyBlock) {
            offset -= isElytraFlight ? 0.05 : 0.25;
            offsetReductionMask |= 1 << 2;
        }

        // This is a section where I hack around current issues with Grim itself...
        if (player.uncertaintyHandler.influencedByBouncyBlock() && (!player.isPointThree() || player.inVehicle())) {
            offset -= 0.03;
            offsetReductionMask |= 1 << 3;
        }
        // This is the end of that section.

        // I can't figure out how the client exactly tracks boost time
        if (player.compensatedEntities.self.getRiding() instanceof PacketEntityRideable vehicle) {
            if (vehicle.currentBoostTime < vehicle.boostTimeMax + 20) {
                offset -= 0.01;
                offsetReductionMask |= 1 << 4;
            }
        }

        return Math.max(0, offset);
    }

    public void checkForHardCollision() {
        // Look for boats the player could collide with
        if (hasHardCollision()) player.uncertaintyHandler.lastHardCollidingLerpingEntity.reset();
        if (isSteppingNearShulker) player.uncertaintyHandler.lastShulkerBoxNearby.reset();
    }

    private boolean hasHardCollision() {
        SimpleCollisionBox expandedBB = player.boundingBox.copy().expand(1);
        return regularHardCollision(expandedBB) || striderCollision(expandedBB) || boatCollision(expandedBB);
    }

    private boolean regularHardCollision(SimpleCollisionBox expandedBB) {
        final PacketEntity riding = player.compensatedEntities.self.getRiding();
        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            // Passengers of our own vehicle move with it and can't be collided with, don't give them lenience
            if ((entity.isBoat || entity.getType() == EntityTypes.SHULKER || entity.isHappyGhast) && entity != riding
                    && (riding == null || !riding.hasPassenger(entity))
                    && entity.getPossibleCollisionBoxes().isIntersected(expandedBB)) {
                return true;
            }
        }

        return false;
    }

    private boolean striderCollision(SimpleCollisionBox expandedBB) {
        // Stiders can walk on top of other striders
        if (player.compensatedEntities.self.getRiding() instanceof PacketEntityStrider) {
            for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
                if (entity.getType() == EntityTypes.STRIDER && entity != player.compensatedEntities.self.getRiding()
                        && !entity.hasPassenger(entity) && entity.getPossibleCollisionBoxes().isIntersected(expandedBB)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean boatCollision(SimpleCollisionBox expandedBB) {
        // Boats can collide with quite literally anything
        final PacketEntity riding = player.compensatedEntities.self.getRiding();
        if (riding == null || !riding.isBoat) return false;

        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            if (entity != riding && entity.isPushable() && !riding.hasPassenger(entity)
                    && entity.getPossibleCollisionBoxes().isIntersected(expandedBB)) {
                return true;
            }
        }
        return false;
    }
    public void beginBoxTerms() {
        Arrays.fill(boxTermScratch, 0);
        boxMutatorScratch = 0;
    }

    public void recordBoxBounds(Vector3dm candidate, Vector3dm min, Vector3dm max) {
        boxBoundsScratch[0] = min.getX() - candidate.getX();
        boxBoundsScratch[1] = min.getY() - candidate.getY();
        boxBoundsScratch[2] = min.getZ() - candidate.getZ();
        boxBoundsScratch[3] = max.getX() - candidate.getX();
        boxBoundsScratch[4] = max.getY() - candidate.getY();
        boxBoundsScratch[5] = max.getZ() - candidate.getZ();
    }

    public void stashBoxTerms(Vector3dm candidate, Vector3dm afterBox) {
        System.arraycopy(boxTermScratch, 0, boxTermPending, 0, boxTermScratch.length);
        System.arraycopy(boxBoundsScratch, 0, boxBoundsPending, 0, boxBoundsScratch.length);
        boxMutatorPending = boxMutatorScratch;
        buildPending[0] = candidate.getX();
        buildPending[1] = candidate.getY();
        buildPending[2] = candidate.getZ();
        buildPending[3] = afterBox.getX();
        buildPending[4] = afterBox.getY();
        buildPending[5] = afterBox.getZ();
    }

    public void commitBoxTerms() {
        System.arraycopy(boxTermPending, 0, boxTermBest, 0, boxTermPending.length);
        System.arraycopy(boxBoundsPending, 0, boxBoundsBest, 0, boxBoundsPending.length);
        System.arraycopy(buildPending, 0, buildBest, 0, buildPending.length);
        boxMutatorBest = boxMutatorPending;
    }

    public String describeCandidate() {
        return String.format(Locale.ROOT, "x%+.6f y%+.6f z%+.6f", buildBest[0], buildBest[1], buildBest[2]);
    }

    public String describeBuild(Vector3dm predicted) {
        return String.format(Locale.ROOT,
                "slack moved x%+.4f y%+.4f z%+.4f, collision x%+.4f y%+.4f z%+.4f",
                buildBest[3] - buildBest[0], buildBest[4] - buildBest[1], buildBest[5] - buildBest[2],
                predicted.getX() - buildBest[3], predicted.getY() - buildBest[4], predicted.getZ() - buildBest[5]);
    }

    // Bounds relative to the selected candidate. Empty when all bounds are zero.
    public String describeBox() {
        for (double bound : boxBoundsBest) {
            if (bound != 0) {
                return String.format(Locale.ROOT, "x%+.4f/%+.4f y%+.4f/%+.4f z%+.4f/%+.4f",
                        boxBoundsBest[0], boxBoundsBest[3], boxBoundsBest[1], boxBoundsBest[4],
                        boxBoundsBest[2], boxBoundsBest[5]);
            }
        }
        return "";
    }

    public String describeSources() {
        return describeSources(Integer.MAX_VALUE);
    }

    public String describeSources(int limit) {
        StringBuilder out = new StringBuilder();
        boolean[] taken = new boolean[boxTermBest.length];
        int printed = 0;

        while (printed < limit) {
            int best = -1;
            for (int i = 0; i < boxTermBest.length; i++) {
                if (taken[i] || boxTermBest[i] <= 0) continue;
                if (best == -1 || boxTermBest[i] > boxTermBest[best]) best = i;
            }
            if (best == -1) break;
            taken[best] = true;
            if (out.length() > 0) out.append(", ");
            out.append(BOX_TERMS[best]).append(String.format(Locale.ROOT, " +%.4f", boxTermBest[best]));
            printed++;
        }

        for (int i = 0; i < BOX_MUTATORS.length && printed < limit; i++) {
            if ((boxMutatorBest & (1 << i)) == 0) continue;
            if (out.length() > 0) out.append(", ");
            out.append(BOX_MUTATORS[i]);
            printed++;
        }

        return out.toString();
    }

    // Axis offsets remain raw; reduceOffset adjusts only the vector magnitude.
    public String describeReduction() {
        if (offsetReduction <= 0) return "";
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "-%.4f", offsetReduction));
        boolean first = true;
        for (int i = 0; i < OFFSET_REDUCTIONS.length; i++) {
            if ((offsetReductionMask & (1 << i)) == 0) continue;
            out.append(first ? " (" : ", ").append(OFFSET_REDUCTIONS[i]);
            first = false;
        }
        if (!first) out.append(')');
        return out.toString();
    }

}
