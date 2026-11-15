package ac.grim.grimac.events.packets;

import ac.grim.grimac.checks.impl.combat.Reach;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.PointThreeEstimator;
import ac.grim.grimac.predictionengine.blockeffects.CompensatedGeysers;
import ac.grim.grimac.testutil.GrimPlayerFixture;
import ac.grim.grimac.utils.chunks.Column;
import ac.grim.grimac.utils.change.PlayerBlockHistory;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.latency.LatencyUtils;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import ac.grim.grimac.utils.nmsutil.BlockBreakSpeed;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v1_16.Chunk_v1_9;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Method;
import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlockBreakPredictionTest {
    private static final Vector3i TARGET = new Vector3i(0, 1, 0);
    private static final Vector3i NEIGHBOR = new Vector3i(0, 1, 1);
    private GrimPlayer player;
    private CompensatedWorld world;
    private double blockDamage;
    private int sequence;
    private ClientVersion clientVersion = ClientVersion.V_1_21_4;

    @BeforeAll
    static void initialize() {
        GrimPlayerFixture.initializePacketEvents();
    }

    @BeforeEach
    void setup() throws ReflectiveOperationException {
        player = GrimPlayerFixture.player();
        Reach reach = mock(Reach.class);
        when(player.checkManager.get(Reach.class)).thenReturn(reach);
        GrimPlayerFixture.set(player, "pointThreeEstimator", mock(PointThreeEstimator.class));
        GrimPlayerFixture.set(player, "compensatedGeysers", mock(CompensatedGeysers.class));
        GrimPlayerFixture.set(player, "queuedBreaks", new ArrayDeque<>());
        GrimPlayerFixture.set(player, "blockHistory", new PlayerBlockHistory());
        GrimPlayerFixture.set(player, "latencyUtils", new LatencyUtils(player));
        player.boundingBox = new SimpleCollisionBox(-3, 0, 0, -2.4, 1.8, .6);
        world = new CompensatedWorld(player);
        GrimPlayerFixture.set(player, "compensatedWorld", world);
        BaseChunk section = new Chunk_v1_9(0, DataPalette.createForChunk());
        world.chunks.put(0L, new Column(0, 0, new BaseChunk[]{section}, new long[1], 0));
        world.updateBlock(TARGET, WrappedBlockState.getDefaultState(ClientVersion.V_1_21_4, StateTypes.STONE));
        world.updateBlock(NEIGHBOR, WrappedBlockState.getDefaultState(ClientVersion.V_1_21_4, StateTypes.STONE));
    }

    @Test
    void twoCancelsAndFinishWithoutStartKeepTheWallSolid() throws ReflectiveOperationException {
        dig(DiggingAction.CANCELLED_DIGGING, NEIGHBOR);
        dig(DiggingAction.CANCELLED_DIGGING, TARGET);
        dig(DiggingAction.FINISHED_DIGGING, TARGET);
        assertEquals(StateTypes.STONE, world.getBlock(TARGET).getType());
    }

    @Test
    void ordinaryFinishPredictsTheBlockBreaking() throws ReflectiveOperationException {
        dig(DiggingAction.START_DIGGING, TARGET);
        dig(DiggingAction.FINISHED_DIGGING, TARGET);
        assertEquals(StateTypes.AIR, world.getBlock(TARGET).getType());
    }

    @Test
    void cancelAndResumeTheSameStartedBlockStillPredictsBreaking() throws ReflectiveOperationException {
        dig(DiggingAction.START_DIGGING, TARGET);
        dig(DiggingAction.CANCELLED_DIGGING, TARGET);
        dig(DiggingAction.FINISHED_DIGGING, TARGET);
        assertEquals(StateTypes.AIR, world.getBlock(TARGET).getType());
    }

    @Test
    void startingAnotherBlockDoesNotPermitFinishingThePreviousOne() throws ReflectiveOperationException {
        dig(DiggingAction.START_DIGGING, TARGET);
        dig(DiggingAction.START_DIGGING, NEIGHBOR);
        dig(DiggingAction.FINISHED_DIGGING, TARGET);
        assertEquals(StateTypes.STONE, world.getBlock(TARGET).getType());
    }

    @Test
    void cancelledStartDoesNotPermitAFalsePrediction() throws ReflectiveOperationException {
        doAnswer(call -> {
            ac.grim.grimac.utils.anticheat.update.BlockBreak block = call.getArgument(0);
            if (block.action == DiggingAction.START_DIGGING) block.cancel();
            return null;
        }).when(player.checkManager).onBlockBreak(any());
        dig(DiggingAction.START_DIGGING, TARGET);
        dig(DiggingAction.FINISHED_DIGGING, TARGET);
        assertEquals(StateTypes.STONE, world.getBlock(TARGET).getType());
    }

    @Test
    void worldResetDoesNotReuseThePreviousMiningTarget() throws ReflectiveOperationException {
        dig(DiggingAction.START_DIGGING, TARGET);
        world.clearPredictions();
        dig(DiggingAction.FINISHED_DIGGING, TARGET);
        assertEquals(StateTypes.STONE, world.getBlock(TARGET).getType());
    }

    @Test
    void instantlyBreakingAnotherBlockKeepsTheResumableTarget() throws ReflectiveOperationException {
        dig(DiggingAction.START_DIGGING, TARGET);
        dig(DiggingAction.CANCELLED_DIGGING, TARGET);
        world.updateBlock(NEIGHBOR, WrappedBlockState.getDefaultState(ClientVersion.V_1_21_4, StateTypes.TORCH));
        blockDamage = 1;
        dig(DiggingAction.START_DIGGING, NEIGHBOR);
        assertEquals(StateTypes.AIR, world.getBlock(NEIGHBOR).getType());
        blockDamage = 0;
        dig(DiggingAction.FINISHED_DIGGING, TARGET);
        assertEquals(StateTypes.AIR, world.getBlock(TARGET).getType());
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {
            "V_1_8", "V_1_14_4", "V_1_17", "V_1_18_2", "V_1_19", "V_1_21_4", "V_1_21_11", "V_26_2"
    })
    void cancelledMiningResumesAcrossClientVersions(ClientVersion version) throws ReflectiveOperationException {
        clientVersion = version;
        when(player.getClientVersion()).thenReturn(version);
        cancelAndResumeTheSameStartedBlockStillPredictsBreaking();
    }

    @Test
    void delayedSuccessAcknowledgementDoesNotClearTheNextTarget() throws ReflectiveOperationException {
        dig(DiggingAction.START_DIGGING, TARGET);
        dig(DiggingAction.FINISHED_DIGGING, TARGET);
        int confirmation = sequence;
        dig(DiggingAction.START_DIGGING, NEIGHBOR);
        world.updateBlock(TARGET, 0);
        world.handlePredictionConfirmation(confirmation);
        dig(DiggingAction.FINISHED_DIGGING, NEIGHBOR);
        assertEquals(StateTypes.AIR, world.getBlock(NEIGHBOR).getType());
    }

    @Test
    void delayedRejectionAcknowledgementDoesNotClearTheNextTarget() throws ReflectiveOperationException {
        dig(DiggingAction.START_DIGGING, TARGET);
        dig(DiggingAction.FINISHED_DIGGING, TARGET);
        int confirmation = sequence;
        dig(DiggingAction.START_DIGGING, NEIGHBOR);
        world.handlePredictionConfirmation(confirmation);
        assertEquals(StateTypes.STONE, world.getBlock(TARGET).getType());
        dig(DiggingAction.FINISHED_DIGGING, NEIGHBOR);
        assertEquals(StateTypes.AIR, world.getBlock(NEIGHBOR).getType());
    }

    private void dig(DiggingAction action, Vector3i pos) throws ReflectiveOperationException {
        var buffer = Unpooled.buffer();
        try (var speed = mockStatic(BlockBreakSpeed.class)) {
            speed.when(() -> BlockBreakSpeed.getBlockDamage(eq(player), any())).thenReturn(blockDamage);
            var wrapper = new WrapperPlayClientPlayerDigging(action, pos, BlockFace.DOWN,
                    action == DiggingAction.CANCELLED_DIGGING ? 0 : ++sequence);
            wrapper.setClientVersion(clientVersion);
            wrapper.setServerVersion(ServerVersion.V_1_21_4);
            wrapper.setBuffer(buffer);
            wrapper.write();
            User user = mock(User.class);
            when(user.getClientVersion()).thenReturn(clientVersion);
            PacketReceiveEvent event = new PacketReceiveEvent(PacketType.Play.Client.PLAYER_DIGGING.getId(ClientVersion.V_1_21_4),
                    PacketType.Play.Client.PLAYER_DIGGING, ServerVersion.V_1_21_4, null, user, null, buffer) {};
            Method method = CheckManagerListener.class.getDeclaredMethod("handleDigging", GrimPlayer.class, PacketReceiveEvent.class);
            method.setAccessible(true);
            method.invoke(null, player, event);
        } finally {
            buffer.release();
        }
    }
}
