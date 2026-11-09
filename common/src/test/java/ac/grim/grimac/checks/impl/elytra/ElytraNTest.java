package ac.grim.grimac.checks.impl.elytra;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.impl.movement.AirStuck;
import ac.grim.grimac.events.packets.PacketEntityAction;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.testutil.GrimPlayerFixture;
import ac.grim.grimac.utils.anticheat.PlayerDataManager;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.OptionalInt;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElytraNTest {
    private GrimPlayer player;
    private ElytraN check;
    private AirStuck airStuck;
    private Runnable fallDistanceReset;

    @BeforeAll
    static void initialize() {
        GrimPlayerFixture.initializePacketEvents();
    }

    @BeforeEach
    void setup() throws ReflectiveOperationException {
        player = GrimPlayerFixture.player();
        player.x = player.z = 8.5;
        player.y = 40;
        player.stuckSpeedMultiplier = StuckSpeed.NONE;
        check = mock(ElytraN.class, CALLS_REAL_METHODS);
        GrimPlayerFixture.set(check, "player", player);
        GrimPlayerFixture.set(check, "lastMovementTime", System.currentTimeMillis() - 2000);
        GrimPlayerFixture.set(check, "anchor", new Vector3d(player.x, player.y, player.z));
        doReturn(true).when(check).flagAndAlert(anyString());
        doReturn(true).when(check).shouldModifyPackets();
        doReturn(true).when(check).shouldSetback();
        airStuck = mock(AirStuck.class);
        when(player.checkManager.get(AirStuck.class)).thenReturn(airStuck);
        doAnswer(call -> {
            fallDistanceReset = call.getArgument(2);
            return null;
        }).when(airStuck).correctElytraStall(anyLong(), any(), any());
    }

    private PacketReceiveEvent event(PacketType.Play.Client type) {
        PacketReceiveEvent event = mock(PacketReceiveEvent.class);
        when(event.getPacketType()).thenReturn(type);
        User user = mock(User.class);
        when(user.getClientVersion()).thenReturn(ClientVersion.V_1_21_4);
        when(event.getUser()).thenReturn(user);
        when(event.getServerVersion()).thenReturn(ServerVersion.V_1_21_4);
        return event;
    }

    private PacketReceiveEvent event(PacketType.Play.Client type, PacketWrapper<?> wrapper) {
        PacketReceiveEvent event = event(type);
        doReturn(wrapper).when(event).getLastUsedWrapper();
        return event;
    }

    private PacketReceiveEvent move(double x, double y, double z) {
        return event(PacketType.Play.Client.PLAYER_POSITION,
                new WrapperPlayClientPlayerFlying(true, false, false, new Location(x, y, z, 0, 0)));
    }

    private boolean activate(PacketReceiveEvent event) {
        try {
            GrimPlayerFixture.set(check, "lastActivationTime", System.currentTimeMillis() - 100);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return check.onStartGliding(event);
    }

    private void stall() {
        for (int i = 0; i < 3; i++) activate(event(PacketType.Play.Client.ENTITY_ACTION));
    }

    @Test
    void stopsRepeatedGlidingAndAttacksEvenWhenGlidingFlagIsSet() {
        player.isGliding = true;
        for (int i = 0; i < 3; i++) {
            check.onPacketReceive(event(PacketType.Play.Client.PLAYER_DIGGING));
            check.onPacketReceive(event(PacketType.Play.Client.USE_ITEM));
            PacketReceiveEvent activation = event(PacketType.Play.Client.ENTITY_ACTION);
            assertEquals(i == 2, activate(activation));
            if (i == 2) verify(activation).setCancelled(true);
            check.onPacketReceive(event(PacketType.Play.Client.PLAYER_DIGGING));
        }
        assertNotNull(fallDistanceReset);
        PacketReceiveEvent attack = event(PacketType.Play.Client.ATTACK);
        check.onPacketReceive(attack);
        verify(attack).setCancelled(true);
        verify(player, never()).onPacketCancel();
    }

    @Test
    void repeatedIdenticalPositionsAndSmallJitterDoNotResetDetection() {
        for (int i = 0; i < 3; i++) {
            check.onPacketReceive(move(player.x + i * 0.001, player.y, player.z));
            activate(event(PacketType.Play.Client.ENTITY_ACTION));
        }
        assertNotNull(fallDistanceReset);
    }

    @Test
    void normalFlightAndMaceFallsRemainAllowed() {
        for (int i = 0; i < 20; i++) {
            check.onPacketReceive(move(player.x + i, player.y - i, player.z));
            assertFalse(activate(event(PacketType.Play.Client.ENTITY_ACTION)));
        }
        PacketReceiveEvent attack = event(PacketType.Play.Client.ATTACK);
        check.onPacketReceive(attack);
        verify(attack, never()).setCancelled(true);
        verify(check, never()).flagAndAlert(anyString());
    }

    @Test
    void singleStationaryActivationIsNotEnough() {
        assertFalse(check.onStartGliding(event(PacketType.Play.Client.ENTITY_ACTION)));
        PacketReceiveEvent attack = event(PacketType.Play.Client.ATTACK);
        check.onPacketReceive(attack);
        verify(attack, never()).setCancelled(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"flight", "vehicle", "water", "lava", "climbing", "gravity", "slowFalling", "levitation", "ground", "teleport", "web"})
    void respectsLegitimateStasisAndPendingTeleports(String exemption) {
        switch (exemption) {
            case "flight" -> player.isFlying = true;
            case "vehicle" -> when(player.inVehicle()).thenReturn(true);
            case "water" -> player.wasTouchingWater = true;
            case "lava" -> player.wasTouchingLava = true;
            case "climbing" -> player.isClimbing = true;
            case "gravity" -> player.hasGravity = false;
            case "web" -> player.stuckSpeedMultiplier = StuckSpeed.COBWEB;
            case "slowFalling" -> when(player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.SLOW_FALLING)).thenReturn(OptionalInt.of(0));
            case "levitation" -> when(player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.LEVITATION)).thenReturn(OptionalInt.of(0));
            case "ground" -> {
                player.onGround = true;
                when(airStuck.hasGroundSupport()).thenReturn(true);
            }
            case "teleport" -> when(player.getSetbackTeleportUtil().shouldBlockMovement()).thenReturn(true);
            default -> throw new AssertionError(exemption);
        }
        stall();
        verify(check, never()).flagAndAlert(anyString());
        assertNull(fallDistanceReset);
    }

    @Test
    void samePositionResyncDoesNotHideRepeatedActivations() {
        for (int i = 0; i < 3; i++) {
            player.packetStateData.lastPacketWasTeleport = true;
            check.onPacketReceive(move(player.x, player.y, player.z));
            activate(event(PacketType.Play.Client.ENTITY_ACTION));
        }
        assertNotNull(fallDistanceReset);
    }

    @Test
    void movementAndUnrelatedResyncDoNotReleaseBankedMaceDamage() {
        stall();
        check.onPacketReceive(move(player.x + 1, player.y - 1, player.z));
        player.packetStateData.lastPacketWasTeleport = true;
        check.onPacketReceive(move(player.x, player.y, player.z));
        PacketReceiveEvent attack = event(PacketType.Play.Client.ATTACK);
        check.onPacketReceive(attack);
        verify(attack).setCancelled(true);
    }

    @Test
    void releasesHitsOnlyAfterFallDistanceResetAndTeleportAcknowledgement() {
        stall();
        fallDistanceReset.run();
        PacketReceiveEvent beforeAcknowledgement = event(PacketType.Play.Client.ATTACK);
        check.onPacketReceive(beforeAcknowledgement);
        verify(beforeAcknowledgement).setCancelled(true);
        player.packetStateData.lastPacketWasTeleport = true;
        check.onPacketReceive(move(player.x, player.y, player.z));
        PacketReceiveEvent attack = event(PacketType.Play.Client.ATTACK);
        check.onPacketReceive(attack);
        verify(attack, never()).setCancelled(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void requiresRealGroundSupportToReleaseHits(boolean supported) {
        stall();
        player.onGround = player.lastOnGround = true;
        when(airStuck.hasGroundSupport()).thenReturn(supported);
        check.onPacketReceive(move(player.x, player.y, player.z));
        PacketReceiveEvent attack = event(PacketType.Play.Client.ATTACK);
        check.onPacketReceive(attack);
        verify(attack, times(supported ? 0 : 1)).setCancelled(true);
    }

    @Test
    void supportedGroundClaimDoesNotReleaseBankedFallDistance() {
        stall();
        player.onGround = player.lastOnGround = true;
        player.fallDistance = 100;
        when(airStuck.hasGroundSupport()).thenReturn(true);
        check.onPacketReceive(move(player.x, player.y, player.z));
        PacketReceiveEvent attack = event(PacketType.Play.Client.ATTACK);
        check.onPacketReceive(attack);
        verify(attack).setCancelled(true);
        assertTrue(activate(event(PacketType.Play.Client.ENTITY_ACTION)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INTERACT", "INTERACT_AT", "ATTACK"})
    void blocksLegacyAttacksButAllowsOtherInteractions(String action) {
        stall();
        var interaction = WrapperPlayClientInteractEntity.InteractAction.valueOf(action);
        PacketReceiveEvent event = event(PacketType.Play.Client.INTERACT_ENTITY,
                new WrapperPlayClientInteractEntity(5, interaction, Vector3d.zero(), InteractionHand.MAIN_HAND, false));
        check.onPacketReceive(event);
        verify(event, times(action.equals("ATTACK") ? 1 : 0)).setCancelled(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"flag", "modify", "setback"})
    void honorsFlagPacketModificationAndSetbackGates(String gate) {
        switch (gate) {
            case "flag" -> doReturn(false).when(check).flagAndAlert(anyString());
            case "modify" -> doReturn(false).when(check).shouldModifyPackets();
            case "setback" -> doReturn(false).when(check).shouldSetback();
            default -> throw new AssertionError(gate);
        }
        stall();
        PacketReceiveEvent attack = event(PacketType.Play.Client.ATTACK);
        check.onPacketReceive(attack);
        verify(attack, times(gate.equals("setback") ? 1 : 0)).setCancelled(true);
        verify(airStuck, never()).correctElytraStall(anyLong(), any(), any());
    }

    @Test
    void delayedPacketBurstIsNotCountedAsSustainedReactivation() {
        for (int i = 0; i < 3; i++) assertFalse(check.onStartGliding(event(PacketType.Play.Client.ENTITY_ACTION)));
        verify(check, never()).flagAndAlert(anyString());
    }

    @Test
    void passivePacketsDoNotAddViolationsOrSpamCorrections() {
        stall();
        for (int i = 0; i < 50; i++) check.onPacketReceive(event(PacketType.Play.Client.PONG));
        verify(check, times(1)).flagAndAlert(anyString());
        verify(airStuck, times(1)).correctElytraStall(anyLong(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"flight", "vehicle", "water", "lava", "climbing", "gravity", "zeroGravity",
            "bed", "slowFalling", "levitation", "web"})
    void newExemptionStopsQueuedCorrectionAndReleasesHitLock(String exemption) {
        stall();
        ArgumentCaptor<BooleanSupplier> gate = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(airStuck).correctElytraStall(anyLong(), gate.capture(), any());
        assertTrue(gate.getValue().getAsBoolean());
        switch (exemption) {
            case "flight" -> player.isFlying = true;
            case "vehicle" -> when(player.inVehicle()).thenReturn(true);
            case "water" -> player.wasTouchingWater = true;
            case "lava" -> player.wasTouchingLava = true;
            case "climbing" -> player.isClimbing = true;
            case "gravity" -> player.hasGravity = false;
            case "zeroGravity" -> player.gravity = 0;
            case "bed" -> player.isInBed = true;
            case "web" -> player.stuckSpeedMultiplier = StuckSpeed.COBWEB;
            case "slowFalling" -> when(player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.SLOW_FALLING)).thenReturn(OptionalInt.of(0));
            case "levitation" -> when(player.compensatedEntities.getPotionLevelForPlayer(PotionTypes.LEVITATION)).thenReturn(OptionalInt.of(0));
            default -> throw new AssertionError(exemption);
        }
        assertFalse(gate.getValue().getAsBoolean());
        PacketReceiveEvent attack = event(PacketType.Play.Client.ATTACK);
        check.onPacketReceive(attack);
        verify(attack, never()).setCancelled(true);
        assertFalse(activate(event(PacketType.Play.Client.ENTITY_ACTION)));
        verify(check, times(1)).flagAndAlert(anyString());
        verify(airStuck, times(1)).correctElytraStall(anyLong(), any(), any());
    }

    @Test
    void oldCorrectionCannotResumeAfterGrantedFlightEnds() {
        stall();
        ArgumentCaptor<BooleanSupplier> gate = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(airStuck).correctElytraStall(anyLong(), gate.capture(), any());
        player.isFlying = true;
        check.onPacketReceive(event(PacketType.Play.Client.PONG));
        player.isFlying = false;
        assertFalse(gate.getValue().getAsBoolean());
        assertFalse(activate(event(PacketType.Play.Client.ENTITY_ACTION)));
        PacketReceiveEvent attack = event(PacketType.Play.Client.ATTACK);
        check.onPacketReceive(attack);
        verify(attack, never()).setCancelled(true);
    }

    @Test
    void actionListenerAppliesStallGateBeforeChangingGlidingState() throws ReflectiveOperationException {
        stall();
        when(player.checkManager.get(ElytraN.class)).thenReturn(check);
        PacketReceiveEvent action = event(PacketType.Play.Client.ENTITY_ACTION,
                new WrapperPlayClientEntityAction(1, WrapperPlayClientEntityAction.Action.START_FLYING_WITH_ELYTRA, 0));
        PlayerDataManager original = GrimAPI.INSTANCE.getPlayerDataManager();
        PlayerDataManager players = mock(PlayerDataManager.class);
        when(players.getPlayer(action.getUser())).thenReturn(player);
        GrimPlayerFixture.set(GrimAPI.INSTANCE, "playerDataManager", players);
        try {
            new PacketEntityAction().onPacketReceive(action);
            verify(action).setCancelled(true);
            assertFalse(player.isGliding);
            verify(player, never()).canGlide();
            verify(player.getSetbackTeleportUtil(), never()).executeNonSimulatingForceResync();
        } finally {
            GrimPlayerFixture.set(GrimAPI.INSTANCE, "playerDataManager", original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"playerModify", "checkModify", "disabledGrim", "disabledCheck", "config", "exempt"})
    void permissionChangesAlsoDisableExistingHitLock(String gate) throws ReflectiveOperationException {
        stall();
        doCallRealMethod().when(check).shouldModifyPackets();
        GrimPlayerFixture.set(check, "configEnabled", true);
        GrimPlayerFixture.set(check, "isEnabled", true);
        assertTrue(check.shouldModifyPackets());
        switch (gate) {
            case "playerModify" -> player.noModifyPacketPermission = true;
            case "checkModify" -> GrimPlayerFixture.set(check, "noModifyPacketPermission", true);
            case "disabledGrim" -> player.disableGrim = true;
            case "disabledCheck" -> GrimPlayerFixture.set(check, "isEnabled", false);
            case "config" -> GrimPlayerFixture.set(check, "configEnabled", false);
            case "exempt" -> GrimPlayerFixture.set(check, "exemptPermission", true);
            default -> throw new AssertionError(gate);
        }
        assertFalse(check.shouldModifyPackets());
        PacketReceiveEvent attack = event(PacketType.Play.Client.ATTACK);
        check.onPacketReceive(attack);
        verify(attack, never()).setCancelled(true);
    }
}
