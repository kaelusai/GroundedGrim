package ac.grim.grimac.utils.data.interpolation;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.testutil.GrimPlayerFixture;
import ac.grim.grimac.utils.data.TrackerData;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityMovementTransactionTest {
    @BeforeAll
    static void initialize() {
        GrimPlayerFixture.initializePacketEvents();
    }

    @Test
    void preMovementPongDoesNotConfirmThePositionUpdate() throws ReflectiveOperationException {
        GrimPlayer player = GrimPlayerFixture.player();
        PacketEntity entity = mock(PacketEntity.class);
        TrackerData tracker = new TrackerData(4, 0, 0, 0, 0, EntityTypes.PLAYER, 10);
        tracker.recordPositionUpdate(12, 10, 100);
        when(player.compensatedEntities.getEntity(1)).thenReturn(entity);
        when(player.compensatedEntities.getTrackedEntity(1)).thenReturn(tracker);
        EntityMovementTransaction task = new EntityMovementTransaction(player, 1, null, null, null,
                true, true, 3, 0, 0, false, 11, tracker);
        task.run();
        assertTrue(tracker.isPositionUpdateStale(11, 2000, 1000));
        task.run();
        assertFalse(tracker.isPositionUpdateStale(12, 2000, 1000));
    }

    @Test
    void reusedEntityIdDoesNotAcknowledgeTheNewTracker() throws ReflectiveOperationException {
        GrimPlayer player = GrimPlayerFixture.player();
        TrackerData original = new TrackerData(0, 0, 0, 0, 0, EntityTypes.PLAYER, 10);
        TrackerData replacement = new TrackerData(4, 0, 0, 0, 0, EntityTypes.PLAYER, 20);
        replacement.recordPositionUpdate(22, 20, 100);
        when(player.compensatedEntities.getTrackedEntity(1)).thenReturn(replacement);
        EntityMovementTransaction task = new EntityMovementTransaction(player, 1, null, null, null,
                true, true, 3, 0, 0, false, 21, original);
        task.run();
        task.run();
        assertTrue(replacement.isPositionUpdateStale(20, 2000, 1000));
    }
}
