package ac.grim.grimac.utils.chunks;

import ac.grim.grimac.checks.impl.combat.Reach;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.PointThreeEstimator;
import ac.grim.grimac.predictionengine.blockeffects.CompensatedGeysers;
import ac.grim.grimac.testutil.GrimPlayerFixture;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v1_16.Chunk_v1_9;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.GlobalPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.ListPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.SingletonPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BaseStorage;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BitStorage;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.LegacyFlexibleStorage;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaletteStorageTest {
    @BeforeAll
    static void initialize() {
        GrimPlayerFixture.initializePacketEvents();
    }

    @Test
    void unprotectedCompactStorageReproducesReportedException() {
        DataPalette data = compact(1);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> data.set(2, 3, 4, 2));
        assertEquals("Illegal value: 2 < 0 || 2 > 1", error.getMessage());
    }

    @Test
    void compactLegacyAndModernSectionsPreserveEveryBlock() {
        int state = WrappedBlockState.getDefaultState(ClientVersion.V_1_21_4, StateTypes.GLASS).getGlobalId();
        for (boolean modern : new boolean[]{false, true}) {
            for (int bits = 1; bits <= 3; bits++) {
                DataPalette data = compact(bits);
                int blockCount = 4096 - (4096 >> bits);
                BaseChunk section = modern
                        ? new Chunk_v1_18(ClientVersion.V_1_21_4, blockCount, 0, data, PaletteType.BIOME.create())
                        : new Chunk_v1_9(blockCount, data);
                section = ChunkSectionCache.ensureStorageCapacity(section);
                section.set(2, 3, 4, state);
                BaseStorage repaired = data.storage;
                assertEquals(4, repaired.getBitsPerEntry());
                assertSame(section, ChunkSectionCache.ensureStorageCapacity(section));
                assertSame(repaired, data.storage);
                for (int index = 0; index < 4096; index++) {
                    int expected = index == 834 ? state : index % (1 << bits);
                    assertEquals(expected, section.getBlockId(index & 15, index >> 8, (index >> 4) & 15));
                }
            }
        }
    }

    @Test
    void worldUpdatesAndPredictionsUseTheGuard() throws ReflectiveOperationException {
        int state = WrappedBlockState.getDefaultState(ClientVersion.V_1_21_4, StateTypes.GLASS).getGlobalId();
        for (boolean prediction : new boolean[]{false, true}) {
            for (boolean modern : new boolean[]{false, true}) {
                DataPalette data = compact(1);
                BaseChunk section = modern
                        ? new Chunk_v1_18(ClientVersion.V_1_21_4, 2048, 0, data, PaletteType.BIOME.create())
                        : new Chunk_v1_9(2048, data);
                CompensatedWorld world = world(section, 0L);
                if (prediction) world.startPredicting();
                world.updateBlock(2, 3, 4, state);
                assertEquals(state, world.getBlock(2, 3, 4).getGlobalId());
                assertEquals(4, data.storage.getBitsPerEntry());
                assertEquals(1, world.getBlock(1, 0, 0).getGlobalId());
            }
        }
    }

    @Test
    void sharedSectionIsDetachedBeforeRepairAndWrite() throws ReflectiveOperationException {
        DataPalette data = compact(1);
        BaseChunk original = new Chunk_v1_9(2048, data);
        ChunkSectionCache.SharedRef shared = ChunkSectionCache.internRef(original);
        CompensatedWorld world = world(shared.section(), shared.key());
        int state = WrappedBlockState.getDefaultState(ClientVersion.V_1_21_4, StateTypes.GLASS).getGlobalId();
        world.updateBlock(2, 3, 4, state);
        assertEquals(state, world.getBlock(2, 3, 4).getGlobalId());
        assertNotSame(shared.section(), world.getChunk(0, 0).chunks()[0]);
        assertEquals(0L, world.getChunk(0, 0).sectionKeys()[0]);
        assertEquals(0, shared.section().getBlockId(2, 3, 4));
        assertEquals(1, data.storage.getBitsPerEntry());
    }

    @Test
    void repairRecoversPaletteAlreadyExtendedByFailedWrite() {
        DataPalette data = compact(1);
        assertThrows(IllegalStateException.class, () -> data.set(2, 3, 4, 2));
        PaletteStorage.ensureCapacity(data);
        data.set(2, 3, 4, 2);
        assertEquals(2, data.get(2, 3, 4));
        assertEquals(1, data.get(1, 0, 0));
    }

    @Test
    void healthyStorageAndGlobalPalettesKeepTheirStorage() {
        DataPalette[] palettes = {compact(4), new DataPalette(GlobalPalette.INSTANCE, new BitStorage(14, 4096), PaletteType.CHUNK)};
        for (DataPalette data : palettes) {
            BaseStorage original = data.storage;
            PaletteStorage.ensureCapacity(data);
            assertSame(original, data.storage);
        }
    }

    @Test
    void singletonPaletteKeepsNormalGrowth() {
        DataPalette data = new DataPalette(new SingletonPalette(0), null, PaletteType.CHUNK);
        PaletteStorage.ensureCapacity(data);
        assertNull(data.storage);
        data.set(2, 3, 4, 1);
        assertEquals(1, data.get(2, 3, 4));
        assertEquals(0, data.get(1, 0, 0));
    }

    @Test
    void legacyPackingIsPreservedWhenWideningStorage() {
        ListPalette palette = new ListPalette(5);
        for (int id = 0; id < 16; id++) palette.stateToId(id);
        DataPalette data = new DataPalette(palette, new LegacyFlexibleStorage(4, 4096), PaletteType.CHUNK);
        for (int index = 0; index < 4096; index++) data.storage.set(index, index % 16);
        PaletteStorage.ensureCapacity(data);
        assertInstanceOf(LegacyFlexibleStorage.class, data.storage);
        data.set(2, 3, 4, 16);
        for (int index = 0; index < 4096; index++) {
            assertEquals(index == 834 ? 16 : index % 16, data.storage.get(index));
        }
    }

    private static DataPalette compact(int bits) {
        ListPalette palette = new ListPalette(4);
        for (int id = 0; id < (1 << bits); id++) palette.stateToId(id);
        BitStorage storage = new BitStorage(bits, 4096);
        for (int index = 0; index < 4096; index++) storage.set(index, index % (1 << bits));
        return new DataPalette(palette, storage, PaletteType.CHUNK);
    }

    private static CompensatedWorld world(BaseChunk section, long sharedKey) throws ReflectiveOperationException {
        GrimPlayer player = GrimPlayerFixture.player();
        Reach reach = mock(Reach.class);
        when(player.checkManager.get(Reach.class)).thenReturn(reach);
        GrimPlayerFixture.set(player, "pointThreeEstimator", mock(PointThreeEstimator.class));
        GrimPlayerFixture.set(player, "compensatedGeysers", mock(CompensatedGeysers.class));
        CompensatedWorld world = new CompensatedWorld(player);
        GrimPlayerFixture.set(player, "compensatedWorld", world);
        world.chunks.put(0L, new Column(0, 0, new BaseChunk[]{section}, new long[]{sharedKey}, 0));
        return world;
    }
}
