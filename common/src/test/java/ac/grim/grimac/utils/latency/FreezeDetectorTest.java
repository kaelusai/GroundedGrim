package ac.grim.grimac.utils.latency;

import ac.grim.grimac.checks.impl.combat.FreezeAttack;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.testutil.GrimPlayerFixture;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FreezeDetectorTest {
    private final AtomicLong time = new AtomicLong(10_000);
    private GrimPlayer player;
    private FreezeDetector detector;

    @BeforeAll
    static void initialize() {
        GrimPlayerFixture.initializePacketEvents();
    }

    @BeforeEach
    void setup() throws ReflectiveOperationException {
        player = GrimPlayerFixture.player();
        detector = new FreezeDetector(player, time::get);
        GrimPlayerFixture.set(player, "freezeDetector", detector);
    }

    @ParameterizedTest
    @ValueSource(longs = {3000, 5000, 5001, 7000, 60000})
    void attacksStayBlockedThroughoutAnUnrecoveredFreeze(long gap) throws ReflectiveOperationException {
        time.addAndGet(gap);
        FreezeAttack check = mock(FreezeAttack.class, CALLS_REAL_METHODS);
        GrimPlayerFixture.set(check, "player", player);
        GrimPlayerFixture.set(check, "enabled", true);
        GrimPlayerFixture.set(check, "cancelHits", true);
        doReturn(true).when(check).shouldModifyPackets();
        PacketReceiveEvent event = mock(PacketReceiveEvent.class);
        when(event.getPacketType()).thenReturn(PacketType.Play.Client.ATTACK);
        check.onPacketReceive(event);
        verify(event).setCancelled(true);
        assertTrue(detector.isFrozen());
        assertFalse(detector.shouldFlag());
        verify(check, never()).flagAndAlert(anyString());
    }

    @ParameterizedTest
    @ValueSource(longs = {50, 1000})
    void movementAndStationaryHeartbeatsBothRecover(long interval) {
        time.addAndGet(7000);
        detector.onAttackPacket();
        detector.onFlyingPacket();
        for (int i = 0; i < detector.recoveryFlyingsRequired; i++) {
            time.addAndGet(interval);
            detector.onFlyingPacket();
        }
        detector.onAttackPacket();
        assertFalse(detector.isFrozen());
        assertEquals(0, detector.getAttacksDuringGap());
    }

    @Test
    void regularStationaryClientDoesNotAccumulateFreezeScore() {
        for (int i = 0; i < 60; i++) {
            time.addAndGet(1000);
            detector.onFlyingPacket();
            detector.onAttackPacket();
            assertFalse(detector.isFrozen());
            assertFalse(detector.shouldFlag());
        }
    }

    @Test
    void cancellationRespectsPacketModificationPermission() throws ReflectiveOperationException {
        time.addAndGet(7000);
        FreezeAttack check = mock(FreezeAttack.class, CALLS_REAL_METHODS);
        GrimPlayerFixture.set(check, "player", player);
        GrimPlayerFixture.set(check, "enabled", true);
        GrimPlayerFixture.set(check, "cancelHits", true);
        doReturn(false).when(check).shouldModifyPackets();
        PacketReceiveEvent event = mock(PacketReceiveEvent.class);
        when(event.getPacketType()).thenReturn(PacketType.Play.Client.ATTACK);
        check.onPacketReceive(event);
        verify(event, never()).setCancelled(true);
    }

    @Test
    void stalePongAndBufferedRecoveryDoNotAuthorizeFlags() {
        for (int i = 0; i < 5; i++) detector.onTransactionReceived(50);
        time.addAndGet(7000);
        detector.onAttackPacket();
        detector.onTransactionReceived(1000);
        assertTrue(detector.getScore() >= detector.scoreFlag);
        assertFalse(detector.shouldFlag());
        detector.onFlyingPacket();
        player.lastTransactionReceived.incrementAndGet();
        detector.onTransactionReceived(1000);
        detector.onAttackPacket();
        assertFalse(detector.shouldFlag());
    }

    @Test
    void freshReplyDuringWithholdingCanFlagButProofExpiresWithMovement() {
        for (int i = 0; i < 5; i++) detector.onTransactionReceived(50);
        time.addAndGet(7000);
        detector.onAttackPacket();
        player.lastTransactionSent.incrementAndGet();
        player.lastTransactionReceived.incrementAndGet();
        detector.onTransactionReceived(1000);
        assertTrue(detector.shouldFlag());
        detector.onFlyingPacket();
        assertFalse(detector.shouldFlag());
        time.addAndGet(3000);
        detector.onAttackPacket();
        assertFalse(detector.shouldFlag());
    }

    @Test
    void teleportAndJoinGraceClearAttackHistory() throws ReflectiveOperationException {
        time.addAndGet(7000);
        detector.onAttackPacket();
        player.packetStateData.lastPacketWasTeleport = true;
        detector.onAttackPacket();
        assertFalse(detector.isFrozen());
        assertEquals(0, detector.getAttacksDuringGap());
        player.packetStateData.lastPacketWasTeleport = false;
        GrimPlayerFixture.set(player, "joinTime", time.get());
        time.addAndGet(3000);
        detector.onAttackPacket();
        assertFalse(detector.isFrozen());
    }
}
