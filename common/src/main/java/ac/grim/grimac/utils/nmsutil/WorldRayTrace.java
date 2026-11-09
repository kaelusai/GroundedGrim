package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.checks.impl.combat.Reach;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.CollisionData;
import ac.grim.grimac.utils.collisions.HitboxData;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.NoCollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.BlockHitData;
import ac.grim.grimac.utils.data.EntityHitData;
import ac.grim.grimac.utils.data.HitData;
import ac.grim.grimac.utils.data.Pair;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.Vector3dm;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

@UtilityClass
public class WorldRayTrace {
    public static BlockHitData getNearestBlockHitResult(GrimPlayer player, StateType heldItem, boolean sourcesHaveHitbox, boolean fluidPlacement, boolean itemUsePlacement) {
        Vector3d startingPos = new Vector3d(player.x, player.y + player.getEyeHeight(), player.z);
        Vector3dm startingVec = new Vector3dm(startingPos.getX(), startingPos.getY(), startingPos.getZ());
        Ray trace = new Ray(player, startingPos.getX(), startingPos.getY(), startingPos.getZ(), player.yaw, player.pitch);
        final double distance = itemUsePlacement && player.getClientVersion().isOlderThan(ClientVersion.V_1_20_5) ? 5 : player.compensatedEntities.self.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        Vector3d endVec = trace.getPointAtDistance(distance);
        Vector3d endPos = new Vector3d(endVec.getX(), endVec.getY(), endVec.getZ());

        return traverseBlocks(player, startingPos, endPos, (block, vector3i) -> {
            if (fluidPlacement && player.getClientVersion().isOlderThan(ClientVersion.V_1_13) && CollisionData.getData(block.getType())
                    .getMovementCollisionBox(player, player.getClientVersion(), block, vector3i.getX(), vector3i.getY(), vector3i.getZ()).isNull()) {
                return null;
            }

            CollisionBox data = HitboxData.getBlockHitbox(player, heldItem, player.getClientVersion(), block, false, vector3i.getX(), vector3i.getY(), vector3i.getZ());
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            data.downCast(boxes);

            double bestHitResult = Double.MAX_VALUE;
            Vector3dm bestHitLoc = null;
            BlockFace bestFace = null;

            for (SimpleCollisionBox box : boxes) {
                Pair<Vector3d, BlockFace> intercept = ReachUtils.calculateIntercept(box, trace.origin(), trace.getPointAtDistance(distance));
                if (intercept.first() == null) continue; // No intercept

                Vector3dm hitLoc = Vector3dm.from(intercept.first());

                if (hitLoc.distanceSquared(startingVec) < bestHitResult) {
                    bestHitResult = hitLoc.distanceSquared(startingVec);
                    bestHitLoc = hitLoc;
                    bestFace = intercept.second();
                }
            }
            if (bestHitLoc != null) {
                return new BlockHitData(vector3i, bestHitLoc, bestFace, block);
            }

            if (sourcesHaveHitbox &&
                    (player.compensatedWorld.isWaterSourceBlock(vector3i.getX(), vector3i.getY(), vector3i.getZ())
                            || player.compensatedWorld.getLavaFluidLevelAt(vector3i.getX(), vector3i.getY(), vector3i.getZ()) == (8 / 9f))) {
                double waterHeight = player.getClientVersion().isOlderThan(ClientVersion.V_1_13) ? 1
                        : player.compensatedWorld.getFluidLevelAt(vector3i.getX(), vector3i.getY(), vector3i.getZ());
                SimpleCollisionBox box = new SimpleCollisionBox(vector3i.getX(), vector3i.getY(), vector3i.getZ(), vector3i.getX() + 1, vector3i.getY() + waterHeight, vector3i.getZ() + 1);

                Pair<Vector3d, BlockFace> intercept = ReachUtils.calculateIntercept(box, trace.origin(), trace.getPointAtDistance(distance));

                if (intercept.first() != null) {
                    return new BlockHitData(vector3i, Vector3dm.from(intercept.first()), intercept.second(), block);
                }
            }

            return null;
        });
    }

    // Copied from MCP...
    // Returns null if there isn't anything.
    //
    // I do have to admit that I'm starting to like bifunctions/new java 8 things more than I originally did.
    // although I still don't understand Mojang's obsession with streams in some of the hottest methods... that kills performance
    public static BlockHitData traverseBlocks(GrimPlayer player, Vector3d start, Vector3d end, BiFunction<WrappedBlockState, Vector3i, BlockHitData> predicate) {
        // I guess go back by the collision epsilon?
        double endX = GrimMath.lerp(-1.0E-7D, end.x, start.x);
        double endY = GrimMath.lerp(-1.0E-7D, end.y, start.y);
        double endZ = GrimMath.lerp(-1.0E-7D, end.z, start.z);
        double startX = GrimMath.lerp(-1.0E-7D, start.x, end.x);
        double startY = GrimMath.lerp(-1.0E-7D, start.y, end.y);
        double startZ = GrimMath.lerp(-1.0E-7D, start.z, end.z);
        int floorStartX = GrimMath.floor(startX);
        int floorStartY = GrimMath.floor(startY);
        int floorStartZ = GrimMath.floor(startZ);

        if (start.equals(end)) return null;

        WrappedBlockState state = player.compensatedWorld.getBlock(floorStartX, floorStartY, floorStartZ);
        BlockHitData apply = predicate.apply(state, new Vector3i(floorStartX, floorStartY, floorStartZ));

        if (apply != null) {
            return apply;
        }

        double xDiff = endX - startX;
        double yDiff = endY - startY;
        double zDiff = endZ - startZ;
        double xSign = Math.signum(xDiff);
        double ySign = Math.signum(yDiff);
        double zSign = Math.signum(zDiff);

        double posXInverse = xSign == 0 ? Double.MAX_VALUE : xSign / xDiff;
        double posYInverse = ySign == 0 ? Double.MAX_VALUE : ySign / yDiff;
        double posZInverse = zSign == 0 ? Double.MAX_VALUE : zSign / zDiff;

        double d12 = posXInverse * (xSign > 0 ? 1.0D - GrimMath.frac(startX) : GrimMath.frac(startX));
        double d13 = posYInverse * (ySign > 0 ? 1.0D - GrimMath.frac(startY) : GrimMath.frac(startY));
        double d14 = posZInverse * (zSign > 0 ? 1.0D - GrimMath.frac(startZ) : GrimMath.frac(startZ));

        // Can't figure out what this code does currently
        while (d12 <= 1.0D || d13 <= 1.0D || d14 <= 1.0D) {
            if (d12 < d13) {
                if (d12 < d14) {
                    floorStartX += xSign;
                    d12 += posXInverse;
                } else {
                    floorStartZ += zSign;
                    d14 += posZInverse;
                }
            } else if (d13 < d14) {
                floorStartY += ySign;
                d13 += posYInverse;
            } else {
                floorStartZ += zSign;
                d14 += posZInverse;
            }

            state = player.compensatedWorld.getBlock(floorStartX, floorStartY, floorStartZ);
            apply = predicate.apply(state, new Vector3i(floorStartX, floorStartY, floorStartZ));

            if (apply != null) {
                return apply;
            }
        }

        return null;
    }

    public record AttackRay(Vector3d start, Vector3d targetHit) {
    }

    public static HitData getNearestHitResult(GrimPlayer player, PacketEntity targetEntity, AttackRay ray,
                                              @Nullable Set<String> ignoredBlocks, @Nullable Set<Vector3i> ignoredPositions) {
        Vector3d startingPos = ray.start();
        Vector3dm startingVec = Vector3dm.from(startingPos);
        HitData closestHit = new EntityHitData(targetEntity, Vector3dm.from(ray.targetHit()));
        double closestDistanceSquared = startingPos.distanceSquared(ray.targetHit());
        if (closestDistanceSquared == 0) return closestHit;

        // targetHit is Reach's accepted intercept; the target box is not rebuilt here.
        BlockHitData blockHitData = getTraverseResult(player, startingPos, ray.targetHit(), ignoredBlocks, ignoredPositions);
        if (blockHitData != null && blockHitData.getBlockHitLocation().distanceSquared(startingVec) < closestDistanceSquared) {
            closestHit = blockHitData;
            closestDistanceSquared = blockHitData.getBlockHitLocation().distanceSquared(startingVec);
        }

        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            if (entity.equals(targetEntity) || !entity.canHit()) continue;
            CollisionBox b = entity.getMinimumPossibleCollisionBoxes();
            if (b instanceof NoCollisionBox) continue;
            SimpleCollisionBox box = (SimpleCollisionBox) b;
            box.expand(-player.checkManager.get(Reach.class).threshold);
            if (!player.packetStateData.didLastLastMovementIncludePosition || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9))
                box.expand(-player.getMovementThreshold());
            if (player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) {
                box.expand(0.1f);
            }


            Pair<Vector3d, BlockFace> intercept = ReachUtils.calculateIntercept(box, startingPos, ray.targetHit());

            if (intercept.first() != null) {
                Vector3dm hitVec = Vector3dm.from(intercept.first());
                double distSquared = hitVec.distanceSquared(startingVec);
                if (distSquared < closestDistanceSquared) {
                    closestDistanceSquared = distSquared;
                    closestHit = new EntityHitData(entity, hitVec);
                }
            }
        }

        return closestHit;
    }

    public static @NotNull Pair<@NotNull Double, @NotNull HitData> didRayTraceHit(GrimPlayer player, PacketEntity targetEntity,
                                                                                   List<AttackRay> attackRays,
                                                                                   @Nullable Set<String> ignoredBlocks,
                                                                                   @Nullable Set<Vector3i> ignoredPositions) {
        HitData firstObstruction = null;
        double firstObstructionDistanceSq = 0;

        for (AttackRay ray : attackRays) {
            Vector3dm eyes = Vector3dm.from(ray.start());
            final HitData hitResult = getNearestHitResult(player, targetEntity, ray, ignoredBlocks, ignoredPositions);

            if (hitResult instanceof EntityHitData && ((EntityHitData) hitResult).getEntity().equals(targetEntity)) {
                double distanceSquared = eyes.distanceSquared(hitResult.getBlockHitLocation());
                return new Pair<>(distanceSquared, hitResult);
            } else if (firstObstruction == null) {
                firstObstruction = hitResult;
                firstObstructionDistanceSq = eyes.distanceSquared(hitResult.getBlockHitLocation());
            }
        }

        // Eye heights are ordered by likelihood, so report the first obstruction when all rays fail.
        assert firstObstruction != null;
        return new Pair<>(firstObstructionDistanceSq, firstObstruction);
    }

    // Shrinking flush blocks independently opens artificial slits along a solid wall.
    private static boolean[] sealedFaces(GrimPlayer player, Vector3i pos, Set<String> ignoredBlocks, Set<Vector3i> ignoredPositions,
                                         List<SimpleCollisionBox> boxes, List<SimpleCollisionBox> bridges) {
        final int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        return new boolean[]{
                neighbourSeals(player, x, y, z, -1, 0, 0, ignoredBlocks, ignoredPositions, boxes, bridges),
                neighbourSeals(player, x, y, z, 1, 0, 0, ignoredBlocks, ignoredPositions, boxes, bridges),
                neighbourSeals(player, x, y, z, 0, -1, 0, ignoredBlocks, ignoredPositions, boxes, bridges),
                neighbourSeals(player, x, y, z, 0, 1, 0, ignoredBlocks, ignoredPositions, boxes, bridges),
                neighbourSeals(player, x, y, z, 0, 0, -1, ignoredBlocks, ignoredPositions, boxes, bridges),
                neighbourSeals(player, x, y, z, 0, 0, 1, ignoredBlocks, ignoredPositions, boxes, bridges)};
    }

    private static void shrinkExposedFaces(GrimPlayer player, SimpleCollisionBox box, Vector3i pos, boolean[] sealed) {
        final double threshold = player.getMovementThreshold();
        final int x = pos.getX(), y = pos.getY(), z = pos.getZ();

        if (box.minX > x || !sealed[0]) box.minX += threshold;
        if (box.maxX < x + 1 || !sealed[1]) box.maxX -= threshold;
        if (box.minY > y || !sealed[2]) box.minY += threshold;
        if (box.maxY < y + 1 || !sealed[3]) box.maxY -= threshold;
        if (box.minZ > z || !sealed[4]) box.minZ += threshold;
        if (box.maxZ < z + 1 || !sealed[5]) box.maxZ -= threshold;
    }

    // A slab sitting on stone is flush with it even though neither is a full cube, so the test is whether
    // the neighbour reaches the shared plane and covers the whole face, not whether it fills its own cell.
    private static boolean neighbourSeals(GrimPlayer player, int x, int y, int z, int dx, int dy, int dz,
                                          Set<String> ignoredBlocks, Set<Vector3i> ignoredPositions,
                                          List<SimpleCollisionBox> currentBoxes, List<SimpleCollisionBox> bridges) {
        final int nx = x + dx, ny = y + dy, nz = z + dz;
        WrappedBlockState state = player.compensatedWorld.getBlock(nx, ny, nz);
        if (ignoredPositions != null && ignoredPositions.contains(new Vector3i(nx, ny, nz))) return false;
        if (ignoredBlocks != null && ignoredBlocks.contains(state.getType().getName().toUpperCase())) return false;
        CollisionBox data = HitboxData.getBlockHitbox(player, null, player.getClientVersion(), state, false, nx, ny, nz);

        boolean sealed = false;
        for (SimpleCollisionBox box : RayTraceShape.coverInternalSeams(data)) {
            for (SimpleCollisionBox current : currentBoxes) {
                SimpleCollisionBox bridge = RayTraceShape.bridge(current, box, dx != 0 ? 0 : dy != 0 ? 1 : 2, player.getMovementThreshold());
                if (bridge != null) bridges.add(bridge);
            }
            if (dx != 0) {
                boolean touches = dx > 0 ? box.minX <= x + 1 : box.maxX >= x;
                sealed |= touches && box.minY <= y && box.maxY >= y + 1 && box.minZ <= z && box.maxZ >= z + 1;
            } else if (dy != 0) {
                boolean touches = dy > 0 ? box.minY <= y + 1 : box.maxY >= y;
                sealed |= touches && box.minX <= x && box.maxX >= x + 1 && box.minZ <= z && box.maxZ >= z + 1;
            } else {
                boolean touches = dz > 0 ? box.minZ <= z + 1 : box.maxZ >= z;
                sealed |= touches && box.minX <= x && box.maxX >= x + 1 && box.minY <= y && box.maxY >= y + 1;
            }
        }
        return sealed;
    }

    private static BlockHitData getTraverseResult(GrimPlayer player, Vector3d startingPos, Vector3d endPos,
                                                  @Nullable Set<String> ignoredBlocks, @Nullable Set<Vector3i> ignoredPositions) {
        Vector3dm startingVec = Vector3dm.from(startingPos);
        return traverseBlocks(player, startingPos, endPos, (block, vector3i) -> {
            // Skipped rather than reported: treating an exempt block as the answer ends the trace and
            // leaves whatever is behind it unexamined, which turns a slab held at eye level into a visor.
            if (ignoredPositions != null && ignoredPositions.contains(vector3i)) return null;
            if (ignoredBlocks != null && ignoredBlocks.contains(block.getType().getName().toUpperCase())) return null;
            CollisionBox data = HitboxData.getBlockHitbox(player, null, player.getClientVersion(), block, false, vector3i.getX(), vector3i.getY(), vector3i.getZ());
            List<SimpleCollisionBox> boxes = RayTraceShape.coverInternalSeams(data);

            double bestHitResult = Double.MAX_VALUE;
            Vector3dm bestHitLoc = null;
            BlockFace bestFace = null;
            List<SimpleCollisionBox> bridges = new ArrayList<>();
            boolean[] sealed = boxes.isEmpty() ? null : sealedFaces(player, vector3i, ignoredBlocks, ignoredPositions, boxes, bridges);
            for (SimpleCollisionBox box : boxes) shrinkExposedFaces(player, box, vector3i, sealed);
            boxes.addAll(bridges);

            for (SimpleCollisionBox box : boxes) {
                if (box.minX >= box.maxX || box.minY >= box.maxY || box.minZ >= box.maxZ) continue;
                Pair<Vector3d, BlockFace> intercept = ReachUtils.calculateIntercept(box, startingPos, endPos);
                if (intercept.first() == null) continue; // No intercept

                Vector3dm hitLoc = Vector3dm.from(intercept.first());

                // The client may be clipping into a block while its position is still uncertain.
                if (ReachUtils.isVecInside(box, startingPos)) {
                    return null;
                }

                if (hitLoc.distanceSquared(startingVec) < bestHitResult) {
                    bestHitResult = hitLoc.distanceSquared(startingVec);
                    bestHitLoc = hitLoc;
                    bestFace = intercept.second();
                }
            }

            if (bestHitLoc != null) {
                return new BlockHitData(vector3i, bestHitLoc, bestFace, block);
            }

            return null;
        });
    }
}
