package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.utils.collisions.datatypes.ComplexCollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import com.github.retrooper.packetevents.util.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RayTraceShapeTest {
    @Test
    void allOctantShapesKeepTheirSolidInteriorAndTheirHoles() {
        for (int mask = 0; mask < 256; mask++) {
            ComplexCollisionBox shape = new ComplexCollisionBox(8);
            for (int octant = 0; octant < 8; octant++) {
                if ((mask & (1 << octant)) == 0) continue;
                double x = (octant & 1) * .5;
                double y = ((octant >> 1) & 1) * .5;
                double z = ((octant >> 2) & 1) * .5;
                shape.add(new SimpleCollisionBox(x, y, z, x + .5, y + .5, z + .5));
            }
            var boxes = RayTraceShape.coverInternalSeams(shape);
            boxes.forEach(box -> box.expand(-.03));
            for (double x : new double[]{.25, .5, .75}) {
                for (double y : new double[]{.25, .5, .75}) {
                    for (double z : new double[]{.25, .5, .75}) {
                        boolean solid = true;
                        for (double dx : new double[]{-.03, .03}) {
                            for (double dy : new double[]{-.03, .03}) {
                                for (double dz : new double[]{-.03, .03}) {
                                    int octant = (x + dx >= .5 ? 1 : 0)
                                            | (y + dy >= .5 ? 2 : 0) | (z + dz >= .5 ? 4 : 0);
                                    solid &= (mask & (1 << octant)) != 0;
                                }
                            }
                        }
                        Vector3d point = new Vector3d(x, y, z);
                        assertEquals(solid, boxes.stream().anyMatch(box -> ReachUtils.isVecInside(box, point)),
                                "mask=" + mask + ", point=" + point);
                    }
                }
            }
        }
    }
}
