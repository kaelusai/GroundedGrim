package ac.grim.grimac.utils.data.interpolation;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.data.ReachInterpolationData;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import com.github.retrooper.packetevents.protocol.vector.positionpath.PositionPath;
import org.jetbrains.annotations.Nullable;

public interface EntityInterpolation {

    void begin(@Nullable PositionPath path, boolean relative, boolean hasPos, double packetX, double packetY, double packetZ, @Nullable Float yaw, @Nullable Float pitch, boolean positionSync, int transaction);

    void confirm(int transaction);

    void tick(boolean tickingReliably);

    void reset(SimpleCollisionBox position);

    SimpleCollisionBox position();

    default CollisionBox minimumHitbox(GrimPlayer player, PacketEntity entity) {
        SimpleCollisionBox bounds = position();
        SimpleCollisionBox low = new SimpleCollisionBox(bounds.minX, bounds.minY, bounds.minZ, bounds.minX, bounds.minY, bounds.minZ);
        SimpleCollisionBox high = new SimpleCollisionBox(bounds.maxX, bounds.maxY, bounds.maxZ, bounds.maxX, bounds.maxY, bounds.maxZ);
        GetBoundingBox.expandBoundingBoxByEntityDimensions(low, player, entity);
        GetBoundingBox.expandBoundingBoxByEntityDimensions(high, player, entity);
        return ReachInterpolationData.getOverlapHitbox(low, high);
    }

    default boolean hasUnconfirmedMovement() {
        return true;
    }

    default SimpleCollisionBox hitbox(GrimPlayer player, PacketEntity entity) {
        SimpleCollisionBox hitbox = position();
        GetBoundingBox.expandBoundingBoxByEntityDimensions(hitbox, player, entity);
        return hitbox;
    }

    default void initializeRotation(float yaw, float pitch) {
    }

}
