package ac.grim.grimac.predictionengine;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.predictions.PredictionEngine;
import ac.grim.grimac.testutil.GrimPlayerFixture;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.math.Vector3dm;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlockPushTest {
    private static final ClientVersion VERSION = ClientVersion.V_1_21_4;
    private final Map<Vector3i, WrappedBlockState> blocks = new HashMap<>();
    private GrimPlayer player;

    @BeforeAll
    static void initialize() {
        GrimPlayerFixture.initializePacketEvents();
    }

    @BeforeEach
    void setup() throws ReflectiveOperationException {
        player = GrimPlayerFixture.player();
        WrappedBlockState air = WrappedBlockState.getDefaultState(VERSION, StateTypes.AIR);
        when(player.compensatedWorld.getBlock(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                blocks.getOrDefault(new Vector3i(call.getArgument(0), call.getArgument(1), call.getArgument(2)), air));
        GrimPlayerFixture.set(player, "uncertaintyHandler", mock(UncertaintyHandler.class));
        player.packetStateData.didLastMovementIncludePosition = true;
    }

    private void block(int x, int y, int z, String state) {
        blocks.put(new Vector3i(x, y, z), WrappedBlockState.getByString(VERSION, "minecraft:" + state));
    }

    private void standAt(double x, double y, double z) {
        player.lastX = x;
        player.lastY = y;
        player.lastZ = z;
        player.boundingBox = new SimpleCollisionBox(x - .3, y, z - .3, x + .3, y + 1.8, z + .3, false);
    }

    @ParameterizedTest
    @CsvSource({
            "-41.676263332146824, 68.82742804234697, -722.9193902489849, -1, -1",
            "-41.49902, 68.83049, -722.65107, 1, -1",
            "-41.33773, 68.75509, -722.40690, 1, -1"
    })
    void lastCornerPerAxisWinsInVanillaOrder(double x, double y, double z, int pushX, int pushZ) {
        block(-42, 68, -723, "dirt");
        standAt(x, y, z);
        assertEquals(new BlockPush(pushX, pushZ), PlayerBaseTick.blockPushAt(player, x, z));
        assertEquals(Set.of(new BlockPush(pushX, pushZ)), PlayerBaseTick.possibleBlockPushes(player));
    }

    @Test
    void pushOverwritesTheAxisOfEveryCandidateIncludingKnockback() {
        player.uncertaintyHandler.blockPushes = Set.of(new BlockPush(-1, -1));
        VectorData normal = new VectorData(new Vector3dm(.180096, .003063, .272543), VectorData.VectorType.Normal);
        VectorData knockback = new VectorData(new Vector3dm(.16125, -.075375, .244125), VectorData.VectorType.Knockback);
        Set<VectorData> velocities = new HashSet<>(Set.of(normal, knockback));

        PredictionEngine.applyBlockPushes(player, velocities, false);

        assertEquals(2, velocities.size());
        for (VectorData data : velocities) {
            assertEquals(VectorData.VectorType.BlockPush, data.vectorType);
            assertEquals(-.1, data.vector.getX());
            assertEquals(-.1, data.vector.getZ());
        }
        assertTrue(velocities.stream().anyMatch(data -> data.isKnockback() && data.vector.getY() == -.075375));
        assertTrue(velocities.stream().anyMatch(data -> !data.isKnockback() && data.vector.getY() == .003063));
    }

    @Test
    void singleAxisPushLeavesTheOtherAxisAlone() {
        player.uncertaintyHandler.blockPushes = Set.of(new BlockPush(1, 0));
        Set<VectorData> velocities = new HashSet<>(Set.of(new VectorData(new Vector3dm(-.2, 0, .27), VectorData.VectorType.Normal)));

        PredictionEngine.applyBlockPushes(player, velocities, false);

        VectorData pushed = velocities.iterator().next();
        assertEquals(.1, pushed.vector.getX());
        assertEquals(.27, pushed.vector.getZ());
    }

    @Test
    void legacyClientsWriteTheFloatValue() {
        when(player.getClientVersion()).thenReturn(ClientVersion.V_1_8);
        player.uncertaintyHandler.blockPushes = Set.of(new BlockPush(0, 1));
        Set<VectorData> velocities = new HashSet<>(Set.of(new VectorData(new Vector3dm(0, 0, -.3), VectorData.VectorType.Normal)));

        PredictionEngine.applyBlockPushes(player, velocities, false);

        assertEquals((double) 0.1F, velocities.iterator().next().vector.getZ());
    }

    @Test
    void uncertainPushesKeepTheUnpushedCandidate() {
        player.uncertaintyHandler.blockPushes = Set.of(BlockPush.NONE, new BlockPush(-1, 0));
        VectorData normal = new VectorData(new Vector3dm(.25, 0, 0), VectorData.VectorType.Normal);
        Set<VectorData> velocities = new HashSet<>(Set.of(normal));

        PredictionEngine.applyBlockPushes(player, velocities, false);

        assertTrue(velocities.contains(normal));
        assertTrue(velocities.stream().anyMatch(data -> data.vector.getX() == -.1));
        assertEquals(2, velocities.size());
    }

    @Test
    void hiddenTicksKeepTheUnpushedCandidateEvenWhenThePushIsCertain() {
        player.uncertaintyHandler.blockPushes = Set.of(new BlockPush(0, -1));
        VectorData pointThree = new VectorData(new Vector3dm(), VectorData.VectorType.ZeroPointZeroThree);
        Set<VectorData> velocities = new HashSet<>(Set.of(pointThree));

        PredictionEngine.applyBlockPushes(player, velocities, true);

        assertTrue(velocities.contains(pointThree));
        assertTrue(velocities.stream().anyMatch(data -> data.vector.getZ() == -.1 && data.isZeroPointZeroThree()));
    }

    @Test
    void standingBesideABlockDoesNotPush() {
        block(-42, 68, -723, "dirt");
        standAt(-40.7, 68.8, -722.5);
        assertEquals(BlockPush.NONE, PlayerBaseTick.blockPushAt(player, -40.7, -722.5));
        assertTrue(PlayerBaseTick.possibleBlockPushes(player).isEmpty());
    }

    @Test
    void noSuffocatingNeighbourMeansNoPush() {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                block(x, 0, z, "stone");
            }
        }
        standAt(.5, .5, .5);
        assertTrue(PlayerBaseTick.possibleBlockPushes(player).isEmpty());
    }

    @Test
    void positionUncertaintyKeepsBothOutcomes() {
        block(0, 0, 0, "stone");
        standAt(-.22, .5, .5);
        assertTrue(PlayerBaseTick.possibleBlockPushes(player).isEmpty());

        when(player.canSkipTicks()).thenReturn(true);
        when(player.getMovementThreshold()).thenReturn(.03);
        Set<BlockPush> pushes = PlayerBaseTick.possibleBlockPushes(player);
        assertTrue(pushes.contains(BlockPush.NONE));
        assertTrue(pushes.contains(new BlockPush(-1, 0)));
    }
}
