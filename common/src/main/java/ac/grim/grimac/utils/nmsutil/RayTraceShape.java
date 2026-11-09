package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;

@UtilityClass
public class RayTraceShape {
    public static List<SimpleCollisionBox> coverInternalSeams(CollisionBox shape) {
        List<SimpleCollisionBox> boxes = new ArrayList<>();
        shape.downCast(boxes);
        // Joined boxes stay inside the original union, so shrinking them opens no seams inside stairs.
        boolean changed;
        do {
            changed = false;
            search:
            for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    SimpleCollisionBox a = boxes.get(i), b = boxes.get(j);
                    for (int axis = 0; axis < 3; axis++) {
                        SimpleCollisionBox joined = join(a, b, axis);
                        if (joined == null || boxes.stream().anyMatch(box -> contains(box, joined))) continue;
                        boxes.removeIf(box -> contains(joined, box));
                        boxes.add(joined);
                        changed = true;
                        break search;
                    }
                }
            }
        } while (changed);
        return boxes;
    }

    private static SimpleCollisionBox join(SimpleCollisionBox a, SimpleCollisionBox b, int axis) {
        if (a.maxX < b.minX || b.maxX < a.minX
                || a.maxY < b.minY || b.maxY < a.minY
                || a.maxZ < b.minZ || b.maxZ < a.minZ) return null;
        double minX = axis == 0 ? Math.min(a.minX, b.minX) : Math.max(a.minX, b.minX);
        double minY = axis == 1 ? Math.min(a.minY, b.minY) : Math.max(a.minY, b.minY);
        double minZ = axis == 2 ? Math.min(a.minZ, b.minZ) : Math.max(a.minZ, b.minZ);
        double maxX = axis == 0 ? Math.max(a.maxX, b.maxX) : Math.min(a.maxX, b.maxX);
        double maxY = axis == 1 ? Math.max(a.maxY, b.maxY) : Math.min(a.maxY, b.maxY);
        double maxZ = axis == 2 ? Math.max(a.maxZ, b.maxZ) : Math.min(a.maxZ, b.maxZ);
        if (minX >= maxX || minY >= maxY || minZ >= maxZ) return null;
        return new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    static SimpleCollisionBox bridge(SimpleCollisionBox current, SimpleCollisionBox neighbour, int axis, double threshold) {
        SimpleCollisionBox joined = join(current, neighbour, axis);
        if (joined == null) return null;
        joined.expand(-threshold);
        // Clip after shrinking so the bridge only reports hits inside the current block.
        joined.minX = Math.max(joined.minX, current.minX);
        joined.minY = Math.max(joined.minY, current.minY);
        joined.minZ = Math.max(joined.minZ, current.minZ);
        joined.maxX = Math.min(joined.maxX, current.maxX);
        joined.maxY = Math.min(joined.maxY, current.maxY);
        joined.maxZ = Math.min(joined.maxZ, current.maxZ);
        return joined.minX < joined.maxX && joined.minY < joined.maxY && joined.minZ < joined.maxZ ? joined : null;
    }

    private static boolean contains(SimpleCollisionBox outer, SimpleCollisionBox inner) {
        return outer.minX <= inner.minX && outer.maxX >= inner.maxX
                && outer.minY <= inner.minY && outer.maxY >= inner.maxY
                && outer.minZ <= inner.minZ && outer.maxZ >= inner.maxZ;
    }
}
