package ac.grim.grimac.predictionengine;

import ac.grim.grimac.manager.LastInstanceManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.predictions.PredictionEngine;
import ac.grim.grimac.predictionengine.predictions.PredictionEngineDualState;
import ac.grim.grimac.predictionengine.predictions.PredictionEngineElytra;
import ac.grim.grimac.predictionengine.predictions.PredictionEngineLava;
import ac.grim.grimac.predictionengine.predictions.PredictionEngineNormal;
import ac.grim.grimac.predictionengine.predictions.PredictionEngineWater;
import ac.grim.grimac.predictionengine.predictions.PredictionEngineWaterLegacy;
import ac.grim.grimac.testutil.GrimPlayerFixture;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.latency.CompensatedFireworks;
import ac.grim.grimac.utils.math.TrigHandler;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FireworkPredictionTest {
    private final PredictionEngineElytra engine = new PredictionEngineElytra();
    private GrimPlayer player;

    @BeforeAll
    static void initializePacketEvents() {
        GrimPlayerFixture.initializePacketEvents();
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        player = GrimPlayerFixture.player();
        player.isGliding = player.wasGliding = true;
        player.stuckSpeedMultiplier = StuckSpeed.NONE;
        player.yaw = -90;
        player.lastYaw = -95;
        when(player.canGlide()).thenReturn(true);
        when(player.compensatedEntities.self.getAttributeValue(Attributes.GRAVITY)).thenReturn(0.08);
        GrimPlayerFixture.set(player, "lastInstanceManager", new LastInstanceManager(player));
        GrimPlayerFixture.set(player, "trigHandler", new TrigHandler(player));
        GrimPlayerFixture.set(player, "fireworks", new CompensatedFireworks(player));
        PointThreeEstimator pointThree = mock(PointThreeEstimator.class);
        when(pointThree.canPredictNextVerticalMovement()).thenReturn(true);
        GrimPlayerFixture.set(player, "pointThreeEstimator", pointThree);
        var uncertainty = new UncertaintyHandler(player);
        uncertainty.fireworkResidualCap = 0.05;
        GrimPlayerFixture.set(player, "uncertaintyHandler", uncertainty);
        var sneaking = mock(SneakingEstimator.class);
        when(sneaking.getSneakingPotentialHiddenVelocity()).thenReturn(new SimpleCollisionBox());
        when(player.checkManager.get(SneakingEstimator.class)).thenReturn(sneaking);
    }

    @Test
    void aTrackedRocketDoesNotIncreaseUnboostedMomentum() {
        player.clientVelocity = new Vector3dm(4, -0.15, 0);
        Vector3dm coasting = glide(player.clientVelocity.clone());
        player.fireworks.addNewFirework(1);
        List<VectorData> candidates = candidates(engine);

        assertEquals(0, predictionError(engine, candidates, coasting), 1e-9);
        assertTrue(predictionError(engine, candidates, coasting.clone().add(0.002, 0, 0)) > 0.001);
    }

    @Test
    void bothRocketDirectionsAndMixedPushesAreAcceptedDuringATurn() {
        player.clientVelocity = new Vector3dm(0.6, -0.1, 0);
        player.fireworks.addNewFirework(1);
        player.fireworks.addNewFirework(2);
        List<VectorData> candidates = candidates(engine);
        Vector3dm previous = ReachUtils.getLook(player, player.lastYaw, player.lastPitch);
        Vector3dm current = ReachUtils.getLook(player, player.yaw, player.pitch);

        assertEquals(0, predictionError(engine, candidates, glide(boost(previous))), 1e-9);
        assertEquals(0, predictionError(engine, candidates, glide(boost(current))), 1e-9);
        Vector3dm mixed = boost(previous);
        PredictionEngineElytra.applyFireworkBoost(mixed, current);
        assertEquals(0, predictionError(engine, candidates, glide(mixed)), 1e-9);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waterPredictionStillToleratesRocketDirectionUncertainty(boolean legacy) {
        if (legacy) when(player.getClientVersion()).thenReturn(ClientVersion.V_1_12_2);
        PredictionEngine water = legacy ? new PredictionEngineWaterLegacy() : new PredictionEngineWater();
        player.wasTouchingWater = true;
        player.clientVelocity = new Vector3dm(0.6, -0.1, 0);
        player.fireworks.addNewFirework(1);
        List<VectorData> candidates = candidates(water);

        Vector3dm previous = ReachUtils.getLook(player, player.lastYaw, player.lastPitch);
        Vector3dm current = ReachUtils.getLook(player, player.yaw, player.pitch);
        assertEquals(0, predictionError(water, candidates, boost(current).midpoint(boost(previous))), 1e-9);
    }

    @Test
    void lavaTravelUsesTheImpulseFromThePreviousRocketTick() {
        PredictionEngine lava = new PredictionEngineLava();
        player.wasTouchingLava = true;
        Vector3dm expected = prepareRocketBeforeTravel();
        assertEquals(0, predictionError(lava, candidates(lava), expected), 1e-9);
    }

    @Test
    void reachingALadderClampsTheHorizontalRocketImpulse() {
        PredictionEngine normal = new PredictionEngineNormal();
        player.isClimbing = true;
        Vector3dm expected = prepareRocketBeforeTravel().setX(0.15F);
        assertEquals(0, predictionError(normal, candidates(normal), expected), 1e-9);
    }

    @Test
    void stoppingGlideKeepsThePreviousRocketImpulse() {
        PredictionEngine dualState = new PredictionEngineDualState();
        Vector3dm expected = prepareRocketBeforeTravel();
        player.isGliding = false;
        when(player.canGlide()).thenReturn(false);
        assertEquals(0, predictionError(dualState, candidates(dualState), expected), 1e-9);
    }

    @Test
    void coastingIsAcceptedAroundRocketSpawnAndRemoval() {
        player.clientVelocity = new Vector3dm(0.6, -0.1, 0);
        Vector3dm coast = glide(player.clientVelocity.clone());
        Vector3dm boosted = glide(boost(ReachUtils.getLook(player, player.lastYaw, player.lastPitch)));
        assertEquals(0, predictionError(engine, candidates(engine), coast), 1e-9);

        player.fireworks.addNewFirework(1);
        assertEquals(0, predictionError(engine, candidates(engine), coast), 1e-9);
        assertEquals(0, predictionError(engine, candidates(engine), boosted), 1e-9);

        player.fireworks.removeFirework(1);
        assertEquals(0, predictionError(engine, candidates(engine), coast), 1e-9);
        assertEquals(0, predictionError(engine, candidates(engine), boosted), 1e-9);

        player.fireworks.onPredictionComplete(null);
        List<VectorData> expired = candidates(engine);
        assertFalse(expired.stream().anyMatch(VectorData::isFireworkBoost));
        assertEquals(0, predictionError(engine, expired, coast), 1e-9);
    }

    private Vector3dm prepareRocketBeforeTravel() {
        when(player.getClientVersion()).thenReturn(ClientVersion.V_1_21_11);
        when(player.supportsEndTickPreVia()).thenReturn(true);
        player.yaw = player.lastYaw = -90;
        player.lastPitch = -13;
        player.pitch = -17;
        Vector3dm look = ReachUtils.getLook(player, player.lastYaw, player.lastPitch);
        player.clientVelocity = look.clone().multiply(1.7).add(0, -0.03, 0);
        player.fireworks.addNewFirework(1);

        // FireworkRocketEntity ticks before this movement, while the player is still gliding.
        Vector3dm velocity = player.clientVelocity;
        return velocity.clone().add(look.clone().multiply(0.1))
                .add(look.clone().multiply(1.5).subtract(velocity).multiply(0.5));
    }

    private List<VectorData> candidates(PredictionEngine predictor) {
        player.uncertaintyHandler.tickFireworksBox();
        return predictor.applyInputsToVelocityPossibilities(player,
                Set.of(new VectorData(player.clientVelocity.clone(), VectorData.VectorType.Normal)), 0);
    }

    private double predictionError(PredictionEngine predictor, List<VectorData> candidates, Vector3dm movement) {
        player.actualMovement = movement;
        return candidates.stream().mapToDouble(candidate ->
                predictor.handleStartingVelocityUncertainty(player, candidate, movement).distance(movement)).min().orElseThrow();
    }

    private Vector3dm boost(Vector3dm look) {
        Vector3dm velocity = player.clientVelocity.clone();
        PredictionEngineElytra.applyFireworkBoost(velocity, look);
        return velocity;
    }

    private Vector3dm glide(Vector3dm velocity) {
        return PredictionEngineElytra.getElytraMovement(player, velocity, ReachUtils.getLook(player, player.yaw, player.pitch))
                .multiply(0.99F, 0.98F, 0.99F);
    }
}
