package ac.grim.grimac.predictionengine.predictions;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.IndexedVector3d;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.math.VectorUtils;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class PredictionEngineElytra extends PredictionEngine {
    private static final Vector3dm ZERO_INPUT = new Vector3dm(0, 0, 0);

    public static Vector3dm getElytraMovement(GrimPlayer player, Vector3dm vector, Vector3dm lookVector) {
        float pitchRadians = GrimMath.radians(player.pitch);
        double horizontalSqrt = Math.sqrt(lookVector.getX() * lookVector.getX() + lookVector.getZ() * lookVector.getZ());
        double horizontalLength = Math.sqrt(vector.getX() * vector.getX() + vector.getZ() * vector.getZ());

        // Mojang changed from using their math to using regular java math in 1.18.2 elytra movement
        ClientVersion version = player.getClientVersion();
        double vertCosRotation;
        if (version.isOlderThan(ClientVersion.V_1_18_2)) {
            float cos = player.trigHandler.cos(pitchRadians);
            double lookLength = VectorUtils.getVanillaLength(version, lookVector);
            vertCosRotation = (float) (cos * (cos * Math.min(1.0D, lookLength / 0.4D)));
        } else {
            double cos = Math.cos(pitchRadians);
            vertCosRotation = cos * cos;
            if (version.isOlderThan(ClientVersion.V_1_21_2)) {
                vertCosRotation *= Math.min(1.0D, lookVector.length() / 0.4D);
            }
        }

        // So we actually use the player's actual movement to get the gravity/slow falling status
        // However, this is wrong with elytra movement because players can control vertical movement after gravity is calculated
        // Yeah, slow falling needs a refactor in grim.
        double recalculatedGravity = player.compensatedEntities.self.getAttributeValue(Attributes.GRAVITY);
        if (player.clientVelocity.getY() <= 0 && player.compensatedEntities.getSlowFallingAmplifier().isPresent()) {
            recalculatedGravity = player.getClientVersion().isOlderThan(ClientVersion.V_1_20_5) ? 0.01 : Math.min(recalculatedGravity, 0.01);
        }

        vector.add(0.0D, recalculatedGravity * (-1.0D + vertCosRotation * 0.75D), 0.0D);
        double d5;

        // Handle slowing the player down when falling
        if (vector.getY() < 0.0D && horizontalSqrt > 0.0D) {
            d5 = vector.getY() * -0.1D * vertCosRotation;
            vector.add(lookVector.getX() * d5 / horizontalSqrt, d5, lookVector.getZ() * d5 / horizontalSqrt);
        }

        // Handle accelerating the player when they are looking down
        if (pitchRadians < 0.0F && horizontalSqrt > 0.0D) {
            d5 = horizontalLength * (double) (-player.trigHandler.sin(pitchRadians)) * 0.04D;
            vector.add(-lookVector.getX() * d5 / horizontalSqrt, d5 * 3.2D, -lookVector.getZ() * d5 / horizontalSqrt);
        }

        // Handle accelerating the player sideways
        if (horizontalSqrt > 0) {
            vector.add((lookVector.getX() / horizontalSqrt * horizontalLength - vector.getX()) * 0.1D, 0.0D, (lookVector.getZ() / horizontalSqrt * horizontalLength - vector.getZ()) * 0.1D);
        }

        return vector;
    }

    /**
     * Applies the vanilla firework boost formula for a single rocket.
     * From FireworkRocketEntity.tick() (1.21 decompile):
     *   vel += look * 0.1 + (look * 1.5 - vel) * 0.5
     * Equivalent to: vel_new = vel * 0.5 + look * 0.85
     */
    public static void applyFireworkBoost(Vector3dm velocity, Vector3dm look) {
        velocity.setX(velocity.getX() * 0.5 + look.getX() * 0.85);
        velocity.setY(velocity.getY() * 0.5 + look.getY() * 0.85);
        velocity.setZ(velocity.getZ() * 0.5 + look.getZ() * 0.85);
    }

    // When server has no glider, server can only realistically apply a single boost before its
    // own stopFallFlying triggers. In the toggle window we allow up to 2 to cover swap-in-flight
    // legit cases (1 just-fired + 1 still active), but never raw — otherwise N-rocket brute
    // forcing in the transition window grants ~0.85*N m/tick burst tolerance per tick.
    // Shared by the water engines, which also keep the glide flag (and thus the boost) underwater.
    public static int cappedFireworksForBoost(GrimPlayer player) {
        int rawMaxFireworks = player.fireworks.getMaxFireworksAppliedPossible();
        if (!player.isGliding && !player.canGlide()) {
            int cap = player.uncertaintyHandler.lastGlidingChange.hasOccurredSince(4) ? 2 : 1;
            return Math.min(cap, rawMaxFireworks);
        }
        return rawMaxFireworks;
    }

    @Override
    public List<VectorData> applyInputsToVelocityPossibilities(GrimPlayer player, Set<VectorData> possibleVectors, float speed) {
        List<VectorData> results = new ArrayList<>();

        int maxFireworks = cappedFireworksForBoost(player);
        boolean hasFireworks = maxFireworks > 0 && (player.isGliding || player.wasGliding
                || player.uncertaintyHandler.lastGlidingChange.hasOccurredSince(4));
        // We must bruteforce Optifine ShitMath
        for (int shitmath = 0; shitmath <= 1; shitmath++, player.trigHandler.toggleShitMath()) {
            Vector3dm currentLook = ReachUtils.getLook(player, player.yaw, player.pitch);
            Vector3dm lastLook = hasFireworks ? ReachUtils.getLook(player, player.lastYaw, player.lastPitch) : null;
            Vector3dm[] fireworkLooks = hasFireworks ? new Vector3dm[]{currentLook, lastLook} : null;

            for (VectorData data : possibleVectors) {
                if (hasFireworks) {
                    for (int numRockets = 0; numRockets <= maxFireworks; numRockets++) {
                        if (numRockets == 0) {
                            addElytraResult(results, player, data, data.vector.clone(), currentLook);
                        } else {
                            for (Vector3dm fireworkLook : fireworkLooks) {
                                Vector3dm boosted = data.vector.clone();
                                for (int i = 0; i < numRockets; i++) {
                                    applyFireworkBoost(boosted, fireworkLook);
                                }
                                addElytraResult(results, player, data, boosted, currentLook);
                            }
                        }
                    }
                } else {
                    addElytraResult(results, player, data, data.vector.clone(), currentLook);
                }
            }
        }

        return results;
    }

    // Fork: one elytra candidate (optionally firework-boosted) routed through upstream's stuck-speed dispatch.
    private void addElytraResult(List<VectorData> results, GrimPlayer player,
                                 VectorData data, Vector3dm inputVelocity, Vector3dm lookVector) {
        VectorData result = data.returnNewModified(getElytraMovement(player, inputVelocity, lookVector), VectorData.VectorType.InputResult);
        result.input = ZERO_INPUT;

        if (player.uncertaintyHandler.shouldSimulateStuckSpeed) {
            // only simulate no stuck speed if player is leaving
            if (player.uncertaintyHandler.stuckSpeedMultiplierMask == 0 || !player.isForceStuckSpeed())
                addStuckSpeedResult(results, result, null);
            addStuckSpeedResult(results, result, player.stuckSpeedMultiplier);
            addPossibleStuckSpeedResults(player, results, result);
        } else {
            for (int applyStuckSpeed = 1; applyStuckSpeed >= 0; applyStuckSpeed--) {
                if (applyStuckSpeed == 0 && player.isForceStuckSpeed()) break;

                addStuckSpeedResult(results, result, applyStuckSpeed != 0 ? player.stuckSpeedMultiplier : null);
            }
        }
    }

    private void addPossibleStuckSpeedResults(GrimPlayer player, List<VectorData> results, VectorData result) {
        int possibleStuckSpeedMultipliers = player.uncertaintyHandler.stuckSpeedMultiplierMask;
        for (IndexedVector3d stuckSpeedMultiplier : StuckSpeed.POSSIBILITIES) {
            if ((possibleStuckSpeedMultipliers & stuckSpeedMultiplier.getIndex()) != 0 && stuckSpeedMultiplier.getIndex() != player.stuckSpeedMultiplier.getIndex()) {
                addStuckSpeedResult(results, result, stuckSpeedMultiplier);
            }
        }
    }

    private void addStuckSpeedResult(List<VectorData> results, VectorData result, IndexedVector3d stuckSpeedMultiplier) {
        if (stuckSpeedMultiplier != null) {
            result = result.returnNewModified(result.vector.clone().multiply(stuckSpeedMultiplier), VectorData.VectorType.StuckMultiplier);
        }
        result.stuckSpeedMultiplier = stuckSpeedMultiplier == null ? StuckSpeed.NONE : stuckSpeedMultiplier;

        result = result.returnNewModified(result.vector.clone().multiply(0.99F, 0.98F, 0.99F), VectorData.VectorType.InputResult);
        results.add(result);
    }

    // Yes... you can jump while using an elytra as long as you are on the ground
    @Override
    public void addJumpsToPossibilities(GrimPlayer player, Set<VectorData> existingVelocities) {
        new PredictionEngineNormal().addJumpsToPossibilities(player, existingVelocities);
    }
}
