package ac.grim.grimac.predictionengine;

import ac.grim.grimac.checks.impl.movement.NoSlow;
import ac.grim.grimac.checks.impl.velocity.ExplosionHandler;
import ac.grim.grimac.checks.impl.velocity.KnockbackHandler;
import ac.grim.grimac.manager.LastInstanceManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.predictions.PredictionEngine;
import ac.grim.grimac.predictionengine.predictions.PredictionEngineDualState;
import ac.grim.grimac.predictionengine.predictions.PredictionEngineNormal;
import ac.grim.grimac.testutil.GrimPlayerFixture;
import ac.grim.grimac.utils.chunks.Column;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.MainSupportingBlockData;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.data.VehicleData;
import ac.grim.grimac.utils.data.tags.SyncedTags;
import ac.grim.grimac.utils.latency.CompensatedFireworks;
import ac.grim.grimac.utils.math.TrigHandler;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PredictionEngineTest {
    private static final double EPSILON = 1e-9;
    private GrimPlayer player;
    private KnockbackHandler knockback;
    private ExplosionHandler explosion;
    private NoSlow noSlow;

    @BeforeAll
    static void initializePacketEvents() {
        GrimPlayerFixture.initializePacketEvents();
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        player = GrimPlayerFixture.player();
        when(player.getClientVersion()).thenReturn(ClientVersion.V_1_21_11);
        when(player.supportsEndTickPreVia()).thenReturn(true);
        when(player.getPossibleVelocities()).thenCallRealMethod();
        when(player.getPossibleVelocitiesMinusKnockback()).thenCallRealMethod();
        when(player.compensatedEntities.self.getAttributeValue(Attributes.GRAVITY)).thenReturn(0.08);

        GrimPlayerFixture.set(player, "lastInstanceManager", new LastInstanceManager(player));
        GrimPlayerFixture.set(player, "trigHandler", new TrigHandler(player));
        GrimPlayerFixture.set(player, "fireworks", new CompensatedFireworks(player));
        GrimPlayerFixture.set(player, "tagManager", mock(SyncedTags.class, RETURNS_DEEP_STUBS));
        GrimPlayerFixture.set(player, "vehicleData", new VehicleData());
        GrimPlayerFixture.set(player, "baseTickWaterPushing", new Vector3dm());
        GrimPlayerFixture.set(player, "baseTickAddition", new Vector3dm());
        GrimPlayerFixture.set(player, "fluidHeight", new Object2DoubleOpenHashMap<>());
        GrimPlayerFixture.set(player, "movementThisTick", new ArrayDeque<>());
        GrimPlayerFixture.set(player, "finalMovementsThisTick", new ArrayList<>());
        PointThreeEstimator pointThree = mock(PointThreeEstimator.class);
        when(pointThree.canPredictNextVerticalMovement()).thenReturn(true);
        GrimPlayerFixture.set(player, "pointThreeEstimator", pointThree);
        GrimPlayerFixture.set(player, "uncertaintyHandler", new UncertaintyHandler(player));

        knockback = mock(KnockbackHandler.class);
        explosion = mock(ExplosionHandler.class);
        noSlow = mock(NoSlow.class);
        when(player.checkManager.getKnockbackHandler()).thenReturn(knockback);
        when(player.checkManager.getExplosionHandler()).thenReturn(explosion);
        when(player.checkManager.getNoSlow()).thenReturn(noSlow);
        SneakingEstimator sneaking = mock(SneakingEstimator.class);
        when(sneaking.getSneakingPotentialHiddenVelocity()).thenReturn(new SimpleCollisionBox());
        when(player.checkManager.get(SneakingEstimator.class)).thenReturn(sneaking);

        WrappedBlockState air = WrappedBlockState.getDefaultState(ClientVersion.V_1_21_11, StateTypes.AIR);
        when(player.compensatedWorld.getBlock(anyInt(), anyInt(), anyInt())).thenReturn(air);
        when(player.compensatedWorld.getBlock(anyDouble(), anyDouble(), anyDouble())).thenReturn(air);
        when(player.compensatedWorld.getBlockType(anyDouble(), anyDouble(), anyDouble())).thenReturn(StateTypes.AIR);
        BaseChunk[] sections = new BaseChunk[24];
        when(player.compensatedWorld.getChunk(anyInt(), anyInt())).thenAnswer(call ->
                new Column(call.getArgument(0), call.getArgument(1), sections, new long[sections.length], 0));

        player.y = player.lastY = 64;
        player.yaw = player.lastYaw = -90;
        player.friction = 0.91F;
        player.stuckSpeedMultiplier = StuckSpeed.NONE;
        player.mainSupportingBlockData = MainSupportingBlockData.AIR_OFF_GROUND;
        player.boundingBox = new SimpleCollisionBox(-0.3, 64, -0.3, 0.3, 65.8, 0.3);
        player.clientVelocity = new Vector3dm(0.4, 0, 0);
    }

    @ParameterizedTest
    @CsvSource({
            "0.24, 0.025, 0.215",
            "1.20, 0.025, 1.175",
            "0.024, 0.025, 0",
            "0.24, 0, 0.24",
            "0, 0.025, 0"
    })
    void movementChecksReceiveLinearResidual(double distance, double allowance, double expected) throws ReflectiveOperationException {
        UncertaintyHandler uncertainty = spy(player.uncertaintyHandler);
        doAnswer(call -> Math.max(0, call.<Double>getArgument(0) - allowance)).when(uncertainty).reduceOffset(anyDouble());
        GrimPlayerFixture.set(player, "uncertaintyHandler", uncertainty);
        player.packetStateData.setSlowedByUsingItem(true);

        VectorData candidate = new VectorData(new Vector3dm(0.4, 0, 0), VectorData.VectorType.Knockback)
                .returnNewModified(VectorData.VectorType.Explosion);
        PredictionEngine engine = new PredictionEngine() {
            @Override
            public List<VectorData> applyInputsToVelocityPossibilities(GrimPlayer player, Set<VectorData> startingVelocities, float speed) {
                return new ArrayList<>(List.of(candidate));
            }
        };
        assertEquals(distance, predict(engine, new Vector3dm(0.4 + distance, 0, 0)), EPSILON);

        ArgumentCaptor<Double> residual = ArgumentCaptor.forClass(Double.class);
        verify(knockback).handlePredictionAnalysis(residual.capture());
        verify(explosion).handlePredictionAnalysis(residual.capture());
        verify(noSlow).handlePredictionAnalysis(residual.capture());
        for (double offset : residual.getAllValues()) {
            assertEquals(expected, offset, EPSILON);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_21_4", "V_1_21_11"})
    void endingGlidePreservesVanillaMomentumAcrossFollowingAirTicks(ClientVersion version) {
        when(player.getClientVersion()).thenReturn(version);
        Vector3dm velocity = player.clientVelocity.clone();
        for (int tick = 0; tick < 12; tick++) {
            velocity = levelGlide(rocketImpulse(velocity));
        }
        for (int tick = 0; tick < 3; tick++) {
            velocity = levelGlide(velocity);
        }
        player.clientVelocity = velocity;
        player.wasGliding = true;
        player.uncertaintyHandler.lastGlidingChange.reset();

        Vector3dm expected = levelGlide(velocity);
        assertEquals(0, predict(new PredictionEngineDualState(), expected), EPSILON);
        assertEquals(0, player.clientVelocity.distance(expected), EPSILON);

        player.wasGliding = false;
        for (int tick = 1; tick <= 7; tick++) {
            player.lastInstanceManager.onPredictionComplete(null);
            player.uncertaintyHandler.tick();
            PredictionEngine engine = tick <= 4 ? new PredictionEngineDualState() : new PredictionEngineNormal();
            assertEquals(0, predict(engine, expected), EPSILON, "Tick " + tick);
            expected = normalAirEndOfTick(expected);
            assertEquals(0, player.clientVelocity.distance(expected), EPSILON, "Tick " + tick);
        }
    }

    @Test
    void lastGlidingTickWithActiveRocketCarriesGlideVelocity() {
        player.wasGliding = true;
        player.uncertaintyHandler.lastGlidingChange.reset();
        player.fireworks.addNewFirework(1);
        player.uncertaintyHandler.tickFireworksBox();

        Vector3dm expected = levelGlide(rocketImpulse(player.clientVelocity));
        assertEquals(0, predict(new PredictionEngineDualState(), expected), EPSILON);
        assertEquals(0, player.clientVelocity.distance(expected), EPSILON);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void winningNormalBranchStillAppliesGravityAndDrag(boolean activeRocket) {
        player.wasGliding = true;
        player.uncertaintyHandler.lastGlidingChange.reset();
        Vector3dm expected = player.clientVelocity.clone();
        if (activeRocket) {
            player.fireworks.addNewFirework(1);
            player.uncertaintyHandler.tickFireworksBox();
            expected = rocketImpulse(expected);
        }

        assertEquals(0, predict(new PredictionEngineDualState(), expected), EPSILON);
        assertEquals(0, player.clientVelocity.distance(normalAirEndOfTick(expected)), EPSILON);
    }

    private double predict(PredictionEngine engine, Vector3dm movement) {
        player.lastX = player.x;
        player.lastY = player.y;
        player.lastZ = player.z;
        player.x += movement.getX();
        player.y += movement.getY();
        player.z += movement.getZ();
        player.actualMovement = movement.clone();
        engine.guessBestMovement(0.02F, player);
        return player.predictedVelocity.vector.distance(player.actualMovement);
    }

    private static Vector3dm rocketImpulse(Vector3dm velocity) {
        return velocity.clone().multiply(0.5).add(0.85, 0, 0);
    }

    // Vanilla 1.21.11 glide physics for zero pitch and movement aligned with the look direction.
    private static Vector3dm levelGlide(Vector3dm velocity) {
        double fallingY = velocity.getY() - 0.02;
        double lift = Math.max(0, -0.1 * fallingY);
        return new Vector3dm((velocity.getX() + 0.9 * lift) * 0.99F, (fallingY + lift) * 0.98F, 0);
    }

    private static Vector3dm normalAirEndOfTick(Vector3dm velocity) {
        return velocity.clone().add(0, -0.08, 0).multiply(0.91F, 0.98F, 0.91F);
    }
}
