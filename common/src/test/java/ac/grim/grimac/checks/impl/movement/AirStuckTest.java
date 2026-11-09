package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.platform.api.PlatformLoader;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.world.PlatformWorld;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.testutil.GrimPlayerFixture;
import ac.grim.grimac.utils.chunks.Column;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.math.Location;
import ac.grim.grimac.utils.math.Vector3dm;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AirStuckTest {
    private static final ClientVersion VERSION = ClientVersion.V_1_21_4;
    private final Map<Vector3i, WrappedBlockState> blocks = new HashMap<>();
    private final BaseChunk[] sections = new BaseChunk[24];
    private GrimPlayer player;
    private AirStuck check;
    private PlatformPlayer platform;
    private PlatformWorld serverWorld;
    private PlatformLoader previousLoader;
    private Runnable pendingTeleport;

    @BeforeAll
    static void initialize() {
        GrimPlayerFixture.initializePacketEvents();
    }

    @BeforeEach
    void setup() throws ReflectiveOperationException {
        player = GrimPlayerFixture.player();
        player.x = player.z = 8.5;
        player.y = 5;
        check = mock(AirStuck.class, CALLS_REAL_METHODS);
        GrimPlayerFixture.set(check, "player", player);
        GrimPlayerFixture.set(check, "enabled", true);
        GrimPlayerFixture.set(check, "maxMs", 2000);
        GrimPlayerFixture.set(check, "flagCooldownMs", 3000);
        GrimPlayerFixture.set(check, "lastPositionTime", System.currentTimeMillis() - 4000);
        when(player.compensatedWorld.getChunk(0, 0)).thenReturn(new Column(0, 0, sections, new long[sections.length], 0));
        WrappedBlockState air = WrappedBlockState.getDefaultState(VERSION, StateTypes.AIR);
        when(player.compensatedWorld.getBlock(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                blocks.getOrDefault(new Vector3i(call.getArgument(0), call.getArgument(1), call.getArgument(2)), air));
        platform = mock(PlatformPlayer.class);
        serverWorld = mock(PlatformWorld.class);
        when(platform.getWorld()).thenReturn(serverWorld);
        when(platform.getPosition()).thenAnswer(call -> new Vector3d(player.x, player.y, player.z));
        when(serverWorld.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(serverWorld.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                blocks.getOrDefault(new Vector3i(call.getArgument(0), call.getArgument(1), call.getArgument(2)), air));
        GrimPlayerFixture.set(player, "platformPlayer", platform);
        previousLoader = GrimAPI.INSTANCE.getLoader();
        PlatformLoader loader = mock(PlatformLoader.class, RETURNS_DEEP_STUBS);
        GrimPlayerFixture.set(GrimAPI.INSTANCE, "loader", loader);
        var scheduler = loader.getScheduler().getEntityScheduler();
        doAnswer(call -> {
            pendingTeleport = call.getArgument(2);
            return null;
        }).when(scheduler).execute(any(), any(), any(), isNull(), eq(0L));
    }

    @AfterEach
    void restoreLoader() throws ReflectiveOperationException {
        GrimPlayerFixture.set(GrimAPI.INSTANCE, "loader", previousLoader);
    }

    private void block(int x, int y, int z, String state) {
        WrappedBlockState parsed = WrappedBlockState.getByString(VERSION, "minecraft:" + state);
        assertNotNull(parsed, state);
        assertNotEquals(StateTypes.AIR, parsed.getType(), state);
        blocks.put(new Vector3i(x, y, z), parsed);
        int sectionIndex = (y + 64) >> 4;
        if (sections[sectionIndex] == null) {
            BaseChunk section = mock(BaseChunk.class);
            sections[sectionIndex] = section;
            int baseY = (sectionIndex << 4) - 64;
            WrappedBlockState air = WrappedBlockState.getDefaultState(VERSION, StateTypes.AIR);
            when(section.get(eq(VERSION), anyInt(), anyInt(), anyInt(), eq(false))).thenAnswer(call ->
                    blocks.getOrDefault(new Vector3i(call.getArgument(1), baseY + (int) call.getArgument(2), call.getArgument(3)), air));
        }
    }

    @Test
    void doesNotTurnInitialPenetrationIntoAnUndergroundLanding() {
        SimpleCollisionBox body = new SimpleCollisionBox(.2, .99, .2, .8, 2.79, .8);
        SimpleCollisionBox surface = new SimpleCollisionBox(0, 0, 0, 1, 1, 1);
        SimpleCollisionBox lower = new SimpleCollisionBox(0, -1, 0, 1, 0, 1);
        assertEquals(-.08, surface.collideY(body, -.08));
        assertNull(AirStuck.findLandingY(body, 5, List.of(surface, lower)));
        player.y = .99;
        block(8, 0, 8, "stone");
        block(8, -1, 8, "stone");
        assertNull(check.findLanding(5));
    }

    @ParameterizedTest
    @CsvSource({"bottom,0.5", "top,1.0", "double,1.0"})
    void landsOnActualSlabSurface(String type, double height) {
        block(8, 0, 8, "stone_slab[type=" + type + ",waterlogged=false]");
        assertEquals(new Vector3d(8.5, height, 8.5), check.findLanding(10));
    }

    @Test
    void firstFloorStopsDescentBeforeCaveBelow() {
        block(8, 2, 8, "stone");
        block(8, -10, 8, "stone");
        assertEquals(new Vector3d(8.5, 3, 8.5), check.findLanding(30));
    }

    @Test
    void usesWholeFootprintAndPoseInsteadOfCentreBlockOrFixedHeight() {
        player.x = 8.8;
        block(9, 0, 8, "stone");
        assertEquals(new Vector3d(8.8, 1, 8.5), check.findLanding(10));
        SimpleCollisionBox ceiling = new SimpleCollisionBox(0, 2.7, 0, 1, 3.7, 1);
        SimpleCollisionBox floor = new SimpleCollisionBox(0, 0, 0, 1, 1, 1);
        assertNull(AirStuck.findLandingY(new SimpleCollisionBox(.2, 1.1, .2, .8, 2.9, .8), 2, List.of(ceiling, floor)));
        assertEquals(1.0, AirStuck.findLandingY(new SimpleCollisionBox(.2, 1.1, .2, .8, 2.6, .8), 2, List.of(ceiling, floor)));
    }

    @Test
    void requiresGroundAndDoesNotTeleportToEndOfFallBudget() {
        assertNull(check.findLanding(10));
        block(8, -20, 8, "stone");
        assertNull(check.findLanding(10));
        player.y = -63;
        assertNull(check.findLanding(10));
    }

    @Test
    void groundedPlayerIsNotMovedDown() {
        player.y = 1;
        block(8, 0, 8, "stone");
        block(8, -10, 8, "stone");
        assertNull(check.findLanding(20));
    }

    @Test
    void refusesMissingOrUnacknowledgedCollisionChunks() {
        block(8, 0, 8, "stone");
        when(player.compensatedWorld.getChunk(0, 0)).thenReturn(null);
        assertNull(check.findLanding(10));
        when(player.compensatedWorld.getChunk(0, 0)).thenReturn(new Column(0, 0, sections, new long[sections.length], 10));
        assertNull(check.findLanding(10));
        when(player.compensatedWorld.getChunk(0, 0)).thenReturn(new Column(0, 0, sections, new long[sections.length], 0));
        player.x = 15.9;
        assertNull(check.findLanding(10));
    }

    @Test
    void ignoresPlatformPositionAndPreservesPredictionState() throws ReflectiveOperationException {
        PlatformPlayer platform = mock(PlatformPlayer.class);
        when(platform.getPosition()).thenReturn(new Vector3d(8.5, -5, 8.5));
        GrimPlayerFixture.set(player, "platformPlayer", platform);
        player.boundingBox = new SimpleCollisionBox(8, 5, 8, 9, 7, 9);
        player.actualMovement = new Vector3dm(.1, -.2, .3);
        SimpleCollisionBox originalBox = player.boundingBox;
        Vector3dm originalMovement = player.actualMovement;
        block(8, 0, 8, "stone");
        assertEquals(new Vector3d(8.5, 1, 8.5), check.findLanding(10));
        assertSame(originalBox, player.boundingBox);
        assertSame(originalMovement, player.actualMovement);
        assertEquals(5, player.y);
        verify(platform, never()).getPosition();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void respectsFlagGateAndValidatesBeforeServerTeleport(boolean flagged) {
        block(8, 0, 8, "stone");
        doReturn(flagged).when(check).flagAndAlert(anyString());
        doReturn(true).when(check).shouldModifyPackets();
        PacketReceiveEvent event = mock(PacketReceiveEvent.class);
        when(event.getPacketType()).thenReturn(PacketType.Play.Client.PONG);
        check.onPacketReceive(event);
        verify(platform, never()).teleportAsync(any());
        if (!flagged) {
            assertNull(pendingTeleport);
            return;
        }
        assertNotNull(pendingTeleport);
        pendingTeleport.run();
        ArgumentCaptor<Location> destination = ArgumentCaptor.forClass(Location.class);
        verify(platform).teleportAsync(destination.capture());
        assertEquals(8.5, destination.getValue().getX());
        assertEquals(1, destination.getValue().getY());
        assertEquals(8.5, destination.getValue().getZ());
        assertSame(serverWorld, destination.getValue().getWorld());
    }

    @ParameterizedTest
    @ValueSource(strings = {"position", "world", "floor", "obstruction", "unloaded", "dead",
            "flight", "serverFlight", "gliding", "climbing", "bed", "gravity", "zeroGravity", "water", "lava",
            "levitation", "slowFalling", "teleport", "noSetback", "modify", "disabled"})
    void abandonsTeleportWhenScheduledDestinationIsNoLongerSafe(String change) throws ReflectiveOperationException {
        block(8, 0, 8, "stone");
        doReturn(true).when(check).flagAndAlert(anyString());
        doReturn(true).when(check).shouldModifyPackets();
        PacketReceiveEvent event = mock(PacketReceiveEvent.class);
        when(event.getPacketType()).thenReturn(PacketType.Play.Client.PONG);
        check.onPacketReceive(event);
        assertNotNull(pendingTeleport);
        switch (change) {
            case "position" -> when(platform.getPosition()).thenReturn(new Vector3d(8.5, 20, 8.5));
            case "world" -> when(platform.getWorld()).thenReturn(mock(PlatformWorld.class));
            case "floor" -> when(serverWorld.getBlockAt(8, 0, 8)).thenReturn(WrappedBlockState.getDefaultState(VERSION, StateTypes.AIR));
            case "obstruction" -> when(serverWorld.getBlockAt(8, 1, 8)).thenReturn(WrappedBlockState.getDefaultState(VERSION, StateTypes.STONE));
            case "unloaded" -> when(serverWorld.isChunkLoaded(0, 0)).thenReturn(false);
            case "dead" -> when(platform.isDead()).thenReturn(true);
            case "flight" -> player.isFlying = true;
            case "serverFlight" -> when(platform.isFlying()).thenReturn(true);
            case "gliding" -> player.isGliding = true;
            case "climbing" -> player.isClimbing = true;
            case "bed" -> player.isInBed = true;
            case "gravity" -> player.hasGravity = false;
            case "zeroGravity" -> player.gravity = 0;
            case "water" -> player.wasTouchingWater = true;
            case "lava" -> player.wasTouchingLava = true;
            case "levitation" -> when(player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.LEVITATION)).thenReturn(OptionalInt.of(0));
            case "slowFalling" -> when(player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.SLOW_FALLING)).thenReturn(OptionalInt.of(0));
            case "teleport" -> when(player.getSetbackTeleportUtil().shouldBlockMovement()).thenReturn(true);
            case "noSetback" -> GrimPlayerFixture.set(check, "noSetbackPermission", true);
            case "modify" -> doReturn(false).when(check).shouldModifyPackets();
            case "disabled" -> GrimPlayerFixture.set(check, "enabled", false);
            default -> throw new AssertionError(change);
        }
        pendingTeleport.run();
        verify(platform, never()).teleportAsync(any());
        verify(platform, never()).resetFallDistance();
    }

    @Test
    void noSetbackPermissionPreventsSchedulingDescent() throws ReflectiveOperationException {
        block(8, 0, 8, "stone");
        doReturn(true).when(check).flagAndAlert(anyString());
        doReturn(true).when(check).shouldModifyPackets();
        GrimPlayerFixture.set(check, "noSetbackPermission", true);
        PacketReceiveEvent event = mock(PacketReceiveEvent.class);
        when(event.getPacketType()).thenReturn(PacketType.Play.Client.PONG);
        check.onPacketReceive(event);
        assertNull(pendingTeleport);
        verify(platform, never()).resetFallDistance();
    }

    @Test
    void doesNotCorrectWhileTeleportIsPending() {
        when(player.getSetbackTeleportUtil().shouldBlockMovement()).thenReturn(true);
        block(8, 0, 8, "stone");
        PacketReceiveEvent event = mock(PacketReceiveEvent.class);
        when(event.getPacketType()).thenReturn(PacketType.Play.Client.PONG);
        check.onPacketReceive(event);
        verify(check, never()).flagAndAlert(anyString());
        assertNull(pendingTeleport);
    }

    @Test
    void elytraStallDescendsEvenWithoutGroundWithinRange() {
        player.isGliding = true;
        player.y = 100;
        Runnable reset = mock(Runnable.class);
        check.correctElytraStall(1000, () -> true, reset);
        assertNotNull(pendingTeleport);
        verify(reset, never()).run();
        pendingTeleport.run();
        ArgumentCaptor<Location> destination = ArgumentCaptor.forClass(Location.class);
        verify(platform).teleportAsync(destination.capture());
        assertTrue(destination.getValue().getY() < 100);
        assertTrue(destination.getValue().getY() > 80);
        var order = inOrder(platform, reset);
        order.verify(platform).resetFallDistance();
        order.verify(reset).run();
        order.verify(platform).teleportAsync(any());
    }

    @Test
    void elytraStallStopsAtFloorAndRevalidatesBeforeReset() {
        block(8, 0, 8, "stone");
        Runnable reset = mock(Runnable.class);
        check.correctElytraStall(1000, () -> true, reset);
        assertNotNull(pendingTeleport);
        when(platform.isDead()).thenReturn(true);
        pendingTeleport.run();
        verify(reset, never()).run();
        verify(platform, never()).resetFallDistance();
        when(platform.isDead()).thenReturn(false);
        pendingTeleport.run();
        ArgumentCaptor<Location> destination = ArgumentCaptor.forClass(Location.class);
        verify(platform).teleportAsync(destination.capture());
        assertEquals(1, destination.getValue().getY());
    }

    @Test
    void groundSupportUsesCollisionShapeRatherThanGroundClaim() {
        player.onGround = player.lastOnGround = true;
        assertFalse(check.hasGroundSupport());
        block(8, 0, 8, "stone");
        assertFalse(check.hasGroundSupport());
        player.y = 1;
        assertTrue(check.hasGroundSupport());
    }

    @Test
    void elytraCorrectionRechecksPermissionBeforeTeleporting() {
        Runnable reset = mock(Runnable.class);
        java.util.concurrent.atomic.AtomicBoolean allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        check.correctElytraStall(1000, allowed::get, reset);
        assertNotNull(pendingTeleport);
        allowed.set(false);
        pendingTeleport.run();
        verify(reset, never()).run();
        verify(platform, never()).resetFallDistance();
        verify(platform, never()).teleportAsync(any());
    }

    @Test
    void elytraCorrectionChecksServerFlightBeforeItsTransactionIsAcknowledged() {
        Runnable reset = mock(Runnable.class);
        check.correctElytraStall(1000, () -> true, reset);
        assertNotNull(pendingTeleport);
        when(platform.isFlying()).thenReturn(true);
        assertFalse(player.isFlying);
        pendingTeleport.run();
        verify(reset, never()).run();
        verify(platform, never()).resetFallDistance();
        verify(platform, never()).teleportAsync(any());
    }
}
