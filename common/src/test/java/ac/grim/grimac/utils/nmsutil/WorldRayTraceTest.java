package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.checks.impl.combat.Reach;
import ac.grim.grimac.manager.CheckManager;
import ac.grim.grimac.manager.SetbackTeleportUtil;
import ac.grim.grimac.predictionengine.PointThreeEstimator;
import ac.grim.grimac.predictionengine.blockeffects.CompensatedGeysers;
import ac.grim.grimac.utils.chunks.Column;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.BlockHitData;
import ac.grim.grimac.utils.data.EntityHitData;
import ac.grim.grimac.utils.data.HitData;
import ac.grim.grimac.utils.data.PacketStateData;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.data.packetentity.PacketEntitySelf;
import ac.grim.grimac.utils.enums.Pose;
import ac.grim.grimac.utils.math.TrigHandler;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.latency.CompensatedEntities;
import ac.grim.grimac.utils.latency.CompensatedInventory;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemAttackRange;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class WorldRayTraceTest {
    private static final ClientVersion VERSION = ClientVersion.V_1_21_4;
    private GrimPlayer player;
    private PacketEntity target;
    private Reach reach;
    private final Map<Vector3i, WrappedBlockState> blocks = new HashMap<>();

    @BeforeAll
    static void packetEvents() throws ClassNotFoundException {
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class, RETURNS_DEEP_STUBS);
        when(api.getServerManager().getVersion()).thenReturn(ServerVersion.V_1_21_11);
        when(api.getSettings()).thenReturn(new PacketEventsSettings());
        when(api.getNettyManager()).thenReturn(new NettyManagerImpl());
        PacketEvents.setAPI(api);
        // Reach's feature gates are fixed at startup; block fixtures use the 1.21.4 palette.
        Class.forName(Reach.class.getName());
        when(api.getServerManager().getVersion()).thenReturn(ServerVersion.V_1_21_4);
        EntityTypes.PLAYER.getName();
        Attributes.ENTITY_INTERACTION_RANGE.getName();
    }

    @BeforeEach
    void world() throws ReflectiveOperationException {
        player = mock(GrimPlayer.class);
        when(player.getClientVersion()).thenReturn(VERSION);
        when(player.getMovementThreshold()).thenReturn(0.0002);
        CompensatedWorld world = mock(CompensatedWorld.class);
        WrappedBlockState air = WrappedBlockState.getDefaultState(VERSION, StateTypes.AIR);
        when(world.getBlock(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                blocks.getOrDefault(new Vector3i(call.getArgument(0), call.getArgument(1), call.getArgument(2)), air));
        when(world.getBlockType(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                world.getBlock(call.getArgument(0), call.getArgument(1), call.getArgument(2)).getType());
        setField(player, "compensatedWorld", world);
        CompensatedEntities entities = mock(CompensatedEntities.class);
        setField(entities, "entityMap", new Int2ObjectOpenHashMap<PacketEntity>());
        setField(player, "compensatedEntities", entities);
        target = mock(PacketEntity.class);
        when(target.getType()).thenReturn(EntityTypes.PLAYER);
        setField(target, "isLivingEntity", true);
        setField(player, "packetStateData", new PacketStateData());
        player.pose = Pose.STANDING;
        reach = mock(Reach.class, CALLS_REAL_METHODS);
        setField(reach, "player", player);
        setField(reach, "blocksChangedThisTick", new HashSet<Vector3i>());
        setField(reach, "playerAttackQueue", new Int2ObjectOpenHashMap<>());
        setField(reach, "cancelBuffers", new HashMap<Integer, Double>());
        setField(reach, "wallHitIgnoredBlocks", Set.of());
        setField(reach, "enableWallHit", true);
        setField(reach, "cancelObstructedHits", true);
        setField(reach, "cancelBufferDecay", .25);
        setField(reach, "hitboxExtraExpansion", .01);
        reach.threshold = .0005;
        CheckManager checks = mock(CheckManager.class);
        when(checks.get(Reach.class)).thenReturn(reach);
        setField(player, "checkManager", checks);
        entities.self = mock(PacketEntitySelf.class);
        when(entities.self.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE)).thenReturn(3.0);
    }

    private static void setField(Object object, String name, Object value) throws ReflectiveOperationException {
        Field field = findField(object.getClass(), name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private void block(int x, int y, int z, String state) {
        WrappedBlockState parsed = WrappedBlockState.getByString(VERSION, "minecraft:" + state);
        assertNotNull(parsed, state);
        assertNotEquals(StateTypes.AIR, parsed.getType(), state);
        blocks.put(new Vector3i(x, y, z), parsed);
    }

    private HitData trace(Vector3d start, Vector3d end) {
        return WorldRayTrace.getNearestHitResult(player, target, new WorldRayTrace.AttackRay(start, end), Set.of(), Set.of());
    }

    private HitData across(double y, double z) {
        return trace(new Vector3d(-1, y, z), new Vector3d(2, y, z));
    }

    @Test
    void acceptedTargetHitStopsBeforeBlockBehindIt() {
        block(0, 0, 0, "stone_slab[type=double,waterlogged=false]");
        assertInstanceOf(EntityHitData.class, trace(new Vector3d(-1, .75, .5), new Vector3d(-.001, .75, .5)));
        assertInstanceOf(BlockHitData.class, across(.75, .5));
        verify(target, never()).getPossibleCollisionBoxes();
    }

    @Test
    void closestEntityAndBlockAreOnlyComparedBeforeTargetHit() {
        PacketEntity obstruction = mock(PacketEntity.class);
        when(obstruction.canHit()).thenReturn(true);
        when(obstruction.getMinimumPossibleCollisionBoxes()).thenAnswer(call -> new SimpleCollisionBox(.5, 0, 0, 1, 1, 1));
        player.compensatedEntities.entityMap.put(2, obstruction);
        assertSame(obstruction, assertInstanceOf(EntityHitData.class, across(.5, .5)).getEntity());
        assertSame(target, assertInstanceOf(EntityHitData.class,
                trace(new Vector3d(-1, .5, .5), new Vector3d(.25, .5, .5))).getEntity());
        block(0, 0, 0, "stone");
        assertInstanceOf(BlockHitData.class, across(.5, .5));
        when(obstruction.getMinimumPossibleCollisionBoxes()).thenAnswer(call -> new SimpleCollisionBox(-.5, 0, 0, -.25, 1, 1));
        assertSame(obstruction, assertInstanceOf(EntityHitData.class, across(.5, .5)).getEntity());
    }

    @Test
    void eyeInsideTargetIsValidEvenWithAnotherObstructedRay() {
        block(0, 0, 0, "stone");
        Vector3d eye = new Vector3d(-1, .5, .5);
        var result = WorldRayTrace.didRayTraceHit(player, target, List.of(
                new WorldRayTrace.AttackRay(eye, new Vector3d(2, .5, .5)),
                new WorldRayTrace.AttackRay(eye, eye)), Set.of(), Set.of());
        assertInstanceOf(EntityHitData.class, result.second());
        assertEquals(0, result.first());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.03, 0.0002})
    void slabsRespectTopBottomAndDouble(double uncertainty) {
        when(player.getMovementThreshold()).thenReturn(uncertainty);
        block(0, 0, 0, "stone_slab[type=bottom,waterlogged=false]");
        assertInstanceOf(BlockHitData.class, across(.25, .5));
        assertInstanceOf(EntityHitData.class, across(.75, .5));
        assertInstanceOf(EntityHitData.class, across(.5 - uncertainty / 2, .5));
        block(0, 0, 0, "stone_slab[type=top,waterlogged=false]");
        assertInstanceOf(EntityHitData.class, across(.25, .5));
        assertInstanceOf(BlockHitData.class, across(.75, .5));
        block(0, 0, 0, "stone_slab[type=double,waterlogged=false]");
        assertInstanceOf(BlockHitData.class, across(.25, .5));
        assertInstanceOf(BlockHitData.class, across(.5, .5));
        assertInstanceOf(BlockHitData.class, across(.75, .5));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.03, 0.0002})
    void stairsHaveOpenSpaceButNoInternalSeams(double uncertainty) {
        when(player.getMovementThreshold()).thenReturn(uncertainty);
        block(0, 0, 0, "stone_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        assertInstanceOf(BlockHitData.class, across(.5, .5));
        assertInstanceOf(BlockHitData.class, across(.75, .5));
        assertInstanceOf(EntityHitData.class, trace(new Vector3d(.25, .75, -1), new Vector3d(.25, .75, 2)));
        assertInstanceOf(BlockHitData.class, trace(new Vector3d(.75, .75, -1), new Vector3d(.75, .75, 2)));
    }

    @Test
    void gateUsesTransmittedInWallEvenWhenNeighboursDisagree() {
        for (boolean open : new boolean[]{false, true}) {
            block(0, 0, 0, "oak_fence_gate[facing=east,in_wall=true,open=" + open + ",powered=false]");
            assertInstanceOf(EntityHitData.class, across(.9, .5));
            assertInstanceOf(BlockHitData.class, across(.5, .5));
            block(0, 0, 0, "oak_fence_gate[facing=east,in_wall=false,open=" + open + ",powered=false]");
            block(0, 0, 1, "cobblestone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]");
            assertInstanceOf(BlockHitData.class, across(.9, .5));
        }
    }

    @Test
    void changedBlockDoesNotHideStableWallBehindIt() {
        block(0, 0, 0, "stone_slab[type=double,waterlogged=false]");
        block(1, 0, 0, "stone");
        var ray = new WorldRayTrace.AttackRay(new Vector3d(-1, .5, .5), new Vector3d(3, .5, .5));
        BlockHitData hit = assertInstanceOf(BlockHitData.class,
                WorldRayTrace.getNearestHitResult(player, target, ray, Set.of(), Set.of(new Vector3i(0, 0, 0))));
        assertEquals(new Vector3i(1, 0, 0), hit.position());
    }

    @ParameterizedTest
    @ValueSource(doubles = {.03, .0002})
    void doorHalvesDoNotHaveAnArtificialGap(double uncertainty) {
        when(player.getMovementThreshold()).thenReturn(uncertainty);
        block(0, 0, 0, "oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]");
        block(0, 1, 0, "oak_door[facing=east,half=upper,hinge=left,open=false,powered=false]");
        assertInstanceOf(BlockHitData.class, across(1, .5));
        var ray = new WorldRayTrace.AttackRay(new Vector3d(-1, 1, .5), new Vector3d(2, 1, .5));
        assertInstanceOf(EntityHitData.class, WorldRayTrace.getNearestHitResult(player, target, ray,
                Set.of(), Set.of(new Vector3i(0, 0, 0))));
    }

    @Test
    void reachAndWallHitUseTheSameExpandedTarget() throws ReflectiveOperationException {
        when(target.getPossibleCollisionBoxes()).thenAnswer(call -> new SimpleCollisionBox(1, 0, 0, 1.6, 1.8, 1));
        when(player.getPossibleEyeHeights()).thenReturn(new double[]{1.805});
        when(player.getPossibleLookVectors(false)).thenAnswer(call -> new Vector3dm[]{new Vector3dm(1, 0, 0)});
        block(2, 1, 0, "stone_slab[type=top,waterlogged=false]");
        assertEquals("NONE", reachResult());
        block(0, 1, 0, "stone_slab[type=double,waterlogged=false]");
        player.x = -1;
        player.z = .5;
        assertTrue(knownInvalid());
        assertEquals("WALL_HIT", reachResult());
        assertTrue(knownInvalid());
        Field field = findField(Reach.class, "cancelBuffers");
        field.setAccessible(true);
        assertEquals(1.0, ((Map<?, ?>) field.get(reach)).get(1));
    }

    @Test
    void clearRayBeyondReachDoesNotExcuseObstructedRayWithinReach() throws ReflectiveOperationException {
        when(target.getPossibleCollisionBoxes()).thenAnswer(call -> new SimpleCollisionBox(1.9, 0, 0, 2.5, 1.8, 1));
        when(player.getPossibleEyeHeights()).thenReturn(new double[]{1.4});
        // Straight ahead enters the target at ~2.89; the dip under the wall only reaches it at ~3.19.
        when(player.getPossibleLookVectors(false)).thenAnswer(call -> new Vector3dm[]{
                new Vector3dm(1, 0, 0), new Vector3dm(2.9, -1.35, 0).normalize()});
        assertEquals("NONE", reachResult());
        block(0, 1, 0, "stone");
        assertEquals("WALL_HIT", reachResult());
    }

    private boolean knownInvalid() throws ReflectiveOperationException {
        Method check = Reach.class.getDeclaredMethod("isKnownInvalid", int.class, PacketEntity.class,
                boolean.class, double.class, float.class, boolean.class);
        check.setAccessible(true);
        return (boolean) check.invoke(reach, 1, target, false, 0f, 0f, true);
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_8", "V_1_17", "V_1_21_4"})
    void legacyDoorUpdateAlsoInvalidatesOtherHalf(ClientVersion client) throws ReflectiveOperationException {
        when(player.getClientVersion()).thenReturn(client);
        block(0, 0, 0, "oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]");
        WrappedBlockState closed = blocks.get(new Vector3i(0, 0, 0));
        WrappedBlockState opened = closed.clone();
        opened.setOpen(true);
        CompensatedWorld world = player.compensatedWorld;
        setField(world, "player", player);
        setField(world, "originalServerBlocks", new Long2ObjectOpenHashMap<>());
        BaseChunk chunk = mock(BaseChunk.class);
        when(chunk.get(VERSION, 0, 0, 0)).thenReturn(closed);
        when(world.getChunk(0, 0)).thenReturn(new Column(0, 0, new BaseChunk[]{chunk}, new long[1], 0));
        setField(player, "pointThreeEstimator", mock(PointThreeEstimator.class));
        setField(player, "compensatedGeysers", mock(CompensatedGeysers.class));
        doCallRealMethod().when(world).updateBlock(anyInt(), anyInt(), anyInt(), anyInt());
        world.updateBlock(0, 0, 0, opened.getGlobalId());
        Field field = findField(Reach.class, "blocksChangedThisTick");
        field.setAccessible(true);
        Set<?> changed = (Set<?>) field.get(reach);
        assertTrue(changed.contains(new Vector3i(0, 0, 0)));
        assertEquals(client.isOlderThan(ClientVersion.V_1_13), changed.contains(new Vector3i(0, 1, 0)));
    }

    private String reachResult() throws ReflectiveOperationException {
        Object result = reachMethod().invoke(reach, 1, target, -1.0, 0.0, .5, false, 0.0, 0f, null, Double.POSITIVE_INFINITY, false, true);
        return resultType(result);
    }

    private Method reachMethod() throws NoSuchMethodException {
        Method check = Reach.class.getDeclaredMethod("checkReach", int.class, PacketEntity.class,
                double.class, double.class, double.class, boolean.class, double.class, float.class,
                Vector3dm.class, double.class, boolean.class, boolean.class);
        check.setAccessible(true);
        return check;
    }

    private String resultType(Object result) throws ReflectiveOperationException {
        Method type = result.getClass().getDeclaredMethod("type");
        type.setAccessible(true);
        return type.invoke(result).toString();
    }

    @ParameterizedTest
    @CsvSource({
            "3, 5,   0,   0,  .3,  0, 3.2,  NONE,  false",
            "3, 3.1, 0,   0,  .3,  0, 3.2,  REACH, true",
            "3, 3.1, 0,   0,  .3,  0, 3.08, NONE,  false",
            "3, 5,   0,   0,  .3,  0, 3.4,  REACH, true",
            "3, 5,   0,   0,   0,  0, 3.1,  REACH, true",
            "3, 5,   0,   0, -.3,  0, 3.2,  REACH, false",
            "3, 5,   0,   0,   0, .3, 3.2,  REACH, false",
            "5, 3,   0,   0,  .3,  0, 3.2,  REACH, true",
            "3, 3,  .3,  .3,  .3,  0, 3.5,  NONE,  false",
            "3, 3,  .3,  .3,  .3,  0, 3.65, REACH, true",
            "3, 5,  .3,   0,   0,  0, 3.2,  NONE,  false",
            "3, 2.9,.3,   0,  .3,  0, 3.25, REACH, true",
            "3, 3,   0,  .3,  .3,  0, 3.2,  NONE,  false",
            "3, 3,   0,  .3,  .3,  0, 3.4,  REACH, true",
            "3, 5,  .3,   0,  .3,  0, 3.5,  NONE,  false",
            "3, 3,   1,   0,   0,  0, 3.9,  NONE,  false"
    })
    void rangeItemChangesRespectBothRanges(float startRange, float currentRange, float startMargin, float currentMargin,
                                          double motionX, double motionZ, double targetX, String expected, boolean cancelEarly) throws ReflectiveOperationException {
        PacketReceiveEvent attack = rangeAttack(startRange, currentRange, startMargin, currentMargin, motionX, motionZ, targetX);
        verify(attack, times(cancelEarly ? 1 : 0)).setCancelled(true);
        assertEquals(expected, queuedReachResult());
    }

    @Test
    void movementAllowanceDoesNotBypassExistingWallCancellation() throws ReflectiveOperationException {
        block(1, 1, 0, "stone");
        PacketReceiveEvent attack = rangeAttack(3, 5, 0, 0, .3, 0, 3.2);
        verify(attack).setCancelled(true);
        assertEquals("WALL_HIT", queuedReachResult());
    }

    @Test
    void validMovementAllowanceAlsoWorksWithAnExistingCancelBuffer() throws ReflectiveOperationException {
        Field field = findField(Reach.class, "cancelBuffers");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Double> buffers = (Map<Integer, Double>) field.get(reach);
        buffers.put(1, 1.0);
        PacketReceiveEvent attack = rangeAttack(3, 5, 0, 0, .3, 0, 3.2);
        verify(attack, never()).setCancelled(true);
        assertEquals("NONE", queuedReachResult());
    }

    private PacketReceiveEvent rangeAttack(float startRange, float currentRange, float startMargin, float currentMargin,
                                          double motionX, double motionZ, double targetX) throws ReflectiveOperationException {
        when(player.getClientVersion()).thenReturn(ClientVersion.V_1_21_11);
        when(player.getSetbackTeleportUtil()).thenReturn(mock(SetbackTeleportUtil.class));
        CompensatedInventory inventory = mock(CompensatedInventory.class);
        setField(player, "inventory", inventory);
        ItemStack start = ItemStack.builder().type(ItemTypes.STICK)
                .component(ComponentTypes.ATTACK_RANGE, new ItemAttackRange(0, startRange, 0, startRange, startMargin, 1)).build();
        ItemStack current = ItemStack.builder().type(ItemTypes.STICK)
                .component(ComponentTypes.ATTACK_RANGE, new ItemAttackRange(0, currentRange, 0, currentRange, currentMargin, 1)).build();
        when(inventory.getStartOfTickStack()).thenReturn(start);
        when(inventory.getItemInHand(any())).thenReturn(current);
        player.z = .5;
        player.clientVelocity = new Vector3dm(motionX, 0, motionZ);
        player.yaw = -90;
        setField(player, "trigHandler", new TrigHandler(player));
        when(player.getPossibleEyeHeights()).thenReturn(new double[]{1.4});
        when(player.getPossibleLookVectors(false)).thenAnswer(call -> new Vector3dm[]{new Vector3dm(1, 0, 0)});
        when(target.canHit()).thenReturn(true);
        when(target.getPossibleCollisionBoxes()).thenAnswer(call -> new SimpleCollisionBox(targetX, 0, 0, targetX + .6, 1.8, 1));
        player.compensatedEntities.entityMap.put(1, target);
        doReturn(true).when(reach).shouldModifyPackets();
        setField(reach, "cancelImpossibleHits", true);
        PacketReceiveEvent event = mock(PacketReceiveEvent.class);
        Method interact = Reach.class.getDeclaredMethod("onInteract", PacketReceiveEvent.class, int.class, InteractionHand.class);
        interact.setAccessible(true);
        interact.invoke(reach, event, 1, InteractionHand.MAIN_HAND);
        return event;
    }

    private String queuedReachResult() throws ReflectiveOperationException {
        Field field = findField(Reach.class, "playerAttackQueue");
        field.setAccessible(true);
        Object attack = ((Int2ObjectOpenHashMap<?>) field.get(reach)).get(1);
        assertNotNull(attack);
        List<Object> arguments = new ArrayList<>(List.of(1, target));
        for (var component : attack.getClass().getRecordComponents()) {
            Method accessor = component.getAccessor();
            accessor.setAccessible(true);
            arguments.add(accessor.invoke(attack));
        }
        arguments.add(false);
        arguments.add(true);
        return resultType(reachMethod().invoke(reach, arguments.toArray()));
    }

    @Test
    void changedBlockExemptionEndsAtClientTickEndButNotPong() throws ReflectiveOperationException {
        Vector3i changed = new Vector3i(0, 0, 0);
        reach.handleBlockChange(changed);
        Field field = findField(Reach.class, "blocksChangedThisTick");
        field.setAccessible(true);
        Set<?> changes = (Set<?>) field.get(reach);
        PacketReceiveEvent event = mock(PacketReceiveEvent.class);
        when(event.getPacketType()).thenReturn(PacketType.Play.Client.PONG);
        reach.onPacketReceive(event);
        assertTrue(changes.contains(changed));
        when(event.getPacketType()).thenReturn(PacketType.Play.Client.CLIENT_TICK_END);
        reach.onPacketReceive(event);
        assertTrue(changes.contains(changed));
        when(player.supportsEndTickPreVia()).thenReturn(true);
        reach.onPacketReceive(event);
        assertTrue(changes.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "north,false,left,z,0.8125", "south,false,left,z,0", "east,false,left,x,0", "west,false,left,x,0.8125",
            "north,true,left,x,0", "north,true,right,x,0.8125", "south,true,left,x,0.8125", "south,true,right,x,0",
            "east,true,left,z,0", "east,true,right,z,0.8125", "west,true,left,z,0.8125", "west,true,right,z,0"
    })
    void doorsFollowFacingHingeAndOpenState(String facing, boolean open, String hinge, String axis, double min) {
        for (ClientVersion client : List.of(ClientVersion.V_1_8, ClientVersion.V_1_17, VERSION)) {
            when(player.getClientVersion()).thenReturn(client);
            when(player.getMovementThreshold()).thenReturn(client.isOlderThan(ClientVersion.V_1_18_2) ? .03 : .0002);
            for (int y = 0; y < 2; y++) {
                block(0, y, 0, "oak_door[facing=" + facing + ",half=" + (y == 0 ? "lower" : "upper")
                        + ",hinge=" + hinge + ",open=" + open + ",powered=false]");
            }
            for (int y = 0; y < 2; y++) {
                Vector3d start = axis.equals("x") ? new Vector3d(-1, y + .5, .5) : new Vector3d(.5, y + .5, -1);
                Vector3d end = axis.equals("x") ? new Vector3d(2, y + .5, .5) : new Vector3d(.5, y + .5, 2);
                assertInstanceOf(BlockHitData.class, trace(start, end));
                double free = min == 0 ? .75 : .25;
                start = axis.equals("x") ? new Vector3d(free, y + .5, -1) : new Vector3d(-1, y + .5, free);
                end = axis.equals("x") ? new Vector3d(free, y + .5, 2) : new Vector3d(2, y + .5, free);
                assertInstanceOf(EntityHitData.class, trace(start, end));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_8", "V_1_17", "V_1_21_4"})
    void trapdoorsRotateFromHorizontalToVertical(ClientVersion client) {
        when(player.getClientVersion()).thenReturn(client);
        when(player.getMovementThreshold()).thenReturn(client.isOlderThan(ClientVersion.V_1_18_2) ? .03 : .0002);
        for (String half : List.of("top", "bottom")) {
            for (String facing : List.of("north", "south", "east", "west")) {
                String state = "oak_trapdoor[facing=" + facing + ",half=" + half + ",open=";
                block(0, 0, 0, state + "false,powered=false,waterlogged=false]");
                assertInstanceOf(EntityHitData.class, across(.5, .5));
                assertInstanceOf(BlockHitData.class, trace(new Vector3d(.5, -1, .5), new Vector3d(.5, 2, .5)));
                assertInstanceOf(BlockHitData.class, across(half.equals("top") ? .9 : .1, .5));
                block(0, 0, 0, state + "true,powered=false,waterlogged=false]");
                boolean xAxis = facing.equals("east") || facing.equals("west");
                assertInstanceOf(BlockHitData.class, xAxis ? across(.5, .5)
                        : trace(new Vector3d(.5, .5, -1), new Vector3d(.5, .5, 2)));
                assertInstanceOf(EntityHitData.class, trace(new Vector3d(.5, -1, .5), new Vector3d(.5, 2, .5)));
            }
        }
    }
}
