package ac.grim.grimac.utils.data.interpolation;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.interpolation.stepped.SteppedEntityInterpolation;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.enums.Pose;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.vector.positionpath.LinearPositionPath;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.util.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SteppedMinimumHitboxTest {
    @BeforeAll
    static void packetEvents() {
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class, RETURNS_DEEP_STUBS);
        when(api.getServerManager().getVersion()).thenReturn(ServerVersion.V_1_21_4);
        when(api.getSettings()).thenReturn(new PacketEventsSettings());
        PacketEvents.setAPI(api);
        EntityTypes.PLAYER.getName();
        Attributes.SCALE.getName();
    }

    @Test
    void onlyUsesAreaCommonToEveryPossiblePosition() {
        GrimPlayer player = mock(GrimPlayer.class);
        when(player.getClientVersion()).thenReturn(ClientVersion.V_26_3);
        PacketEntity entity = mock(PacketEntity.class);
        when(entity.getType()).thenReturn(EntityTypes.PLAYER);
        when(entity.getAttributeValue(Attributes.SCALE)).thenReturn(1.0);
        entity.currentPose = Pose.STANDING;

        EntityInterpolation near = new SteppedEntityInterpolation(2, new SimpleCollisionBox(0, 0, 0, 0.2, 0, 0.1));
        SimpleCollisionBox overlap = assertInstanceOf(SimpleCollisionBox.class, near.minimumHitbox(player, entity));
        assertEquals(-0.1, overlap.minX, 1e-6);
        assertEquals(0.3, overlap.maxX, 1e-6);
        assertEquals(-0.2, overlap.minZ, 1e-6);
        assertEquals(0.3, overlap.maxZ, 1e-6);

        EntityInterpolation far = new SteppedEntityInterpolation(2, new SimpleCollisionBox(0, 0, 0, 0.8, 0, 0));
        CollisionBox noOverlap = far.minimumHitbox(player, entity);
        assertTrue(noOverlap.isNull());
    }

    @Test
    void stationaryEntityDoesNotKeepUnconfirmedMovement() {
        EntityInterpolation interpolation = new SteppedEntityInterpolation(2, new SimpleCollisionBox(0, 64, 0, 0, 64, 0));
        interpolation.confirm(100);
        for (int i = 0; i < 10; i++) interpolation.tick(true);
        assertFalse(interpolation.hasUnconfirmedMovement());
    }

    @Test
    void movementRemainsUnconfirmedUntilItsOwnTransactionArrives() {
        EntityInterpolation interpolation = new SteppedEntityInterpolation(2, new SimpleCollisionBox(0, 64, 0, 0, 64, 0));
        interpolation.begin(new LinearPositionPath(new Vector3d(1, 64, 0)), false, true, 1, 64, 0, 0f, 0f, false, 100);
        assertTrue(interpolation.hasUnconfirmedMovement());
        interpolation.confirm(99);
        assertTrue(interpolation.hasUnconfirmedMovement());
        interpolation.confirm(100);
        assertFalse(interpolation.hasUnconfirmedMovement());

        interpolation.begin(new LinearPositionPath(new Vector3d(2, 64, 0)), false, true, 2, 64, 0, 0f, 0f, false, 101);
        interpolation.confirm(100);
        assertTrue(interpolation.hasUnconfirmedMovement());
        interpolation.confirm(101);
        assertFalse(interpolation.hasUnconfirmedMovement());
    }
}
